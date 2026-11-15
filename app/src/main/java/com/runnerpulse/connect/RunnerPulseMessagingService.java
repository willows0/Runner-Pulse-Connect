package com.runnerpulse.connect;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

public final class RunnerPulseMessagingService extends FirebaseMessagingService {
    static final String CHANNEL = "runnerpulse_coach";
    static void createChannel(android.content.Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Notifiche Coach", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Resoconti e analisi del Coach Runner Pulse");
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    @Override public void onNewToken(String token) {
        super.onNewToken(token);
        // Never register without authenticated user consent. JS requests token on opt-in.
    }
    @Override public void onMessageReceived(RemoteMessage message) {
        Map<String,String> data = message.getData();
        if (data == null || data.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
        createChannel(this);
        String title = data.getOrDefault("title", "Runner Pulse · Coach AI");
        String body = data.getOrDefault("body", "Hai un nuovo messaggio dal Coach.");
        String path = data.getOrDefault("url", "/index.html?weeklyCoach=1");
        if (!path.startsWith("/") || path.startsWith("//")) path = "/index.html?weeklyCoach=1";
        Intent intent = new Intent(this, WebViewLauncherActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("runnerpulse_notification_path", path);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, path.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder n = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(body)
            .setAutoCancel(true).setContentIntent(pending).setPriority(NotificationCompat.PRIORITY_DEFAULT);
        getSystemService(NotificationManager.class).notify(data.getOrDefault("tag", "runnerpulse-coach").hashCode(), n.build());
    }
}
