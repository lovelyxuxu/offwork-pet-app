package com.offworkpet.receiver;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;
import android.net.ConnectivityManager;
import android.net.Network;

public final class ReceiverService extends Service {
    static final String STOP = "com.offworkpet.receiver.STOP";
    static volatile boolean active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Settings settings;
    private Notifications notifications;
    private RelayClient client;
    private boolean ended;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network lastNetwork;
    private boolean networkBlocked;
    private final Runnable renewWakeLock = new Runnable() {
        public void run() {
            if (ended || !active) return;
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(10 * 60_000L);
            main.postDelayed(this, 5 * 60_000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate(); settings = new Settings(this); notifications = new Notifications(this);
        settings.diagnostic("service_create version=" + BuildConfig.VERSION_NAME);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            finish("已停止接收"); return START_NOT_STICKY;
        }
        if (!settings.enabled()) { stopSelf(); return START_NOT_STICKY; }
        if (!notifications.allowed()) { finish("通知权限已关闭，请允许通知后重新开始"); return START_NOT_STICKY; }
        // Promote immediately, before network I/O and before registering the device.
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notifications.STATUS_ID, notifications.status("正在连接…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(Notifications.STATUS_ID, notifications.status("正在连接…"));
        } catch (RuntimeException e) {
            finish("系统未允许后台接收，请返回应用重新开始"); return START_NOT_STICKY;
        }
        if (client != null) return START_STICKY;
        active = true; ended = false;
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OffworkPet:liveReceiver");
        wakeLock.setReferenceCounted(false); renewWakeLock.run();
        String endpoint = BuildConfig.RELAY_URL;
        // Only debug builds accept a local test relay. Production has no endpoint input.
        if (BuildConfig.DEBUG && intent != null && intent.hasExtra("test_endpoint")) endpoint = intent.getStringExtra("test_endpoint");
        try {
            client = new RelayClient(endpoint, settings.code(), settings.nickname(), settings, SystemClock::elapsedRealtime, new RelayClient.Listener() {
                public void diagnostic(String event) { main.post(() -> {
                    android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
                    settings.diagnostic(event + " idle=" + power.isDeviceIdleMode()
                        + " exempt=" + power.isIgnoringBatteryOptimizations(getPackageName())
                        + " wake=" + (wakeLock != null && wakeLock.isHeld()));
                }); }
                public void traffic(long elapsed) { main.post(() -> settings.prefs.edit().putLong("last_data_at",
                        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsed)).apply()); }
                public void status(String text) { main.post(() -> {
                    if (!ended) { settings.status(text); notifications.updateStatus(text); }
                }); }
                public void alert(AlertProtocol.Alert alert) { main.post(() -> {
                    if (ended || !settings.enabled()) return;
                    if (!notifications.allowed()) { finish("通知权限已关闭，请允许通知后重新开始"); return; }
                    notifications.alert(alert);
                    settings.prefs.edit().putString("last_alert", alert.label + " · " + (alert.source.isEmpty() ? "搭子" : alert.source))
                            .putLong("last_alert_at", System.currentTimeMillis()).apply();
                }); }
                public void fatal(String text) { main.post(() -> { if (!ended) finish(text); }); }
            });
            client.start();
            connectivity = getSystemService(ConnectivityManager.class);
            lastNetwork = connectivity.getActiveNetwork();
            settings.prefs.edit().remove("network_blocked").apply();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    main.post(() -> {
                        if (!ended && !network.equals(lastNetwork)) {
                            lastNetwork = network; networkBlocked = false;
                            settings.prefs.edit().remove("network_blocked").apply();
                            if (client != null) client.networkAvailable();
                        }
                    });
                }
                @Override public void onBlockedStatusChanged(Network network, boolean blocked) {
                    main.post(() -> {
                        if (ended || !network.equals(lastNetwork)) return;
                        boolean wasBlocked = networkBlocked; networkBlocked = blocked;
                        settings.prefs.edit().putBoolean("network_blocked", blocked).apply();
                        settings.diagnostic("network_blocked=" + blocked + " restrict_background=" + connectivity.getRestrictBackgroundStatus());
                        if (blocked) {
                            settings.status("系统限制应用联网"); notifications.updateStatus("系统限制应用联网");
                        } else if (wasBlocked && client != null) client.networkAvailable();
                    });
                }
                @Override public void onLost(Network network) {
                    main.post(() -> { if (network.equals(lastNetwork)) { lastNetwork = null; settings.diagnostic("default_network_lost"); } });
                }
            };
            connectivity.registerDefaultNetworkCallback(networkCallback);
        } catch (IllegalArgumentException e) { finish(e.getMessage()); return START_NOT_STICKY; }
        return START_STICKY;
    }
    private void releaseBackground() {
        main.removeCallbacks(renewWakeLock);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (connectivity != null && networkCallback != null) {
            connectivity.unregisterNetworkCallback(networkCallback); networkCallback = null;
        }
    }
    private void finish(String text) {
        ended = true; active = false; settings.enabled(false); settings.status(text);
        if (client != null) { client.close(); client = null; }
        releaseBackground(); notifications.clearAlert(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        ended = true; active = false;
        settings.diagnostic("service_destroy");
        if (client != null) client.close();
        releaseBackground(); notifications.clearAlert(); main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
