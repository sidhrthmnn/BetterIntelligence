package com.example.betterclient;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.content.*;
import android.widget.TextView;
import android.util.Log;
import com.example.ipc.ILocalAiCoreService;
import com.example.ipc.ILocalAiStreamCallback;

/** Minimal real third-party consumer. It needs no model weights or INTERNET permission. */
public class ClientActivity extends Activity {
    private TextView output;
    private boolean bound;
    private void show(String text) {
        Log.i("BetterClient", text);
        runOnUiThread(() -> output.setText(text));
    }
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            ILocalAiCoreService service = ILocalAiCoreService.Stub.asInterface(binder);
            new Thread(() -> {
                try {
                    for (int attempts = 0; attempts < 60 && !service.isModelLoaded(); attempts++) Thread.sleep(500);
                    if (!service.isModelLoaded()) { show("Select an installed GGUF model in Better Intelligence first."); return; }
                    String result = service.generateTextSync("What is two plus two? Answer briefly.", "Be concise.", 0f, 24);
                    show("SYNC: " + result);
                    if (result.startsWith("Security Error:")) return;
                    service.generateStream("What is three plus three? Answer briefly.", "Be concise.", 0f, 0.9f, 24,
                        new ILocalAiStreamCallback.Stub() {
                            private final StringBuilder text = new StringBuilder();
                            public void onToken(String token, int index) { text.append(token); show("STREAM: " + text); }
                            public void onComplete(String full, long ms, float speed, int prompt, int completion) {
                                show("COMPLETE: " + full + " (" + completion + " tokens)");
                            }
                            public void onError(int code, String message) { show("ERROR " + code + ": " + message); }
                        });
                } catch (Exception error) { show("ERROR: " + error.getMessage()); }
            }).start();
        }
        public void onServiceDisconnected(ComponentName name) { show("AI Core disconnected"); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        output = new TextView(this); output.setPadding(32, 32, 32, 32); setContentView(output);
        Intent intent = new Intent("com.aistudio.localaicore.ACTION_BIND_AI_CORE").setPackage("com.aistudio.betterintelligence.vqkzp");
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) show("Install Better Intelligence first.");
    }
    @Override public void onDestroy() { if (bound) unbindService(connection); super.onDestroy(); }
}
