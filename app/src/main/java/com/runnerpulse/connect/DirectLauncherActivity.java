package com.runnerpulse.connect;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import androidx.browser.customtabs.CustomTabsIntent;

/** Opens Runner Pulse immediately without an intermediate button screen. */
public final class DirectLauncherActivity extends Activity {
    private static final Uri WEB_URL = Uri.parse("https://runner-pulse-web.onrender.com/");
    private boolean launched = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView message = new TextView(this);
        message.setText("Apertura di Runner Pulse…");
        message.setTextSize(18f);
        message.setGravity(android.view.Gravity.CENTER);
        setContentView(message);
        if (state != null) launched = state.getBoolean("launched", false);
        if (!launched) {
            launched = true;
            try {
                new CustomTabsIntent.Builder().setShowTitle(false).build().launchUrl(this, WEB_URL);
            } catch (Exception e) {
                try {
                    Intent browser = new Intent(Intent.ACTION_VIEW, WEB_URL);
                    browser.addCategory(Intent.CATEGORY_BROWSABLE);
                    startActivity(browser);
                } catch (Exception missingBrowser) {
                    message.setText("Impossibile aprire Runner Pulse. Verifica che Chrome sia installato e aggiornato.");
                }
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("launched", launched);
        super.onSaveInstanceState(out);
    }
}
