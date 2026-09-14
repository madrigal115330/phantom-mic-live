package com.b4apaw.audiotap;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "B4APawAudioTap";
    private static final int PORT = 28663;
    private static final int MAGIC = 0x42344150;
    private static final int NORMAL_RATE = 16000;
    private static final int MAX_FRAME = 256 * 1024;

    private static final Set<String> RECOGNIZER_PACKAGES = new java.util.HashSet<String>(Arrays.asList(
            "b4a.example",
            "com.b4apaw.call",
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.google.android.as",
            "com.samsung.android.bixby.agent",
            "com.samsung.android.svoiceime"
    ));

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final String pkg = lpparam.packageName == null ? "" : lpparam.packageName;
        if ("com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg)) {
            log("producer loaded in " + pkg + " process=" + lpparam.processName);
            Producer.install();
            return;
        }
        if (RECOGNIZER_PACKAGES.contains(pkg)) {
            log("consumer loaded in " + pkg + " process=" + lpparam.processName);
            Consumer.install();
        }
    }

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    private static final class Producer {
        private static final ArrayBlockingQueue<byte[]> QUEUE = new ArrayBlockingQueue<byte[]>(64);
        private static volatile boolean workerStarted = false;
        private static volatile long lastFrameAt = 0L;

        static void install() {
            hookByteArray(3);
            hookByteArray(4);
            hookShortArray(3);
            hookShortArray(4);
            hookByteBuffer();
            startWorker();
        }

        private static boolean isVoiceTrack(AudioTrack t) {
            try {
                AudioAttributes a = t.getAudioAttributes();
                if (a != null) {
                    int u = a.getUsage();
                    if (u == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                            u == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING) return true;
                }
            } catch (Throwable ignored) {}
            try {
                return t.getStreamType() == AudioManager.STREAM_VOICE_CALL;
            } catch (Throwable ignored) {}
            return false;
        }

        private static int safeRate(AudioTrack t) {
            try {
                int r = t.getSampleRate();
                return r > 0 ? r : 48000;
            } catch (Throwable e) { return 48000; }
        }

        private static int safeChannels(AudioTrack t) {
            try {
                int c = t.getChannelCount();
                return c > 0 ? c : 1;
            } catch (Throwable e) { return 1; }
        }

        private static int safeEncoding(AudioTrack t) {
            try { return t.getAudioFormat(); }
            catch (Throwable e) { return AudioFormat.ENCODING_PCM_16BIT; }
        }

        private static void offerPcm(AudioTrack t, byte[] raw) {
            if (t == null || raw == null || raw.length < 2 || !isVoiceTrack(t)) return;
            int enc = safeEncoding(t);
            if (enc != AudioFormat.ENCODING_PCM_16BIT) return;
            byte[] norm = normalizePcm16(raw, safeRate(t), safeChannels(t));
            if (norm == null || norm.length == 0) return;
            if (!QUEUE.offer(norm)) {
                QUEUE.poll();
                QUEUE.offer(norm);
            }
            lastFrameAt = System.currentTimeMillis();
        }

        private static void hookByteArray(int arity) {
            try {
                if (arity == 3) {
                    XposedHelpers.findAndHookMethod(AudioTrack.class, "write",
                            byte[].class, int.class, int.class,
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    captureBytes((AudioTrack)p.thisObject, (byte[])p.args[0], (Integer)p.args[1], (Integer)p.args[2]);
                                }
                            });
                } else {
                    XposedHelpers.findAndHookMethod(AudioTrack.class, "write",
                            byte[].class, int.class, int.class, int.class,
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    captureBytes((AudioTrack)p.thisObject, (byte[])p.args[0], (Integer)p.args[1], (Integer)p.args[2]);
                                }
                            });
                }
            } catch (Throwable t) { log("byte write hook unavailable: " + t); }
        }

        private static void hookShortArray(int arity) {
            try {
                if (arity == 3) {
                    XposedHelpers.findAndHookMethod(AudioTrack.class, "write",
                            short[].class, int.class, int.class,
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    captureShorts((AudioTrack)p.thisObject, (short[])p.args[0], (Integer)p.args[1], (Integer)p.args[2]);
                                }
                            });
                } else {
                    XposedHelpers.findAndHookMethod(AudioTrack.class, "write",
                            short[].class, int.class, int.class, int.class,
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    captureShorts((AudioTrack)p.thisObject, (short[])p.args[0], (Integer)p.args[1], (Integer)p.args[2]);
                                }
                            });
                }
            } catch (Throwable t) { log("short write hook unavailable: " + t); }
        }

        private static void hookByteBuffer() {
            try {
                XposedHelpers.findAndHookMethod(AudioTrack.class, "write",
                        ByteBuffer.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                AudioTrack track = (AudioTrack)p.thisObject;
                                if (!isVoiceTrack(track)) return;
                                ByteBuffer src = (ByteBuffer)p.args[0];
                                int size = (Integer)p.args[1];
                                if (src == null || size <= 0) return;
                                ByteBuffer dup = src.duplicate();
                                int n = Math.min(size, dup.remaining());
                                if (n <= 0) return;
                                byte[] b = new byte[n];
                                dup.get(b);
                                offerPcm(track, b);
                            }
                        });
            } catch (Throwable t) { log("ByteBuffer write hook unavailable: " + t); }
        }

        private static void captureBytes(AudioTrack t, byte[] b, int off, int len) {
            if (b == null || off < 0 || len <= 0 || off + len > b.length) return;
            offerPcm(t, Arrays.copyOfRange(b, off, off + len));
        }

        private static void captureShorts(AudioTrack t, short[] s, int off, int len) {
            if (s == null || off < 0 || len <= 0 || off + len > s.length || !isVoiceTrack(t)) return;
            byte[] b = new byte[len * 2];
            int j = 0;
            for (int i = off; i < off + len; i++) {
                short v = s[i];
                b[j++] = (byte)(v & 0xff);
                b[j++] = (byte)((v >>> 8) & 0xff);
            }
            offerPcm(t, b);
        }

        private static byte[] normalizePcm16(byte[] in, int inRate, int channels) {
            if (inRate <= 0) inRate = 48000;
            if (channels <= 0) channels = 1;
            int inFrames = in.length / (2 * channels);
            if (inFrames <= 0) return null;
            short[] mono = new short[inFrames];
            for (int f = 0; f < inFrames; f++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int p = (f * channels + c) * 2;
                    int lo = in[p] & 0xff;
                    int hi = in[p + 1];
                    sum += (short)(lo | (hi << 8));
                }
                mono[f] = (short)(sum / channels);
            }
            int outFrames = Math.max(1, (int)(((long)inFrames * NORMAL_RATE) / inRate));
            byte[] out = new byte[outFrames * 2];
            for (int i = 0; i < outFrames; i++) {
                int src = (int)Math.min(inFrames - 1, ((long)i * inRate) / NORMAL_RATE);
                short v = mono[src];
                out[i * 2] = (byte)(v & 0xff);
                out[i * 2 + 1] = (byte)((v >>> 8) & 0xff);
            }
            return out;
        }

        private static synchronized void startWorker() {
            if (workerStarted) return;
            workerStarted = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { senderLoop(); }
            }, "B4APaw-AudioTap-Tx");
            t.setDaemon(true);
            t.start();
        }

        private static void senderLoop() {
            Socket socket = null;
            DataOutputStream out = null;
            for (;;) {
                try {
                    byte[] frame = QUEUE.poll(1500, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        if (socket != null && System.currentTimeMillis() - lastFrameAt > 3000L) {
                            try { socket.close(); } catch (Throwable ignored) {}
                            socket = null;
                            out = null;
                        }
                        continue;
                    }
                    if (socket == null || socket.isClosed() || !socket.isConnected()) {
                        socket = new Socket(InetAddress.getByName("127.0.0.1"), PORT);
                        socket.setTcpNoDelay(true);
                        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 32768));
                        log("connected producer -> consumer");
                    }
                    out.writeInt(MAGIC);
                    out.writeInt(frame.length);
                    out.write(frame);
                    out.flush();
                } catch (Throwable e) {
                    if (socket != null) try { socket.close(); } catch (Throwable ignored) {}
                    socket = null;
                    out = null;
                    try { Thread.sleep(180L); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private static final class Consumer {
        private static final PcmQueue PCM = new PcmQueue(16000 * 2 * 8);
        private static volatile boolean serverStarted = false;
        private static volatile boolean producerConnected = false;
        private static volatile long lastPacketAt = 0L;
        private static volatile long lastFormatLogAt = 0L;

        static void install() {
            startServer();
            hookByteArrayRead();
            hookShortArrayRead();
            hookByteBufferRead();
        }

        private static boolean active() {
            return producerConnected || (System.currentTimeMillis() - lastPacketAt < 2500L);
        }

        private static void hookByteArrayRead() {
            try {
                XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
                        byte[].class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!active()) return;
                                AudioRecord r = (AudioRecord)p.thisObject;
                                byte[] dst = (byte[])p.args[0];
                                int off = (Integer)p.args[1];
                                int len = (Integer)p.args[2];
                                if (dst == null || off < 0 || len <= 0 || off + len > dst.length) return;
                                logFormatSometimes(r);
                                p.setResult(fillBytes(dst, off, len, r));
                            }
                        });
            } catch (Throwable t) { log("byte read hook unavailable: " + t); }
            try {
                XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
                        byte[].class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!active()) return;
                                AudioRecord r = (AudioRecord)p.thisObject;
                                byte[] dst = (byte[])p.args[0];
                                int off = (Integer)p.args[1];
                                int len = (Integer)p.args[2];
                                if (dst == null || off < 0 || len <= 0 || off + len > dst.length) return;
                                logFormatSometimes(r);
                                p.setResult(fillBytes(dst, off, len, r));
                            }
                        });
            } catch (Throwable t) { log("byte read4 hook unavailable: " + t); }
        }

        private static void hookShortArrayRead() {
            try {
                XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
                        short[].class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!active()) return;
                                AudioRecord r = (AudioRecord)p.thisObject;
                                short[] dst = (short[])p.args[0];
                                int off = (Integer)p.args[1];
                                int len = (Integer)p.args[2];
                                if (dst == null || off < 0 || len <= 0 || off + len > dst.length) return;
                                logFormatSometimes(r);
                                byte[] tmp = new byte[len * 2];
                                fillBytes(tmp, 0, tmp.length, r);
                                for (int i = 0; i < len; i++) {
                                    int lo = tmp[i * 2] & 0xff;
                                    int hi = tmp[i * 2 + 1];
                                    dst[off + i] = (short)(lo | (hi << 8));
                                }
                                p.setResult(len);
                            }
                        });
            } catch (Throwable t) { log("short read hook unavailable: " + t); }
            try {
                XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
                        short[].class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!active()) return;
                                AudioRecord r = (AudioRecord)p.thisObject;
                                short[] dst = (short[])p.args[0];
                                int off = (Integer)p.args[1];
                                int len = (Integer)p.args[2];
                                if (dst == null || off < 0 || len <= 0 || off + len > dst.length) return;
                                logFormatSometimes(r);
                                byte[] tmp = new byte[len * 2];
                                fillBytes(tmp, 0, tmp.length, r);
                                for (int i = 0; i < len; i++) {
                                    int lo = tmp[i * 2] & 0xff;
                                    int hi = tmp[i * 2 + 1];
                                    dst[off + i] = (short)(lo | (hi << 8));
                                }
                                p.setResult(len);
                            }
                        });
            } catch (Throwable t) { log("short read4 hook unavailable: " + t); }
        }

        private static void hookByteBufferRead() {
            try {
                XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
                        ByteBuffer.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!active()) return;
                                AudioRecord r = (AudioRecord)p.thisObject;
                                ByteBuffer dst = (ByteBuffer)p.args[0];
                                int len = (Integer)p.args[1];
                                if (dst == null || len <= 0) return;
                                logFormatSometimes(r);
                                int n = Math.min(len, dst.remaining());
                                byte[] tmp = new byte[n];
                                fillBytes(tmp, 0, n, r);
                                dst.put(tmp);
                                p.setResult(n);
                            }
                        });
            } catch (Throwable t) { log("ByteBuffer read hook unavailable: " + t); }
        }

        private static int fillBytes(byte[] dst, int off, int len, AudioRecord r) {
            int rate = 16000;
            int channels = 1;
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            try { rate = r.getSampleRate(); } catch (Throwable ignored) {}
            try { channels = r.getChannelCount(); } catch (Throwable ignored) {}
            try { enc = r.getAudioFormat(); } catch (Throwable ignored) {}
            if (enc != AudioFormat.ENCODING_PCM_16BIT || channels != 1 || rate != NORMAL_RATE) {
                Arrays.fill(dst, off, off + len, (byte)0);
                return len;
            }
            int n = PCM.read(dst, off, len);
            if (n < len) Arrays.fill(dst, off + n, off + len, (byte)0);
            return len;
        }

        private static void logFormatSometimes(AudioRecord r) {
            long now = System.currentTimeMillis();
            if (now - lastFormatLogAt < 5000L) return;
            lastFormatLogAt = now;
            try {
                log("inject AudioRecord rate=" + r.getSampleRate() + " ch=" + r.getChannelCount() + " enc=" + r.getAudioFormat());
            } catch (Throwable t) {
                log("inject AudioRecord format unknown");
            }
        }

        private static synchronized void startServer() {
            if (serverStarted) return;
            serverStarted = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { serverLoop(); }
            }, "B4APaw-AudioTap-Rx");
            t.setDaemon(true);
            t.start();
        }

        private static void serverLoop() {
            for (;;) {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"));
                    ss.setReuseAddress(true);
                    log("consumer listening on 127.0.0.1:" + PORT);
                    for (;;) {
                        Socket s = ss.accept();
                        s.setTcpNoDelay(true);
                        producerConnected = true;
                        log("producer connected");
                        try {
                            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 32768));
                            while (true) {
                                int magic = in.readInt();
                                if (magic != MAGIC) throw new java.io.IOException("bad magic");
                                int len = in.readInt();
                                if (len <= 0 || len > MAX_FRAME) throw new java.io.IOException("bad len " + len);
                                byte[] b = new byte[len];
                                in.readFully(b);
                                PCM.write(b, 0, b.length);
                                lastPacketAt = System.currentTimeMillis();
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            producerConnected = false;
                            try { s.close(); } catch (Throwable ignored) {}
                            log("producer disconnected");
                        }
                    }
                } catch (Throwable t) {
                    log("consumer server error: " + t);
                    try { Thread.sleep(500L); } catch (InterruptedException ignored) {}
                } finally {
                    if (ss != null) try { ss.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static final class PcmQueue {
        private final byte[] buf;
        private int head = 0;
        private int size = 0;

        PcmQueue(int capacity) {
            buf = new byte[Math.max(4096, capacity)];
        }

        synchronized void write(byte[] src, int off, int len) {
            if (src == null || len <= 0) return;
            if (len >= buf.length) {
                off += len - buf.length;
                len = buf.length;
                head = 0;
                size = 0;
            }
            int free = buf.length - size;
            if (len > free) {
                int drop = len - free;
                head = (head + drop) % buf.length;
                size -= drop;
            }
            int tail = (head + size) % buf.length;
            int first = Math.min(len, buf.length - tail);
            System.arraycopy(src, off, buf, tail, first);
            if (first < len) System.arraycopy(src, off + first, buf, 0, len - first);
            size += len;
        }

        synchronized int read(byte[] dst, int off, int len) {
            int n = Math.min(len, size);
            if (n <= 0) return 0;
            int first = Math.min(n, buf.length - head);
            System.arraycopy(buf, head, dst, off, first);
            if (first < n) System.arraycopy(buf, 0, dst, off + first, n - first);
            head = (head + n) % buf.length;
            size -= n;
            return n;
        }
    }
}
