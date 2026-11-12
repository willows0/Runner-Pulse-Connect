package com.runnerpulse.connect;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public class PermissionRationaleActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);
 TextView t=new TextView(this);t.setPadding(40,70,40,40);t.setTextSize(18);
 t.setText("Runner Pulse Connect legge solo i dati di Health Connect che autorizzi. In questa versione di prova i dati restano sul telefono: non vengono inviati a server o Firestore. Puoi revocare i permessi dalle impostazioni di Health Connect.");
 setContentView(t);
 }
}
