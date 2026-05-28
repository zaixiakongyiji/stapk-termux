package com.termux.app.stapk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.termux.R;
import com.termux.app.StapkControlActivity;

/**
 * 前台服务 - 防止 Android 在后台杀死 SillyTavern 进程。
 * 启动酒馆时 startService，停止时 stopService。
 */
public class StapkForegroundService extends Service {

    private static final String CHANNEL_ID = "stapk_foreground";
    private static final int NOTIFICATION_ID = 1400;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SillyTavern",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("SillyTavern 服务运行状态");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, StapkControlActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stapk_notification)
                .setContentTitle("SillyTavern 运行中")
                .setContentText("酒馆服务正在运行")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
