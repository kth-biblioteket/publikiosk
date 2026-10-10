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
    private final TextView navBack, navHome, navTitle, navHost;
    private final LinearLayout navApps;
    private final View navInfo;
    private final Actions actions;

    /** Flera appar: hem-appen (index 0) och de övriga. Tom lista = en app, som förut. */
    private java.util.List<KioskApps.App> apps = new java.util.ArrayList<>();
    private String homeLabel = "";
    private String homeIcon = "house";
    private final java.util.List<MaterialButton> appButtons = new java.util.ArrayList<>();
    /** Förstasida (HOME_MODE=launcher): tjänsterna, och om förstasidan visas just nu. Då visar ramen
     *  tjänstens namn och Startsida i stället för flikar. */
    private java.util.List<KioskApps.App> launcherApps = new java.util.ArrayList<>();
    private boolean launcherVisible = false;

    private Scope scope = new Scope(null, "/");
    private Scope landedScope = null;
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
        navBack.setTypeface(bold);
        navHome.setTypeface(bold);
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
                          String startLabel, String startIcon, String appsRaw) {
        Scope explicit = Scope.parse(appScope);
        scope = explicit != null ? explicit : Scope.fromStartUrl(startUrl);
        landedScope = null;
        mode = navigation == null || navigation.isEmpty() ? "auto" : navigation;
        english = "en".equals(language);
        apps = KioskApps.parse(appsRaw);
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
        for (KioskApps.App a : launcherApps) if (a.scope.contains(url)) return a;
        return null;
    }

    /** Fler än en app: ramen visar en knapp per app i stället för sidans titel */
    public boolean hasApps() {
        return !apps.isEmpty();
    }

    /** Hem-appen eller en annan app som sidan hör till, annars -1 (en sida utanför apparna) */
    private int activeApp(String url) {
        if (insideApp(url)) return 0;
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).scope.contains(url)) return i + 1;
        }
        return -1;
    }

    private void buildAppButtons() {
        navApps.removeAllViews();
        appButtons.clear();
        if (apps.isEmpty()) return;
        int total = apps.size() + 1;
        for (int i = 0; i < total; i++) {
            final int index = i;
            MaterialButton b = new MaterialButton(activity);
            String icon = i == 0 ? homeIcon : apps.get(i - 1).icon;
            int res = KioskApps.icon(icon);
            if (res != 0) {
                b.setIconResource(res);
                b.setIconSize(s(26));
                b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
                b.setIconPadding(s(10));
            }
            b.setAllCaps(false);
            b.setMaxLines(1);
            b.setHorizontallyScrolling(false);
            b.setEllipsize(null);
            b.setLetterSpacing(0f);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(20));
            b.setTypeface(Fonts.bold(activity));
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setCornerRadius(s(14));
            b.setStrokeWidth(Math.max(1, s(2)));
            b.setPadding(s(22), 0, s(22), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, s(60), 1f);
            if (i > 0) lp.setMarginStart(s(16));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                if (index == 0) actions.home();
                else actions.openApp(apps.get(index - 1).url);
            });
            navApps.addView(b);
            appButtons.add(b);
        }
        labelAppButtons();
        markActive(-1);
    }

    private void labelAppButtons() {
        for (int i = 0; i < appButtons.size(); i++) {
            String label = i == 0 ? (homeLabel.isEmpty() ? (english ? "Home" : "Hem") : homeLabel) : apps.get(i - 1).label(english);
            appButtons.get(i).setText(label);
            appButtons.get(i).setContentDescription(label);
        }
    }

    /** Den aktiva appen är blåfylld, de andra vita med ram */
    private void markActive(int active) {
        int blue = activity.getColor(R.color.kth_blue);
        int text = activity.getColor(R.color.nav_text);
        int border = activity.getColor(R.color.nav_border);
        for (int i = 0; i < appButtons.size(); i++) {
            MaterialButton b = appButtons.get(i);
            boolean on = i == active;
            b.setBackgroundTintList(ColorStateList.valueOf(on ? blue : Color.WHITE));
            b.setStrokeColor(ColorStateList.valueOf(on ? blue : border));
            b.setTextColor(on ? Color.WHITE : text);
            b.setIconTint(ColorStateList.valueOf(on ? Color.WHITE : text));
            b.setSelected(on);
        }
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

    /** Knappens bredd räknas ut ur texten, så att ingen bokstav klipps (Material mäter ibland för snävt med egna typsnitt) */
    private void fitWidth(MaterialButton b, int padStart, int padEnd, int iconGap) {
        android.graphics.Paint paint = new android.graphics.Paint(b.getPaint());
        float text = paint.measureText(b.getText().toString());
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        lp.width = Math.round(s(padStart) + s(26) + s(iconGap) + text + s(padEnd) + s(4));
        b.setLayoutParams(lp);
    }

    /** Textknappar utan ikon: bredden ur texten, av samma skäl som fitWidth */
    private void fitTextButtons() {
        for (int id : new int[]{R.id.error_retry, R.id.error_home, R.id.blocked_close}) {
            MaterialButton b = activity.findViewById(id);
            b.setMaxLines(1);
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            lp.width = Math.round(new android.graphics.Paint(b.getPaint()).measureText(b.getText().toString()) + s(28) * 2 + s(4));
            b.setLayoutParams(lp);
        }
        MaterialButton restart = activity.findViewById(R.id.idle_restart);
        restart.setMaxLines(1);
        ViewGroup.LayoutParams rlp = restart.getLayoutParams();
        rlp.width = Math.round(new android.graphics.Paint(restart.getPaint()).measureText(restart.getText().toString()) + s(24) * 2 + s(4));
        restart.setLayoutParams(rlp);
    }

    private void applyScale() {
        ViewGroup.LayoutParams bar = navBar.getLayoutParams();
        bar.height = s(88);
        navBar.setLayoutParams(bar);
        navBar.setPadding(s(20), 0, s(20), 0);
        for (TextView t : new TextView[]{navBack, navHome}) {
            MaterialButton b = (MaterialButton) t;
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            lp.height = s(60);
            b.setLayoutParams(lp);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(20));
            b.setCornerRadius(s(14));
            b.setIconSize(s(26));
            b.setIconPadding(s(t == navBack ? 6 : 10));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setSingleLine(true);
            b.setLetterSpacing(0f);
        }
        ((MaterialButton) navBack).setStrokeWidth(Math.max(1, s(2)));
        navBack.setPadding(s(14), 0, s(22), 0);
        navHome.setPadding(s(18), 0, s(24), 0);
        navTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(22));
        navHost.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(15));
        navApps.setPadding(s(16), 0, 0, 0);
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
            b.setStrokeWidth(id == R.id.error_retry ? Math.max(1, s(2)) : 0);
        }
        ((ViewGroup.MarginLayoutParams) activity.findViewById(R.id.error_retry).getLayoutParams()).setMarginEnd(s(14));
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
        return scope.contains(url) || (landedScope != null && landedScope.contains(url));
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
        ((TextView) activity.findViewById(R.id.idle_text)).setText(english
                ? "Nobody has touched the screen for a while. In " + secondsLeft + " seconds the app starts over and what you did here is cleared."
                : "Ingen har rört skärmen på en stund. Om " + secondsLeft + " sekunder börjar appen om från början och det du har gjort här rensas.");
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
            qr.setContentDescription(english ? "QR code for " + host : "QR-kod till " + host);
        }
        ((TextView) activity.findViewById(R.id.blocked_text)).setText(code != null
                ? (english
                    ? host + " can't be opened on this screen. Scan the code with your phone to continue there."
                    : host + " öppnas inte på den här skärmen. Skanna koden med mobilen för att fortsätta där.")
                : (english ? "This link can't be opened on this screen." : "Den här länken kan inte öppnas på skärmen."));
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
                ? (english ? "Waiting for the network…" : "Väntar på nätverket…")
                : (english ? "The page could not be loaded" : "Sidan kunde inte laddas"));
        ((TextView) activity.findViewById(R.id.error_text)).setText(offline
                ? (english ? "The page loads by itself as soon as the screen is connected."
                           : "Sidan laddas av sig själv så snart skärmen är ansluten.")
                : (english ? "It may be a temporary network problem. Try again, or start over."
                           : "Det kan vara ett tillfälligt nätverksfel. Försök igen, eller börja om från början."));
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
        navBack.setText(english ? "Back" : "Tillbaka");
        navHome.setText(launcherMode() ? (english ? "Home page" : "Startsida") : (english ? "Home" : "Hem"));
        labelAppButtons();
        fitTextButtons();
        fitWidth((MaterialButton) navBack, 14, 22, 6);
        fitWidth((MaterialButton) navHome, 18, 24, 10);
        ((TextView) activity.findViewById(R.id.error_retry)).setText(english ? "Try again" : "Försök igen");
        // Startknappen på felsidan heter det som START_LABEL anger, annars som startknappen i ramen
        ((TextView) activity.findViewById(R.id.error_home)).setText(!homeLabel.isEmpty() ? homeLabel
                : launcherMode() ? (english ? "Home page" : "Startsida") : (english ? "Home" : "Hem"));
        ((TextView) activity.findViewById(R.id.idle_title)).setText(english ? "Are you still there?" : "Är du kvar?");
        ((TextView) activity.findViewById(R.id.idle_continue)).setText(english ? "Continue here" : "Fortsätt här");
        ((TextView) activity.findViewById(R.id.idle_restart)).setText(english ? "Start over now" : "Börja om nu");
        ((TextView) activity.findViewById(R.id.blocked_title)).setText(english ? "This page can't be opened here" : "Sidan kan inte öppnas här");
        ((TextView) activity.findViewById(R.id.blocked_close)).setText(english ? "Close" : "Stäng");
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
