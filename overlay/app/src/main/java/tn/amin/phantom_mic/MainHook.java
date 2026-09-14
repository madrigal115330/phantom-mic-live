package tn.amin.phantom_mic;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import tn.amin.phantom_mic.log.Logger;

public class MainHook implements IXposedHookLoadPackage {
    private PhantomManager phantomManager = null;

    private boolean needHook = true;

    private String packageName;

    private int accBytes = 0;

    // Diagnostic incoming-audio capture. This does not touch PhantomMic's
    // microphone queue; it only observes AudioTrack output owned by WhatsApp.
    private volatile Context appContext;
    private final Map<AudioTrack, WavCapture> outputCaptures =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicInteger outputCaptureSeq = new AtomicInteger(0);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (needHook) {
            needHook = false;

            packageName = lpparam.packageName;
            System.loadLibrary("xposedlab");

            Logger.d("Beginning hook");
            doHook(lpparam);
            Logger.d("Successful hook");
        }
    }

    private void doHook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.media.MediaRecorder", lpparam.classLoader, "start" , new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Logger.d("MediaRecorder start");
            }
        });

        XposedHelpers.findAndHookMethod("android.media.AudioRecord", lpparam.classLoader, "startRecording", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Logger.d("AudioRecord start");
            }
        });

        // WhatsApp call playback normally reaches an Android AudioTrack. Hook every
        // write overload so the first test can tell us whether remote caller PCM is visible.
        XposedBridge.hookAllMethods(AudioTrack.class, "write", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!(param.thisObject instanceof AudioTrack)) return;
                    captureAudioTrackWrite((AudioTrack) param.thisObject, param.args);
                } catch (Throwable t) {
                    Logger.d("AudioTrack capture error: " + t);
                }
            }
        });

        XC_MethodHook closeTrackCapture = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof AudioTrack) {
                    closeAudioTrackCapture((AudioTrack) param.thisObject);
                }
            }
        };
        XposedBridge.hookAllMethods(AudioTrack.class, "stop", closeTrackCapture);
        XposedBridge.hookAllMethods(AudioTrack.class, "release", closeTrackCapture);

        XposedHelpers.findAndHookMethod(Activity.class, "performCreate", Bundle.class, PersistableBundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                phantomManager.interceptIntent(activity.getIntent());
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                onActivityObtained(activity);
            }
        });

        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application application = (Application) param.args[0];
                appContext = application.getApplicationContext();
                if (phantomManager == null) {
                    initPhantomManager(application.getApplicationContext());
                }
            }
        });
    }

    private void captureAudioTrackWrite(AudioTrack track, Object[] args) {
        if (track == null || args == null || args.length < 3) return;
        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) return;

        byte[] pcm = extractWriteBytes(track, args);
        if (pcm == null || pcm.length == 0) return;

        WavCapture capture;
        synchronized (outputCaptures) {
            capture = outputCaptures.get(track);
            if (capture == null) {
                capture = createWavCapture(track, args[0]);
                if (capture == null) return;
                outputCaptures.put(track, capture);
            }
        }

        capture.write(pcm);
    }

    private byte[] extractWriteBytes(AudioTrack track, Object[] args) {
        Object data = args[0];
        if (!(args[1] instanceof Integer) || !(args[2] instanceof Integer)) return null;
        int second = (Integer) args[1];
        int third = (Integer) args[2];

        if (data instanceof ByteBuffer) {
            ByteBuffer src = ((ByteBuffer) data).duplicate();
            // ByteBuffer write overload: args[1] is sizeInBytes, args[2] is writeMode.
            int requested = Math.max(0, second);
            int len = Math.min(requested, src.remaining());
            if (len <= 0) return null;
            byte[] out = new byte[len];
            src.get(out);
            return out;
        }

        int offsetOrSize = second;
        int size = third;
        if (size <= 0) return null;

        if (data instanceof byte[]) {
            byte[] src = (byte[]) data;
            int off = Math.max(0, offsetOrSize);
            int len = Math.min(size, Math.max(0, src.length - off));
            if (len <= 0) return null;
            byte[] out = new byte[len];
            System.arraycopy(src, off, out, 0, len);
            return out;
        }

        if (data instanceof short[]) {
            short[] src = (short[]) data;
            int off = Math.max(0, offsetOrSize);
            int count = Math.min(size, Math.max(0, src.length - off));
            if (count <= 0) return null;
            ByteBuffer bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) bb.putShort(src[off + i]);
            return bb.array();
        }

        if (data instanceof float[]) {
            float[] src = (float[]) data;
            int off = Math.max(0, offsetOrSize);
            int count = Math.min(size, Math.max(0, src.length - off));
            if (count <= 0) return null;
            ByteBuffer bb = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) bb.putFloat(src[off + i]);
            return bb.array();
        }

        return null;
    }

    private WavCapture createWavCapture(AudioTrack track, Object firstBuffer) {
        Context ctx = appContext;
        if (ctx == null) return null;

        try {
            int sampleRate = track.getSampleRate();
            int channels = Math.max(1, track.getChannelCount());
            int encoding = track.getAudioFormat();
            int bitsPerSample;
            int wavFormat;

            if (firstBuffer instanceof short[]) {
                encoding = AudioFormat.ENCODING_PCM_16BIT;
            } else if (firstBuffer instanceof float[]) {
                encoding = AudioFormat.ENCODING_PCM_FLOAT;
            }

            if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                bitsPerSample = 16;
                wavFormat = 1;
            } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                bitsPerSample = 8;
                wavFormat = 1;
            } else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                bitsPerSample = 32;
                wavFormat = 3;
            } else {
                Logger.d("Skipping non-PCM AudioTrack encoding=" + encoding);
                return null;
            }

            File base = ctx.getExternalFilesDir(null);
            if (base == null) return null;
            File dir = new File(new File(base, "Recordings"), "incoming_capture");
            if (!dir.exists() && !dir.mkdirs()) return null;

            int seq = outputCaptureSeq.incrementAndGet();
            String name = String.format(java.util.Locale.ROOT,
                    "wa_out_%03d_%dHz_%dch_%dbit.wav",
                    seq, sampleRate, channels, bitsPerSample);
            File out = new File(dir, name);

            WavCapture capture = new WavCapture(out, sampleRate, channels, bitsPerSample, wavFormat);
            Logger.d("AudioTrack capture started: " + out.getAbsolutePath() +
                    " encoding=" + encoding);
            return capture;
        } catch (Throwable t) {
            Logger.d("Could not create AudioTrack WAV capture: " + t);
            return null;
        }
    }

    private void closeAudioTrackCapture(AudioTrack track) {
        WavCapture capture;
        synchronized (outputCaptures) {
            capture = outputCaptures.remove(track);
        }
        if (capture != null) {
            capture.close();
            Logger.d("AudioTrack capture closed: " + capture.file.getAbsolutePath() +
                    " bytes=" + capture.dataBytes);
        }
    }

    private static final class WavCapture {
        private static final long MAX_CAPTURE_BYTES = 128L * 1024L * 1024L;
        private final File file;
        private final RandomAccessFile raf;
        private final int sampleRate;
        private final int channels;
        private final int bitsPerSample;
        private final int wavFormat;
        private long dataBytes = 0;
        private boolean closed = false;

        WavCapture(File file, int sampleRate, int channels, int bitsPerSample, int wavFormat) throws Exception {
            this.file = file;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.wavFormat = wavFormat;
            raf = new RandomAccessFile(file, "rw");
            raf.setLength(0);
            writeHeader(0);
        }

        synchronized void write(byte[] data) {
            if (closed || data == null || data.length == 0) return;
            try {
                long room = MAX_CAPTURE_BYTES - dataBytes;
                if (room <= 0) return;
                int len = (int) Math.min((long) data.length, room);
                raf.seek(44 + dataBytes);
                raf.write(data, 0, len);
                dataBytes += len;

                // Keep WAV playable even if WhatsApp is killed instead of releasing the track.
                if ((dataBytes & 0x3ffffL) < len) {
                    writeHeader(dataBytes);
                }
            } catch (Throwable ignored) {
            }
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                writeHeader(dataBytes);
            } catch (Throwable ignored) {
            }
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }

        private void writeHeader(long bytes) throws Exception {
            long safeBytes = Math.min(bytes, 0xffffffffL - 36L);
            int blockAlign = channels * bitsPerSample / 8;
            long byteRate = (long) sampleRate * blockAlign;

            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put(new byte[]{'R','I','F','F'});
            h.putInt((int) (36L + safeBytes));
            h.put(new byte[]{'W','A','V','E'});
            h.put(new byte[]{'f','m','t',' '});
            h.putInt(16);
            h.putShort((short) wavFormat);
            h.putShort((short) channels);
            h.putInt(sampleRate);
            h.putInt((int) byteRate);
            h.putShort((short) blockAlign);
            h.putShort((short) bitsPerSample);
            h.put(new byte[]{'d','a','t','a'});
            h.putInt((int) safeBytes);

            long pos = raf.getFilePointer();
            raf.seek(0);
            raf.write(h.array());
            raf.seek(Math.max(44, pos));
        }
    }

    private void onActivityObtained(Activity activity) {
        if (phantomManager == null) {
            initPhantomManager(activity.getApplicationContext());
        }

        if (phantomManager.needPrepare()) {
            phantomManager.prepare(activity);
        }
    }

    private void initPhantomManager(Context context) {
        phantomManager = new PhantomManager(context, isNativeHook());
        if (isSpecialCase()) {
            phantomManager.forceUriPath();
//            phantomManager.updateAudioFormat(16000, 2, 1);
//            phantomManager.load();
        }
    }

    private boolean isSpecialCase() {
        return packageName.equals("com.whatsapp")
                || packageName.equals("com.android.soundrecorder");
    }

    public boolean isNativeHook() {
        return true;
    }
}
