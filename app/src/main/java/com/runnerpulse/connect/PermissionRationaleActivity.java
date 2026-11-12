package com.runnerpulse.connect;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public class PermissionRationaleActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);
 TextView t=new TextView(this);t.setPadding(40,70,40,40);t.setTextSize(18);
 t.setText("Runner Pulse Connect usera solo i dati Health Connect autorizzati. Questa versione verifica soltanto la disponibilita del servizio: non legge o invia dati personali.");
 setContentView(t);
 }
}
