package se.kth.lib.publikiosk;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
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
                b.setIconSize(dp(26));
                b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
                b.setIconPadding(dp(8));
            }
            b.setAllCaps(false);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setTextSize(18);
            b.setTypeface(Fonts.bold(activity));
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setCornerRadius(dp(14));
            b.setStrokeWidth(dp(2));
            b.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(60), 1f);
            if (i > 0) lp.setMarginStart(dp(10));
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

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
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

    public void showWarning(int secondsLeft) {
        ((TextView) activity.findViewById(R.id.idle_count)).setText(String.valueOf(secondsLeft));
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
        ((TextView) activity.findViewById(R.id.error_retry)).setText(english ? "Try again" : "Försök igen");
        ((TextView) activity.findViewById(R.id.error_home)).setText(english ? "Home" : "Hem");
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
