package se.kth.lib.publikiosk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Liten händelselogg som överlever att appen dör: start, stopp, kraschar, minnestryck, förlorad fokus,
 * omstart, uppdatering och livstecken. De senaste händelserna följer med statusrapporten, så att man
 * kan se varför en kiosk försvann utan att koppla in en dator (Android 10 och äldre har inget
 * getHistoricalProcessExitReasons). Sparas med commit så att en krasch hinner skriva sin rad.
 */
final class EventLog {

    private static final String PREFS = "EventLog";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_ALIVE = "alive";
    private static final int MAX = 40;

    private EventLog() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void add(Context c, String text) {
        try {
            SharedPreferences p = prefs(c);
            String clean = text.replace('\n', ' ').replace('\t', ' ');
            if (clean.length() > 200) clean = clean.substring(0, 200);
            String all = p.getString(KEY_EVENTS, "");
            String[] lines = all.isEmpty() ? new String[0] : all.split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, lines.length - (MAX - 1));
            for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
            long now = System.currentTimeMillis();
            sb.append(now).append('\t').append(clean);
            p.edit().putString(KEY_EVENTS, sb.toString()).putLong(KEY_ALIVE, now).commit();
        } catch (Exception e) {
            android.util.Log.w("EventLog", "Kunde inte skriva", e);
            // loggen får aldrig störa kiosken
        }
    }

    /** Livstecken: appen kör fortfarande (anropas varje minut) */
    static void alive(Context c) {
        try {
            prefs(c).edit().putLong(KEY_ALIVE, System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        }
    }

    static long lastAlive(Context c) {
        return prefs(c).getLong(KEY_ALIVE, 0);
    }

    static String time(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(millis));
    }

    /** De senaste n händelserna, äldst först, som "yyyy-MM-dd HH:mm:ss text" i enhetens tid */
    static synchronized List<String> recent(Context c, int n) {
        List<String> out = new ArrayList<>();
        String all = prefs(c).getString(KEY_EVENTS, "");
        if (all.isEmpty()) return out;
        String[] lines = all.split("\n");
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            int tab = lines[i].indexOf('\t');
            if (tab < 0) continue;
            try {
                out.add(time(Long.parseLong(lines[i].substring(0, tab))) + " " + lines[i].substring(tab + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static JSONArray recentJson(Context c, int n) {
        JSONArray a = new JSONArray();
        for (String s : recent(c, n)) a.put(s);
        return a;
    }
}
