package com.offworkpet.receiver;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;

final class Notifications {
    static final String STATUS_CHANNEL = "receiver_status_v1", ALERT_CHANNEL = "rally_alerts_v2";
    static final int STATUS_ID = 1;
    private int nextAlertId = 100;
    private final Context context;
    private final NotificationManager manager;
    Notifications(Context context) {
        this.context = context; manager = context.getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(STATUS_CHANNEL, "接收状态", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("安静显示连接状态，可随时停止接收"); status.setSound(null, null); status.enableVibration(false);
        manager.createNotificationChannel(status);
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL, "集会提醒", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("收到电脑发来的提醒时弹出并震动，默认不响铃");
        alerts.setSound(null, null); alerts.enableVibration(false);
        alerts.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE); manager.createNotificationChannel(alerts);
    }
    boolean allowed() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(ALERT_CHANNEL).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    private PendingIntent open() {
        return PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    Notification status(String text) {
        PendingIntent stop = PendingIntent.getService(context, 1, new Intent(context, ReceiverService.class).setAction(ReceiverService.STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, STATUS_CHANNEL).setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("搭子提醒").setContentText(text).setContentIntent(open())
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止接收", stop).build()).build();
    }
    void updateStatus(String text) { manager.notify(STATUS_ID, status(text)); }
    void alert(AlertProtocol.Alert signal) {
        if (!allowed()) return;
        Notification publicVersion = new Notification.Builder(context, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("收到集会提醒").setContentText("解锁查看提醒内容").build();
        Notification notification = new Notification.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_bell).setContentTitle(signal.label)
                .setContentText(signal.source.isEmpty() ? "来自集会" : "来自 " + signal.source)
                .setColor(Color.parseColor(signal.color)).setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(open()).setAutoCancel(true).setOnlyAlertOnce(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                .setTimeoutAfter(signal.remainingMillis).build();
        manager.notify(nextAlertId++, notification);
        if (new Settings(context).prefs.getBoolean("vibrate", true)
                && manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL) {
            // One short pulse for every incoming signal; independent of notification update throttling.
            android.os.Vibrator vibrator = context.getSystemService(android.os.Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());
        }
    }
    void test() { alert(new AlertProtocol.Alert("test", "safe", "测试提醒", "#287A60", "手机本机测试", 6000)); }
    void clearAlert() {
        for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications())
            if (n.getId() != STATUS_ID) manager.cancel(n.getId());
    }
}
