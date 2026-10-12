package se.kth.lib.publikiosk;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Förstasidan (HOME_MODE=launcher): stora kort för tjänsterna som besökaren kan välja bland,
 * ritad av appen själv och inte en webbsida. Ett tryck på ett kort öppnar tjänsten, och Startsida i
 * kiosknavigeringen tar besökaren hit igen. Utseendet följer KTH:s grafiska profil, med linjemönstret
 * i huvudets nedre högra hörn (aldrig bakom text).
 */
public class LauncherScreen {

    public interface Listener {
        void open(KioskApps.App app);

        void toggleLanguage();
    }

    private final Activity activity;
    private final Listener listener;
    private final View page;
    private final LinearLayout tiles;
    private final TextView label, title, subtitle, footer, language;
    private final ImageView pattern, logo;
    private final View headRow;
    private final LauncherFooter infoFooter;
    /** Pixlar per skisspixel: förstasidan ritas som skissen (1280 bred liggande, 800 stående) och skalas till skärmen */
    private float u = 1f;
    private boolean english = false;
    private List<KioskApps.App> apps = new java.util.ArrayList<>();
    private int perPage = 4;
    private boolean portrait = false;
    private final Pager pager;
    private final LinearLayout dots;
    private int pageNow = 0, pageCount = 1;
    private int builtW = -1, builtH = -1;

    /** Förstasidans sidor: svep sidledes och fäst på närmaste sida (en tjänst på två sidor, prickar under) */
    private final class Pager extends HorizontalScrollView {
        private int startX;

        Pager(Activity a) {
            super(a);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setFillViewport(true);
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (getWidth() > 0) {
                pageNow = Math.max(0, Math.min(pageCount - 1, Math.round(l / (float) getWidth())));
                markDots();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) startX = getScrollX();
            boolean r = super.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                int w = Math.max(1, getWidth());
                int target = Math.round(startX / (float) w);
                float moved = getScrollX() - startX;
                if (moved > 0.15f * w) target++;
                else if (moved < -0.15f * w) target--;
                goTo(Math.max(0, Math.min(pageCount - 1, target)), true);
            }
            return r;
        }

        void goTo(int page, boolean smooth) {
            if (smooth) smoothScrollTo(page * getWidth(), 0);
            else scrollTo(page * getWidth(), 0);
        }
    }

    public LauncherScreen(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        page = activity.findViewById(R.id.launcher_page);
        tiles = activity.findViewById(R.id.launcher_tiles);
        label = activity.findViewById(R.id.launcher_label);
        title = activity.findViewById(R.id.launcher_title);
        subtitle = activity.findViewById(R.id.launcher_subtitle);
        footer = activity.findViewById(R.id.launcher_footer);
        language = activity.findViewById(R.id.launcher_language);
        pattern = activity.findViewById(R.id.launcher_pattern);
        logo = activity.findViewById(R.id.launcher_logo);
        headRow = activity.findViewById(R.id.launcher_head_row);
        infoFooter = new LauncherFooter(activity);
        pager = new Pager(activity);
        dots = new LinearLayout(activity);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        tiles.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tiles.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Raderna byggs när ytan har fått sin storlek (och om den ändras)
        pager.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != builtW || b - t != builtH) rebuild();
        });
        label.setTypeface(Fonts.bold(activity));
        title.setTypeface(Fonts.extraBold(activity));
        subtitle.setTypeface(Fonts.regular(activity));
        footer.setTypeface(Fonts.regular(activity));
        language.setTypeface(Fonts.bold(activity));
        language.setOnClickListener(v -> listener.toggleLanguage());
    }

    public boolean isShown() {
        return page.getVisibility() == View.VISIBLE;
    }

    public void show() {
        page.setVisibility(View.VISIBLE);
        // Startsida går tillbaka till första sidan
        pageNow = 0;
        pager.post(() -> pager.goTo(0, false));
        infoFooter.start();
    }

    public void hide() {
        page.setVisibility(View.GONE);
        infoFooter.stop();
    }

    /**
     * Bygger sidan. Texter som är tomma får standardtexten på besökarens språk; en egen text (från
     * LAUNCHER_TITLE, LAUNCHER_SUBTITLE) visas som den är. Tom fottext döljer raden.
     */
    public void configure(List<KioskApps.App> apps, int perPage, Texts texts, LauncherFooter.Config footerConfig, UrlPolicy policy, boolean english) {
        this.english = english;
        this.apps = apps;
        this.perPage = perPage;
        label.setText(english ? "KTH Library" : "KTH Biblioteket");
        title.setText(pick(english, texts.title, texts.titleEn, english ? "What do you need?" : "Vad vill du göra?"));
        subtitle.setText(pick(english, texts.subtitle, texts.subtitleEn, english ? "Tap a service to begin." : "Tryck på en tjänst för att börja."));
        String foot = pick(english, texts.footer, texts.footerEn, "");
        footer.setText(foot);
        footer.setVisibility(foot.isEmpty() || footerConfig.hasPanel() ? View.GONE : View.VISIBLE);
        language.setText(english ? "Svenska" : "English");

        portrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        u = activity.getResources().getDisplayMetrics().widthPixels / (portrait ? 800f : 1280f);
        layoutHeader(portrait);
        infoFooter.configure(footerConfig, policy, english, portrait, u);
        footer.setPadding(px(portrait ? 48 : 56), px(24), px(portrait ? 48 : 56), px(24));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20));
        tiles.setPadding(0, 0, 0, 0);
        builtW = -1;
        rebuild();
    }

    /** Kolumner och rader för k kort i ytan w × h: rutor eller breda rader (ca 2,2:1), helst utan tomma rutor */
    static int[] gridFor(int k, float w, float h, boolean narrowPortrait) {
        if (k <= 1) return new int[]{1, 1};
        if (narrowPortrait && k <= 4) return new int[]{1, k};
        int bestC = 1, bestR = k;
        double bestScore = Double.MAX_VALUE;
        for (int c = 1; c <= Math.min(5, k); c++) {
            int r = (k + c - 1) / c;
            double score = Math.abs(Math.log((w / c) / (h / r) / 2.2)) + 0.15 * (c * r - k) + 0.12 * Math.abs(c - r);
            if (score < bestScore - 1e-9) {
                bestScore = score;
                bestC = c;
                bestR = r;
            }
        }
        return new int[]{bestC, bestR};
    }

    private static float clamp(float lo, float v, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Bygger sidorna ur tjänsterna: perPage kort per sida, fler tjänster ger flera sidor med prickar */
    private void rebuild() {
        int wPx = pager.getWidth(), hPx = pager.getHeight();
        if (wPx <= 0 || hPx <= 0 || u <= 0) return;
        builtW = wPx;
        builtH = hPx;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int n = apps.size();
        int per = Math.max(1, Math.min(perPage, Math.max(1, n)));
        pageCount = Math.max(1, (n + per - 1) / per);
        float padX = portrait ? 48 : 56, padY = portrait ? 32 : 28, gap = 24;
        float availW = wPx / u - 2 * padX, availH = hPx / u - 2 * padY;
        int[] g = gridFor(per, availW, availH, (float) activity.getResources().getDisplayMetrics().widthPixels
                / activity.getResources().getDisplayMetrics().heightPixels < 0.75f);
        int cols = g[0], rows = g[1];
        float cw = (availW - (cols - 1) * gap) / cols, ch = (availH - (rows - 1) * gap) / rows;
        for (int p = 0; p < pageCount; p++) {
            LinearLayout pg = new LinearLayout(activity);
            pg.setOrientation(LinearLayout.VERTICAL);
            pg.setPadding(px(padX), px(padY), px(padX), px(padY));
            for (int r = 0; r < rows; r++) {
                LinearLayout line = new LinearLayout(activity);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                if (r > 0) rlp.topMargin = px(gap);
                pg.addView(line, rlp);
                for (int c = 0; c < cols; c++) {
                    int i = p * per + r * cols + c;
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    if (c > 0) clp.leftMargin = px(gap);
                    if (i < n && i < (p + 1) * per) line.addView(tile(apps.get(i), cw, ch), clp);
                    else line.addView(new View(activity), clp);
                }
            }
            row.addView(pg, new LinearLayout.LayoutParams(wPx, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pager.removeAllViews();
        pager.addView(row, new FrameLayout.LayoutParams(wPx * pageCount, ViewGroup.LayoutParams.MATCH_PARENT));
        buildDots();
        pageNow = Math.min(pageNow, pageCount - 1);
        pager.post(() -> pager.goTo(pageNow, false));
    }

    private void buildDots() {
        dots.removeAllViews();
        dots.setVisibility(pageCount > 1 ? View.VISIBLE : View.GONE);
        dots.setPadding(0, 0, 0, px(10));
        for (int p = 0; p < pageCount; p++) {
            final int page = p;
            FrameLayout hit = new FrameLayout(activity);
            View dot = new View(activity);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(px(12), px(12), Gravity.CENTER);
            hit.addView(dot, dlp);
            hit.setTag(dot);
            hit.setOnClickListener(v -> pager.goTo(page, true));
            dots.addView(hit, new LinearLayout.LayoutParams(px(36), px(30)));
        }
        markDots();
    }

    private void markDots() {
        for (int p = 0; p < dots.getChildCount(); p++) {
            View dot = (View) dots.getChildAt(p).getTag();
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(p == pageNow ? activity.getColor(R.color.kth_navy) : 0xFFB9C2D0);
            dot.setBackground(d);
        }
    }

    /** Texterna från inställningarna, svenska och engelska. Tom engelsk text: den svenska, sedan standardtexten. */
    public static final class Texts {
        final String title, titleEn, subtitle, subtitleEn, footer, footerEn;

        public Texts(String title, String titleEn, String subtitle, String subtitleEn, String footer, String footerEn) {
            this.title = title;
            this.titleEn = titleEn;
            this.subtitle = subtitle;
            this.subtitleEn = subtitleEn;
            this.footer = footer;
            this.footerEn = footerEn;
        }
    }

    private static String pick(boolean english, String sv, String en, String fallback) {
        if (english && !en.isEmpty()) return en;
        if (!sv.isEmpty()) return sv;
        return fallback;
    }

    /** Ett kort i en cell på cw × ch skisspixlar: breda rader (ikonen till vänster) eller rutor (ikonen ovanför), som i Linux-kiosken */
    private View tile(KioskApps.App app, float cw, float ch) {
        boolean wide = cw / ch >= 2f;
        float iconD, nameS, descS, gapD, padH, padV;
        boolean showDesc;
        if (wide) {
            iconD = clamp(36, Math.min(ch - 24, 0.30f * cw), 112);
            nameS = clamp(17, 0.26f * ch, 32);
            descS = clamp(13, 0.17f * ch, 20);
            gapD = clamp(12, 0.12f * ch, 28);
            padH = clamp(14, 0.10f * ch, 36);
            padV = 0;
            showDesc = ch > 90;
        } else {
            iconD = clamp(36, Math.min(0.36f * ch, 0.40f * cw), 112);
            nameS = clamp(16, Math.min(0.12f * ch, 0.095f * cw), 30);
            descS = clamp(12, Math.min(0.07f * ch, 0.055f * cw), 18);
            gapD = clamp(6, 0.04f * ch, 14);
            padH = 12;
            padV = 8;
            showDesc = ch > 150;
        }
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        tile.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        tile.setBackgroundResource(R.drawable.launcher_tile_bg);
        tile.setPadding(px(padH), px(padV), px(padH), px(padV));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(app.label(english) + (app.desc(english).isEmpty() ? "" : ". " + app.desc(english)));
        tile.setOnClickListener(v -> listener.open(app));

        int box = px(iconD);
        FrameLayoutHolder icon = new FrameLayoutHolder(activity, KioskApps.tileIcon(app.icon), Math.round(box * 0.54f));
        tile.addView(icon.box, new LinearLayout.LayoutParams(box, box));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(wide ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tlp = wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (wide) tlp.leftMargin = px(gapD);
        else tlp.topMargin = px(gapD);
        tile.addView(text, tlp);

        TextView name = new TextView(activity);
        name.setText(app.label(english));
        name.setTextColor(activity.getColor(R.color.kth_navy));
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(nameS));
        name.setTypeface(Fonts.extraBold(activity));
        name.setGravity(wide ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        if (showDesc && !app.desc(english).isEmpty()) {
            TextView desc = new TextView(activity);
            desc.setText(app.desc(english));
            desc.setTextColor(activity.getColor(R.color.nav_muted));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(descS));
            desc.setTypeface(Fonts.regular(activity));
            desc.setGravity(wide ? Gravity.START : Gravity.CENTER_HORIZONTAL);
            desc.setMaxLines(3);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = px(wide ? 6 : 4);
            text.addView(desc, dlp);
        }
        return tile;
    }

    /** Ikonrutan: en ljusblå rundad ruta med ikonen i KTH-blått */
    private static final class FrameLayoutHolder {
        final LinearLayout box;

        FrameLayoutHolder(Activity activity, int iconRes, int iconPx) {
            box = new LinearLayout(activity);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundResource(R.drawable.launcher_icon_bg);
            ImageView iv = new ImageView(activity);
            iv.setImageResource(iconRes);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.kth_blue)));
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(iv, new LinearLayout.LayoutParams(iconPx, iconPx));
        }
    }

    /** Huvudets mått som i skissen, skalade till skärmen */
    private void layoutHeader(boolean portrait) {
        headRow.setPadding(px(portrait ? 48 : 56), px(portrait ? 104 : 36), px(portrait ? 48 : 56), px(portrait ? 160 : 36));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(px(portrait ? 86 : 110), px(portrait ? 96 : 123));
        logo.setLayoutParams(llp);
        View text = (View) title.getParent();
        // Landskap: rubriken bryts före mönstret, så att ingen text hamnar över det
        text.setPadding(px(32), 0, portrait ? 0 : px(250 + 24), 0);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(portrait ? 26 : 28));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(portrait ? 66 : 76));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28));
        language.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18));
        language.setPadding(px(20), 0, px(20), 0);
        language.setCompoundDrawablePadding(px(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48), Gravity.TOP | Gravity.END);
        lp.topMargin = px(28);
        lp.rightMargin = px(portrait ? 48 : 56);
        language.setLayoutParams(lp);
        // Mönstret: nedre högra hörnet, vänt upp och ner, under språkknappen
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(px(portrait ? 260 : 250), px(portrait ? 160 : 153), Gravity.BOTTOM | Gravity.END);
        pattern.setLayoutParams(plp);
    }

    private int px(float designPx) {
        return Math.round(designPx * u);
    }
}
