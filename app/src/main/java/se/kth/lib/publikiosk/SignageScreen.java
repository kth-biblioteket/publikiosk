package se.kth.lib.publikiosk;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
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

        Page(String url, int seconds) {
            this.url = url;
            this.seconds = seconds;
        }
    }

    interface Overlay {
        /** Felsidan visas (offline: enheten saknar nät) eller tas bort */
        void error(boolean show, boolean offline);

        /** Sidan som visas är laddad: statusrapporten får veta det */
        void loaded(boolean ok);
    }

    static final int MIN_S = 5, MAX_S = 3600, MAX_PAGES = 36;
    private static final long RETRY_MS = 30_000;
    private static final int PRELOAD_S = 10;        // nästa sida börjar laddas så här många sekunder före bytet
    private static final long SETTLE_MS = 1500;     // tid efter laddning för att skripten ska hinna rita klart
    private static final long MAX_WAIT_MS = 10_000; // väntar högst så här länge på en sida som inte är färdig vid bytet
    private static final long SINGLE_RELOAD_MS = 10 * 60_000L;

    private final Activity activity;
    private final View container;
    private final WebView[] views = new WebView[2];
    private final SandboxedPage[] pages = new SandboxedPage[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UrlPolicy policy;
    private Overlay overlay;
    private List<Page> list = new ArrayList<>();
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
                    if (o != null) add(out, o.optString("url", ""), o.has("s") ? o.optString("s") : null, def);
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
                if (bar > 0 && line.substring(bar + 1).trim().matches("\\d+")) add(out, line.substring(0, bar), line.substring(bar + 1), def);
                else add(out, line, null, def);
            }
        }
        if (out.isEmpty()) add(out, fallbackUrl, null, def);
        return out;
    }

    private static void add(List<Page> out, String url, String seconds, int def) {
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
        out.add(new Page(u, s));
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

    public void configure(List<Page> pages, boolean scripts, int scalePercent, UrlPolicy policy, Overlay overlay) {
        this.list = pages;
        this.policy = policy;
        this.overlay = overlay;
        for (SandboxedPage p : this.pages) p.configure(scripts, false, scalePercent);
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
        if (list.isEmpty()) {
            // Inget att visa: felsidan står kvar tills inställningarna ändrats
            overlay.error(true, false);
            return;
        }
        show(0);
        // En enda sida laddas om med jämna mellanrum
        if (list.size() == 1) handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running || list.size() != 1) return;
                pages[active].load(list.get(0).url);
                handler.postDelayed(this, SINGLE_RELOAD_MS);
            }
        }, SINGLE_RELOAD_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
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
        if (overlay != null) {
            overlay.error(failed, failed && !isOnline());
            overlay.loaded(cur.isOk());
        }
        handler.removeCallbacks(retry);
        if (failed) handler.postDelayed(retry, RETRY_MS);
    }

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
        handler.postDelayed(() -> {
            if (!running) return;
            pages[1 - active].load(list.get(next).url);
            handler.postDelayed(() -> swapTo(next, 0), lead * 1000L);
        }, (p.seconds - lead) * 1000L);
    }

    /** Byter till den förladdade vyn när nästa sida är klar (eller efter MAX_WAIT_MS) */
    private void swapTo(final int next, final long waited) {
        if (!running) return;
        SandboxedPage standby = pages[1 - active];
        boolean ready = standby.isOk() && System.currentTimeMillis() - standby.okAt() >= SETTLE_MS;
        if (!ready && !standby.hasFailed() && waited < MAX_WAIT_MS) {
            handler.postDelayed(() -> swapTo(next, waited + 500), 500);
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
