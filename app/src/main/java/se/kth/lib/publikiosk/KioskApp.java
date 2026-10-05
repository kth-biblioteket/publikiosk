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

    @Override
    public void onCreate() {
        super.onCreate();
        if (!new KioskPolicy(this).isDeviceOwner()) return;

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Log.e("KioskApp", "Appen kraschade, startar om kiosken", error);
            try {
                Intent restart = new Intent(this, SplashActivity.class)
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
