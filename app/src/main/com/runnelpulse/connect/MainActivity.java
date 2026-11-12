package com.runnerpulse.connect;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.health.connect.client.HealthConnectClient;

public class MainActivity extends Activity {
  private TextView status;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(32, 48, 32, 32);
    layout.setBackgroundColor(Color.rgb(16, 35, 58));
    TextView title = new TextView(this);
    title.setText("Runner Pulse Connect");
    title.setTextColor(Color.WHITE);
    title.setTextSize(26);
    layout.addView(title);
    status = new TextView(this);
    status.setTextColor(Color.WHITE);
    status.setTextSize(17);
    status.setPadding(0, 32, 0, 24);
    status.setText("App avviata. Premi Verifica Health Connect.");
    layout.addView(status);
    Button check = new Button(this);
    check.setText("Verifica Health Connect");
    layout.addView(check);
    check.setOnClickListener(v -> {
      try {
        int result = HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata");
        if (result == HealthConnectClient.SDK_AVAILABLE) {
          status.setText("Health Connect disponibile. Verifica iniziale riuscita. La lettura dei dati sara attivata nella prossima versione.");
        } else if (result == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
          status.setText("Health Connect richiede un aggiornamento.");
        } else {
          status.setText("Health Connect non disponibile.");
        }
      } catch (Throwable e) {
        status.setText("Errore verifica Health Connect: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    });
    Button site = new Button(this);
    site.setText("Apri Runner Pulse Web");
    layout.addView(site);
    site.setOnClickListener(v -> {
      try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://runner-pulse-web.onrender.com/"))); }
      catch (Exception e) { status.setText("Impossibile aprire il sito: " + e.getMessage()); }
    });
    setContentView(layout);
  }
}
