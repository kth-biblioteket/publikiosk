package se.kth.lib.publikiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Inställningarna från publicomtools, skrivna till samma SharedPreferences ("MyPrefs") som
 * menyn använder, så att resten av appen läser dem som förut och enheten fungerar utan nät
 * med den senaste kopian. Nycklarna och standardvärdena står i config/catalog.json; en nyckel
 * som inte är satt i admin får katalogens standardvärde. Ogiltiga värden ignoreras (det
 * nuvarande värdet behålls).
 */
public final class ManagedConfig {

    private static final String TAG = "ManagedConfig";
    static final String PREFS = "MyPrefs";

    /** Sätts när enheten styrs från publicomtools: menyns fält är då skrivskyddade */
    static final String PREF_MANAGED = "managed";
    static final String PREF_CONFIG_VERSION = "configversion";
    static final String PREF_HEARTBEAT_INTERVAL = "heartbeatinterval";
    static final String PREF_APP_RELEASE = "apprelease";
    static final String PREF_COMPUTER_NAME = "computername";

    private ManagedConfig() {
    }

    /** Skriver inställningarna. Returnerar true om något som syns på skärmen ändrades. */
    public static boolean apply(Context context, JSONObject config) {
        JSONObject values = config.optJSONObject("values");
        if (values == null) values = new JSONObject();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = prefs.edit();
        boolean changed = false;

        String url = values.optString("START_URL", "https://wagnerguide.com/c/kth/kth").trim();
        if (url.startsWith("https://")) changed |= putString(prefs, e, "url", url);
        else Log.w(TAG, "START_URL är inte https, behåller den nuvarande: " + url);

        changed |= putString(prefs, e, "allowedhosts", values.optString("ALLOWED_HOSTS", "kth.se").trim());
        changed |= putString(prefs, e, "inactivitytimeout", String.valueOf(seconds(values, "INACTIVITY_TIMEOUT", 60) * 1000L));
        changed |= putString(prefs, e, "inactivitytimeoutweb", String.valueOf(seconds(values, "INACTIVITY_TIMEOUT_WEB", 30) * 1000L));
        changed |= putBoolean(prefs, e, "clearsession", bool(values, "CLEAR_SESSION_ON_IDLE", true));
        String orientation = values.optString("ORIENTATION", "landscape").toLowerCase(Locale.ROOT);
        changed |= putInt(prefs, e, "orientation", "portrait".equals(orientation) ? 0 : 1);
        changed |= putString(prefs, e, "initialscale", String.valueOf(intValue(values, "INITIAL_SCALE", 100, 10, 500)));
        changed |= putBoolean(prefs, e, "fullscreen", bool(values, "FULLSCREEN", true));

        String navigation = values.optString("NAVIGATION", "auto").toLowerCase(Locale.ROOT);
        if (!navigation.equals("always") && !navigation.equals("none")) navigation = "auto";
        changed |= putString(prefs, e, "navigation", navigation);
        changed |= putString(prefs, e, "appscope", values.optString("APP_SCOPE", "").trim());
        String homeMode = "launcher".equals(values.optString("HOME_MODE", "app").trim()) ? "launcher" : "app";
        changed |= putString(prefs, e, "homemode", homeMode);
        changed |= putString(prefs, e, "launchertitle", values.optString("LAUNCHER_TITLE", "").trim());
        changed |= putString(prefs, e, "launchersubtitle", values.optString("LAUNCHER_SUBTITLE", "").trim());
        changed |= putString(prefs, e, "launcherfooter", values.optString("LAUNCHER_FOOTER", "").trim());
        changed |= putString(prefs, e, "launchertitle_en", values.optString("LAUNCHER_TITLE_EN", "").trim());
        changed |= putString(prefs, e, "launchersubtitle_en", values.optString("LAUNCHER_SUBTITLE_EN", "").trim());
        changed |= putString(prefs, e, "launcherfooter_en", values.optString("LAUNCHER_FOOTER_EN", "").trim());
        changed |= putString(prefs, e, "launcherfield1", values.optString("LAUNCHER_FIELD_1", "").trim());
        changed |= putString(prefs, e, "launcherfield2", values.optString("LAUNCHER_FIELD_2", "").trim());
        changed |= putString(prefs, e, "launcherfield3", values.optString("LAUNCHER_FIELD_3", "").trim());
        changed |= putString(prefs, e, "launcherfield4", values.optString("LAUNCHER_FIELD_4", "").trim());
        changed |= putString(prefs, e, "launchermessage", values.optString("LAUNCHER_MESSAGE", "").trim());
        changed |= putString(prefs, e, "launchermessage_en", values.optString("LAUNCHER_MESSAGE_EN", "").trim());
        changed |= putString(prefs, e, "launchermessageurl", values.optString("LAUNCHER_MESSAGE_URL", "").trim());
        String messageStyle = values.optString("LAUNCHER_MESSAGE_STYLE", "warning").trim();
        changed |= putString(prefs, e, "launchermessagestyle", "alert".equals(messageStyle) || "info".equals(messageStyle) ? messageStyle : "warning");
        String messageIcon = values.optString("LAUNCHER_MESSAGE_ICON", "info").trim();
        changed |= putString(prefs, e, "launchermessageicon", messageIcon.isEmpty() ? "info" : messageIcon);
        changed |= putInt(prefs, e, "launcherrefresh", intValue(values, "LAUNCHER_REFRESH", 1, 1, 60));
        changed |= putString(prefs, e, "apps", values.optString("APPS", "").trim());
        changed |= putString(prefs, e, "startlabel", values.optString("START_LABEL", "").trim());
        String startIcon = values.optString("START_ICON", "house").trim();
        changed |= putString(prefs, e, "starticon", startIcon.isEmpty() ? "house" : startIcon);
        changed |= putInt(prefs, e, "idlewarning", intValue(values, "IDLE_WARNING", 10, 0, 120));
        changed |= putString(prefs, e, "language", "en".equals(values.optString("LANGUAGE", "sv")) ? "en" : "sv");
        changed |= putBoolean(prefs, e, "webdebug", bool(values, "WEB_DEBUG", false));

        // Påverkar inte skärmen
        e.putInt(PREF_HEARTBEAT_INTERVAL, intValue(values, "HEARTBEAT_INTERVAL", 5, 1, 60));
        e.putString(PREF_APP_RELEASE, values.optString("APP_RELEASE", "latest").trim());
        e.putString(PREF_COMPUTER_NAME, values.optString("COMPUTER_NAME", ""));
        e.putString(PREF_CONFIG_VERSION, config.optString("version", ""));
        e.putBoolean(PREF_MANAGED, true);
        e.apply();
        return changed;
    }

    public static boolean isManaged(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_MANAGED, false);
    }

    /** Enheten kopplas från (t ex inskriven på nytt): menyn går att använda igen. */
    public static void release(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_MANAGED, false).apply();
    }

    public static String configVersion(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_CONFIG_VERSION, "");
    }

    public static int heartbeatIntervalMinutes(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PREF_HEARTBEAT_INTERVAL, 5);
    }

    public static String computerName(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_COMPUTER_NAME, "");
    }

    private static int seconds(JSONObject values, String key, int def) {
        return intValue(values, key, def, 5, 24 * 3600);
    }

    private static int intValue(JSONObject values, String key, int def, int min, int max) {
        String v = values.optString(key, "").trim();
        if (v.isEmpty()) return def;
        try {
            int n = Integer.parseInt(v);
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) {
        }
        Log.w(TAG, key + " ogiltigt (" + v + "), använder " + def);
        return def;
    }

    private static boolean bool(JSONObject values, String key, boolean def) {
        String v = values.optString(key, "").trim();
        if ("true".equals(v)) return true;
        if ("false".equals(v)) return false;
        return def;
    }

    private static boolean putString(SharedPreferences prefs, SharedPreferences.Editor e, String key, String value) {
        boolean changed = !value.equals(prefs.getString(key, null));
        e.putString(key, value);
        return changed;
    }

    private static boolean putBoolean(SharedPreferences prefs, SharedPreferences.Editor e, String key, boolean value) {
        boolean changed = !prefs.contains(key) || prefs.getBoolean(key, !value) != value;
        e.putBoolean(key, value);
        return changed;
    }

    private static boolean putInt(SharedPreferences prefs, SharedPreferences.Editor e, String key, int value) {
        boolean changed = !prefs.contains(key) || prefs.getInt(key, value - 1) != value;
        e.putInt(key, value);
        return changed;
    }
}
