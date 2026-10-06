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
 * Ett besök kräver minst två tryck med minst två sekunders mellanrum: ett ensamt tryck (någon
 * nuddar skärmen i förbifarten) räknas inte. Tryck i inställningsmenyn räknas inte heller.
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
    private static final int MIN_TAPS = 2;
    private static final long MIN_DURATION_MS = 2_000;

    private static VisitLog instance;

    private final SharedPreferences prefs;
    private long startedAt = 0;
    private long lastActivityAt = 0;
    private int pages = 0;
    private int taps = 0;

    public static synchronized VisitLog get(Context context) {
        if (instance == null) instance = new VisitLog(context.getApplicationContext());
        return instance;
    }

    private VisitLog(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Beröring av skärmen: startar ett besök om inget pågår. newTap: ett nytt tryck (fingret ner),
     * inte en rörelse i samma tryck.
     */
    public synchronized void activity(boolean newTap) {
        long now = System.currentTimeMillis();
        if (startedAt == 0) {
            startedAt = now;
            pages = 0;
            taps = 0;
        }
        if (newTap) taps++;
        lastActivityAt = now;
    }

    /**
     * Inställningsmenyn öppnas (trycket i hörnet): tryck under de senaste withinMs var personal,
     * inte en besökare. Ett besök som pågått längre behålls.
     */
    public synchronized void discardIfRecent(long withinMs) {
        if (startedAt != 0 && System.currentTimeMillis() - startedAt < withinMs) reset();
    }

    private void reset() {
        startedAt = 0;
        lastActivityAt = 0;
        pages = 0;
        taps = 0;
    }

    /** Besökaren gick till en ny sida (inte startsidan) */
    public synchronized void page() {
        if (startedAt != 0) pages++;
    }

    /** Besöket är slut: idle, home, config, reboot eller crash */
    public synchronized void end(String reason) {
        if (startedAt == 0) return;
        if (taps < MIN_TAPS || lastActivityAt - startedAt < MIN_DURATION_MS) {
            Log.d(TAG, "Ignorerade " + taps + " tryck (inget besök)");
            reset();
            return;
        }
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
        reset();
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
