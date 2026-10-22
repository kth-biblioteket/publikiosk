package se.kth.lib.publikiosk;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Skylt (HOME_MODE=signage): sidorna i SIGN_PAGES visas i tur och ordning i helskärm, bara visa (all inmatning
 * ignoreras, inga länkar följs). Som skylten i Linux-kiosken: två webbvyer ligger över varandra, båda synliga (annars
 * stryps skripten), den aktiva överst. Nästa sida laddas i den andra bakom, en stund före bytet, och byts in först
 * när den hunnit rita klart. En sida som inte går att ladda visar felsidan (utan knappar) och laddas om av sig själv
 * var 30:e sekund; rotationen går vidare i vanlig tid. Sidorna ritas för skärmens riktiga storlek (INITIAL_SCALE gäller).
 */
public class SignageScreen {

    static final class Page {
        final String url;
        final int seconds;
        final String start, end; // första och sista dag (YYYY-MM-DD, inklusive), null = ingen gräns

        Page(String url, int seconds, String start, String end) {
            this.url = url;
            this.seconds = seconds;
            this.start = start;
            this.end = end;
        }

        boolean on(String date) {
            return (start == null || date.compareTo(start) >= 0) && (end == null || date.compareTo(end) <= 0);
        }
    }

    /** Sidorna som gäller på dagen (YYYY-MM-DD) */
    static List<Page> on(List<Page> pages, String date) {
        List<Page> out = new ArrayList<>();
        for (Page p : pages) if (p.on(date)) out.add(p);
        return out;
    }

    static String today(java.util.Calendar c) {
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** Nyckel för vilka sidor som visas, så att en ändring (en sida går ut eller börjar gälla) märks */
    static String signature(List<Page> pages) {
        StringBuilder sb = new StringBuilder();
        for (Page p : pages) sb.append(p.url).append('|');
        return Integer.toHexString(sb.toString().hashCode());
    }

    interface Overlay {
        /** Felsidan visas (offline: enheten saknar nät) eller tas bort */
        void error(boolean show, boolean offline);

        /** Sidan som visas är laddad: statusrapporten får veta det */
        void loaded(boolean ok);
    }

    static final int MIN_S = 5, MAX_S = 3600, MAX_PAGES = 36;
    private static final String TAG = "SignageScreen";
    private static final long RETRY_MS = 30_000;       // sidfel provas igen efter så här länge
    private static final long RETRY_NET_MS = 10_000;   // nätverksfel provas igen snabbare
    private static final long SKIP_MS = 10_000;        // en misslyckad sida lämnas efter så här länge (om listan har fler sidor)
    private static final int PRELOAD_S = 10;        // nästa sida börjar laddas så här många sekunder före bytet
    private static final long SETTLE_MS = 1500;     // tid efter laddning för att skripten ska hinna rita klart
    private static final long MAX_WAIT_MS = 10_000; // väntar högst så här länge på en sida som inte är färdig vid bytet

    private final Activity activity;
    private final View container;
    private final WebView[] views = new WebView[2];
    private final SandboxedPage[] pages = new SandboxedPage[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UrlPolicy policy;
    private Overlay overlay;
    private List<Page> list = new ArrayList<>();
    private List<Page> defaultList = new ArrayList<>();
    private String startUrl = "";
    private int reloadMinutes = 10; // SIGN_RELOAD: en ensam sida laddas om så här ofta (0 = aldrig)
    private SignSchedule schedule = null;
    private String currentKey = "";
    /** Varje ny planering av förladdning och byte får ett nummer; äldre planer som väntar ogiltigförklaras */
    private int generation = 0;
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private int index = 0, active = 0;
    private boolean running = false;

    public SignageScreen(Activity activity) {
        this.activity = activity;
        container = activity.findViewById(R.id.signage_page);
        views[0] = activity.findViewById(R.id.signage_view_a);
        views[1] = activity.findViewById(R.id.signage_view_b);
        for (int i = 0; i < 2; i++) {
            final int n = i;
            pages[i] = new SandboxedPage(views[i], new SandboxedPage.Host() {
                @Override
                public boolean allows(String u) {
                    return policy != null && policy.allows(u);
                }

                @Override
                public void failed() {
                    handler.post(() -> {
                        if (n == active) settle();
                    });
                }

                @Override
                public void recovered() {
                    handler.post(() -> {
                        if (n == active) settle();
                    });
                }
            });
            pages[i].followRedirects(true);
            pages[i].lockInput();
        }
    }

    /** SIGN_PAGES: en JSON-lista [{"url":"https://…","s":30}] eller en rad per sida "https://adress|sekunder" (rader skiljs av radbrytning eller bokstavliga \n). Bara https; högst 36. Utan sidor: reservadressen. */
    static List<Page> parse(String raw, int defaultSeconds, String fallbackUrl) {
        int def = clamp(defaultSeconds, 30);
        List<Page> out = new ArrayList<>();
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("[")) {
            try {
                JSONArray array = new JSONArray(text);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o != null) add(out, o.optString("url", ""), o.has("s") ? o.optString("s") : null, def, date(o, "start"), date(o, "end"));
                }
            } catch (org.json.JSONException ignored) {
                // tolkas som rader
            }
        }
        if (out.isEmpty() && !text.isEmpty() && !text.startsWith("[")) {
            String[] lines = text.contains("\n") ? text.split("\\r?\\n") : text.split("\\\\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                int bar = line.lastIndexOf('|');
                if (bar > 0 && line.substring(bar + 1).trim().matches("\\d+")) add(out, line.substring(0, bar), line.substring(bar + 1), def, null, null);
                else add(out, line, null, def, null, null);
            }
        }
        if (out.isEmpty()) add(out, fallbackUrl, null, def, null, null);
        return out;
    }

    /** Datumfält på en sida; saknas eller är ogiltigt: ingen gräns */
    private static String date(JSONObject o, String key) {
        String d = o.optString(key, "");
        return d.matches("\\d{4}-\\d{2}-\\d{2}") ? d : null;
    }

    private static void add(List<Page> out, String url, String seconds, int def, String start, String end) {
        String u = url == null ? "" : url.trim();
        if (!u.startsWith("https://") || android.net.Uri.parse(u).getHost() == null || out.size() >= MAX_PAGES) return;
        int s = def;
        if (seconds != null) {
            try {
                s = clamp(Integer.parseInt(seconds.trim()), def);
            } catch (NumberFormatException ignored) {
                s = def;
            }
        }
        out.add(new Page(u, s, start, end));
    }

    private static int clamp(int s, int def) {
        return s <= 0 ? def : Math.max(MIN_S, Math.min(MAX_S, s));
    }

    /** Värdarna som sidorna ligger på, för listan över tillåtna webbplatser */
    static String hosts(List<Page> pages) {
        StringBuilder sb = new StringBuilder();
        for (Page p : pages) {
            String h = android.net.Uri.parse(p.url).getHost();
            if (h != null) sb.append(',').append(h);
        }
        return sb.toString();
    }

    public void configure(List<Page> pages, String startUrl, int reloadMinutes, SignSchedule schedule, boolean scripts, boolean openResources, int scalePercent, UrlPolicy policy, Overlay overlay) {
        this.defaultList = pages;
        this.startUrl = startUrl;
        this.reloadMinutes = Math.max(0, Math.min(1440, reloadMinutes));
        this.schedule = schedule;
        this.list = pages;
        this.policy = policy;
        this.overlay = overlay;
        for (SandboxedPage p : this.pages) {
            p.configure(scripts, false, scalePercent);
            p.openResources(openResources);
        }
        for (SandboxedPage p : this.pages) p.lockInput();
    }

    public boolean isRunning() {
        return running;
    }

    /** Startar (eller startar om) rotationen från första sidan */
    public void start() {
        stop();
        running = true;
        container.setVisibility(View.VISIBLE);
        views[0].setVisibility(View.VISIBLE);
        views[1].setVisibility(View.VISIBLE);
        active = 0;
        raise();
        currentKey = "";
        applySchedule(true);
        tickHandler.removeCallbacksAndMessages(null);
        tickHandler.postDelayed(tick, 20_000);
    }

    /** Varje 20 sekunder: gäller en annan lista nu börjar den direkt */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            applySchedule(false);
            tickHandler.postDelayed(this, 20_000);
        }
    };

    /** Väljer listan som gäller nu (schema, annars standardlistan, eller svart skärm) och startar den om den ändrats */
    private void applySchedule(boolean first) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        SignSchedule.Choice c = schedule == null ? new SignSchedule.Choice("default", null, false)
                : schedule.choose(now);
        // Standardlistan filtreras på dagen; ingen sida kvar: startsidan
        List<Page> shown = c.pages;
        String key = c.key;
        if (!c.black && shown == null) {
            shown = on(defaultList, today(now));
            if (shown.isEmpty() && !startUrl.isEmpty()) shown = parse("", 30, startUrl);
            key = "default#" + signature(shown);
        }
        if (!first && key.equals(currentKey)) return;
        // Från svart skärm eller en sida som inte gick att ladda: den nya listan börjar direkt (inget att visa bakom)
        boolean direct = first || "off".equals(currentKey) || (pages[active].hasFailed() && !pages[active].isOk());
        currentKey = key;
        generation++;
        handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Lista: " + (c.black ? "svart skärm" : c.pages == null ? "standardlistan" : key.substring(5, key.indexOf('#'))));
        if (c.black) {
            // Svart skärm: sidorna släpps och inget visas
            for (SandboxedPage p : pages) p.clear();
            overlay.error(false, false);
            container.setBackgroundColor(android.graphics.Color.BLACK);
            return;
        }
        list = shown;
        if (list.isEmpty()) {
            // Inget att visa: felsidan står kvar tills inställningarna ändrats
            overlay.error(true, false);
            return;
        }
        if (direct) {
            overlay.error(false, false);
            container.setBackgroundColor(android.graphics.Color.BLACK);
            show(0);
        } else {
            // Byte av lista: börja direkt på första sidan i den nya, förladdad bakom den som visas
            pages[1 - active].load(list.get(0).url);
            final int g = ++generation;
            handler.postDelayed(() -> swapTo(0, 0, g), 800);
        }
        // En enda sida laddas om med jämna mellanrum (SIGN_RELOAD)
        if (list.size() == 1 && reloadMinutes > 0) handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running || list.size() != 1) return;
                softReload(list.get(0).url);
                handler.postDelayed(this, reloadMinutes * 60_000L);
            }
        }, reloadMinutes * 60_000L);
    }

    /** Mjuk omladdning: sidan laddas i den dolda vyn och byts in när den ritat klart. Går den inte att ladda står den gamla kvar. */
    private void softReload(String url) {
        pages[1 - active].load(url);
        Log.i(TAG, "Laddar om den ensamma sidan");
        final int g = ++generation;
        pollReload(url, 0, g);
    }

    private void pollReload(String url, long waited, int g) {
        if (!running || g != generation) return;
        SandboxedPage standby = pages[1 - active];
        if (standby.isOk() && System.currentTimeMillis() - standby.okAt() >= SETTLE_MS) {
            swapTo(index, 0, g);
        } else if (standby.hasFailed() || waited >= MAX_WAIT_MS) {
            Log.w(TAG, "Omladdningen misslyckades, den gamla sidan står kvar");
            standby.clear();
        } else {
            handler.postDelayed(() -> pollReload(url, waited + 500, g), 500);
        }
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        tickHandler.removeCallbacksAndMessages(null);
        for (SandboxedPage p : pages) p.clear();
        if (overlay != null) overlay.error(false, false);
        container.setVisibility(View.GONE);
    }

    /** Nätet kom tillbaka: försök igen direkt */
    public void retryNow() {
        if (!running || list.isEmpty()) return;
        if (pages[active].hasFailed() && !pages[active].isOk()) pages[active].load(list.get(index).url);
    }

    private void show(int i) {
        index = i;
        pages[active].load(list.get(i).url);
        scheduleNext();
    }

    /** Den aktiva vyn överst */
    private void raise() {
        views[active].bringToFront();
    }

    /** Visar felsidan så länge den aktuella sidan inte har kunnat laddas, och försöker igen */
    private void settle() {
        SandboxedPage cur = pages[active];
        boolean failed = cur.hasFailed() && !cur.isOk();
        // Ett fel som beror på nätverket (DNS, anslutning, tidsgräns) räknas som nätverk även om länken är uppe
        boolean net = failed && (cur.netError() || !isOnline());
        if (overlay != null) {
            overlay.error(failed, net);
            overlay.loaded(cur.isOk());
        }
        handler.removeCallbacks(retry);
        handler.removeCallbacks(skip);
        if (failed) {
            long retryMs = net ? RETRY_NET_MS : RETRY_MS;
            handler.postDelayed(retry, retryMs);
            Log.w(TAG, "Felsidan visas (" + (net ? "nätverksfel (DNS eller ingen anslutning)" : "sidan gick inte att ladda")
                    + "), försöker igen om " + retryMs / 1000 + " s" + (list.size() > 1 ? ", går vidare till nästa sida om " + SKIP_MS / 1000 + " s" : ""));
            if (list.size() > 1) handler.postDelayed(skip, SKIP_MS);
        }
    }

    /** Den aktiva sidan gick inte att ladda: rotationen går vidare till nästa, som laddas direkt */
    private final Runnable skip = new Runnable() {
        @Override
        public void run() {
            SandboxedPage cur = pages[active];
            if (!running || list.size() < 2 || !(cur.hasFailed() && !cur.isOk())) return;
            int next = (index + 1) % list.size();
            Log.i(TAG, "Lämnar sidan som inte gick att ladda, nästa: " + list.get(next).url);
            int g = ++generation;
            pages[1 - active].load(list.get(next).url);
            handler.postDelayed(() -> swapTo(next, 0, g), 500);
        }
    };

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (running && !list.isEmpty()) pages[active].load(list.get(index).url);
        }
    };

    /** Planerar förladdning och byte för sidan som visas nu */
    private void scheduleNext() {
        if (list.size() < 2) return;
        Page p = list.get(index);
        final int next = (index + 1) % list.size();
        final int lead = Math.min(PRELOAD_S, Math.max(1, p.seconds - 2));
        final int g = ++generation;
        handler.postDelayed(() -> {
            if (!running || g != generation) return;
            pages[1 - active].load(list.get(next).url);
            handler.postDelayed(() -> swapTo(next, 0, g), lead * 1000L);
        }, (p.seconds - lead) * 1000L);
    }

    /** Byter till den förladdade vyn när nästa sida är klar (eller efter MAX_WAIT_MS) */
    private void swapTo(final int next, final long waited, final int g) {
        if (!running || g != generation) return;
        SandboxedPage standby = pages[1 - active];
        boolean ready = standby.isOk() && System.currentTimeMillis() - standby.okAt() >= SETTLE_MS;
        if (!ready && !standby.hasFailed() && waited < MAX_WAIT_MS) {
            handler.postDelayed(() -> swapTo(next, waited + 500, g), 500);
            return;
        }
        final int old = active;
        active = 1 - active;
        index = next;
        raise();
        settle();
        // Den gamla vyn släpps (stoppar skript och film) så att den är tom när den blir nästa förladdningsvy
        handler.postDelayed(() -> {
            if (active != old) pages[old].clear();
        }, 1000);
        scheduleNext();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
