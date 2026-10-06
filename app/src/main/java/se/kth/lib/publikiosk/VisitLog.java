package se.kth.lib.publikiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Besök för användningsstatistiken i publicomtools. Ett besök är från första trycket efter att
 * startsidan visats till sista trycket innan appen går tillbaka till början (efter inaktivitet,
 * Hem, nya inställningar …); den inaktiva tiden på slutet räknas inte. Inga adresser eller text,
 * bara tider och hur många sidor besökaren gick till.
 *
 * Avslutade besök köas i SharedPreferences och skickas med statusrapporten (StatusReporter), som
 * tar bort dem när publicomtools svarat visitsAck. En och samma instans för hela appen, så att ett
 * pågående besök överlever att aktiviteten skapas om.
 */
public final class VisitLog {

    private static final String TAG = "VisitLog";
    private static final String PREFS = "visits";
    private static final String KEY_QUEUE = "queue";
    /** Utan nät i flera dagar: behåll bara de senaste */
    private static final int MAX_QUEUED = 500;

    private static VisitLog instance;

    private final SharedPreferences prefs;
    private long startedAt = 0;
    private long lastActivityAt = 0;
    private int pages = 0;

    public static synchronized VisitLog get(Context context) {
        if (instance == null) instance = new VisitLog(context.getApplicationContext());
        return instance;
    }

    private VisitLog(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Ett tryck: startar ett besök om inget pågår */
    public synchronized void activity() {
        long now = System.currentTimeMillis();
        if (startedAt == 0) {
            startedAt = now;
            pages = 0;
        }
        lastActivityAt = now;
    }

    /** Besökaren gick till en ny sida (inte startsidan) */
    public synchronized void page() {
        if (startedAt != 0) pages++;
    }

    /** Besöket är slut: idle, home, config, reboot eller crash */
    public synchronized void end(String reason) {
        if (startedAt == 0) return;
        try {
            JSONArray queue = queue();
            JSONObject visit = new JSONObject();
            visit.put("start", startedAt / 1000);
            visit.put("end", Math.max(startedAt, lastActivityAt) / 1000);
            visit.put("reason", reason);
            visit.put("pages", pages);
            queue.put(visit);
            JSONArray kept = new JSONArray();
            for (int i = Math.max(0, queue.length() - MAX_QUEUED); i < queue.length(); i++) kept.put(queue.get(i));
            prefs.edit().putString(KEY_QUEUE, kept.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Besöket kunde inte sparas", e);
        }
        startedAt = 0;
        lastActivityAt = 0;
        pages = 0;
    }

    /** Besöken som väntar på att skickas (en kopia) */
    public synchronized JSONArray pending() {
        return queue();
    }

    /** publicomtools har sparat de första count besöken */
    public synchronized void acknowledge(int count) {
        JSONArray queue = queue();
        JSONArray kept = new JSONArray();
        try {
            for (int i = count; i < queue.length(); i++) kept.put(queue.get(i));
        } catch (JSONException e) {
            Log.w(TAG, "Kön kunde inte läsas", e);
        }
        prefs.edit().putString(KEY_QUEUE, kept.toString()).apply();
    }

    private JSONArray queue() {
        try {
            return new JSONArray(prefs.getString(KEY_QUEUE, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
