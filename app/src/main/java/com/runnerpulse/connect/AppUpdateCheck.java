package com.runnerpulse.connect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Optional, non-blocking check. No installation privileges or web bridge. */
final class AppUpdateCheck {
    private static final String ORIGIN = "https://runner-pulse-web.onrender.com";
    private static final long CHECK_INTERVAL_MS = 30L * 60 * 1000;
    private static final long RETRY_INTERVAL_MS = 60L * 1000;
    private static final int MAX_RESPONSE = 16384;
    private final Activity activity;
    private final BooleanSupplier safeToPrompt;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean destroyed, foreground, checking;
    private long nextCheck;
    private Release pending;
    private AlertDialog dialog;
    private final Runnable tick = this::check;

    private static final class Release {
        final long code;
        final String name, notes;
        Release(long code, String name, String notes) {
            this.code = code; this.name = name; this.notes = notes;
        }
    }
    AppUpdateCheck(Activity activity, BooleanSupplier safeToPrompt) {
        this.activity = activity;
        this.safeToPrompt = safeToPrompt;
        prefs = activity.getSharedPreferences("rp_app_updates", Activity.MODE_PRIVATE);
    }
    void resume() { foreground = true; schedule(4000); }
    void pause() { foreground = false; handler.removeCallbacks(tick); }
    void focus() { if (foreground) schedule(1000); }
    void destroy() {
        destroyed = true; foreground = false;
        handler.removeCallbacksAndMessages(null); executor.shutdownNow();
        if (dialog != null) dialog.dismiss();
    }
    private void schedule(long delay) {
        if (destroyed || !foreground) return;
        handler.removeCallbacks(tick); handler.postDelayed(tick, delay);
    }
    private void check() {
        if (destroyed || !foreground || activity.isFinishing()) return;
        if (!activity.hasWindowFocus() || !safeToPrompt.getAsBoolean()) { schedule(3000); return; }
        if (dialog != null || checking) return;
        if (pending != null) { showIfNeeded(); return; }
        long now = SystemClock.elapsedRealtime();
        if (now < nextCheck) { schedule(nextCheck - now); return; }
        checking = true;
        executor.execute(() -> {
            Release release = null; boolean ok = false;
            try {
                long installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
                JSONObject m = fetchManifest();
                if (!m.optBoolean("success") || !m.optBoolean("available")) throw new IllegalStateException("No manifest");
                long code = m.optLong("versionCode", -1);
                String name = m.optString("versionName", ""), path = m.optString("downloadUrl", "");
                if (UpdatePolicy.validRelease(installed, code, name, path)) {
                    String notes = m.optString("notes", "").trim();
                    if (notes.length() > 1000) notes = notes.substring(0, 1000);
                    release = new Release(code, name, notes);
                }
                ok = true;
            } catch (Exception ignored) { /* Network outage must not block Runner Pulse. */ }
            final Release result = release; final boolean success = ok;
            handler.post(() -> {
                if (destroyed) return;
                checking = false; pending = result;
                nextCheck = SystemClock.elapsedRealtime() + (success ? CHECK_INTERVAL_MS : RETRY_INTERVAL_MS);
                if (foreground) schedule(0);
            });
        });
    }
    private JSONObject fetchManifest() throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(ORIGIN + "/api/app/version").openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(false); c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("Version unavailable");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > MAX_RESPONSE) throw new IllegalStateException("Manifest too large");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { c.disconnect(); }
    }
    private void postpone(Release r) {
        prefs.edit().putLong("version", r.code).putLong("until", System.currentTimeMillis() + UpdatePolicy.SNOOZE_MS).apply();
    }
    private void showIfNeeded() {
        Release r = pending;
        pending = null;
        if (r == null) return;
        if (UpdatePolicy.snoozed(r.code, prefs.getLong("version", -1), prefs.getLong("until", 0), System.currentTimeMillis())) {
            schedule(CHECK_INTERVAL_MS); return;
        }
        String message = "È disponibile Runner Pulse " + r.name + ".\n\n" +
            (r.notes.isEmpty() ? "Miglioramenti e nuove funzionalità." : r.notes) +
            "\n\nScarica l'APK ufficiale e conferma l'installazione in Android. Non disinstallare la versione attuale.";
        dialog = new AlertDialog.Builder(activity)
            .setTitle("Aggiornamento disponibile")
            .setMessage(message)
            .setNegativeButton("Più tardi", (d, w) -> postpone(r))
            .setPositiveButton("Aggiorna ora", (d, w) -> {
                try {
                    // Fixed first-party HTTPS URL, not a URL supplied by the manifest.
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ORIGIN + UpdatePolicy.DOWNLOAD_PATH)));
                    postpone(r);
                } catch (Exception ex) {
                    Toast.makeText(activity, "Apri runner-pulse-web.onrender.com dal browser per scaricare l'app.", Toast.LENGTH_LONG).show();
                }
            }).setOnCancelListener(d -> postpone(r)).create();
        dialog.setOnDismissListener(d -> { dialog = null; if (!destroyed) schedule(CHECK_INTERVAL_MS); });
        dialog.show();
    }
}
