package se.kth.lib.publikiosk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;

/**
 * Attract loop: när ingen har rört förstasidan på en stund visas en helskärmssida från en https-adress
 * (bildspel, siffror, film), i stället för förstasidan. Första trycket tar bort den och räknas inte som
 * ett besök. Sidan visas i en SandboxedPage: den kan inte öppna något annat och har ingen koppling till
 * appen. Den laddas om med jämna mellanrum medan förstasidan visas, och pausas när den inte syns.
 */
public class AttractScreen {

    private final Activity activity;
    private WebView view;
    private final SandboxedPage page;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UrlPolicy policy;
    private String url = "";
    private int afterSeconds = 0, refreshMinutes = 10;
    private static final long RECYCLE_MS = 3 * 3600_000L;
    private long recycledAt = System.currentTimeMillis();
    private int scalePercent = 100;
    private boolean scripts = false, portrait = false, running = false, shown = false;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            // En pausad sida laddas inte om: väck den en stund
            if (!shown) page.resume();
            if (!shown && System.currentTimeMillis() - recycledAt >= RECYCLE_MS) recycle();
            page.load(url);
            if (!shown) handler.postDelayed(() -> {
                if (!shown) page.pause();
            }, 20_000);
            handler.postDelayed(this, refreshMinutes * 60_000L);
        }
    };

    public AttractScreen(Activity activity) {
        this.activity = activity;
        view = activity.findViewById(R.id.attract_view);
        page = new SandboxedPage(view, new SandboxedPage.Host() {
            @Override
            public boolean allows(String u) {
                return policy != null && policy.allows(u);
            }

            @Override
            public void failed() {
                // Går sidan inte att ladda visas förstasidan som vanligt
                handler.post(AttractScreen.this::hide);
            }

            @Override
            public void recovered() {
            }
        });
    }

    public void configure(String url, int afterSeconds, boolean scripts, int refreshMinutes, UrlPolicy policy, boolean portrait, int widthPx) {
        String newUrl = url != null && url.startsWith("https://") ? url : "";
        boolean urlChanged = !newUrl.equals(this.url);
        this.url = newUrl;
        this.afterSeconds = Math.max(0, Math.min(3600, afterSeconds));
        this.scripts = scripts;
        this.refreshMinutes = Math.max(1, Math.min(60, refreshMinutes));
        this.policy = policy;
        this.portrait = portrait;
        scalePercent = Math.round(100f * widthPx / (portrait ? 800f : 1280f));
        page.configure(scripts, false, scalePercent);
        if (!isEnabled()) {
            hide();
            page.clear();
        } else if (running && urlChanged) {
            page.load(this.url);
        }
    }

    public boolean isEnabled() {
        return !url.isEmpty() && afterSeconds > 0;
    }

    public long afterMillis() {
        return afterSeconds * 1000L;
    }

    /** Går det att visa den nu: inställd, tillåten adress och inget fel vid senaste laddningen */
    public boolean canShow() {
        return isEnabled() && policy != null && policy.allows(url) && !page.hasFailed();
    }

    public boolean isShown() {
        return shown;
    }

    /** Förstasidan visas: ladda sidan och håll den fräsch */
    public void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(refresh);
        if (isEnabled()) handler.post(refresh);
    }

    /** Kritiskt minnestryck: släpp sidan när den inte syns (den laddas på nytt när förstasidan visas igen) */
    public void releaseMemory() {
        if (!shown) page.clear();
    }

    /**
     * Byt ut WebView mot en ny, då och då och bara när sidan inte syns: en WebView som visat en
     * animerad sida i timmar växer, en ny börjar om. Sidan laddas direkt efteråt av anroparen.
     */
    private void recycle() {
        try {
            android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
            if (parent == null) return;
            WebView old = view;
            WebView fresh = new WebView(activity);
            fresh.setId(old.getId());
            fresh.setBackground(old.getBackground());
            fresh.setElevation(old.getElevation());
            fresh.setVisibility(View.GONE);
            int index = parent.indexOfChild(old);
            parent.removeView(old);
            parent.addView(fresh, index, old.getLayoutParams());
            old.destroy();
            view = fresh;
            page.rebind(fresh);
            page.configure(scripts, false, scalePercent);
            recycledAt = System.currentTimeMillis();
            EventLog.add(activity, "Attract: ny webbvy (minnet släppt)");
        } catch (Exception e) {
            recycledAt = System.currentTimeMillis();
            android.util.Log.w("AttractScreen", "recycle", e);
        }
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(refresh);
        hide();
    }

    public void show() {
        if (shown) return;
        shown = true;
        page.resume();
        view.setVisibility(View.VISIBLE);
    }

    public void hide() {
        if (!shown) return;
        shown = false;
        view.setVisibility(View.GONE);
        page.pause();
    }
}
