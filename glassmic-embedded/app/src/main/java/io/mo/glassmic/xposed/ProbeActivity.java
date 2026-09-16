package io.mo.glassmic.xposed;
public final class ProbeActivity extends android.app.Activity {
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private android.widget.TextView text;
    private final Runnable refresh=new Runnable(){public void run(){text.setText(NativeAAudioHook.status());handler.postDelayed(this,200);}};
    public void onCreate(android.os.Bundle saved){
        super.onCreate(saved);
        android.widget.LinearLayout layout=new android.widget.LinearLayout(this);layout.setOrientation(1);layout.setPadding(24,48,24,24);
        text=new android.widget.TextView(this);layout.addView(text);
        android.widget.Button arm=new android.widget.Button(this);arm.setText("ARM 3-SECOND TEST TONE");layout.addView(arm);
        arm.setOnClickListener(v->{try{
            if(!getPackageName().equals("com.whatsapp")) throw new IllegalStateException("Payload must be merged into WhatsApp first");
            NativeAAudioHook.arm();
        }catch(Throwable e){android.widget.Toast.makeText(this,e.toString(),1).show();android.util.Log.e("GlassMicProbe","Arm failed",e);}});
        android.widget.Button stop=new android.widget.Button(this);stop.setText("STOP / REAL MICROPHONE");layout.addView(stop);stop.setOnClickListener(v->NativeAAudioHook.stop());
        setContentView(layout);
    }
    protected void onResume(){super.onResume();handler.post(refresh);}
    protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
