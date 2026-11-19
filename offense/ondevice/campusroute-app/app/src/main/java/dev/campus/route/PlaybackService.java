package dev.campus.route;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class PlaybackService extends Service {
    static final String PREPARE = "dev.campus.route.PREPARE";
    static final String MOVE = "dev.campus.route.MOVE";
    static final String STOP = "dev.campus.route.STOP";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 101;
    private static final long END_HOLD_MS = 60_000;
    private static final String TAG = "CampusPlayback";

    enum Phase { IDLE, READY, MOVING, ARRIVED, FINISHED, STOPPED, FAILED }

    static final class Status {
        final Phase phase;
        final Geo.Route route;
        final Geo.Point point;
        final double distance;
        final long endDeadline;
        final String error;

        Status(Phase phase, Geo.Route route, Geo.Point point, double distance,
                long endDeadline, String error) {
            this.phase = phase;
            this.route = route;
            this.point = point;
            this.distance = distance;
            this.endDeadline = endDeadline;
            this.error = error;
        }

        boolean active() {
            return phase == Phase.READY || phase == Phase.MOVING || phase == Phase.ARRIVED;
        }
    }

    private static volatile Status current = new Status(Phase.IDLE, null, null, 0, 0, null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> providers = new ArrayList<>();
    private LocationManager locations;
    private PowerManager.WakeLock wakeLock;
    private Geo.Route route;
    private double metersPerSecond;
    private long movedAt;
    private long endDeadline;
    private Phase phase = Phase.IDLE;

    private final Runnable tick = this::publish;

    static Status status() { return current; }

    static Intent prepare(Context context, Geo.Route route, double metersPerSecond) {
        return new Intent(context, PlaybackService.class).setAction(PREPARE)
                .putExtra("coordinates", route.coordinates()).putExtra("speed", metersPerSecond);
    }

    static Intent command(Context context, String action) {
        return new Intent(context, PlaybackService.class).setAction(action);
    }

    static boolean validSpeed(double speed) {
        return speed > 0 && speed <= Float.MAX_VALUE && Float.isFinite((float) speed) && (float) speed > 0;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? null : intent.getAction();
        if (PREPARE.equals(action)) {
            if (route == null) startSession(intent);
        } else if (MOVE.equals(action) && phase == Phase.READY) {
            startMotion();
        } else if (STOP.equals(action)) {
            finish(Phase.STOPPED, null);
        } else if (route == null) {
            stopSelf(id);
        }
        return START_NOT_STICKY;
    }

    private void startSession(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(Phase.READY),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification(Phase.READY));
            }
            route = Geo.Route.fromCoordinates(intent.getDoubleArrayExtra("coordinates"));
            metersPerSecond = intent.getDoubleExtra("speed", 0);
            if (!validSpeed(metersPerSecond)) {
                finish(Phase.FAILED, "请输入有效的速度");
                return;
            }
            locations = getSystemService(LocationManager.class);
            addProvider(LocationManager.GPS_PROVIDER, true, false);
            addOptionalProvider(LocationManager.NETWORK_PROVIDER, true);
            if (Build.VERSION.SDK_INT >= 31 && locations.hasProvider("fused")) {
                addOptionalProvider("fused", true);
            }
            phase = Phase.READY;
            publish();
        } catch (SecurityException error) {
            Log.w(TAG, "Mock provider or foreground permission denied", error);
            finish(Phase.FAILED, "无法输出定位。请在开发者选项中选择「校园路线」，并允许定位权限。");
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not prepare route playback", error);
            finish(Phase.FAILED, "系统定位启动失败，请检查手机定位设置后重试。");
        }
    }

    @SuppressWarnings("deprecation")
    private void addProvider(String name, boolean satellite, boolean network) {
        locations.addTestProvider(name, network, satellite, network, false,
                false, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        providers.add(name);
        locations.setTestProviderEnabled(name, true);
    }

    private void addOptionalProvider(String name, boolean network) {
        try {
            addProvider(name, false, network);
        } catch (RuntimeException error) {
            Log.i(TAG, "Optional location provider unavailable: " + name);
            if (providers.remove(name)) removeProvider(name);
        }
    }

    private void startMotion() {
        try {
            PowerManager manager = getSystemService(PowerManager.class);
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CampusRoute:Motion");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            movedAt = SystemClock.elapsedRealtime();
            phase = Phase.MOVING;
            refreshNotification();
            handler.removeCallbacks(tick);
            publish();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not begin motion", error);
            finish(Phase.FAILED, "开始移动失败。请重新准备系统定位。");
        }
    }

    private void publish() {
        long now = SystemClock.elapsedRealtime();
        double distance = phase == Phase.READY ? 0
                : Math.min(route.meters, (now - movedAt) / 1_000.0 * metersPerSecond);
        if (phase == Phase.MOVING && distance >= route.meters) {
            phase = Phase.ARRIVED;
            endDeadline = now + END_HOLD_MS;
            releaseWakeLock();
            refreshNotification();
        }
        if (phase == Phase.ARRIVED && now >= endDeadline) {
            finish(Phase.FINISHED, null);
            return;
        }

        Geo.Sample sample = route.at(distance);
        float speed = phase == Phase.MOVING ? (float) metersPerSecond : 0;
        long time = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtimeNanos();
        try {
            for (int i = 0; i < providers.size();) {
                String provider = providers.get(i);
                try {
                    Location location = new Location(provider);
                    location.setLatitude(sample.point.latitude);
                    location.setLongitude(sample.point.longitude);
                    location.setTime(time);
                    location.setElapsedRealtimeNanos(elapsed);
                    location.setAccuracy(5);
                    location.setSpeed(speed);
                    if (speed > 0) location.setBearing(sample.bearing);
                    locations.setTestProviderLocation(provider, location);
                    i++;
                } catch (SecurityException | IllegalArgumentException error) {
                    if (LocationManager.GPS_PROVIDER.equals(provider)) throw error;
                    providers.remove(i);
                    removeProvider(provider);
                }
            }
            current = new Status(phase, route, sample.point, distance, endDeadline, null);
        } catch (RuntimeException error) {
            Log.w(TAG, "Location output interrupted", error);
            finish(Phase.FAILED, "系统定位输出中断，请检查模拟定位应用设置。");
            return;
        }

        long interval = 1_000;
        if (phase == Phase.MOVING) {
            interval = Math.max(1, Math.min(interval, (long) Math.ceil((route.meters - distance)
                    / metersPerSecond * 1_000)));
        } else if (phase == Phase.ARRIVED) {
            interval = Math.max(1, Math.min(interval, endDeadline - now));
        }
        handler.postDelayed(tick, interval);
    }

    private Notification notification(Phase state) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "路线定位输出",
                NotificationManager.IMPORTANCE_LOW));
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent move = PendingIntent.getService(this, 2, command(this, MOVE), flags);
        PendingIntent stop = PendingIntent.getService(this, 3, command(this, STOP), flags);
        String title;
        String message;
        if (state == Phase.READY) {
            title = "已定位到起点";
            message = "准备好后，点下方按钮开始移动。";
        } else if (state == Phase.ARRIVED) {
            title = "已到达终点";
            message = "终点定位保留 60 秒，也可以提前停止输出。";
        } else {
            title = "正在输出路线定位";
            message = "按设定速度移动中。";
        }
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(Ui.ACCENT)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        if (state == Phase.READY) {
            builder.addAction(new Notification.Action.Builder(null, "开始移动", move).build());
        }
        return builder.addAction(new Notification.Action.Builder(null, "停止输出", stop).build()).build();
    }

    private void refreshNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(phase));
    }

    private void removeProvider(String provider) {
        try {
            locations.removeTestProvider(provider);
        } catch (RuntimeException error) {
            Log.i(TAG, "Provider no longer removable: " + provider);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void cleanup() {
        handler.removeCallbacks(tick);
        releaseWakeLock();
        for (int i = providers.size() - 1; i >= 0; i--) {
            removeProvider(providers.get(i));
        }
        providers.clear();
        route = null;
    }

    private void finish(Phase result, String error) {
        Status last = current;
        current = new Status(result, route, last.point, last.distance, 0, error);
        phase = result;
        cleanup();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (current.active()) {
            Status last = current;
            current = new Status(Phase.STOPPED, route, last.point, last.distance, 0, null);
        }
        cleanup();
        super.onDestroy();
    }
}
