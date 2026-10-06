package se.kth.lib.publikiosk;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Statusrapport till publicomtools med intervallet från inställningarna (HEARTBEAT_INTERVAL):
 * app- och WebView-version, modell, batteri, om startsidan laddats och om enheten är låst i
 * kioskläge. Appen kör alltid i förgrunden som kiosk, så en Handler räcker.
 */
public class StatusReporter {

    private static final String TAG = "StatusReporter";
    private static final int CLIENT_VERSION = 1;

    /** Läget i appen när rapporten skickas, och kommandona i svaret */
    public interface State {
        Boolean pageLoaded();

        /** Senaste händelsen i menyn (PIN bytt, upplåst …), eller null */
        String menuEvent();

        /**
         * Svaret från publicomtools, på huvudtråden: reload (hämta inställningar), reboot (starta om),
         * screenshot (skärmdump) och pinUnlock (lås upp menyn).
         */
        void onCommands(JSONObject response);
    }

    private final Context context;
    private final PublicomClient client;
    private final State state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Delad mellan instanser: aktiviteten kan skapas om (t ex efter att WebView-processen försvunnit) */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean running = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            sendNow();
            handler.postDelayed(this, ManagedConfig.heartbeatIntervalMinutes(context) * 60_000L);
        }
    };

    public StatusReporter(Context context, PublicomClient client, State state) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.state = state;
    }

    /** Första rapporten skickas när inställningarna hämtats (sendNow), sedan med intervallet. */
    public void start() {
        if (running) return;
        running = true;
        handler.postDelayed(tick, ManagedConfig.heartbeatIntervalMinutes(context) * 60_000L);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** En rapport direkt (t ex när startsidan laddats efter nya inställningar). */
    public void sendNow() {
        if (!client.isEnrolled()) return;
        JSONObject status = collect();
        // Besöken som skickas nu tas bort först när publicomtools har sparat dem
        JSONArray visits = VisitLog.get(context).pending();
        try {
            if (visits.length() > 0) status.put("visits", visits);
        } catch (JSONException e) {
            Log.w(TAG, "Besöken kunde inte läggas till", e);
        }
        executor.execute(() -> {
            try {
                JSONObject response = client.heartbeat(status);
                if (response != null && visits.length() > 0 && response.optBoolean("visitsAck")) {
                    VisitLog.get(context).acknowledge(visits.length());
                }
                if (response != null) handler.post(() -> state.onCommands(response));
            } catch (Exception e) {
                Log.w(TAG, "Statusrapporten kunde inte skickas: " + e.getMessage());
            }
        });
    }

    private JSONObject collect() {
        JSONObject s = new JSONObject();
        try {
            String host = client.host();
            s.put("clientVersion", CLIENT_VERSION);
            s.put("platform", "android");
            s.put("host", host);
            s.put("hostname", host);
            String name = ManagedConfig.computerName(context);
            if (!name.isEmpty()) s.put("computerName", name);
            s.put("uptimeSeconds", SystemClock.elapsedRealtime() / 1000);
            s.put("os", "Android " + Build.VERSION.RELEASE);
            s.put("model", (Build.MANUFACTURER + " " + Build.MODEL).trim());
            s.put("appVersion", appVersion());
            String webView = webViewVersion();
            if (webView != null) s.put("webViewVersion", webView);
            String version = ManagedConfig.configVersion(context);
            if (!version.isEmpty()) s.put("configVersion", version);
            s.put("intervalMinutes", ManagedConfig.heartbeatIntervalMinutes(context));
            String menuEvent = state.menuEvent();
            if (menuEvent != null) s.put("menuEvent", menuEvent);
            Boolean loaded = state.pageLoaded();
            if (loaded != null) s.put("pageLoaded", loaded);
            s.put("kioskLocked", kioskLocked());
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) s.put("batteryPercent", Math.round(level * 100f / scale));
                // Utan batteri (t ex en skärm på nätström) rapporteras det inte som laddning
                s.put("charging", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Kunde inte samla status", e);
        }
        return s;
    }

    private String appVersion() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "okänd";
        }
    }

    private static String webViewVersion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        PackageInfo info = WebView.getCurrentWebViewPackage();
        return info == null ? null : info.versionName;
    }

    private boolean kioskLocked() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }
}
