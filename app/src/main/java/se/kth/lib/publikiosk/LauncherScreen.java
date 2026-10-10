package se.kth.lib.publikiosk;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.widget.FrameLayout;
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
    public void configure(List<KioskApps.App> apps, Texts texts, LauncherFooter.Config footerConfig, UrlPolicy policy, boolean english) {
        this.english = english;
        label.setText(english ? "KTH Library" : "KTH Biblioteket");
        title.setText(pick(english, texts.title, texts.titleEn, english ? "What do you need?" : "Vad vill du göra?"));
        subtitle.setText(pick(english, texts.subtitle, texts.subtitleEn, english ? "Tap a service to begin." : "Tryck på en tjänst för att börja."));
        String foot = pick(english, texts.footer, texts.footerEn, "");
        footer.setText(foot);
        footer.setVisibility(foot.isEmpty() || footerConfig.hasPanel() ? View.GONE : View.VISIBLE);
        language.setText(english ? "Svenska" : "English");

        boolean portrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        u = activity.getResources().getDisplayMetrics().widthPixels / (portrait ? 800f : 1280f);
        layoutHeader(portrait);
        infoFooter.configure(footerConfig, policy, english, portrait, u);
        int cols = portrait ? 1 : 2;
        tiles.removeAllViews();
        tiles.setPadding(px(portrait ? 48 : 56), px(portrait ? 32 : 28), px(portrait ? 48 : 56), px(portrait ? 32 : 28));
        footer.setPadding(px(portrait ? 48 : 56), px(24), px(portrait ? 48 : 56), px(24));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20));
        int rows = (apps.size() + cols - 1) / cols;
        int gap = px(24);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r > 0) rlp.topMargin = gap;
            tiles.addView(row, rlp);
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) clp.leftMargin = gap;
                if (i < apps.size()) row.addView(tile(apps.get(i), portrait), clp);
                else row.addView(new View(activity), clp);
            }
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

    private View tile(KioskApps.App app, boolean portrait) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setBackgroundResource(R.drawable.launcher_tile_bg);
        tile.setPadding(px(36), 0, px(36), 0);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(app.label(english) + (app.desc(english).isEmpty() ? "" : ". " + app.desc(english)));
        tile.setOnClickListener(v -> listener.open(app));

        FrameLayoutHolder icon = new FrameLayoutHolder(activity, KioskApps.tileIcon(app.icon), px(60));
        tile.addView(icon.box, new LinearLayout.LayoutParams(px(112), px(112)));
        // Ryms inte kortet (informationsfält och meddelande tar plats) krymper ikonrutan i stället för att klippas
        tile.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int box = Math.min(px(112), (b - t) - px(24));
            if (box < px(48)) box = px(48);
            ViewGroup.LayoutParams lp = icon.box.getLayoutParams();
            if (lp.height == box) return;
            lp.width = box;
            lp.height = box;
            icon.box.setLayoutParams(lp);
            View glyph = icon.box.getChildAt(0);
            ViewGroup.LayoutParams glp = glyph.getLayoutParams();
            glp.width = glp.height = Math.round(box * 60f / 112f);
            glyph.setLayoutParams(glp);
        });

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = px(28);
        tile.addView(text, tlp);

        TextView name = new TextView(activity);
        name.setText(app.label(english));
        name.setTextColor(activity.getColor(R.color.kth_navy));
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32));
        name.setTypeface(Fonts.extraBold(activity));
        name.setMaxLines(2);
        text.addView(name);
        if (!app.desc(english).isEmpty()) {
            TextView desc = new TextView(activity);
            desc.setText(app.desc(english));
            desc.setTextColor(activity.getColor(R.color.nav_muted));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(20));
            desc.setTypeface(Fonts.regular(activity));
            desc.setMaxLines(3);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = px(8);
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
