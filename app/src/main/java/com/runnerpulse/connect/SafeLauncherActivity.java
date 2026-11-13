package com.runnerpulse.connect;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.browser.customtabs.CustomTabsIntent;

/**
 * A stable native launcher. It intentionally does not finish() after opening Chrome.
 * If a TWA/browser fails to start on a device, the user returns to this screen,
 * rather than to Android's home screen.
 *
 * Google authentication stays in the browser: do not replace this with WebView.
 */
public final class SafeLauncherActivity extends Activity {
    private static final Uri WEB_URL = Uri.parse("https://runner-pulse-web.onrender.com/");
    private TextView message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int navy = Color.rgb(15, 30, 52);
        int blue = Color.rgb(49, 105, 220);
        int white = Color.WHITE;

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        column.setPadding(pad, dp(48), pad, pad);
        column.setBackgroundColor(navy);

        TextView heading = new TextView(this);
        heading.setText("RUNNER PULSE");
        heading.setTextSize(29f);
        heading.setTextColor(white);
        heading.setGravity(Gravity.CENTER);
        column.addView(heading, new LinearLayout.LayoutParams(-1, dp(68)));

        TextView subtitle = new TextView(this);
        subtitle.setText("Allenamenti, programma e coach");
        subtitle.setTextColor(Color.rgb(217, 228, 250));
        subtitle.setTextSize(16f);
        subtitle.setGravity(Gravity.CENTER);
        column.addView(subtitle);

        message = new TextView(this);
        message.setText("Puoi aprire Runner Pulse anche se la modalità a schermo intero non funziona sul telefono.");
        message.setTextColor(white);
        message.setTextSize(16f);
        message.setPadding(0, dp(38), 0, dp(24));
        message.setGravity(Gravity.CENTER);
        column.addView(message);

        Button open = new Button(this);
        open.setText("APRI RUNNER PULSE");
        open.setAllCaps(false);
        open.setTextColor(white);
        open.setBackgroundTintList(android.content.res.ColorStateList.valueOf(blue));
        column.addView(open, new LinearLayout.LayoutParams(-1, dp(60)));
        open.setOnClickListener(v -> openWeb());

        Button health = new Button(this);
        health.setText("COLLEGA HEALTH CONNECT");
        health.setAllCaps(false);
        LinearLayout.LayoutParams healthLp = new LinearLayout.LayoutParams(-1, dp(56));
        healthLp.topMargin = dp(12);
        column.addView(health, healthLp);
        health.setOnClickListener(v -> {
            try {
                Intent healthIntent = new Intent(this, MainActivity.class);
                healthIntent.setAction(Intent.ACTION_VIEW);
                healthIntent.setData(Uri.parse("runnerpulseconnect://authorize"));
                startActivity(healthIntent);
            } catch (Exception e) {
                message.setText("Health Connect non si apre: " + e.getClass().getSimpleName());
            }
        });

        Button full = new Button(this);
        full.setText("PROVA MODALITÀ A SCHERMO INTERO");
        full.setAllCaps(false);
        LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(-1, dp(56));
        fullLp.topMargin = dp(12);
        column.addView(full, fullLp);
        full.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, TwaLauncherActivity.class));
            } catch (Exception e) {
                message.setText("Modalità a schermo intero non disponibile. Usa APRI RUNNER PULSE.");
            }
        });

        TextView hint = new TextView(this);
        hint.setText("L'accesso Google rimane quello di Chrome. Non serve riconfigurare l'orologio né cancellare i dati.");
        hint.setTextColor(Color.rgb(191, 211, 238));
        hint.setTextSize(13f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(24), 0, dp(4));
        column.addView(hint);

        ScrollView root = new ScrollView(this);
        root.setFillViewport(true);
        root.addView(column, new ScrollView.LayoutParams(-1, -2));
        setContentView(root);

        // Do not automatically start a TWA here: the previous launcher returned
        // instantly to the home screen on the user's Honor. This screen allows a
        // reliable first launch and isolates any browser/TWA problem.
    }

    private void openWeb() {
        try {
            CustomTabsIntent customTabs = new CustomTabsIntent.Builder()
                .setShowTitle(false)
                .build();
            customTabs.launchUrl(this, WEB_URL);
            message.setText("Runner Pulse aperto. Se il browser non compare, controlla che Chrome sia aggiornato.");
        } catch (Exception customTabError) {
            try {
                Intent browser = new Intent(Intent.ACTION_VIEW, WEB_URL);
                browser.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(browser);
                message.setText("Apertura con il browser del telefono.");
            } catch (Exception noBrowser) {
                message.setText("Nessun browser disponibile. Installa o aggiorna Chrome e riprova.");
            }
        }
        // Never finish(): keep a working app screen behind the browser.
    }

    private int dp(int dip) {
        return (int) (dip * getResources().getDisplayMetrics().density + 0.5f);
    }
}
