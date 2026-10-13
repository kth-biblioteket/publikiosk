package se.kth.lib.publikiosk;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Collections;
import java.util.Locale;

/**
 * Kiosknavigeringen runt webbappen: en ram med Tillbaka och Hem som visas när besökaren har
 * lämnat appen, en varning innan appen börjar om, ett ark med QR-kod när en länk inte får
 * öppnas, och en egen felsida. Allt är appens egna vyer, inte injicerat i webbsidorna, så det
 * fungerar likadant på alla webbplatser och kan inte påverkas av sidornas CSS.
 *
 * "Inne i appen" = startadressens värd och sökväg som katalog (apps.lib.kth.se/applikation1/),
 * eller APP_SCOPE om den är satt. Där har webbappen sin egen navigering och ramen döljs.
 */
public class KioskChrome {

    private static final String TAG = "KioskChrome";
    /** Engelskans färg på de vita korten */
    private static final int SUB_TEXT = 0xFF7B8494;
    /** Rubriker och text på de ramade knapparna, som i skissen och Linux-kiosken */
    private static final int TITLE = 0xFF1F2937;

    /** Vad knapparna gör, i MainActivity */
    public interface Actions {
        void back();

        void home();

        /** En annan app än hem-appen: ny start på dess startsida */
        void openApp(String url);

        void retry();

        void keepGoing();
    }

    private final Activity activity;
    private final View navBar, errorPage, idleWarning, blockedSheet;
    private final TextView navTitle, navHost;
    private final NavButton navBack, navHome;
    private final LinearLayout navApps;
    private final View navInfo;
    private final Actions actions;

    /** Flera appar: hem-appen (index 0) och de övriga. Tom lista = en app, som förut. */
    private java.util.List<KioskApps.App> apps = new java.util.ArrayList<>();
    private String homeLabel = "";
    private String homeIcon = "house";
    private final java.util.List<NavButton> appButtons = new java.util.ArrayList<>();
    /** Förstasida (HOME_MODE=launcher): tjänsterna, och om förstasidan visas just nu. Då visar ramen
     *  tjänstens namn och Startsida i stället för flikar. */
    private java.util.List<KioskApps.App> launcherApps = new java.util.ArrayList<>();
    private boolean launcherVisible = false;

    private Scope scope = new Scope(null, "/");
    private Scope landedScope = null;
    /** Hem-appens engelska område (adress_en på en annan värd eller sökväg) */
    private Scope homeScopeEn = null;
    /** auto: bara utanför appen; always: alltid; none: aldrig (skyltar) */
    private String mode = "auto";
    private boolean english = false;

    public KioskChrome(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        navApps = activity.findViewById(R.id.nav_apps);
        navInfo = activity.findViewById(R.id.nav_info);
        navBar = activity.findViewById(R.id.nav_bar);
        errorPage = activity.findViewById(R.id.error_page);
        idleWarning = activity.findViewById(R.id.idle_warning);
        blockedSheet = activity.findViewById(R.id.blocked_sheet);
        navBack = activity.findViewById(R.id.nav_back);
        navHome = activity.findViewById(R.id.nav_home);
        navTitle = activity.findViewById(R.id.nav_title);
        navHost = activity.findViewById(R.id.nav_host);

        android.graphics.Typeface bold = Fonts.bold(activity);
        navTitle.setTypeface(bold);
        navHost.setTypeface(Fonts.regular(activity));
        navBack.setOnClickListener(v -> actions.back());
        navHome.setOnClickListener(v -> actions.home());
        activity.findViewById(R.id.error_retry).setOnClickListener(v -> actions.retry());
        activity.findViewById(R.id.error_home).setOnClickListener(v -> actions.home());
        activity.findViewById(R.id.idle_continue).setOnClickListener(v -> {
            hideWarning();
            actions.keepGoing();
        });
        activity.findViewById(R.id.idle_restart).setOnClickListener(v -> actions.home());
        activity.findViewById(R.id.blocked_close).setOnClickListener(v -> hideBlocked());
        applyTexts();
    }

    /**
     * Från inställningarna: startadress, ev. APP_SCOPE, NAVIGATION och LANGUAGE, samt hem-appens
     * namn och ikon och övriga appar (APPS) när enheten har flera.
     */
    public void configure(String startUrl, String appScope, String navigation, String language,
                          String startLabel, String startIcon, java.util.List<KioskApps.App> appList) {
        apps = appList == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(appList);
        // I ramen får det plats högst sex flikar, hem-appen (den första tjänsten) inräknad
        if (apps.size() > KioskApps.TAB_APPS) apps = new java.util.ArrayList<>(apps.subList(0, KioskApps.TAB_APPS));
        Scope explicit = Scope.parse(appScope);
        scope = !apps.isEmpty() ? apps.get(0).scope : (explicit != null ? explicit : Scope.fromStartUrl(startUrl));
        homeScopeEn = apps.isEmpty() ? null : apps.get(0).scopeEn;
        landedScope = null;
        mode = navigation == null || navigation.isEmpty() ? "auto" : navigation;
        english = "en".equals(language);
        homeLabel = startLabel == null ? "" : startLabel.trim();
        homeIcon = startIcon == null || startIcon.trim().isEmpty() ? "house" : startIcon.trim();
        applyScale();
        buildAppButtons();
        applyTexts();
    }

    /** Förstasida: tjänsterna (tom lista = ingen förstasida) */
    public void setLauncherApps(java.util.List<KioskApps.App> apps) {
        launcherApps = apps == null ? new java.util.ArrayList<>() : apps;
        applyTexts();
    }

    public void setLauncherVisible(boolean visible) {
        launcherVisible = visible;
    }

    /** Språket i kiosknavigeringen kan bytas av besökaren (knappen på förstasidan) */
    public void setEnglish(boolean en) {
        english = en;
        applyTexts();
    }

    private boolean launcherMode() {
        return !launcherApps.isEmpty();
    }

    private KioskApps.App launcherApp(String url) {
        for (KioskApps.App a : launcherApps) if (a.contains(url)) return a;
        return null;
    }

    /** Fler än en tjänst: ramen visar en flik per tjänst (hem först) i stället för sidans titel */
    public boolean hasApps() {
        return apps.size() >= 2;
    }

    /** Fliken (0 = hem, den första tjänsten) som sidan hör till, annars -1 (en sida utanför apparna) */
    private int activeApp(String url) {
        if (insideApp(url)) return 0;
        for (int i = 1; i < apps.size(); i++) {
            if (apps.get(i).contains(url)) return i;
        }
        return -1;
    }

    private void buildAppButtons() {
        navApps.removeAllViews();
        appButtons.clear();
        if (!hasApps()) return;
        int total = apps.size();
        for (int i = 0; i < total; i++) {
            final int index = i;
            NavButton b = new NavButton(activity);
            b.setIcon(KioskApps.icon(apps.get(i).icon));
            b.setOnClickListener(v -> {
                if (index == 0) actions.home();
                else actions.openApp(apps.get(index).url(english));
            });
            navApps.addView(b);
            appButtons.add(b);
        }
        styleNav();
        labelAppButtons();
        markActive(-1);
    }

    /** Svenska och engelska på varje flik, ur tjänstens namn och namn_en */
    private void labelAppButtons() {
        for (int i = 0; i < appButtons.size() && i < apps.size(); i++) {
            KioskApps.App a = apps.get(i);
            appButtons.get(i).setLabels(a.label, a.labelEn);
        }
    }

    /** Den aktiva appen är blåfylld, de andra vita med ram */
    private void markActive(int active) {
        for (int i = 0; i < appButtons.size(); i++) appButtons.get(i).setActive(i == active);
    }

    /**
     * Liggande: ikonen till vänster, 60 hög. Stående, eller fler än fem flikar: ikonen ovanför texten,
     * 88 hög, lika breda flikar.
     */
    private boolean portrait() {
        return activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    private void styleNav() {
        boolean stacked = portrait() || appButtons.size() > 5;
        float u = scale();
        int h = s(stacked ? 88 : 60);
        navBack.setStyle(stacked, u);
        navBack.setIcon(R.drawable.ic_lucide_chevron_left);
        setSize(navBack, stacked ? s(88) : ViewGroup.LayoutParams.WRAP_CONTENT, h);
        navHome.setStyle(stacked, u);
        navHome.setIcon(launcherMode() || KioskApps.icon(homeIcon) == 0 ? R.drawable.ic_lucide_house : KioskApps.icon(homeIcon));
        navHome.setActive(true);
        navHome.setMinimumWidth(stacked ? s(88) : 0);
        setSize(navHome, ViewGroup.LayoutParams.WRAP_CONTENT, h);
        for (int i = 0; i < appButtons.size(); i++) {
            NavButton b = appButtons.get(i);
            b.setStyle(stacked, u);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
            lp.setMarginStart(i == 0 ? 0 : s(stacked ? 8 : 16));
            b.setLayoutParams(lp);
        }
        ViewGroup.LayoutParams bar = navBar.getLayoutParams();
        bar.height = s(stacked ? 116 : 88);
        navBar.setLayoutParams(bar);
        navApps.setPadding(s(stacked ? 8 : 16), 0, 0, 0);
    }

    /**
     * Mått som i skissen: ramen ritas för en skärm som är 1280 bred liggande (800 stående) och skalas till
     * skärmens bredd, som förstasidan. Då ser ramen likadan ut på alla skärmar, och som i Linux-kiosken.
     */
    private int s(float design) {
        return Math.round(design * scale());
    }

    private float scale() {
        boolean portrait = activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        return activity.getResources().getDisplayMetrics().widthPixels / (portrait ? 800f : 1280f);
    }

    /**
     * Tvåspråkig text på en knapp: svenska i knappens storlek, engelska mindre under. Bredden räknas
     * ur den bredaste raden (padDesign < 0: bredden rörs inte), så att ingen bokstav klipps.
     */
    private void setTwo(MaterialButton b, String sv, String en, float svDesign, float enDesign, float padDesign, int enColor) {
        b.setMaxLines(en == null ? 1 : 2);
        b.setIncludeFontPadding(false);
        b.setLineSpacing(0, 1f);
        b.setText(Bilingual.two(sv, en, s(enDesign), enColor, Fonts.regular(activity)));
        if (padDesign < 0) return;
        android.graphics.Paint svp = new android.graphics.Paint();
        svp.setTypeface(Fonts.bold(activity));
        svp.setTextSize(s(svDesign));
        android.graphics.Paint enp = new android.graphics.Paint();
        enp.setTypeface(Fonts.regular(activity));
        enp.setTextSize(s(enDesign));
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        lp.width = Math.round(Bilingual.width(sv, en, svp, enp) + s(padDesign) * 2 + s(4));
        b.setLayoutParams(lp);
    }

    private void applyScale() {
        navBar.setPadding(s(20), 0, s(20), 0);
        styleNav();
        navTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(22));
        navHost.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(15));
        applyIdleScale();
        applyBlockedScale();
        applyErrorScale();
    }

    /** "Är du kvar?" med skissens mått (kort 560, ring 120, rubrik 30, text 20, knappar 72 och 56), skalade som resten */
    private void applyIdleScale() {
        idleWarning.setBackgroundColor(Color.argb(140, 0, 0, 40));
        ViewGroup card = (ViewGroup) activity.findViewById(R.id.idle_count).getParent();
        card.setPadding(s(40), s(40), s(40), s(40));
        android.widget.FrameLayout.LayoutParams clp = (android.widget.FrameLayout.LayoutParams) card.getLayoutParams();
        clp.width = Math.min(s(560), activity.getResources().getDisplayMetrics().widthPixels - s(64));
        card.setLayoutParams(clp);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(s(24));
        card.setBackground(bg);
        TextView count = activity.findViewById(R.id.idle_count);
        setSize(count, s(120), s(120));
        count.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(48));
        count.setTypeface(Fonts.extraBold(activity));
        TextView title = activity.findViewById(R.id.idle_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(30));
        title.setTypeface(Fonts.extraBold(activity));
        title.setTextColor(TITLE);
        setTopMargin(title, s(18));
        TextView text = activity.findViewById(R.id.idle_text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(20));
        text.setTypeface(Fonts.regular(activity));
        text.setTextColor(Color.parseColor("#3D4452"));
        text.setLineSpacing(0, 1.5f);
        setTopMargin(text, s(18));
        MaterialButton cont = activity.findViewById(R.id.idle_continue);
        setSize(cont, ViewGroup.LayoutParams.MATCH_PARENT, s(72));
        cont.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(24));
        cont.setCornerRadius(s(16));
        cont.setLetterSpacing(0f);
        cont.setTypeface(Fonts.bold(activity));
        setTopMargin(cont, s(18));
        MaterialButton restart = activity.findViewById(R.id.idle_restart);
        setSize(restart, ViewGroup.LayoutParams.WRAP_CONTENT, s(56));
        restart.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(20));
        restart.setCornerRadius(s(14));
        restart.setLetterSpacing(0f);
        restart.setTypeface(Fonts.bold(activity));
        restart.setPadding(s(24), 0, s(24), 0);
        restart.setTextColor(activity.getColor(R.color.kth_blue));
        setTopMargin(restart, s(18));
    }


    /** Spärr-arket med skissens mått: ark 760 brett, text 30 och 20, Stäng 60 hög, QR 210 i en ram */
    private void applyBlockedScale() {
        blockedSheet.setBackgroundColor(Color.argb(115, 0, 0, 40));
        ViewGroup sheet = (ViewGroup) ((ViewGroup) blockedSheet).getChildAt(0);
        android.widget.FrameLayout.LayoutParams slp = (android.widget.FrameLayout.LayoutParams) sheet.getLayoutParams();
        slp.width = Math.min(s(760), activity.getResources().getDisplayMetrics().widthPixels);
        sheet.setLayoutParams(slp);
        sheet.setPadding(s(40), s(36), s(40), s(40));
        TextView title = activity.findViewById(R.id.blocked_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(30));
        title.setTypeface(Fonts.extraBold(activity));
        title.setTextColor(TITLE);
        TextView text = activity.findViewById(R.id.blocked_text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(20));
        text.setTypeface(Fonts.regular(activity));
        text.setTextColor(Color.parseColor("#3D4452"));
        text.setLineSpacing(0, 1.5f);
        setTopMargin(text, s(14));
        MaterialButton close = activity.findViewById(R.id.blocked_close);
        setSize(close, ViewGroup.LayoutParams.WRAP_CONTENT, s(60));
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(20));
        close.setCornerRadius(s(14));
        close.setLetterSpacing(0f);
        close.setTypeface(Fonts.bold(activity));
        close.setPadding(s(28), 0, s(28), 0);
        setTopMargin(close, s(20));
        ImageView qr = activity.findViewById(R.id.blocked_qr);
        setSize(qr, s(238), s(238));
        qr.setPadding(s(14), s(14), s(14), s(14));
        ((ViewGroup.MarginLayoutParams) qr.getLayoutParams()).setMarginStart(s(32));
    }

    /** Felsidan med skissens mått: ikon 72, rubrik 34, text 20, knappar 60 hög med 14 mellanrum */
    private void applyErrorScale() {
        errorPage.setPadding(s(48), s(48), s(48), s(48));
        ViewGroup page = (ViewGroup) errorPage;
        setSize(page.getChildAt(0), s(72), s(72));
        TextView title = activity.findViewById(R.id.error_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(34));
        title.setTypeface(Fonts.extraBold(activity));
        title.setTextColor(TITLE);
        setTopMargin(title, s(18));
        TextView text = activity.findViewById(R.id.error_text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(20));
        text.setTypeface(Fonts.regular(activity));
        text.setTextColor(Color.parseColor("#3D4452"));
        text.setLineSpacing(0, 1.5f);
        text.setMaxWidth(s(560));
        setTopMargin(text, s(18));
        setTopMargin(page.getChildAt(3), s(26));
        for (int id : new int[]{R.id.error_retry, R.id.error_home}) {
            MaterialButton b = activity.findViewById(id);
            setSize(b, ViewGroup.LayoutParams.WRAP_CONTENT, s(60));
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(20));
            b.setCornerRadius(s(14));
            b.setLetterSpacing(0f);
            b.setTypeface(Fonts.bold(activity));
            b.setPadding(s(28), 0, s(28), 0);
            if (id == R.id.error_retry) outline(b);
            else b.setStrokeWidth(0);
        }
        ((ViewGroup.MarginLayoutParams) activity.findViewById(R.id.error_retry).getLayoutParams()).setMarginEnd(s(14));
    }

    /** Den andra knappen: vit med ram och mörk text, som i skissen och Linux-kiosken */
    private void outline(MaterialButton b) {
        b.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
        b.setStrokeColor(ColorStateList.valueOf(activity.getColor(R.color.nav_border)));
        b.setStrokeWidth(Math.max(1, s(2)));
        b.setTextColor(TITLE);
    }

    private static void setSize(View v, int w, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    private static void setTopMargin(View v, int px) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = px;
        v.setLayoutParams(lp);
    }

    /**
     * Dit startadressen faktiskt ledde, om det är en annan webbplats (t ex en app som omdirigerar
     * till sin egen domän), räknas också som inne i appen. En omdirigering till en annan sökväg på
     * samma värd utvidgar inte appen: där ligger andra appar (apps.lib.kth.se/applikation2).
     */
    public void setLandedStart(String url) {
        Scope s = Scope.fromStartUrl(url);
        landedScope = s != null && scope.host != null && !s.host.equals(scope.host) ? s : null;
    }

    public boolean insideApp(String url) {
        return scope.contains(url) || (landedScope != null && landedScope.contains(url))
                || (homeScopeEn != null && homeScopeEn.contains(url));
    }

    /** Visa eller dölj ramen för den sida som visas nu. */
    public void update(String url, String title, boolean canGoBack, boolean keyboardOpen) {
        if (launcherMode()) {
            // Tjänsten som visas: Tillbaka, tjänstens namn och Startsida (till förstasidan)
            boolean showBar = !launcherVisible && !keyboardOpen && !"none".equals(mode);
            navBar.setVisibility(showBar ? View.VISIBLE : View.GONE);
            if (!showBar) return;
            navApps.setVisibility(View.GONE);
            navInfo.setVisibility(View.VISIBLE);
            navHome.setVisibility(View.VISIBLE);
            KioskApps.App app = launcherApp(url);
            Uri u = url == null ? null : Uri.parse(url);
            String host = u == null || u.getHost() == null ? "" : u.getHost();
            boolean noTitle = title == null || title.trim().isEmpty() || title.startsWith("http")
                    || errorPage.getVisibility() == View.VISIBLE;
            navTitle.setText(app != null ? app.label(english) : (noTitle ? host : title.trim()));
            navHost.setText(host);
            navBack.setEnabled(canGoBack);
            navBack.setAlpha(canGoBack ? 1f : 0.45f);
            return;
        }
        boolean show = !keyboardOpen && !"none".equals(mode)
                && (hasApps() || "always".equals(mode) || !insideApp(url));
        navBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        boolean multi = hasApps();
        navApps.setVisibility(multi ? View.VISIBLE : View.GONE);
        navInfo.setVisibility(multi ? View.GONE : View.VISIBLE);
        navHome.setVisibility(multi ? View.GONE : View.VISIBLE);
        if (multi) {
            markActive(activeApp(url));
            navBack.setEnabled(canGoBack);
            navBack.setAlpha(canGoBack ? 1f : 0.45f);
            return;
        }
        Uri uri = url == null ? null : Uri.parse(url);
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost();
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath();
        String where = host + (path.length() > 1 ? path : "");
        // På felsidan är titeln Chromiums egen ("Webpage not available"): visa webbplatsen i stället
        boolean noTitle = title == null || title.trim().isEmpty() || title.startsWith("http")
                || errorPage.getVisibility() == View.VISIBLE;
        navTitle.setText(noTitle ? host : title.trim());
        navHost.setText(where);
        navBack.setEnabled(canGoBack);
        navBack.setAlpha(canGoBack ? 1f : 0.45f);
    }

    // --- Varning före tillbakagång ---

    private int warningTotal = 0;

    public void showWarning(int secondsLeft) {
        if (idleWarning.getVisibility() != View.VISIBLE || secondsLeft > warningTotal) warningTotal = secondsLeft;
        CountdownView count = activity.findViewById(R.id.idle_count);
        count.setText(String.valueOf(secondsLeft));
        count.setProgress(warningTotal > 0 ? secondsLeft / (float) warningTotal : 0f);
        ((TextView) activity.findViewById(R.id.idle_text)).setText(Bilingual.two(
                "Ingen har rört skärmen på en stund. Om " + secondsLeft + " sekunder börjar appen om från början och det du har gjort här rensas.",
                "Nobody has touched the screen for a while. In " + secondsLeft + " seconds the app starts over and what you did here is cleared.",
                s(16), SUB_TEXT, Fonts.regular(activity)));
        idleWarning.setVisibility(View.VISIBLE);
    }

    public void hideWarning() {
        idleWarning.setVisibility(View.GONE);
    }

    public boolean isWarningShown() {
        return idleWarning.getVisibility() == View.VISIBLE;
    }

    // --- Blockerad länk ---

    /** "Sidan kan inte öppnas här", med QR-kod för webbadresser så att besökaren kan fortsätta på mobilen. */
    public void showBlocked(String url) {
        Uri uri = Uri.parse(url);
        boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost() == null ? "" : uri.getHost();
        ImageView qr = activity.findViewById(R.id.blocked_qr);
        Bitmap code = web ? qrCode(url) : null;
        qr.setVisibility(code != null ? View.VISIBLE : View.GONE);
        if (code != null) {
            qr.setImageBitmap(code);
            qr.setContentDescription("QR-kod till " + host + " / QR code for " + host);
        }
        ((TextView) activity.findViewById(R.id.blocked_text)).setText(code != null
                ? Bilingual.two(host + " öppnas inte på den här skärmen. Skanna koden med mobilen för att fortsätta där.",
                        host + " can't be opened on this screen. Scan the code with your phone to continue there.",
                        s(16), SUB_TEXT, Fonts.regular(activity))
                : Bilingual.two("Den här länken kan inte öppnas på skärmen.", "This link can't be opened on this screen.",
                        s(16), SUB_TEXT, Fonts.regular(activity)));
        blockedSheet.setVisibility(View.VISIBLE);
    }

    public void hideBlocked() {
        blockedSheet.setVisibility(View.GONE);
    }

    // --- Felsida ---

    /**
     * Egen felsida. offline: enheten saknar nät (t ex wifi inte uppe än efter omstart); sidan
     * laddas då om av sig själv när nätet kommer, och texten säger det.
     */
    public void showError(boolean offline) {
        ((TextView) activity.findViewById(R.id.error_title)).setText(offline
                ? Bilingual.two("Väntar på nätverket…", "Waiting for the network…", s(22), SUB_TEXT, Fonts.bold(activity))
                : Bilingual.two("Sidan kunde inte laddas", "The page could not be loaded", s(22), SUB_TEXT, Fonts.bold(activity)));
        ((TextView) activity.findViewById(R.id.error_text)).setText(offline
                ? Bilingual.two("Sidan laddas av sig själv så snart skärmen är ansluten.",
                        "The page loads by itself as soon as the screen is connected.", s(16), SUB_TEXT, Fonts.regular(activity))
                : Bilingual.two("Det kan vara ett tillfälligt nätverksfel. Försök igen, eller börja om från början.",
                        "It may be a temporary network problem. Try again, or start over.", s(16), SUB_TEXT, Fonts.regular(activity)));
        errorPage.setVisibility(View.VISIBLE);
    }

    public boolean isErrorShown() {
        return errorPage.getVisibility() == View.VISIBLE;
    }

    public void hideError() {
        errorPage.setVisibility(View.GONE);
    }

    public void hideAll() {
        hideWarning();
        hideBlocked();
        hideError();
    }

    private void applyTexts() {
        navBack.setLabels("Tillbaka", "Back");
        if (launcherMode()) navHome.setLabels("Startsida", "Home page");
        else if (!homeLabel.isEmpty()) navHome.setLabels(homeLabel, null);
        else navHome.setLabels("Hem", "Home");
        labelAppButtons();
        MaterialButton retry = activity.findViewById(R.id.error_retry);
        setTwo(retry, "Försök igen", "Try again", 20, 14, 28, Bilingual.LIGHT_ON_WHITE);
        // Startknappen på felsidan heter det som START_LABEL anger (på en rad), annars som startknappen i ramen
        MaterialButton errorHome = activity.findViewById(R.id.error_home);
        if (!homeLabel.isEmpty()) setTwo(errorHome, homeLabel, null, 20, 14, 28, Bilingual.LIGHT_ON_BLUE);
        else if (launcherMode()) setTwo(errorHome, "Startsida", "Home page", 20, 14, 28, Bilingual.LIGHT_ON_BLUE);
        else setTwo(errorHome, "Hem", "Home", 20, 14, 28, Bilingual.LIGHT_ON_BLUE);
        ((TextView) activity.findViewById(R.id.idle_title)).setText(
                Bilingual.two("Är du kvar?", "Are you still there?", s(20), SUB_TEXT, Fonts.bold(activity)));
        setTwo(activity.findViewById(R.id.idle_continue), "Fortsätt här", "Continue here", 24, 15, -1, Bilingual.LIGHT_ON_BLUE);
        setTwo(activity.findViewById(R.id.idle_restart), "Börja om nu", "Start over now", 20, 13, 24, Bilingual.LIGHT_ON_WHITE);
        ((TextView) activity.findViewById(R.id.blocked_title)).setText(
                Bilingual.two("Sidan kan inte öppnas här", "This page can't be opened here", s(20), SUB_TEXT, Fonts.bold(activity)));
        setTwo(activity.findViewById(R.id.blocked_close), "Stäng", "Close", 20, 14, 28, Bilingual.LIGHT_ON_BLUE);
    }

    /** QR-kod i KTH-navy på vitt, skapad i enheten (inget nätanrop). Null om adressen inte går att koda. */
    private static Bitmap qrCode(String text) {
        try {
            int size = 440;
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 0));
            int[] pixels = new int[size * size];
            int dark = Color.rgb(0, 0, 0x61);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = m.get(x, y) ? dark : Color.WHITE;
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            Log.w(TAG, "QR-koden kunde inte skapas", e);
            return null;
        }
    }

    /** En värd och en sökväg som katalog: apps.lib.kth.se + /applikation1/ */
    static final class Scope {
        final String host;
        final String prefix;

        Scope(String host, String prefix) {
            this.host = host;
            this.prefix = prefix;
        }

        /** APP_SCOPE: "apps.lib.kth.se/applikation1/" eller en hel adress. Null om tom eller ogiltig. */
        static Scope parse(String value) {
            if (value == null || value.trim().isEmpty()) return null;
            String v = value.trim();
            if (!v.contains("://")) v = "https://" + v;
            Uri uri = Uri.parse(v);
            if (uri.getHost() == null) return null;
            return new Scope(uri.getHost().toLowerCase(Locale.ROOT), directory(uri.getPath(), true));
        }

        /** Startadressens värd och dess sökväg som katalog (/kiosk och /kiosk/ ger /kiosk/, /app/index.html ger /app/). */
        static Scope fromStartUrl(String url) {
            if (url == null) return null;
            Uri uri = Uri.parse(url);
            if (uri.getHost() == null) return null;
            return new Scope(uri.getHost().toLowerCase(Locale.ROOT), directory(uri.getPath(), false));
        }

        private static String directory(String path, boolean explicit) {
            String p = path == null || path.isEmpty() ? "/" : path;
            if (!p.endsWith("/")) {
                String last = p.substring(p.lastIndexOf('/') + 1);
                // Ett filnamn (index.html) är inte en katalog; i APP_SCOPE räknas allt som katalog
                p = !explicit && last.contains(".") ? p.substring(0, p.lastIndexOf('/') + 1) : p + "/";
            }
            return p;
        }

        boolean contains(String url) {
            if (url == null || host == null) return false;
            Uri uri = Uri.parse(url);
            if (uri.getHost() == null || !host.equalsIgnoreCase(uri.getHost())) return false;
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            return (path + "/").startsWith(prefix) || path.startsWith(prefix);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Scope && ((Scope) o).host.equals(host) && ((Scope) o).prefix.equals(prefix);
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + prefix.hashCode();
        }
    }
}
