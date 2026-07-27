package com.lancast.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

public final class ReceiverKeepAliveService extends Service {
    private static final String CHANNEL_ID = "lancast_receiver";
    private static final int NOTIFICATION_ID = 470;
    private static final long HEALTH_CHECK_INTERVAL_MS = 5000;

    private final Handler handler = new Handler();
    private final Runnable healthCheck = new Runnable() {
        @Override public void run() {
            ensureReceiverActivity();
            handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        Intent service = new Intent(context, ReceiverKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) startForegroundServiceNotification();
        handler.post(healthCheck);
        Log.i("LanCast-Service", "Receiver keep-alive service started");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ensureReceiverActivity();
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        ensureReceiverActivity();
        super.onTaskRemoved(rootIntent);
    }

    private void ensureReceiverActivity() {
        if (ReceiverActivity.isReceiverVisible()) return;
        Intent launch = new Intent(this, ReceiverActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(launch);
        } catch (Exception e) {
            Log.e("LanCast-Service", "Unable to launch receiver activity", e);
        }
    }

    @android.annotation.TargetApi(26)
    private void startForegroundServiceNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "LanCast 接收服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持局域网投屏与 DLNA 接收服务运行");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);

        Intent open = new Intent(this, ReceiverActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, pendingFlags);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("LanCast 正在等待投屏")
                .setContentText("接收服务已在后台运行")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(healthCheck);
        Log.i("LanCast-Service", "Receiver keep-alive service stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
