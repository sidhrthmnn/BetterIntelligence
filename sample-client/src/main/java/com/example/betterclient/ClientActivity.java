package com.example.betterclient;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.widget.*;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real third-party consumer with no INTERNET permission or bundled model. */
public class ClientActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Uri endpoint = Uri.parse("content://com.aistudio.betterintelligence.mobile.provider");
    private TextView output;
    private void show(String text) {
        Log.i("BetterClient", text);
        runOnUiThread(() -> output.setText(text));
    }
    private void request(String method, String text) {
        worker.execute(() -> {
            try {
                Bundle result = getContentResolver().call(endpoint, method, text, null);
                if (result == null) { show("Install Better Intelligence first."); return; }
                String status = result.getString("status", "ERROR");
                show(status + "\n" + result.getString("text", result.getString("error", "")));
            } catch (Exception error) { show("Connection error: " + error.getMessage()); }
        });
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 48, 32, 32);
        TextView title = new TextView(this); title.setText("Shared local AI - Example app"); title.setTextSize(22);
        layout.addView(title);
        EditText input = new EditText(this); input.setText("i has 2 apples."); input.setMinLines(3); layout.addView(input);
        Button check = new Button(this); check.setText("Check connection"); layout.addView(check);
        check.setOnClickListener(v -> request("status", null));
        Button polish = new Button(this); polish.setText("Polish with Better Intelligence"); layout.addView(polish);
        polish.setOnClickListener(v -> request("polish", input.getText().toString()));
        output = new TextView(this); output.setTextIsSelectable(true); layout.addView(output);
        setContentView(layout);
        request("status", null);
    }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
