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
    /** Pixlar per skisspixel: förstasidan ritas som skissen (1280 bred liggande, 800 stående) och skalas till skärmen */
    private float u = 1f;

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
    }

    public void hide() {
        page.setVisibility(View.GONE);
    }

    /**
     * Bygger sidan. Texter som är tomma får standardtexten på besökarens språk; en egen text (från
     * LAUNCHER_TITLE, LAUNCHER_SUBTITLE) visas som den är. Tom fottext döljer raden.
     */
    public void configure(List<KioskApps.App> apps, String customTitle, String customSubtitle,
                          String customFooter, boolean english) {
        label.setText(english ? "KTH Library" : "KTH Biblioteket");
        title.setText(!customTitle.isEmpty() ? customTitle : (english ? "What do you need?" : "Vad vill du göra?"));
        subtitle.setText(!customSubtitle.isEmpty() ? customSubtitle : (english ? "Tap a service to begin." : "Tryck på en tjänst för att börja."));
        footer.setText(customFooter);
        footer.setVisibility(customFooter.isEmpty() ? View.GONE : View.VISIBLE);
        language.setText(english ? "Svenska" : "English");

        boolean portrait = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        u = activity.getResources().getDisplayMetrics().widthPixels / (portrait ? 800f : 1280f);
        layoutHeader(portrait);
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

    private View tile(KioskApps.App app, boolean portrait) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setBackgroundResource(R.drawable.launcher_tile_bg);
        tile.setPadding(px(36), 0, px(36), 0);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(app.label + (app.desc.isEmpty() ? "" : ". " + app.desc));
        tile.setOnClickListener(v -> listener.open(app));

        FrameLayoutHolder icon = new FrameLayoutHolder(activity, KioskApps.tileIcon(app.icon), px(60));
        tile.addView(icon.box, new LinearLayout.LayoutParams(px(112), px(112)));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = px(28);
        tile.addView(text, tlp);

        TextView name = new TextView(activity);
        name.setText(app.label);
        name.setTextColor(activity.getColor(R.color.kth_navy));
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32));
        name.setTypeface(Fonts.extraBold(activity));
        name.setMaxLines(2);
        text.addView(name);
        if (!app.desc.isEmpty()) {
            TextView desc = new TextView(activity);
            desc.setText(app.desc);
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
