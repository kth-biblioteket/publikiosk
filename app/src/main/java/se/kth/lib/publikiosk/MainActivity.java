package se.kth.lib.publikiosk;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.drawerlayout.widget.DrawerLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    WebView myWeb;
    private DrawerLayout drawerLayout;
    private View triggerArea;
    private EditText urlInput;
    private EditText allowedHostsInput;
    private CheckBox clearSessionCheckbox;
    private EditText initialscaleInput;
    private EditText inactivitytimeoutInput;
    private EditText inactivitytimeoutwebInput;
    private Spinner orientationSpinner;
    private CheckBox fullscreenCheckbox;

    private Handler inactivityHandler;


    // Variabler för att spara settings i shared preferences
    private static final String PREFS_INACTIVITY_TIMEOUT = "inactivitytimeout";
    private static final String PREFS_INACTIVITY_TIMEOUT_WEB = "inactivitytimeoutweb";
    private static final String PREFS_NAME = "MyPrefs";
    private static final String PREF_ALLOWED_HOSTS = "allowedhosts";
    private static final String DEFAULT_ALLOWED_HOSTS = "kth.se";
    private static final String PREF_CLEAR_SESSION = "clearsession";
    private static final String PREF_INITIAL_SCALE = "initialscale";
    private static final String PREF_ORIENTATION = "orientation";
    private static final String PREF_FULLSCREEN = "fullscreen";
    private static final String PREF_URL = "url";

    private String savedAllowedHosts;
    private boolean savedClearSession;
    private UrlPolicy urlPolicy;
    private PinStore pinStore;
    private KioskPolicy kioskPolicy;
    private PublicomClient publicomtools;
    private KioskChrome chrome;
    /** NAVIGATION, APP_SCOPE, IDLE_WARNING (sekunder) och LANGUAGE från inställningarna */
    private String savedNavigation = "auto";
    private String savedAppScope = "";
    /** APPS, START_LABEL och START_ICON: fler webbappar på enheten, med hem-appen (START_URL) först */
    private String savedApps = "";
    private String savedStartLabel = "";
    private String savedStartIcon = "house";
    private int savedIdleWarning = 10;
    private String savedLanguage = "sv";
    private StatusReporter statusReporter;
    private final java.util.concurrent.ExecutorService background = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Om startsidan laddats (null = vet inte än), till statusrapporten */
    private volatile Boolean pageLoaded = null;

    /** JS-bryggan "Android" svarar bara när sidan kommer från startsidans värd (sätts i onPageStarted). */
    static volatile boolean bridgeAllowed = false;
    private int savedOrientation;
    private String savedInitialScale;
    private boolean savedFullscreen;
    private String savedUrl;
    private String savedInactivityTimeout;
    private String savedInactivityTimeoutWeb;

    private boolean isPinDialogOpen = false;
    private boolean isPinVerified = false;

    private static final int CLICK_THRESHOLD = 5;
    private static final long TIME_LIMIT = 1000;
    private int clickCount = 0;
    private long lastClickTime = 0;


    private GestureDetector gestureDetector;

    boolean isUserNavigation = false;
    private boolean keyboardOpen = false;
    private String lastHttpsUpgrade;
    private long lastHttpsUpgradeAt;
    boolean isInitialLoading = true;
    String lastUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        applyWebDebug(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("webdebug", false));

        pinStore = new PinStore(this);
        kioskPolicy = new KioskPolicy(this);
        publicomtools = new PublicomClient(this);
        statusReporter = new StatusReporter(this, publicomtools, reporterState());

        setContentView(R.layout.activity_main);

        // Är appen "device owner?"
        if (kioskPolicy.isDeviceOwner()) {
            // Locktask utan användardialog, och resten av låsningarna (statusfält, hemknapp, begränsningar)
            kioskPolicy.apply();

            AutoUpdate updateManager = new AutoUpdate(this);
            autoUpdate = updateManager;
            updateManager.checkForUpdate();
            TextView currentVersion = findViewById(R.id.currentVersion);
            if (currentVersion != null) {
                currentVersion.setText(
                        "Aktuell version: " + updateManager.getCurrentVersion());
            }

            startLockTask();
            Toast.makeText(this, "Kiosk startad", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Appen är inte device owner", Toast.LENGTH_SHORT).show();
        }

        myWeb = findViewById(R.id.myWeb);
        chrome = new KioskChrome(this, new KioskChrome.Actions() {
            @Override
            public void back() {
                if (myWeb.canGoBack()) myWeb.goBack();
            }

            @Override
            public void home() {
                returnToStart("home");
            }

            @Override
            public void openApp(String url) {
                openAppUrl(url);
            }

            @Override
            public void retry() {
                retryLoad();
            }

            @Override
            public void keepGoing() {
                lastActivityAt = System.currentTimeMillis();
            }
        });
        // Sidan laddas om av sig själv när nätet kommer (t ex wifi efter en omstart)
        network = new NetworkWatcher(this, () -> {
            if (chrome.isErrorShown()) retryLoad();
            // Saknades nätet när appen startade blev uppdateringskontrollen aldrig av: gör den nu
            if (autoUpdate != null && !AutoUpdate.hasChecked()) autoUpdate.checkForUpdate();
        });
        network.start();
        ConstraintLayout myMain = findViewById(R.id.main);

        myWeb.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        drawerLayout = findViewById(R.id.drawer_layout);
        triggerArea = findViewById(R.id.trigger_area);
        initialscaleInput = findViewById(R.id.initialscale_input);
        inactivitytimeoutInput = findViewById(R.id.inactivitytimeout_input);
        inactivitytimeoutwebInput = findViewById(R.id.inactivitytimeoutweb_input);
        urlInput = findViewById(R.id.url_input);
        orientationSpinner = findViewById(R.id.orientation_spinner);
        fullscreenCheckbox = findViewById(R.id.fullscreen_checkbox);
        allowedHostsInput = findViewById(R.id.allowedhosts_input);
        clearSessionCheckbox = findViewById(R.id.clearsession_checkbox);
        Button changePinButton = findViewById(R.id.changePinButton);
        changePinButton.setOnClickListener(v -> promptForNewPin(false));
        Button saveButton = findViewById(R.id.save_button);

        myMain.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                Rect r = new Rect();
                myMain.getWindowVisibleDisplayFrame(r);
                int screenHeight = myMain.getRootView().getHeight();
                int keypadHeight = screenHeight - r.bottom;

                boolean open = keypadHeight > screenHeight * 0.15;
                // Bara den del av vyn som hamnar bakom tangentbordet. I helskärm krymper inte Android
                // fönstret (adjustResize gäller inte), men när navigeringsfältet visas gör den det, och
                // då ska ingen extra marginal läggas till.
                int[] location = new int[2];
                myMain.getLocationOnScreen(location);
                int hidden = Math.max(0, location[1] + myMain.getHeight() - r.bottom);
                int margin = open ? hidden : 0;
                ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) myWeb.getLayoutParams();
                if (params.bottomMargin != margin) {
                    params.bottomMargin = margin;
                    myWeb.setLayoutParams(params);
                }
                // Medan tangentbordet är uppe visas navigeringsfältet, så att knappen för att fälla ner
                // det syns (i helskärm måste man annars svepa upp från nederkanten för att se den)
                if (open != keyboardOpen) {
                    keyboardOpen = open;
                    if (open) showSystemUI();
                    else applyFullscreen(savedFullscreen);
                    // Tangentbordet behöver platsen: ramen döljs medan det är uppe
                    refreshChrome(myWeb.getUrl());
                }
            }
        });

        // Visa logg
        Button showLogButton = findViewById(R.id.showLogButton);
        showLogButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LogActivity.class);
            startActivity(intent);
        });

        // Avsluta kiosk
        Button quitKiosk = findViewById(R.id.quitKiosk);
        quitKiosk.setOnClickListener(v -> {
            quitKiosk();
        });

        //Gör så att javascript kan användas
        myWeb.getSettings().setJavaScriptEnabled(true);
        //Gör så att webview agerar som desktop
        myWeb.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");


        myWeb.getSettings().setDomStorageEnabled(true);
        // Bara https, inga lokala filer, inga popup-fönster (target=_blank öppnas i samma vy och
        // går då genom shouldOverrideUrlLoading), ingen plats och inga sparade formulär
        myWeb.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        myWeb.getSettings().setAllowFileAccess(false);
        myWeb.getSettings().setAllowContentAccess(false);
        myWeb.getSettings().setSupportMultipleWindows(false);
        myWeb.getSettings().setGeolocationEnabled(false);
        myWeb.getSettings().setSaveFormData(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(myWeb, true);
        myWeb.addJavascriptInterface(new WebAppInterface(this), "Android");

        //Se till att alerts från websidor visas(exvis vid delete av bokning)
        myWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return super.onJsAlert(view, url, message, result);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                refreshChrome(view.getUrl());
            }
        });
        myWeb.setWebViewClient(new WebViewClient() {
            //Kontrollera om användaren klickat på en navigation(länk/knapp)
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blockNavigation(request.getUrl().toString());
            }

            // Android 6 anropar bara den här varianten
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockNavigation(url);
            }

            // WebViews renderingsprocess kraschade eller stängdes av systemet. Utan det här dör hela
            // appen och enheten lämnar kioskläget; nu startas aktiviteten om i samma låsta läge.
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.e("publikiosk", "WebView-processen försvann (krasch: " + detail.didCrash() + "), startar om");
                VisitLog.get(MainActivity.this).end("crash");
                if (view.getParent() != null) ((android.view.ViewGroup) view.getParent()).removeView(view);
                view.destroy();
                recreate();
                return true;
            }

            // Startsidan gick inte att ladda (nätverk, DNS, certifikat …): syns i statusrapporten
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (!request.isForMainFrame()) return;
                if (UrlPolicy.sameSite(request.getUrl().toString(), savedUrl)) pageLoaded = false;
                // Egen felsida i stället för Chromiums, med Försök igen och Hem
                chrome.showError(!network.isOnline());
                scheduleAutoRetry();
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // En server som omdirigerar till http (t ex https://…/supportkiosk → http://…/supportkiosk/)
                // går inte via shouldOverrideUrlLoading. Är värden tillåten: ladda https-adressen i stället.
                String https = urlPolicy != null ? urlPolicy.httpsUpgrade(url) : null;
                long now = System.currentTimeMillis();
                // Skydd mot loop: en server som skickar https tillbaka till http igen
                if (https != null && !(https.equals(lastHttpsUpgrade) && now - lastHttpsUpgradeAt < 10_000)) {
                    lastHttpsUpgrade = https;
                    lastHttpsUpgradeAt = now;
                    Log.d("publikiosk", "Omdirigering till http, öppnar med https: " + https);
                    view.stopLoading();
                    view.loadUrl(https);
                    return;
                }
                bridgeAllowed = UrlPolicy.sameSite(url, savedUrl);
                chrome.hideError();
            }

            // Varje ny sida i historiken, även när en SPA byter sida utan att ladda om
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // Sidor per besök, för statistiken (startsidan och omladdningar räknas inte)
                if (!isReload && touchedSinceStart && !isStartPage(url)) VisitLog.get(MainActivity.this).page();
                refreshChrome(url);
            }

            // Skapa javascript på laddad websida(lägger till en knapp med länk tillbaks till huvudsida)
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d("publikiosk", "onPageFinished: ");

                // Första sidan som laddas efter att startadressen öppnats: dit den ledde
                // (efter omdirigeringar), som då också räknas som startsidan
                if (awaitingStart) {
                    landedStartUrl = url;
                    awaitingStart = false;
                    chrome.setLandedStart(url);
                }


                refreshChrome(url);
                if (isStartPage(url)) {
                    if (pageLoaded == null || !pageLoaded) {
                        boolean first = pageLoaded == null;
                        pageLoaded = true;
                        if (!first) statusReporter.sendNow();
                    }
                    isUserNavigation = false;
                    // Funktion för att kunna logga användaraktivitet(klick på websidans element)
                    new Handler().postDelayed(() -> {
                        String js =
                                "if (!window.hasLoggedClickEvent) {" +
                                "  document.addEventListener('click', function(event) {" +
                                "    let element = event.target;" +
                                "    let details = {" +
                                "        tag: element.tagName," +
                                "        id: element.id || null," +
                                "        class: element.className || null," +
                                "        text: element.innerText.trim() || null," +
                                "        attributes: {}" +
                                "    };" +
                                "    for (let attr of element.attributes) {" +
                                "        details.attributes[attr.name] = attr.value;" +
                                "    }" +
                                "    if (typeof Android !== 'undefined') {" +
                                "        Android.logActivity(JSON.stringify(details));" +
                                "    } else {" +
                                "        console.log('Android interface not available:', details);" +
                                "    }" +
                                "  });" +
                                "  window.hasLoggedClickEvent = true;" +
                                "}";
                        view.evaluateJavascript(js, null);
                    }, 100);
                }
                lastUrl = url;
            }
        });

        // Se till att long press inte är aktivit
        myWeb.setOnLongClickListener(v -> true);
        myWeb.setLongClickable(false);

        // Hämta spinner options för screen orientation från string.xml
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.orientation_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        orientationSpinner.setAdapter(adapter);

        // Hämta och sätt  skärmens upplösning
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;

        TextView resolutionText = findViewById(R.id.resolutionTextView);
        resolutionText.setText("Upplösning: " + width + " x " + height);

        // Ladda settings
        loadSettings();

        // Ladda url i webview, utan något kvar från förra sessionen
        clearSessionIfEnabled();
        myWeb.loadUrl(savedUrl);
        isUserNavigation = false;
        isInitialLoading = true;

        disableSwipeToOpenDrawer();
        setupClickListener();

        //Hantera saveknapp i settings
        saveButton.setOnClickListener(v -> {
            savedUrl = urlInput.getText().toString().trim();
            savedAllowedHosts = allowedHostsInput.getText().toString().trim();
            savedInitialScale = initialscaleInput.getText().toString().trim();
            savedInactivityTimeout = inactivitytimeoutInput.getText().toString().trim();
            savedInactivityTimeoutWeb = inactivitytimeoutwebInput.getText().toString().trim();
            savedClearSession = clearSessionCheckbox.isChecked();
            if (UrlPolicy.host(savedUrl) == null || !savedUrl.startsWith("https://")) {
                Toast.makeText(this, "Startsidan måste börja med https://", Toast.LENGTH_LONG).show();
                return;
            }
            saveSettings();
            applySettings();
            drawerLayout.closeDrawer(Gravity.LEFT);
            myWeb.loadUrl(savedUrl);
        });

        // Ingen PIN än (ny installation): den som installerar väljer en innan något annat
        if (!pinStore.isSet()) {
            promptForNewPin(true);
        }

        // publicomtools: anslutning i menyn, inställningar vid start och statusrapporter
        setupPublicomtools();
        if (publicomtools.isEnrolled()) {
            fetchConfig(false);
            statusReporter.start();
        }

        /*
          Lyssna på ändringar i settings dialog
         */
        orientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                savedOrientation = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        fullscreenCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            savedFullscreen = isChecked;
        });

        urlInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                savedUrl = urlInput.getText().toString().trim();
            }
        });

        initialscaleInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                savedInitialScale = initialscaleInput.getText().toString().trim();
            }
        });

        inactivitytimeoutInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                savedInactivityTimeout = inactivitytimeoutInput.getText().toString().trim();
            }
        });

        inactivitytimeoutwebInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                savedInactivityTimeoutWeb = inactivitytimeoutwebInput.getText().toString().trim();
            }
        });

        // Tillbaka till startsidan när ingen använder enheten
        inactivityHandler = new Handler(Looper.getMainLooper());
        inactivityHandler.postDelayed(idleCheck, IDLE_CHECK_INTERVAL_MS);

    }

    /**
     * Blockera navigering som inte är tillåten (andra värdar, andra scheman än https).
     * http till en tillåten värd öppnas med https i stället.
     * Returnerar true om sidan inte ska laddas.
     */
    private boolean blockNavigation(String url) {
        // http-länk till en tillåten webbplats: öppna https-adressen i stället
        String https = urlPolicy != null ? urlPolicy.httpsUpgrade(url) : null;
        if (https != null) {
            Log.d("publikiosk", "Öppnar med https: " + https);
            isUserNavigation = true;
            myWeb.loadUrl(https);
            return true;
        }
        if (urlPolicy != null && !urlPolicy.allows(url)) {
            Log.w("publikiosk", "Blockerad navigering: " + url);
            chrome.showBlocked(url);
            return true;
        }
        isUserNavigation = true;
        return false;
    }

    /**
     * Rensa allt som en besökare kan ha lämnat efter sig: cookies (inloggningar), lagrad data,
     * cache, historik, formulärdata och sparade HTTP-inloggningar.
     */
    private void clearSession() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(null);
        cookies.flush();
        WebStorage.getInstance().deleteAllData();
        myWeb.clearCache(true);
        myWeb.clearHistory();
        myWeb.clearFormData();
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
        Log.d("publikiosk", "Session rensad");
    }

    private void clearSessionIfEnabled() {
        if (savedClearSession) clearSession();
    }

    private NetworkWatcher network;
    /** Bara satt när appen är device owner (då uppdaterar den sig själv) */
    private AutoUpdate autoUpdate;
    private static final long AUTO_RETRY_MS = 15_000;
    /** Medan felsidan visas: nytt försök med jämna mellanrum (nätet finns men servern svarade inte) */
    private final Runnable autoRetry = () -> {
        if (chrome.isErrorShown()) retryLoad();
    };

    private void scheduleAutoRetry() {
        commandHandler.removeCallbacks(autoRetry);
        commandHandler.postDelayed(autoRetry, AUTO_RETRY_MS);
    }

    private void retryLoad() {
        commandHandler.removeCallbacks(autoRetry);
        chrome.hideError();
        // Misslyckades redan första laddningen kan WebView sakna adress: börja då från startsidan
        String url = myWeb.getUrl();
        if (url == null || url.isEmpty() || url.startsWith("about:")) myWeb.loadUrl(savedUrl);
        else myWeb.reload();
    }

    /** Visa eller dölj kiosknavigeringen för sidan som visas. */
    private void refreshChrome(String url) {
        if (chrome == null || myWeb == null) return;
        chrome.update(url, myWeb.getTitle(), myWeb.canGoBack(), keyboardOpen);
    }

    /**
     * Tillbaka till startsidan: ny besökare, ny session. reason (idle, home, config) är varför
     * besöket tog slut, för statistiken.
     */
    private void returnToStart(String reason) {
        VisitLog.get(this).end(reason);
        chrome.hideAll();
        lastActivityAt = System.currentTimeMillis();
        touchedSinceStart = false;
        awaitingStart = true;
        clearSessionIfEnabled();
        myWeb.loadUrl(savedUrl);
        // clearHistory gäller först när nästa sida laddats
        clearHistorySoon();
    }

    /** Rensar historiken efter att sidan börjat laddas, och visar sedan Tillbaka som avstängd i ramen */
    private void clearHistorySoon() {
        myWeb.postDelayed(() -> {
            myWeb.clearHistory();
            refreshChrome(myWeb.getUrl());
        }, 1000);
        myWeb.postDelayed(() -> refreshChrome(myWeb.getUrl()), 3000);
    }

    /**
     * Ny start i en annan app (från appväljaren): dess startsida, utan historik. Besöket pågår och
     * sessionen rensas inte, det görs vid inaktivitet och med Hem.
     */
    private void openAppUrl(String url) {
        chrome.hideAll();
        lastActivityAt = System.currentTimeMillis();
        myWeb.loadUrl(url);
        // clearHistory gäller först när nästa sida laddats
        clearHistorySoon();
    }

    // --- Kommandon från publicomtools (svaret på statusrapporten) ---

    /** Senaste tryck på skärmen, för att bara starta om eller ladda om när ingen använder enheten */
    private long lastTouchAt = 0;
    private static final long IDLE_MS = 2 * 60_000;
    private static final long IDLE_CHECK_MS = 30_000;
    private final java.util.Set<String> waitingForIdle = new java.util.HashSet<>();
    private final Handler commandHandler = new Handler(Looper.getMainLooper());

    private StatusReporter.State reporterState() {
        return new StatusReporter.State() {
            @Override
            public Boolean pageLoaded() {
                return pageLoaded;
            }

            @Override
            public String menuEvent() {
                return pinStore.lastEvent();
            }

            @Override
            public void onCommands(org.json.JSONObject response) {
                handleCommands(response);
            }
        };
    }

    private void handleCommands(org.json.JSONObject response) {
        if (response.optBoolean("pinUnlock")) {
            pinStore.unlock();
            Log.i("publikiosk", "Menyn upplåst från publicomtools");
        }
        // Skärmdumpen tas när ingen använder enheten: då har appen redan gått tillbaka till
        // startsidan och rensat sessionen, så inget som en besökare gjort kommer med på bilden
        if (response.optBoolean("screenshot")) whenIdle("screenshot", this::takeScreenshot);
        // En omstart hämtar också inställningarna, så den vinner över reload
        if (response.optBoolean("reboot")) {
            whenIdle("reboot", () -> {
                VisitLog.get(this).end("reboot");
                if (!kioskPolicy.reboot()) Log.w("publikiosk", "Omstart stöds inte på enheten (kräver Android 7 och device owner)");
            });
        } else if (response.optBoolean("reload")) {
            whenIdle("reload", () -> fetchConfig(false));
        }
    }

    private final Runnable closeDrawerWhenIdle = new Runnable() {
        @Override
        public void run() {
            if (!drawerLayout.isDrawerOpen(Gravity.LEFT)) return;
            if (System.currentTimeMillis() - lastTouchAt >= IDLE_MS && !isPinDialogOpen) {
                drawerLayout.closeDrawer(Gravity.LEFT);
            } else {
                commandHandler.postDelayed(this, IDLE_CHECK_MS);
            }
        }
    };

    private boolean isIdle() {
        return System.currentTimeMillis() - lastTouchAt >= IDLE_MS
                && !drawerLayout.isDrawerOpen(Gravity.LEFT)
                && !isPinDialogOpen;
    }

    /** Kör när ingen har rört skärmen på 2 minuter (och menyn inte är öppen). Samma kommando bara en gång åt gången. */
    private void whenIdle(String key, Runnable action) {
        if (!waitingForIdle.add(key)) return;
        Runnable check = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (isIdle()) {
                    waitingForIdle.remove(key);
                    action.run();
                } else {
                    commandHandler.postDelayed(this, IDLE_CHECK_MS);
                }
            }
        };
        commandHandler.post(check);
    }

    /**
     * Skärmdump av appens eget fönster (inget annat på enheten), högst 1280 bildpunkter bred,
     * som JPEG till publicomtools. Bara via whenIdle, aldrig medan någon använder enheten.
     */
    private void takeScreenshot() {
        View root = getWindow().getDecorView();
        int width = root.getWidth(), height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // PixelCopy får med WebView, som ritas med hårdvaruacceleration
            android.view.PixelCopy.request(getWindow(), bitmap, result -> {
                if (result == android.view.PixelCopy.SUCCESS) uploadScreenshot(bitmap);
                else Log.w("publikiosk", "Skärmdumpen misslyckades: " + result);
            }, commandHandler);
        } else {
            root.draw(new android.graphics.Canvas(bitmap));
            uploadScreenshot(bitmap);
        }
    }

    private void uploadScreenshot(android.graphics.Bitmap bitmap) {
        background.execute(() -> {
            try {
                android.graphics.Bitmap scaled = bitmap;
                if (bitmap.getWidth() > 1280) {
                    int h = Math.round(bitmap.getHeight() * 1280f / bitmap.getWidth());
                    scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 1280, h, true);
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
                publicomtools.uploadScreenshot(out.toByteArray());
            } catch (Exception e) {
                Log.w("publikiosk", "Skärmdumpen kunde inte skickas: " + e.getMessage());
            }
        });
    }

    /** Menyns del för publicomtools: inskrivning med kod, eller status när enheten är ansluten. */
    private void setupPublicomtools() {
        EditText url = findViewById(R.id.publicomtools_url);
        EditText code = findViewById(R.id.enroll_code);
        url.setText(publicomtools.baseUrl());
        findViewById(R.id.enroll_button).setOnClickListener(v -> {
            String baseUrl = url.getText().toString();
            String enrollCode = code.getText().toString();
            v.setEnabled(false);
            background.execute(() -> {
                try {
                    PublicomClient.Enrollment enrollment = publicomtools.enroll(baseUrl, enrollCode);
                    if (enrollment.recoveryCode != null) pinStore.setRecoveryCode(enrollment.recoveryCode);
                    runOnUiThread(() -> {
                        code.setText("");
                        Toast.makeText(this, "Ansluten som " + enrollment.host, Toast.LENGTH_LONG).show();
                        fetchConfig(true);
                        statusReporter.start();
                        updatePublicomtoolsUi();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> code.setError(e.getMessage()));
                } finally {
                    runOnUiThread(() -> v.setEnabled(true));
                }
            });
        });
        findViewById(R.id.fetch_button).setOnClickListener(v -> fetchConfig(true));
        // T ex för att flytta enheten till en annan publicomtools: inställningarna står kvar och kan ändras i menyn
        findViewById(R.id.disconnect_button).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Koppla från publicomtools?")
                .setMessage("Enheten slutar hämta inställningar och skicka status. Den behåller de senaste inställningarna. "
                        + "För att ansluta igen behövs en ny kod från publicomtools.")
                .setPositiveButton("Koppla från", (d, w) -> {
                    statusReporter.stop();
                    publicomtools.forget();
                    ManagedConfig.release(this);
                    updatePublicomtoolsUi();
                })
                .setNegativeButton("Avbryt", null)
                .show());
        updatePublicomtoolsUi();
    }

    private void updatePublicomtoolsUi() {
        boolean enrolled = publicomtools.isEnrolled();
        TextView status = findViewById(R.id.publicomtools_status);
        status.setText(enrolled
                ? "Ansluten som " + publicomtools.host() + " (" + publicomtools.baseUrl() + ")"
                : "Inte ansluten. Inställningarna görs här i menyn.");
        findViewById(R.id.enroll_form).setVisibility(enrolled ? View.GONE : View.VISIBLE);
        findViewById(R.id.fetch_button).setVisibility(enrolled ? View.VISIBLE : View.GONE);
        findViewById(R.id.disconnect_button).setVisibility(enrolled ? View.VISIBLE : View.GONE);

        // Styrs enheten från publicomtools är inställningarna skrivskyddade här
        boolean managed = enrolled && ManagedConfig.isManaged(this);
        findViewById(R.id.managed_note).setVisibility(managed ? View.VISIBLE : View.GONE);
        for (View field : new View[]{urlInput, allowedHostsInput, clearSessionCheckbox, initialscaleInput, inactivitytimeoutInput,
                inactivitytimeoutwebInput, orientationSpinner, fullscreenCheckbox}) {
            field.setEnabled(!managed);
        }
        findViewById(R.id.save_button).setVisibility(managed ? View.GONE : View.VISIBLE);
    }

    /**
     * Hämta inställningarna från publicomtools och använd dem. Har något som syns ändrats laddas
     * startsidan om, med en ny session. Utan nät gäller den senast hämtade kopian.
     */
    private void fetchConfig(boolean showResult) {
        background.execute(() -> {
            try {
                org.json.JSONObject config = publicomtools.fetchConfig();
                if (config == null) return;
                boolean changed = ManagedConfig.apply(this, config);
                runOnUiThread(() -> {
                    if (changed) {
                        loadSettings();
                        applySettings();
                        returnToStart("config");
                    }
                    updatePublicomtoolsUi();
                    statusReporter.sendNow();
                    if (showResult) Toast.makeText(this, changed ? "Nya inställningar" : "Inställningarna är oförändrade", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.w("publikiosk", "Inställningarna kunde inte hämtas: " + e.getMessage());
                runOnUiThread(() -> {
                    if (!publicomtools.isEnrolled()) ManagedConfig.release(this);
                    updatePublicomtoolsUi();
                    if (showResult) Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }


    // Avsluta kioskläge
    private void quitKiosk() {
        kioskPolicy.release();
        stopLockTask();
        Toast.makeText(MainActivity.this, "Kioskläge avslutat", Toast.LENGTH_SHORT).show();
    }

    // --- Tillbaka till startsidan när ingen använder enheten ---

    private static final long IDLE_CHECK_INTERVAL_MS = 1_000;
    /** Senaste tryck eller start av startsidan: tidsgränserna räknas härifrån */
    private long lastActivityAt = System.currentTimeMillis();
    /** Någon har rört skärmen sedan startsidan laddades (då laddas den om även om den visas) */
    private boolean touchedSinceStart = false;
    /** Dit startadressen faktiskt ledde (efter omdirigeringar), räknas också som startsidan */
    private String landedStartUrl = null;
    private boolean awaitingStart = true;

    /**
     * Startsidan, oavsett snedstreck på slutet, frågeparametrar och # (t ex ?lang=sv), och dit
     * startadressen omdirigerade.
     */
    private boolean isStartPage(String url) {
        return UrlPolicy.samePage(url, savedUrl) || (landedStartUrl != null && UrlPolicy.samePage(url, landedStartUrl));
    }

    /**
     * Var femte sekund: har ingen rört skärmen under tidsgränsen går appen tillbaka till
     * startsidan (med ny session). På en annan sida gäller tidsgränsen för extern webb; på
     * startsidan den vanliga, och då bara om någon har använt den (eller startbilden visas).
     * Oberoende av hur besökaren kom till sidan: länk, omdirigering, formulär eller navigering
     * inne i en webbapp.
     */
    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            try {
                long idle = System.currentTimeMillis() - lastActivityAt;
                boolean onStart = isStartPage(myWeb.getUrl());
                long limit = Long.parseLong(onStart ? savedInactivityTimeout : savedInactivityTimeoutWeb);
                boolean busy = drawerLayout.isDrawerOpen(Gravity.LEFT) || isPinDialogOpen;
                // Något händer när tiden går ut: tillbaka till början
                boolean willAct = !busy && (!onStart || touchedSinceStart);
                long left = limit - idle;
                // "Är du kvar?" de sista sekunderna, så att ingen förlorar det hen höll på med. Inte på
                // startsidan: där återställs den tyst (en SPA som inte byter adress räknas hit)
                if (willAct && !onStart && savedIdleWarning > 0 && left > 0 && left <= savedIdleWarning * 1000L) {
                    chrome.showWarning((int) Math.ceil(left / 1000.0));
                } else if (chrome.isWarningShown()) {
                    chrome.hideWarning();
                }
                if (!busy && idle >= limit) {
                    if (!onStart || touchedSinceStart) returnToStart("idle");
                }
            } catch (NumberFormatException e) {
                Log.w("publikiosk", "Ogiltig tidsgräns för inaktivitet", e);
            }
            inactivityHandler.postDelayed(this, IDLE_CHECK_INTERVAL_MS);
        }
    };

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastTouchAt = System.currentTimeMillis();
        lastActivityAt = lastTouchAt;
        touchedSinceStart = true;
        // Tryck i inställningsmenyn är personal, inget besök
        if (drawerLayout == null || !drawerLayout.isDrawerOpen(Gravity.LEFT)) {
            VisitLog.get(this).activity(ev.getActionMasked() == MotionEvent.ACTION_DOWN);
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Hantera klick för att öppna settings
     */
    private void setupClickListener() {
        triggerArea.setOnClickListener(view -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastClickTime <= TIME_LIMIT) {
                clickCount++;
            } else {
                clickCount = 1;
            }

            lastClickTime = currentTime;

            if (clickCount >= CLICK_THRESHOLD) {
                if (isPinVerified) {
                    drawerLayout.openDrawer(Gravity.LEFT);
                } else if (!isPinDialogOpen) {
                    promptForPin();
                }
                clickCount = 0;
            }
        });
    }

    private void disableSwipeToOpenDrawer() {
        drawerLayout.addDrawerListener(new DrawerLayout.DrawerListener() {
            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                // Gör inget
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                // En meny som glöms öppen ger vem som helst åtkomst till den (t ex Lämna kioskläge):
                // stäng den efter 2 minuter utan tryck
                commandHandler.postDelayed(closeDrawerWhenIdle, IDLE_CHECK_MS);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                isPinVerified = false;
                commandHandler.removeCallbacks(closeDrawerWhenIdle);
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                // Gör inget
            }
        });

        // Set the drawer lock mode to LOCKED_CLOSED to prevent opening by swipe
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySettings();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    /**
     * Ladda settings från lokalt sparade
     */
    private void loadSettings() {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Standard tills enheten styrs från publicomtools (ALLOWED_HOSTS). Underdomäner ingår,
        // så kth.se täcker t ex apps.lib.kth.se och spacefinder.lib.kth.se; startsidans värd
        // (wagnerguide.com) är alltid tillåten.
        savedAllowedHosts = sharedPreferences.getString(PREF_ALLOWED_HOSTS, DEFAULT_ALLOWED_HOSTS);
        savedNavigation = sharedPreferences.getString("navigation", "auto");
        savedAppScope = sharedPreferences.getString("appscope", "");
        savedApps = sharedPreferences.getString("apps", "");
        savedStartLabel = sharedPreferences.getString("startlabel", "");
        savedStartIcon = sharedPreferences.getString("starticon", "house");
        savedIdleWarning = sharedPreferences.getInt("idlewarning", 10);
        savedLanguage = sharedPreferences.getString("language", "sv");
        savedClearSession = sharedPreferences.getBoolean(PREF_CLEAR_SESSION, true);
        savedInitialScale = sharedPreferences.getString(PREF_INITIAL_SCALE, "100");
        savedInactivityTimeout = sharedPreferences.getString(PREFS_INACTIVITY_TIMEOUT, "60000");
        savedInactivityTimeoutWeb = sharedPreferences.getString(PREFS_INACTIVITY_TIMEOUT_WEB, "30000");
        savedOrientation = sharedPreferences.getInt(PREF_ORIENTATION, 1);
        savedFullscreen = sharedPreferences.getBoolean(PREF_FULLSCREEN, true);
        savedUrl = sharedPreferences.getString(PREF_URL, "https://wagnerguide.com/c/kth/kth");

        orientationSpinner.setSelection(savedOrientation);
        fullscreenCheckbox.setChecked(savedFullscreen);
        urlInput.setText(savedUrl);
        allowedHostsInput.setText(savedAllowedHosts);
        clearSessionCheckbox.setChecked(savedClearSession);
        initialscaleInput.setText(savedInitialScale);
        inactivitytimeoutInput.setText(savedInactivityTimeout);
        inactivitytimeoutwebInput.setText(savedInactivityTimeoutWeb);
        urlPolicy = newUrlPolicy();
        configureChrome();

    }

    /** Tillåtna webbplatser, och värdarna som de andra apparna ligger på */
    private UrlPolicy newUrlPolicy() {
        return new UrlPolicy(savedUrl, savedAllowedHosts + KioskApps.hosts(KioskApps.parse(savedApps)));
    }

    private void configureChrome() {
        chrome.configure(savedUrl, savedAppScope, savedNavigation, savedLanguage,
                savedStartLabel, savedStartIcon, savedApps);
    }

    private void saveSettings() {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        editor.putString(PREF_ALLOWED_HOSTS, savedAllowedHosts);
        editor.putBoolean(PREF_CLEAR_SESSION, savedClearSession);
        editor.putString(PREF_INITIAL_SCALE, savedInitialScale);
        editor.putString(PREFS_INACTIVITY_TIMEOUT, savedInactivityTimeout);
        editor.putString(PREFS_INACTIVITY_TIMEOUT_WEB, savedInactivityTimeoutWeb);
        editor.putInt(PREF_ORIENTATION, savedOrientation);
        editor.putBoolean(PREF_FULLSCREEN, savedFullscreen);
        editor.putString(PREF_URL, savedUrl);
        editor.apply();
    }

    /**
     * Felsökning av WebView (chrome://inspect) i debug-byggen, och i release bara när WEB_DEBUG är
     * påslagen för enheten i publicomtools: på en publik enhet ger den åtkomst till sidorna och
     * deras cookies via USB.
     */
    private void applyWebDebug(boolean enabled) {
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable || enabled);
        if (enabled && !debuggable) Log.w("publikiosk", "WebView-felsökning påslagen (WEB_DEBUG)");
    }

    private void applySettings() {
        applyWebDebug(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("webdebug", false));
        urlPolicy = newUrlPolicy();
        configureChrome();
        refreshChrome(myWeb.getUrl());
        setInitialScale(savedInitialScale);
        setOrientation(savedOrientation);
        applyFullscreen(savedFullscreen);
    }

    private void applyFullscreen(boolean isFullscreen) {
        if (isFullscreen) {
            hideSystemUI();
        } else {
            showSystemUI();
        }
    }

    /**
     * Hantera dialog för pin. Efter för många fel spärras menyn en stund (PinStore), men
     * spärren är alltid tillfällig och kiosken fungerar som vanligt under tiden.
     */
    private void promptForPin() {
        // Trycken i hörnet som öppnade menyn var personal, inte en besökare
        VisitLog.get(this).discardIfRecent(15_000);
        long locked = pinStore.lockedForMs();
        if (locked > 0) {
            showLocked(locked);
            return;
        }
        isPinDialogOpen = true;
        final EditText pinInput = pinField("PIN");
        AlertDialog.Builder pinBuilder = new AlertDialog.Builder(this)
                .setTitle("Ange PIN")
                .setView(pinInput)
                .setPositiveButton("OK", null)
                .setNegativeButton("Avbryt", (d, which) -> d.cancel())
                .setOnDismissListener(d -> isPinDialogOpen = false);
        // Glömd PIN: återställningskoden från inskrivningen (står under Teknik i publicomtools)
        if (pinStore.hasRecoveryCode()) pinBuilder.setNeutralButton("Glömt PIN?", (d, which) -> promptForRecoveryCode());
        AlertDialog dialog = pinBuilder.create();
        dialog.show();
        closeWhenAbandoned(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            switch (pinStore.verify(pinInput.getText().toString())) {
                case OK:
                    dialog.dismiss();
                    if (pinStore.mustChange()) {
                        // Äldre PIN (1234 eller för kort): byt innan menyn öppnas
                        promptForNewPin(true);
                    } else {
                        openSettings();
                    }
                    break;
                case LOCKED:
                    dialog.dismiss();
                    showLocked(pinStore.lockedForMs());
                    break;
                default:
                    pinInput.setText("");
                    pinInput.setError("Fel PIN, " + pinStore.attemptsLeft() + " försök kvar");
            }
        });
    }

    /** "Glömt PIN?": rätt återställningskod ger en ny PIN. Fel kod räknas som fel PIN. */
    private void promptForRecoveryCode() {
        isPinDialogOpen = true;
        final EditText codeInput = new EditText(this);
        codeInput.setHint("t ex K7QM-2XPA");
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Återställningskod")
                .setMessage("Koden står under Teknik för enheten i publicomtools.")
                .setView(codeInput)
                .setPositiveButton("OK", null)
                .setNegativeButton("Avbryt", (d, which) -> d.cancel())
                .setOnDismissListener(d -> isPinDialogOpen = false)
                .create();
        dialog.show();
        closeWhenAbandoned(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            switch (pinStore.verifyRecovery(codeInput.getText().toString())) {
                case OK:
                    dialog.dismiss();
                    promptForNewPin(true);
                    break;
                case LOCKED:
                    dialog.dismiss();
                    showLocked(pinStore.lockedForMs());
                    break;
                default:
                    codeInput.setText("");
                    codeInput.setError("Fel kod, " + pinStore.attemptsLeft() + " försök kvar");
            }
        });
    }

    /**
     * En PIN-ruta som lämnats öppen (t ex av en besökare som tryckt i hörnet) stängs efter 2
     * minuter, annars skulle appen aldrig gå tillbaka till startsidan.
     */
    private void closeWhenAbandoned(AlertDialog dialog) {
        long openedAt = System.currentTimeMillis();
        commandHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) return;
                long now = System.currentTimeMillis();
                if (now - openedAt >= IDLE_MS && now - lastTouchAt >= IDLE_MS) dialog.dismiss();
                else commandHandler.postDelayed(this, IDLE_CHECK_MS);
            }
        }, IDLE_CHECK_MS);
    }

    private void showLocked(long ms) {
        long minutes = Math.max(1, (ms + 59_999) / 60_000);
        new AlertDialog.Builder(this)
                .setTitle("Menyn är spärrad")
                .setMessage("För många fel PIN. Försök igen om " + minutes + (minutes == 1 ? " minut." : " minuter."))
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Välj en ny PIN (minst PinStore.MIN_LENGTH siffror, två gånger). Med required går dialogen
     * inte att avbryta: ny installation, eller en äldre PIN som måste bytas.
     */
    private void promptForNewPin(boolean required) {
        isPinDialogOpen = true;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, 0, pad, 0);
        final EditText first = pinField("Ny PIN, minst " + PinStore.MIN_LENGTH + " siffror");
        final EditText second = pinField("Samma PIN igen");
        layout.addView(first);
        layout.addView(second);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(required ? "Välj PIN för inställningarna" : "Byt PIN")
                .setView(layout)
                .setPositiveButton("Spara", null)
                .setCancelable(!required)
                .setOnDismissListener(d -> isPinDialogOpen = false);
        if (!required) builder.setNegativeButton("Avbryt", (d, which) -> d.cancel());
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!required);
        dialog.show();
        // Måste en PIN väljas (ny installation) står rutan kvar; annars stängs den som de andra
        if (!required) closeWhenAbandoned(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = first.getText().toString();
            if (!PinStore.isValidNewPin(pin)) {
                first.setError("Minst " + PinStore.MIN_LENGTH + " siffror, inte samma siffra överallt");
                return;
            }
            if (!pin.equals(second.getText().toString())) {
                second.setError("PIN:arna är inte lika");
                return;
            }
            pinStore.set(pin);
            dialog.dismiss();
            Toast.makeText(this, "PIN sparad", Toast.LENGTH_SHORT).show();
            if (required && pinStore.isSet()) openSettings();
        });
    }

    private EditText pinField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return field;
    }

    private void openSettings() {
        isPinVerified = true;
        drawerLayout.openDrawer(Gravity.LEFT);
    }

    private void setInitialScale(String scale) {
        myWeb.setInitialScale(Integer.parseInt(scale));
    }

    private void setOrientation(int position) {
        switch (position) {
            case 0:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case 1:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                break;
        }
    }

    /**
     * Gör systemknappar etc
     */
    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void showSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /**
     * Hantera loggning från javascript på initiala sidan.
     */
    public static class WebAppInterface {
        Context context;

        private static final String LOG_FILE_NAME = "webview_logs.txt";

        WebAppInterface(Context context) {
            this.context = context;
        }

        // Loggen får inte växa obegränsat: en sida kan anropa bryggan hur ofta som helst
        private static final long MAX_LOG_BYTES = 1024 * 1024;

        @JavascriptInterface
        // Anropas av tillagda javascript på laddade websidor i webview
        public void logActivity(String data) {
            // Bara startsidans värd får använda bryggan
            if (!bridgeAllowed || data == null || data.length() > 4096) return;
            try {
                JSONObject json = new JSONObject(data);
                String tag = json.getString("tag");
                String id = json.optString("id", "no-id");
                String className = json.optString("class", "no-class");
                String text = json.optString("text", "no-text");
                saveLogToFile("ElementDetails: " + "Tag: " + tag + ", ID: " + id + ", Class: " + className + ", Text: " + text);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // Metod för att spara loggen till en fil
        private void saveLogToFile(String log) {
            // Appens interna katalog: den externa går att läsa för andra appar och via USB
            File directory = context.getFilesDir();

            // Kontrollera om katalogen finns, skapa den om inte
            if (directory != null && !directory.exists()) {
                directory.mkdirs(); // Skapar katalogen om den inte existerar
            }

            // Skapa filen i katalogen
            File logFile = new File(directory, LOG_FILE_NAME);
            if (logFile.length() > MAX_LOG_BYTES) {
                logFile.delete();
            }

            try {
                // Använd FileWriter för att öppna filen i append-läge
                FileWriter writer = new FileWriter(logFile, true); // true för att lägga till i slutet av filen
                writer.append(log).append("\n");
                writer.flush();
                writer.close();
            } catch (IOException e) {
                Log.e("WebAppInterface", "Failed to save log", e);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreenCheckbox.isChecked() && !keyboardOpen) {
            hideSystemUI();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statusReporter.stop();
        if (network != null) network.stop();
        commandHandler.removeCallbacksAndMessages(null);
        background.shutdown();
        // Timrarna får inte köra mot en aktivitet som är borta (t ex efter recreate)
        if (inactivityHandler != null) inactivityHandler.removeCallbacksAndMessages(null);
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREF_FULLSCREEN, fullscreenCheckbox.isChecked());
        editor.apply();
    }
}