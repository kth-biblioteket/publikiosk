package se.kth.lib.publikiosk;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Förstasidans nederkant: ett informationsfält med upp till fyra fält (egen text, klocka eller text från
 * en webbadress) och en meddelanderad ovanför. Texten från en adress hämtas av appen själv, bara över
 * https mot en tillåten värd, och visas som ren text, aldrig som HTML. Ligger kvar tills nästa hämtning
 * om nätet försvinner, och byts mot reservtexten efter en timme utan svar.
 */
public class LauncherFooter {

    static final int MAX_FIELDS = 4;
    private static final long STALE_MS = 60L * 60 * 1000;
    private static final int MAX_BYTES = 2048;
    private static final int MAX_LENGTH = 300;

    /** Ett fält. Rad: Etikett|typ|värde|Label (engelsk etikett). Typ: text, clock eller url. */
    public static final class Field {
        final String label, labelEn, type, value;

        Field(String label, String labelEn, String type, String value) {
            this.label = label;
            this.labelEn = labelEn;
            this.type = type;
            this.value = value;
        }

        String label(boolean english) {
            return english && !labelEn.isEmpty() ? labelEn : label;
        }

        /** Null om raden är tom eller ogiltig (okänd typ, saknat värde, adress som inte är https) */
        static Field parse(String line) {
            if (line == null || line.trim().isEmpty()) return null;
            String[] p = line.split("\\|", -1);
            String type = p.length > 1 ? p[1].trim().toLowerCase(java.util.Locale.ROOT) : "";
            String label = p[0].trim();
            String value = p.length > 2 ? p[2].trim() : "";
            String labelEn = p.length > 3 ? p[3].trim() : "";
            if (type.equals("clock")) return new Field(label, labelEn, type, "");
            if (type.equals("text") && !value.isEmpty()) return new Field(label, labelEn, type, value);
            if (type.equals("url") && value.startsWith("https://")) return new Field(label, labelEn, type, value);
            return null;
        }
    }

    /** Inställningarna för nederkanten, från publicomtools */
    public static final class Config {
        final List<Field> fields = new ArrayList<>();
        final String message, messageEn, messageUrl, style, icon;
        final int refreshMinutes;
        /** En hel sida från en https-adress i stället för fälten (tom: inga), dess höjd i skisspixlar, och om skript och tryck tillåts */
        final String htmlUrl;
        final int htmlHeight;
        final boolean htmlJs, htmlTouch;

        public Config(String[] fieldLines, String message, String messageEn, String messageUrl, String style, int refreshMinutes, String icon,
                      String htmlUrl, int htmlHeight, boolean htmlJs, boolean htmlTouch) {
            this.htmlUrl = htmlUrl != null && htmlUrl.startsWith("https://") ? htmlUrl : "";
            this.htmlHeight = Math.max(40, Math.min(400, htmlHeight));
            this.htmlJs = htmlJs;
            this.htmlTouch = htmlTouch;
            for (String line : fieldLines) {
                Field f = Field.parse(line);
                if (f != null && fields.size() < MAX_FIELDS) fields.add(f);
            }
            this.message = message == null ? "" : message;
            this.messageEn = messageEn == null ? "" : messageEn;
            this.messageUrl = messageUrl == null ? "" : messageUrl;
            this.style = "alert".equals(style) || "info".equals(style) ? style : "warning";
            this.refreshMinutes = Math.max(1, Math.min(60, refreshMinutes));
            this.icon = icon == null || icon.isEmpty() ? "info" : icon;
        }

        boolean hasHtml() {
            return !htmlUrl.isEmpty();
        }

        boolean hasPanel() {
            return !fields.isEmpty() || hasHtml();
        }
    }

    private static final class Cached {
        final String text;
        final long at;

        Cached(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    private final Activity activity;
    private final LinearLayout panel;
    private final TextView message;
    private final WebView html;
    private boolean htmlFailed = false;
    /** Det pågående försöket att ladda sidan har gått fel (Chromiums felsida räknas inte som en lyckad sida) */
    private boolean htmlAttemptFailed = false;
    private String htmlLoaded = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Cached> cache = new HashMap<>();
    private Config config = new Config(new String[0], "", "", "", "warning", 1, "info", "", 100, false, false);
    private UrlPolicy policy;
    private boolean english, portrait, running;
    private float u = 1f;
    private final List<TextView> valueViews = new ArrayList<>();
    private final List<Field> valueFields = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            handler.postDelayed(this, config.refreshMinutes * 60_000L);
        }
    };

    public LauncherFooter(Activity activity) {
        this.activity = activity;
        panel = activity.findViewById(R.id.launcher_info);
        message = activity.findViewById(R.id.launcher_message);
        html = activity.findViewById(R.id.launcher_html);
        setUpHtml();
        message.setTypeface(Fonts.regular(activity));
    }

    public boolean hasPanel() {
        return config.hasPanel();
    }

    public void configure(Config config, UrlPolicy policy, boolean english, boolean portrait, float u) {
        this.config = config;
        this.policy = policy;
        this.english = english;
        this.portrait = portrait;
        this.u = u;
        build();
        if (running) refresh();
    }

    public void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private void build() {
        valueViews.clear();
        valueFields.clear();
        panel.removeAllViews();
        boolean showHtml = config.hasHtml() && !htmlFailed;
        applyHtml(showHtml);
        if (showHtml || config.fields.isEmpty()) {
            panel.setVisibility(View.GONE);
        } else {
            panel.setVisibility(View.VISIBLE);
            int side = px(portrait ? 48 : 56);
            panel.setPadding(side, 0, side, 0);
            panel.setGravity(Gravity.CENTER_VERTICAL);
            panel.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(portrait ? 110 : 100)));
            int gap = px(portrait ? 20 : 36);
            for (Field f : config.fields) {
                if (panel.getChildCount() > 0) panel.addView(separator(gap));
                panel.addView(f.type.equals("clock") ? clock() : cell(f));
            }
        }
        updateTexts();
    }

    private View separator(int gap) {
        View v = new View(activity);
        v.setBackgroundColor(Color.argb(51, 0, 0, 97));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, px(1)), px(portrait ? 52 : 60));
        lp.leftMargin = gap;
        lp.rightMargin = gap;
        v.setLayoutParams(lp);
        return v;
    }

    private View cell(Field f) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!f.label(english).isEmpty()) {
            TextView label = new TextView(activity);
            label.setText(f.label(english).toUpperCase(english ? java.util.Locale.ENGLISH : new java.util.Locale("sv")));
            label.setTextColor(activity.getColor(R.color.kth_blue));
            label.setTypeface(Fonts.bold(activity));
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(portrait ? 12 : 14));
            label.setLetterSpacing(0.06f);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(label);
        }
        TextView value = new TextView(activity);
        value.setTextColor(activity.getColor(R.color.kth_navy));
        value.setTypeface(Fonts.extraBold(activity));
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(portrait ? 30 : 36));
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = px(4);
        box.addView(value, vlp);
        valueViews.add(value);
        valueFields.add(f);
        return box;
    }

    private View clock() {
        TextClock c = new TextClock(activity);
        c.setFormat24Hour("HH:mm");
        c.setFormat12Hour("HH:mm");
        c.setTextColor(activity.getColor(R.color.kth_navy));
        c.setTypeface(Fonts.extraBold(activity));
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(portrait ? 38 : 46));
        c.setSingleLine(true);
        c.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** Sätter texten i fälten och meddelanderaden ur det som hämtats hittills */
    private void updateTexts() {
        for (int i = 0; i < valueViews.size(); i++) {
            Field f = valueFields.get(i);
            valueViews.get(i).setText(f.type.equals("text") ? f.value : fresh(f.value, "–"));
        }
        // En tom text från adressen döljer raden; inget svar (eller för gammalt) ger den fasta texten
        String text = config.messageUrl.isEmpty() ? null : fresh(config.messageUrl, null);
        if (text == null) text = english && !config.messageEn.isEmpty() ? config.messageEn : config.message;
        if (text.isEmpty()) {
            message.setVisibility(View.GONE);
            return;
        }
        boolean alert = config.style.equals("alert");
        boolean info = config.style.equals("info");
        int fg = alert ? Color.WHITE : info ? Color.parseColor("#000061") : Color.parseColor("#4A3200");
        message.setText(text);
        if (info) {
            // Blå: KTH:s ljusblå med en kant upptill
            android.graphics.drawable.LayerDrawable bg = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.ColorDrawable(Color.parseColor("#DEF0FF")),
                    new android.graphics.drawable.ColorDrawable(Color.parseColor("#004791"))});
            bg.setLayerHeight(1, Math.max(2, px(2)));
            bg.setLayerGravity(1, Gravity.TOP);
            message.setBackground(bg);
        } else {
            message.setBackgroundColor(alert ? Color.parseColor("#B3261E") : Color.parseColor("#FFF3D6"));
        }
        message.setTextColor(fg);
        message.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(portrait ? 20 : 22));
        message.setPadding(px(portrait ? 48 : 56), px(12), px(portrait ? 48 : 56), px(12));
        // Ikonen väljs i inställningen (LAUNCHER_MESSAGE_ICON); färgen visar graden
        if (config.icon.equals("none")) {
            message.setCompoundDrawablesRelative(null, null, null, null);
        } else {
            android.graphics.drawable.Drawable ic = activity.getDrawable(KioskApps.tileIcon(config.icon)).mutate();
            int size = px(portrait ? 26 : 28);
            ic.setBounds(0, 0, size, size);
            ic.setTint(fg);
            message.setCompoundDrawablesRelative(ic, null, null, null);
            message.setCompoundDrawablePadding(px(14));
        }
        message.setGravity(Gravity.CENTER_VERTICAL);
        message.setVisibility(View.VISIBLE);
    }

    private String fresh(String url, String fallback) {
        Cached c = cache.get(url);
        if (c == null || System.currentTimeMillis() - c.at > STALE_MS) return fallback;
        return c.text;
    }

    private void refresh() {
        refreshHtml();
        List<String> urls = new ArrayList<>();
        for (Field f : config.fields) if (f.type.equals("url") && !urls.contains(f.value)) urls.add(f.value);
        if (!config.messageUrl.isEmpty() && !urls.contains(config.messageUrl)) urls.add(config.messageUrl);
        for (String url : urls) {
            if (policy == null || !policy.allows(url)) continue;
            executor.execute(() -> {
                String text = fetch(url);
                if (text == null) return;
                handler.post(() -> {
                    cache.put(url, new Cached(text, System.currentTimeMillis()));
                    if (running) updateTexts();
                });
            });
        }
    }

    /**
     * HTML-fotraden: en egen WebView som bara visar sidan. Den kan inte navigera (alla länkar och
     * omdirigeringar nekas), har ingen brygga mot appen, inga filer eller blandat innehåll, och hämtar
     * bara delresurser från tillåtna webbplatser. Tryck och skript är av om inte inställningarna tillåter.
     */
    private void setUpHtml() {
        WebSettings ws = html.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setDomStorageEnabled(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setSupportMultipleWindows(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        html.setVerticalScrollBarEnabled(false);
        html.setHorizontalScrollBarEnabled(false);
        html.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CookieManager.getInstance().setAcceptThirdPartyCookies(html, false);
        html.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("data:") || (policy != null && policy.allows(url))) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    htmlFailed = true;
                    htmlAttemptFailed = true;
                    handler.post(LauncherFooter.this::build);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                    htmlFailed = true;
                    htmlAttemptFailed = true;
                    handler.post(LauncherFooter.this::build);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (htmlFailed && !htmlAttemptFailed && url != null && !url.startsWith("about:")) {
                    htmlFailed = false;
                    build();
                }
            }
        });
    }

    private void applyHtml(boolean show) {
        if (!show) {
            html.setVisibility(View.GONE);
            return;
        }
        html.getSettings().setJavaScriptEnabled(config.htmlJs);
        html.getSettings().setDomStorageEnabled(config.htmlJs);
        html.setOnTouchListener(config.htmlTouch ? null : (v, e) -> true);
        html.setInitialScale(Math.max(1, Math.round(100 * u)));
        html.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(config.htmlHeight)));
        html.setVisibility(View.VISIBLE);
    }

    private void refreshHtml() {
        if (!config.hasHtml()) {
            if (!htmlLoaded.isEmpty()) {
                html.loadUrl("about:blank");
                htmlLoaded = "";
            }
            return;
        }
        if (policy == null || !policy.allows(config.htmlUrl)) return;
        if (htmlLoaded.equals(config.htmlUrl)) html.reload();
        else {
            htmlLoaded = config.htmlUrl;
            html.loadUrl(config.htmlUrl);
        }
    }

    /** Första svaret som ren text, eller null vid fel. Inga omdirigeringar, så att svaret alltid kommer från den tillåtna värden. */
    static String fetch(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "text/plain");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < MAX_BYTES) out.write(buf, 0, Math.min(n, MAX_BYTES - out.size()));
                return clean(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Ren text: kontrolltecken bort, radbrytningar som blanksteg, högst 300 tecken */
    static String clean(String raw) {
        String s = raw.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replaceAll("\\s*\\n\\s*", " ").trim();
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
    }

    private int px(float designPx) {
        return Math.round(designPx * u);
    }
}
