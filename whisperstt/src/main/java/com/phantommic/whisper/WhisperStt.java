package com.phantommic.whisper;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Offline whisper.cpp wrapper for externally supplied 16-bit mono PCM. */
public final class WhisperStt implements AutoCloseable {
    static { System.loadLibrary("whisper"); }

    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
    private long context;
    private int threads = defaultThreadCount();

    public WhisperStt() {}

    /** Loads a ggml Whisper model from a normal filesystem path. */
    public synchronized boolean init(String modelPath) {
        if (modelPath == null || modelPath.trim().isEmpty()) {
            throw new IllegalArgumentException("modelPath is empty");
        }
        close();
        context = nativeInit(modelPath);
        return context != 0;
    }

    public synchronized boolean isInitialized() { return context != 0; }

    public synchronized void setThreads(int value) {
        if (value < 1) throw new IllegalArgumentException("threads must be at least 1");
        threads = value;
    }

    public synchronized int getThreads() { return threads; }

    /** Adds one little-endian PCM16 mono buffer received from ESP32/B4A. */
    public synchronized void pushPcm16Le(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        int evenLength = pcm.length & ~1;
        pcmBuffer.write(pcm, 0, evenLength);
    }

    public synchronized int getBufferedSamples() { return pcmBuffer.size() / 2; }
    public synchronized void clearPcm() { pcmBuffer.reset(); }

    /** Transcribes buffered PCM. Pass "auto" for automatic language detection. */
    public synchronized String transcribeBuffered(int sampleRate, String language, boolean clearAfter) {
        byte[] bytes = pcmBuffer.toByteArray();
        try {
            return transcribePcm16Le(bytes, sampleRate, language);
        } finally {
            if (clearAfter) pcmBuffer.reset();
        }
    }

    public synchronized String transcribePcm16Le(byte[] pcm, int sampleRate, String language) {
        requireInitialized();
        if (pcm == null || pcm.length < 2) return "";
        return nativeTranscribe(context, littleEndianBytesToShorts(pcm), sampleRate,
                normalizeLanguage(language), threads);
    }

    public synchronized String transcribePcm16(short[] pcm, int sampleRate, String language) {
        requireInitialized();
        if (pcm == null || pcm.length == 0) return "";
        return nativeTranscribe(context, Arrays.copyOf(pcm, pcm.length), sampleRate,
                normalizeLanguage(language), threads);
    }

    @Override
    public synchronized void close() {
        if (context != 0) {
            nativeFree(context);
            context = 0;
        }
        pcmBuffer.reset();
    }

    private void requireInitialized() {
        if (context == 0) throw new IllegalStateException("Whisper model is not initialized");
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.trim().isEmpty() ? "auto" : language.trim();
    }

    private static short[] littleEndianBytesToShorts(byte[] bytes) {
        int count = bytes.length / 2;
        short[] output = new short[count];
        for (int i = 0; i < count; i++) {
            int lo = bytes[i * 2] & 0xff;
            int hi = bytes[i * 2 + 1] << 8;
            output[i] = (short) (hi | lo);
        }
        return output;
    }

    private static int defaultThreadCount() {
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, available - 1));
    }

    private static native long nativeInit(String modelPath);
    private static native String nativeTranscribe(long context, short[] pcm, int sampleRate,
                                                   String language, int threads);
    private static native void nativeFree(long context);
}
