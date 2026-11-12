package com.runnerpulse.connect;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.PermissionController;
import androidx.health.connect.client.permission.HealthPermission;
import androidx.health.connect.client.records.StepsRecord;
import androidx.health.connect.client.records.SleepSessionRecord;
import androidx.health.connect.client.records.HeartRateRecord;
import androidx.health.connect.client.records.ExerciseSessionRecord;
import androidx.health.connect.client.request.ReadRecordsRequest;
import androidx.health.connect.client.time.TimeRangeFilter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import kotlin.coroutines.*;
import kotlin.jvm.functions.Function1;

public class MainActivity extends ComponentActivity {
 private TextView status;
 private HealthConnectClient client;
 private ActivityResultLauncher<Set<String>> permissionLauncher;
 private final Set<String> permissions = new HashSet<>(Arrays.asList(
  HealthPermission.getReadPermission(StepsRecord.class),
  HealthPermission.getReadPermission(SleepSessionRecord.class),
  HealthPermission.getReadPermission(HeartRateRecord.class),
  HealthPermission.getReadPermission(ExerciseSessionRecord.class)
 ));
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
  layout.setPadding(32,48,32,32);layout.setBackgroundColor(Color.rgb(16,35,58));
  TextView title=new TextView(this);title.setText("Runner Pulse Connect");title.setTextSize(26);title.setTextColor(Color.WHITE);layout.addView(title);
  status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(16);status.setPadding(0,28,0,28);layout.addView(status);
  Button authorize=new Button(this);authorize.setText("Autorizza Health Connect");layout.addView(authorize);
  Button read=new Button(this);read.setText("Verifica dati reali");layout.addView(read);
  Button site=new Button(this);site.setText("Apri Runner Pulse Web");layout.addView(site);
  setContentView(layout);
  permissionLauncher=registerForActivityResult(PermissionController.createRequestPermissionResultContract(), granted -> {
    status.setText("Permessi concessi: "+granted.size()+"/4. Tocca «Verifica dati reali».");
  });
  int availability=HealthConnectClient.getSdkStatus(this,"com.google.android.apps.healthdata");
  if(availability==HealthConnectClient.SDK_AVAILABLE){
    client=HealthConnectClient.getOrCreate(this);
    status.setText("Health Connect disponibile. Autorizza la lettura dei dati.");
  }else if(availability==HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED){
    status.setText("Health Connect richiede un aggiornamento.");
  }else status.setText("Health Connect non disponibile su questo dispositivo.");
  authorize.setOnClickListener(v->{if(client!=null)permissionLauncher.launch(permissions);});
  read.setOnClickListener(v->readRealData());
  site.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://runner-pulse-web.onrender.com/"))));
 }
 private <T> void asyncCall(Function1<Continuation<? super T>,Object> action,java.util.function.Consumer<T> success){
  action.invoke(new Continuation<T>(){
   public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
   @Override public void resumeWith(Object result){
    runOnUiThread(()->{
      try {kotlin.ResultKt.throwOnFailure(result);success.accept((T)result);}
      catch(Throwable error){status.setText("Lettura non riuscita: "+error.getMessage());}
    });
   }
  });
 }
 private void readRealData(){
  if(client==null){status.setText("Health Connect non disponibile");return;}
  status.setText("Verifica autorizzazioni…");
  asyncCall(c->client.getPermissionController().getGrantedPermissions(c), granted->{
   if(!granted.contains(HealthPermission.getReadPermission(StepsRecord.class))||
      !granted.contains(HealthPermission.getReadPermission(SleepSessionRecord.class))){
     status.setText("Autorizza almeno passi e sonno, poi riprova.");return;
   }
   Instant now=Instant.now();
   ReadRecordsRequest<StepsRecord> request=new ReadRecordsRequest<>(StepsRecord.class,TimeRangeFilter.between(now.minus(1,ChronoUnit.DAYS),now),false,100);
   asyncCall(c->client.readRecords(request,c), steps->{
    long count=0;for(StepsRecord record:steps.getRecords())count+=record.getCount();
    long total=count;
    ReadRecordsRequest<SleepSessionRecord> sleepRequest=new ReadRecordsRequest<>(SleepSessionRecord.class,TimeRangeFilter.between(now.minus(7,ChronoUnit.DAYS),now),false,100);
    asyncCall(c->client.readRecords(sleepRequest,c), sleeps->{
     status.setText("Health Connect collegato sul telefono.\\nPassi registrati nelle ultime 24 ore: "+total+
       "\\nSessioni di sonno negli ultimi 7 giorni: "+sleeps.getRecords().size()+
       "\\n\\nQuesta è una verifica locale. Nessun dato viene ancora inviato a Firestore.");
    });
   });
  });
 }
}
