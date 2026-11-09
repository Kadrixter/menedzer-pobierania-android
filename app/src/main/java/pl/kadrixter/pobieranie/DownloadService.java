package pl.kadrixter.pobieranie;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;

/**
 * Usługa pierwszoplanowa: trzyma proces przy życiu, nie pozwala telefonowi uśpić Wi-Fi/CPU
 * w trakcie pobierania i sama się wyłącza, gdy nie ma już nic do pobrania.
 */
public class DownloadService extends Service implements Downloader.Listener {
    static final String CH_PROGRESS = "progress", CH_DONE = "done";
    static final String ACTION_PAUSE_ALL = "pause_all";
    static final int NOTIF_ID = 1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Downloader dl;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private ConnectivityManager.NetworkCallback netCb;
    private long idleSince = 0;
    private int doneCount = 0;

    /** Uruchom usługę, jeśli jest coś do pobrania. */
    public static void ensureRunning(Context c) {
        if (Downloader.get(c).isBusy()) {
            c.startForegroundService(new Intent(c, DownloadService.class));
        }
    }

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel p = new NotificationChannel(CH_PROGRESS, "Postęp pobierania", NotificationManager.IMPORTANCE_LOW);
        p.setShowBadge(false);
        nm.createNotificationChannel(p);
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Ukończone pobierania", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        dl = Downloader.get(this);
        dl.listener = this;

        Notification n = buildProgress();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pobieranie:dl");
        wake.setReferenceCounted(false);
        wake.acquire();
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifi = wm.createWifiLock(mode, "pobieranie:wifi");
        wifi.setReferenceCounted(false);
        wifi.acquire();

        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { dl.retryNow(); }
        };
        try {
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCb);
        } catch (Exception ignored) { netCb = null; }

        h.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PAUSE_ALL.equals(intent.getAction())) dl.pauseAll();
        idleSince = 0;
        return START_STICKY; // jeśli system zabije proces – wstanie i wznowi pobieranie
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            dl.save(false);
            if (dl.isBusy()) {
                idleSince = 0;
                getSystemService(NotificationManager.class).notify(NOTIF_ID, buildProgress());
            } else {
                if (idleSince == 0) idleSince = System.currentTimeMillis();
                if (System.currentTimeMillis() - idleSince > 3000) { finishAll(); return; }
            }
            h.postDelayed(this, 1000);
        }
    };

    private void finishAll() {
        dl.save(true);
        int errs = 0, paused = 0;
        for (Downloader.Item i : dl.snapshot()) {
            if (i.status == Downloader.ERROR) errs++;
            if (i.status == Downloader.PAUSED) paused++;
        }
        if (doneCount > 0 && paused == 0) {
            String t = "Wszystko pobrane (" + doneCount + ")" + (errs > 0 ? ", błędy: " + errs : "");
            Notification n = new Notification.Builder(this, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(t)
                    .setContentText("Menedżer wyłączył się z tła")
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build();
            getSystemService(NotificationManager.class).notify(2, n);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildProgress() {
        List<Downloader.Item> list = Downloader.get(this).snapshot();
        int active = 0, retry = 0, queued = 0;
        long got = 0, tot = 0;
        double speed = 0;
        boolean unknown = false;
        for (Downloader.Item i : list) {
            if (i.status == Downloader.ACTIVE) active++;
            else if (i.status == Downloader.RETRY) retry++;
            else if (i.status == Downloader.QUEUED) queued++;
            else continue;
            speed += i.speed;
            if (i.total > 0) { got += i.downloaded; tot += i.total; } else unknown = true;
        }
        int pct = tot > 0 ? (int) (got * 100 / tot) : 0;
        String title = Fmt.files(active + retry + queued) + " w toku";
        StringBuilder text = new StringBuilder();
        if (tot > 0) text.append(pct).append("% · ").append(Fmt.size(got)).append(" / ").append(Fmt.size(tot));
        if (speed > 0) text.append(" · ").append(Fmt.size((long) speed)).append("/s");
        if (retry > 0) text.append(" · ponawianie: ").append(retry);
        if (text.length() == 0) text.append("Łączenie…");

        PendingIntent pause = PendingIntent.getService(this, 1,
                new Intent(this, DownloadService.class).setAction(ACTION_PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, pct, tot == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .addAction(new Notification.Action.Builder(null, "Wstrzymaj wszystkie", pause).build())
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    @Override
    public void onDone(Downloader.Item it) {
        h.post(() -> {
            doneCount++;
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(it.uri), it.mime != null ? it.mime : "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, it.id.hashCode(),
                    Intent.createChooser(view, "Otwórz plik").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Pobrano")
                    .setContentText(it.filename)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            getSystemService(NotificationManager.class).notify(1000 + (it.id.hashCode() & 0xffff), n);
        });
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        if (dl.listener == this) dl.listener = null;
        if (netCb != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        }
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        dl.save(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
