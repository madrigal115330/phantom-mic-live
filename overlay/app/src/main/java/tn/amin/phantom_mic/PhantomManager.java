package tn.amin.phantom_mic;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Comparator;

import tn.amin.phantom_mic.audio.AudioMaster;
import tn.amin.phantom_mic.hook.ActivityResultWrapper;
import tn.amin.phantom_mic.log.Logger;

public class PhantomManager {
    private static final String DEFAULT_RECORDINGS_PATH = "Recordings";
    private static final String QUEUE_PREFIX = "pmq_";
    private static final String QUEUE_EXTENSION = ".wav";
    private static final String MODE_FILE = "phantom_mode.txt";
    private static final String STATUS_FILE = "phantom_status.txt";
    private static final String ERROR_FILE = "phantom_errors.log";
    private static final int REQUEST_CODE = 2608;

    private Uri mUriPath;
    private final WeakReference<Context> mContext;
    private final AudioMaster mAudioMaster;
    private final SPManager mSPManager;
    private final FileManager mFileManager;
    private boolean mNeedPrepare = true;

    private final Object mQueueLock = new Object();
    private volatile boolean mQueueRunning = false;
    private Thread mQueueThread;
    private File mCurrentFile;
    private String mLastStatus = "";

    public PhantomManager(Context context, boolean isNativeHook) {
        Logger.d("Init phantom manager");

        mContext = new WeakReference<>(context);
        mAudioMaster = new AudioMaster();
        mSPManager = new SPManager(context);
        mFileManager = new FileManager(context);

        if (isNativeHook) {
            nativeHook();
        }
    }

    public void interceptIntent(Intent intent) {
    }

    public void forceUriPath() {
        ensureHasUriPath();
    }

    public void prepare(Activity activity) {
        if (mUriPath != null) {
            return;
        }

        mNeedPrepare = false;
        if (mSPManager.getUriPath() == null) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, getDefaultUriPath());
            }

            ActivityResultWrapper arWrapper = new ActivityResultWrapper(activity, REQUEST_CODE);
            Toast.makeText(mContext.get(), "PhantomMic: Chose recordings folder", Toast.LENGTH_LONG).show();
            arWrapper.start(intent, (resultCode, resultData) -> {
                if (resultCode == Activity.RESULT_OK) {
                    if (resultData != null && resultData.getData() != null) {
                        Uri uri = resultData.getData();
                        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                        getContentResolver().takePersistableUriPermission(uri, takeFlags);

                        mSPManager.setUriPath(uri);
                        mUriPath = uri;

                        Logger.d("Saved uri " + mUriPath);
                    }
                }
            });
        }
        else {
            mUriPath = mSPManager.getUriPath();
        }
        Logger.d("PhantomManager.prepare done");
    }

    public Uri getDefaultUriPath() {
        File defaultPath = new File(Environment.getExternalStorageDirectory(), DEFAULT_RECORDINGS_PATH);
        return Uri.fromFile(defaultPath);
    }

    public void updateAudioFormat(int sampleRate, int channelMask, int encoding) {
        mAudioMaster.setFormat(sampleRate, channelMask, encoding);
        Logger.d("Target: " + sampleRate + "Hz, encoding " + encoding + ", channel count " + mAudioMaster.getFormat().getChannelCount());
    }

    /**
     * Called by the native AudioRecord::set hook. The original hook remains installed for the
     * whole call. This only starts the queue controller once.
     */
    public void load() {
        ensureHasUriPath();
        startQueueWorker();
    }

    private void startQueueWorker() {
        synchronized (mQueueLock) {
            if (mQueueRunning) {
                return;
            }

            mQueueRunning = true;
            mQueueThread = new Thread(this::queueLoop, "PhantomMicQueue");
            mQueueThread.setDaemon(true);
            mQueueThread.start();
        }
    }

    private void queueLoop() {
        writeStatus("STARTING");

        while (mQueueRunning) {
            try {
                boolean aiMode = readAiMode();
                nativeSetAiMode(aiMode);

                if (!aiMode) {
                    writeStatus("HUMAN");
                    sleepQuietly(75);
                    continue;
                }

                if (mCurrentFile != null) {
                    if (mAudioMaster.isLastLoadFailed()) {
                        File failed = mCurrentFile;
                        mCurrentFile = null;
                        nativeResetAudioBuffer();
                        markFailed(failed, "decoder failed");
                        continue;
                    }

                    if (nativeIsPlaybackFinished()) {
                        File finished = mCurrentFile;
                        mCurrentFile = null;
                        nativeResetAudioBuffer();

                        if (!finished.delete()) {
                            appendError("Could not delete finished file: " + finished.getAbsolutePath());
                        }
                        else {
                            Logger.d("Deleted played queue file " + finished.getName());
                        }
                        continue;
                    }

                    writeStatus("AI PLAYING " + mCurrentFile.getName());
                    sleepQuietly(35);
                    continue;
                }

                if (mAudioMaster.isLoading()) {
                    sleepQuietly(35);
                    continue;
                }

                File next = findNextQueueFile();
                if (next == null) {
                    writeStatus("AI WAITING");
                    sleepQuietly(60);
                    continue;
                }

                nativeResetAudioBuffer();
                mCurrentFile = next;
                writeStatus("AI LOADING " + next.getName());

                String baseName = stripExtension(next.getName());
                FileDescriptor fd = mFileManager.openAudioWithName(mUriPath, baseName);
                if (fd == null) {
                    File failed = mCurrentFile;
                    mCurrentFile = null;
                    markFailed(failed, "could not open audio file");
                    continue;
                }

                mAudioMaster.load(fd);
            } catch (Throwable t) {
                appendError("Queue worker error: " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
                Logger.d("Queue worker error: " + t);
                sleepQuietly(100);
            }
        }

        nativeSetAiMode(false);
        writeStatus("STOPPED");
    }

    private File findNextQueueFile() {
        File dir = getQueueDirectory();
        if (dir == null || !dir.isDirectory()) {
            return null;
        }

        File[] files = dir.listFiles((d, name) -> name != null
                && name.startsWith(QUEUE_PREFIX)
                && name.endsWith(QUEUE_EXTENSION));

        if (files == null || files.length == 0) {
            return null;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        return files[0];
    }

    private boolean readAiMode() {
        File dir = getQueueDirectory();
        if (dir == null) {
            return true;
        }

        File mode = new File(dir, MODE_FILE);
        if (!mode.isFile()) {
            return true;
        }

        String line = mFileManager.readLine(mUriPath, MODE_FILE);
        if (line == null) {
            return true;
        }

        return !"HUMAN".equalsIgnoreCase(line.trim());
    }

    private void markFailed(File failed, String reason) {
        if (failed == null) {
            return;
        }

        appendError(reason + ": " + failed.getAbsolutePath());
        File renamed = new File(failed.getParentFile(), failed.getName() + ".error");
        if (!failed.renameTo(renamed)) {
            appendError("Could not rename failed queue file: " + failed.getAbsolutePath());
        }
        writeStatus("ERROR " + failed.getName());
        sleepQuietly(100);
    }

    private File getQueueDirectory() {
        if (mUriPath == null || mUriPath.getPath() == null) {
            return null;
        }
        if (!"file".equals(mUriPath.getScheme())) {
            return null;
        }
        return new File(mUriPath.getPath());
    }

    private void writeStatus(String status) {
        if (status.equals(mLastStatus)) {
            return;
        }
        mLastStatus = status;
        Logger.d("Status: " + status);

        File dir = getQueueDirectory();
        if (dir == null) {
            return;
        }

        try (FileWriter writer = new FileWriter(new File(dir, STATUS_FILE), false)) {
            writer.write(status);
            writer.write('\n');
        } catch (IOException e) {
            Logger.d("Status write failed: " + e.getMessage());
        }
    }

    private void appendError(String message) {
        Logger.d(message);

        File dir = getQueueDirectory();
        if (dir == null) {
            return;
        }

        try (FileWriter writer = new FileWriter(new File(dir, ERROR_FILE), true)) {
            writer.write(String.valueOf(System.currentTimeMillis()));
            writer.write(" ");
            writer.write(message);
            writer.write('\n');
        } catch (IOException e) {
            Logger.d("Error log write failed: " + e.getMessage());
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureHasUriPath() {
        if (mUriPath == null) {
            Context context = mContext.get();
            if (context != null && context.getExternalFilesDir(null) != null) {
                mUriPath = Uri.fromFile(new File(context.getExternalFilesDir(null), DEFAULT_RECORDINGS_PATH));
            }
        }
    }

    public void unload() {
        synchronized (mQueueLock) {
            mQueueRunning = false;
            if (mQueueThread != null) {
                mQueueThread.interrupt();
                mQueueThread = null;
            }
        }

        mAudioMaster.unload();
        mFileManager.close();
        mCurrentFile = null;
        nativeSetAiMode(false);
        Logger.d("Done unloading data");
    }

    private ContentResolver getContentResolver() {
        return mContext.get().getContentResolver();
    }

    public boolean needPrepare() {
        return mNeedPrepare;
    }

    private native void nativeHook();
    private native void nativeSetAiMode(boolean aiMode);
    private native boolean nativeIsPlaybackFinished();
    private native void nativeResetAudioBuffer();
}
