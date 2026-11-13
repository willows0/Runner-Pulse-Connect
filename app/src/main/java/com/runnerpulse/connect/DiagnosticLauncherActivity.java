package com.runnerpulse.connect;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Temporary diagnostic launcher. Never uploads logs or secrets. */
public final class DiagnosticLauncherActivity extends Activity {
    private static final String PREFS = "rp_twa_diagnostic";
    private static final String LAST_ERROR = "last_error";
    private static final String ATTEMPTED = "attempted";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(LAST_ERROR, sw.toString()).commit();
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
        boolean attempted = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ATTEMPTED, false);
        if (!attempted) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ATTEMPTED, true).apply();
            // A short delay ensures the diagnostic Activity has been initialized.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { startActivity(new Intent(this, TwaLauncherActivity.class)); }
                catch (Throwable t) { showReport("Errore nell'avvio TWA: " + t); }
            }, 200);
        } else {
            showReport(getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_ERROR,
                "Nessuna eccezione Java registrata. La TWA potrebbe essersi chiusa senza crash, oppure il processo potrebbe essere stato terminato dal sistema."));
        }
    }

    private void showReport(String report) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Runner Pulse – Diagnostica avvio"); title.setTextSize(22);
        layout.addView(title);
        TextView detail = new TextView(this);
        detail.setText(report); detail.setTextIsSelectable(true); detail.setTextSize(12);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(detail); layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button copy = new Button(this); copy.setText("COPIA ERRORE");
        copy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Runner Pulse error", report));
            android.widget.Toast.makeText(this, "Errore copiato", android.widget.Toast.LENGTH_SHORT).show();
        }); layout.addView(copy);
        Button web = new Button(this); web.setText("APRI RUNNER PULSE (MODALITÀ FUNZIONANTE)");
        web.setOnClickListener(v -> startActivity(new Intent(this, DirectLauncherActivity.class)));
        layout.addView(web);
        Button retry = new Button(this); retry.setText("RIPROVA SCHERMO INTERO");
        retry.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(LAST_ERROR).apply();
            try { startActivity(new Intent(this, TwaLauncherActivity.class)); }
            catch (Throwable t) { showReport("Errore nell'avvio TWA: " + t); }
        }); layout.addView(retry);
        setContentView(layout);
    }
}
