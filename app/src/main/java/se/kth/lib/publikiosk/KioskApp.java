package se.kth.lib.publikiosk;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Startar om kiosken direkt om appen kraschar. Annars lämnar Android locktask och visar
 * enhetens vanliga startskärm, där en besökare kommer åt resten av enheten.
 * Bara när appen är device owner (då får den starta en aktivitet från bakgrunden).
 */
public class KioskApp extends Application {

    private static final long RESTART_DELAY_MS = 1500;
    private final android.os.Handler aliveHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Processen startade: när förra körningen sist visade livstecken, och om enheten startats om sedan dess */
    private void logStart() {
        long last = EventLog.lastAlive(this);
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        int boot = -1;
        try {
            boot = android.provider.Settings.Global.getInt(getContentResolver(), "boot_count", -1);
        } catch (Exception ignored) {
        }
        EventLog.add(this, "Start " + version + ", enhetens drifttid " + SystemClock.elapsedRealtime() / 1000 + " s, uppstart nr " + boot
                + (last > 0 ? ", förra livstecken " + EventLog.time(last) : ""));
        aliveHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                EventLog.alive(KioskApp.this);
                aliveHandler.postDelayed(this, 60_000);
            }
        }, 60_000);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logStart();
        if (!new KioskPolicy(this).isDeviceOwner()) return;

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Log.e("KioskApp", "Appen kraschade, startar om kiosken", error);
            EventLog.add(this, "Krasch: " + error);
            try {
                Intent restart = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pending = PendingIntent.getActivity(this, 0, restart,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
                AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending);
            } catch (Exception e) {
                Log.e("KioskApp", "Kunde inte schemalägga omstart", e);
            }
            // Avsluta direkt, utan Androids kraschdialog som annars står kvar framför kiosken
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }
}
