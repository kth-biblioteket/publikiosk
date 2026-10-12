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

    private final WebView view;
    private final SandboxedPage page;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UrlPolicy policy;
    private String url = "";
    private int afterSeconds = 0, refreshMinutes = 10;
    private boolean scripts = false, portrait = false, running = false, shown = false;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            // En pausad sida laddas inte om: väck den en stund
            if (!shown) page.resume();
            page.load(url);
            if (!shown) handler.postDelayed(() -> {
                if (!shown) page.pause();
            }, 20_000);
            handler.postDelayed(this, refreshMinutes * 60_000L);
        }
    };

    public AttractScreen(Activity activity) {
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
        page.configure(scripts, false, Math.round(100f * widthPx / (portrait ? 800f : 1280f)));
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
