package io.mo.glassmic.xposed;

import android.os.ParcelFileDescriptor;
import com.bytedance.shadowhook.ShadowHook;
import java.io.FileOutputStream;

/** Standalone bootstrap: no Xposed classes, no provider, no root commands. */
public final class NativeAAudioHook {
    private static boolean loaded;
    private static volatile long generation;
    private static volatile boolean armed;
    private static volatile String status = "Not initialized";
    public static String status() { return status; }
    public static synchronized void initialize() {
        if (loaded) return;
        ShadowHook.init(new ShadowHook.ConfigBuilder().setMode(ShadowHook.Mode.UNIQUE).setDebuggable(true).build());
        System.loadLibrary("glassmic_native");
        int result = nativeInstall();
        nativeSetDecision(0);
        if (result != 0) throw new IllegalStateException("nativeInstall=" + result + "; inspect GlassMic logs");
        loaded = true;
        status = "Loaded; real microphone active";
    }
    public static synchronized void stop() {
        armed = false; generation++;
        if (loaded) { nativeSetDecision(0); nativeSetPcmFd(-1,0,0); }
        status = "Real microphone active";
    }
    public static synchronized void arm() throws Exception {
        initialize();
        if (armed) return;
        nativeDrainStats();
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        nativeSetPcmFd(pipe[0].detachFd(),48000,1);
        final ParcelFileDescriptor write = pipe[1];
        final long ticket = ++generation;
        armed = true;
        status = "Armed: start a TEST call within 30 seconds";
        nativeSetDecision(1);
        new Thread(() -> {
            long samples=0;
            try (FileOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
                // PCM16 mono 48 kHz, a soft 440 Hz tone (-26 dBFS). The watchdog stops it.
                while (armed && generation == ticket) {
                    byte[] frame=new byte[1920];
                    for(int i=0;i<960;i++) {
                        double fade=Math.min(1.0,samples/480.0);
                        short value=(short)(1600*fade*Math.sin(2*Math.PI*440*samples++/48000.0));
                        frame[2*i]=(byte)value; frame[2*i+1]=(byte)(value>>8);
                    }
                    out.write(frame); Thread.sleep(20);
                }
            } catch(Throwable e) { if(armed) android.util.Log.e("GlassMicProbe","PCM writer",e); }
        },"GlassMicProbeWriter").start();
        new Thread(() -> {
            long deadline=android.os.SystemClock.elapsedRealtime()+30000;
            long firstHit=0;
            try {
                while(armed && generation == ticket) {
                    long now=android.os.SystemClock.elapsedRealtime();
                    long[] s=nativeDrainStats();
                    if(s!=null && s.length>=2 && s[0]>0) {
                        if(firstHit==0) firstHit=now;
                        status="Hook hit: reads="+s[0]+", bytes="+s[1];
                        android.util.Log.i("GlassMicProbe",status);
                    }
                    if(generation==ticket && firstHit>0 && now-firstHit>=3000) { stop(); status="Test ended; real microphone active"; break; }
                    if(generation==ticket && now>=deadline) { stop(); status="No capture hook observed; test stopped"; break; }
                    Thread.sleep(100);
                }
            } catch(Throwable e) { if(generation==ticket) { stop(); status="Probe error: "+e; } }
        },"GlassMicProbeWatchdog").start();
    }
    private static native int nativeInstall();
    private static native void nativeSetDecision(int decision);
    private static native void nativeSetPcmFd(int fd,int rate,int channels);
    private static native long[] nativeDrainStats();
}
