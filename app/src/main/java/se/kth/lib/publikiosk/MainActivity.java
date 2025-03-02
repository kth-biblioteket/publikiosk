package se.kth.lib.publikiosk;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
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
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
    private EditText pincodeInput;
    private EditText initialscaleInput;
    private EditText inactivitytimeoutInput;
    private EditText inactivitytimeoutwebInput;
    private Spinner orientationSpinner;
    private CheckBox fullscreenCheckbox;
    private CheckBox splashscreenCheckbox;
    private CheckBox splashscreenvideoCheckbox;

    private Handler inactivityHandler;
    private Runnable inactivityRunnable;

    private Handler inactivitywebHandler;
    private Runnable inactivitywebRunnable;

    // Variabler för att spara settings i shared preferences
    private static final String PREFS_INACTIVITY_TIMEOUT = "inactivitytimeout";
    private static final String PREFS_INACTIVITY_TIMEOUT_WEB = "inactivitytimeoutweb";
    private static final String PREFS_NAME = "MyPrefs";
    private static final String PREF_PIN = "pin";
    private static final String PREF_INITIAL_SCALE = "initialscale";
    private static final String PREF_ORIENTATION = "orientation";
    private static final String PREF_FULLSCREEN = "fullscreen";
    private static final String PREF_SPLASHSCREEN = "splashscreen";
    private static final String PREF_SPLASHSCREENVIDEO = "splashscreenvideo";
    private static final String PREF_URL = "url";

    private String savedPincode;
    private int savedOrientation;
    private String savedInitialScale;
    private boolean savedFullscreen;
    private boolean savedSplashscreen;
    private boolean savedSplashscreenvideo;
    private String savedUrl;
    private String savedInactivityTimeout;
    private String savedInactivityTimeoutWeb;

    private boolean isPinDialogOpen = false;
    private boolean isPinVerified = false;

    private static final int CLICK_THRESHOLD = 5;
    private static final long TIME_LIMIT = 1000;
    private int clickCount = 0;
    private long lastClickTime = 0;

    private boolean screentouched = false;

    private GestureDetector gestureDetector;

    boolean isUserNavigation = false;
    boolean isInitialLoading = true;
    String lastUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Möjliggör debug i chrome
        WebView.setWebContentsDebuggingEnabled(true);

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName componentName = new ComponentName(this, MyDeviceAdminReceiver.class);

        // Är appen "device owner?"
        if (devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
            // Låt appen sätta locktask utan att en användardialg visas
            devicePolicyManager.setLockTaskPackages(componentName, new String[]{getPackageName()});

            startLockTask();
            Toast.makeText(this, "Kiosk startad", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Appen är inte device owner", Toast.LENGTH_SHORT).show();
        }

        setContentView(R.layout.activity_main);

        myWeb = findViewById(R.id.myWeb);
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
        splashscreenCheckbox = findViewById(R.id.splashscreen_checkbox);
        splashscreenvideoCheckbox = findViewById(R.id.splashscreenvideo_checkbox);
        pincodeInput = findViewById(R.id.pincode_input);
        Button saveButton = findViewById(R.id.save_button);

        myMain.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                Rect r = new Rect();
                myMain.getWindowVisibleDisplayFrame(r);
                int screenHeight = myMain.getRootView().getHeight();
                int keypadHeight = screenHeight - r.bottom;

                if (keypadHeight > screenHeight * 0.15) {
                    ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) myWeb.getLayoutParams();
                    params.bottomMargin = keypadHeight;
                    myWeb.setLayoutParams(params);
                } else {
                    ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) myWeb.getLayoutParams();
                    params.bottomMargin = 0;
                    myWeb.setLayoutParams(params);
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
        myWeb.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.36");
        myWeb.addJavascriptInterface(new WebAppInterface(this), "Android");

        //Se till att alerts från websidor visas(exvis vid delete av bokning)
        myWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return super.onJsAlert(view, url, message, result);
            }
        });
        myWeb.setWebViewClient(new WebViewClient() {
            //Kontrollera om användaren klickat på en navigation(länk/knapp)
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                isUserNavigation = true;
                return false;
            }

            // Skapa javascript på laddad websida(lägger till en knapp med länk tillbaks till huvudsida)
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                //Hantera att sidor använder redirects
                if (isInitialLoading) {
                    if (url.equals(savedUrl)) {
                        isInitialLoading = false;
                    } else {
                        return;
                    }
                }

                // Om det inte är den initials sidan så visa en navigationsknapp
                if (isUserNavigation && !url.startsWith(savedUrl)) {
                    new Handler().postDelayed(() -> {
                            String js = "javascript:(function() {" +
                                "function getParameterByName(name, url) {" +
                                "    if (!url) url = window.location.href;" +
                                "    name = name.replace(/[\\[\\]]/g, '\\\\$&');" +
                                "    var regex = new RegExp('[?&]' + name + '(=([^&#]*)|&|#|$)')," +
                                "        results = regex.exec(url);" +
                                "    if (!results) return null;" +
                                "    if (!results[2]) return '';" +
                                "    return decodeURIComponent(results[2].replace(/\\+/g, ' '));" +
                                "}" +

                                "var lang = getParameterByName('lang');" +

                                "var homeText = 'Library Map';" +
                                "if (lang === 'sv') {" +
                                "    homeText = 'Karta över biblioteket';" +
                                "}" +

                                "var link = document.createElement('link');" +
                                "link.rel = 'stylesheet';" +
                                "link.href = 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0-beta3/css/all.min.css';" +
                                "document.head.appendChild(link);" +
                                "var nav = document.createElement('div');" +
                                "nav.style.position = 'fixed';" +
                                "nav.style.top = '0';" +
                                "nav.style.left = '0';" +
                                "nav.style.backgroundColor = 'transparent';" +
                                "nav.style.padding = '10px';" +
                                "nav.style.zIndex = '1000';" +
                                "nav.style.display = 'flex';" +
                                "nav.style.justifyContent = 'space-between';" +
                                "nav.style.alignItems = 'center';" +
                                "var homeButton = document.createElement('button');" +
                                "homeButton.className = 'btn btn-sprimary';" +
                                "homeButton.style.position = 'relative';" +
                                "homeButton.style.color = '#ffffff';" +
                                "homeButton.style.width = '150px';" +
                                "homeButton.style.height = '100px';" +
                                "homeButton.style.backgroundColor = '#d02f80';" +
                                "homeButton.onclick = function() {" +
                                        "window.location.href = '" + savedUrl + "';" +
                                "};" +
                                "homeButton.innerHTML = '<i class=\"fas fa-location-dot\" style=\"color:#ffffff26;font-size: 70px; position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);\"></i><span style=\"font-size: 20px;font-weight: 700\">' + homeText +'</span>';"  +
                                "nav.appendChild(homeButton);" +
                                "document.body.appendChild(nav);" +
                            "})()";
                            view.evaluateJavascript(js, null);
                    }, 100);
                    isUserNavigation = false;
                    isInitialLoading = true;
                    // Starta timer för inaktivitet för extern websida
                    startWebInactivityDetection();
                }
                if (url.equals(savedUrl)) {
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
                    // Starta timer för inaktivitet för huvudsidan
                    startInactivityDetection();
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

        // Ladda url i webview
        myWeb.loadUrl(savedUrl);
        isUserNavigation = false;
        isInitialLoading = true;

        disableSwipeToOpenDrawer();
        setupClickListener();

        //Hantera saveknapp i settings
        saveButton.setOnClickListener(v -> {
            saveSettings();
            applySettings();
            drawerLayout.closeDrawer(Gravity.LEFT);
            myWeb.loadUrl(savedUrl);
        });

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

        splashscreenCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            savedSplashscreen = isChecked;
        });

        splashscreenvideoCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            savedSplashscreenvideo = isChecked;
        });

        urlInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                savedUrl = urlInput.getText().toString().trim();
            }
        });

        pincodeInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                savedPincode = pincodeInput.getText().toString().trim();
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

        // Initialisera inaktivitethanterare för huvudsidan
        inactivityHandler = new Handler(Looper.getMainLooper());

        // Initialisera inaktivitethanterare för externa URL:er
        inactivitywebHandler = new Handler(Looper.getMainLooper());

    }

    // Avsluta kioskläge
    private void quitKiosk() {
        stopLockTask();
        Toast.makeText(MainActivity.this, "Kioskläge avslutat", Toast.LENGTH_SHORT).show();
    }

    //Timer för inaktivitet som startar när en huvudsidan  laddats i webview
    private void startInactivityDetection() {
        if (inactivityRunnable == null) {  // Kontrollera om en timer redan är igång
            // När tiden går ut så stoppas timern för main //
            // om splash är enablqat så avslutas main och splash startas
            // Om splash inte är enablat så laddas huvudsidan om
            inactivityRunnable = () -> {
                // Hämta preferenser
                SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                boolean isSplashEnabled = sharedPreferences.getBoolean(PREF_SPLASHSCREEN, false);

                if (isSplashEnabled) {
                    // Stoppa timer för main
                    inactivityHandler.removeCallbacks(inactivityRunnable);
                    inactivityRunnable = null;
                    Intent intent = new Intent(this, SplashActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish(); // Stänger den nuvarande aktiviteten
                } else {
                    resetInactivityDetection();
                    if (screentouched) {
                        myWeb.loadUrl(savedUrl);  // Ladda om huvudsidan
                    }
                    // Reset att användaren inte har rört skärmen
                    screentouched = false;
                }
            };
            // Stoppa timer för web om den är igång
            if (inactivitywebRunnable != null) {
                inactivitywebHandler.removeCallbacks(inactivitywebRunnable);
                inactivitywebRunnable = null;
            }
            // Starta timern
            inactivityHandler.postDelayed(inactivityRunnable, Long.parseLong(savedInactivityTimeout));
        }
    }

    private void resetInactivityDetection() {
        if (inactivityRunnable != null) {
            inactivityHandler.removeCallbacks(inactivityRunnable);
            inactivityHandler.postDelayed(inactivityRunnable, Long.parseLong(savedInactivityTimeout));
        }
    }

    //Timer för inaktivitet som startar när en extern sida laddats i webview
    private void startWebInactivityDetection() {
        if (inactivitywebRunnable == null) {  // Kontrollera om en timer redan är igång
            // När tiden går ut så stoppas timern för web och statar om timern för main.
            inactivitywebRunnable = () -> {
                // Reset att användaren inte har rört skärmen
                screentouched = false;
                // Stoppa timer för web
                inactivitywebHandler.removeCallbacks(inactivitywebRunnable);
                inactivitywebRunnable = null;
                //Reset timer för main
                resetInactivityDetection();
                if (!Objects.equals(myWeb.getUrl(), savedUrl)) {
                    myWeb.loadUrl(savedUrl);  // Ladda om huvudsidan
                }
            };
            inactivityHandler.removeCallbacks(inactivityRunnable);
            inactivityRunnable = null;
            // Starta timern för externa URL:er
            inactivitywebHandler.postDelayed(inactivitywebRunnable, Long.parseLong(savedInactivityTimeoutWeb));
        }
    }

    private void resetWebInactivityDetection() {
        if (inactivitywebRunnable != null) {
            inactivitywebHandler.removeCallbacks(inactivitywebRunnable);
            inactivitywebHandler.postDelayed(inactivitywebRunnable, Long.parseLong(savedInactivityTimeoutWeb));
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        screentouched = true;
        // Starta om timers
        resetInactivityDetection();
        resetWebInactivityDetection();
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
                } else {
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
                // Gör inget
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                isPinVerified = false;
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
        AutoUpdate updateManager = new AutoUpdate(this);
        updateManager.checkForUpdate();
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

        savedPincode = sharedPreferences.getString(PREF_PIN, "1234");
        savedInitialScale = sharedPreferences.getString(PREF_INITIAL_SCALE, "100");
        savedInactivityTimeout = sharedPreferences.getString(PREFS_INACTIVITY_TIMEOUT, "60000");
        savedInactivityTimeoutWeb = sharedPreferences.getString(PREFS_INACTIVITY_TIMEOUT_WEB, "30000");
        savedOrientation = sharedPreferences.getInt(PREF_ORIENTATION, 1);
        savedFullscreen = sharedPreferences.getBoolean(PREF_FULLSCREEN, true);
        savedSplashscreen = sharedPreferences.getBoolean(PREF_SPLASHSCREEN, false);
        savedSplashscreenvideo = sharedPreferences.getBoolean(PREF_SPLASHSCREENVIDEO, false);
        savedUrl = sharedPreferences.getString(PREF_URL, "https://wagnerguide.com/c/kth/kth");

        orientationSpinner.setSelection(savedOrientation);
        fullscreenCheckbox.setChecked(savedFullscreen);
        splashscreenCheckbox.setChecked(savedSplashscreen);
        splashscreenvideoCheckbox.setChecked(savedSplashscreenvideo);
        urlInput.setText(savedUrl);
        pincodeInput.setText(savedPincode);
        initialscaleInput.setText(savedInitialScale);
        inactivitytimeoutInput.setText(savedInactivityTimeout);

    }

    private void saveSettings() {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        editor.putString(PREF_PIN, savedPincode);
        editor.putString(PREF_INITIAL_SCALE, savedInitialScale);
        editor.putString(PREFS_INACTIVITY_TIMEOUT, savedInactivityTimeout);
        editor.putString(PREFS_INACTIVITY_TIMEOUT_WEB, savedInactivityTimeoutWeb);
        editor.putInt(PREF_ORIENTATION, savedOrientation);
        editor.putBoolean(PREF_FULLSCREEN, savedFullscreen);
        editor.putBoolean(PREF_SPLASHSCREEN, savedSplashscreen);
        editor.putBoolean(PREF_SPLASHSCREENVIDEO, savedSplashscreenvideo);
        editor.putString(PREF_URL, savedUrl);
        editor.apply();
    }

    private void applySettings() {
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
     * Hantera dialog för pin
     */
    private void promptForPin() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter PIN");

        final EditText pinInput = new EditText(this);
        pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        builder.setView(pinInput);

        builder.setPositiveButton("Verify", null);
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            isPinDialogOpen = false;
            dialog.cancel();
        });

        builder.setOnDismissListener(dialog -> {
            isPinDialogOpen = false;
        });

        AlertDialog dialog = builder.create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String enteredPin = pinInput.getText().toString();
            if (verifyPin(enteredPin)) {
                dialog.dismiss();
            }
        });
    }

    private boolean verifyPin(String enteredPin) {
        if (enteredPin.equals(savedPincode)) {
            Toast.makeText(this, "PIN Verified!", Toast.LENGTH_SHORT).show();
            drawerLayout.openDrawer(Gravity.LEFT);
            isPinVerified = true;
            return true;
        } else {
            Toast.makeText(this, "Incorrect PIN!", Toast.LENGTH_SHORT).show();
            return false;
        }
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

        @JavascriptInterface
        // Anropas av tillagda javascript på laddade websidor i webview
        public void logActivity(String data) {
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
            // Hämta katalogen där loggen ska sparas
            File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);

            // Kontrollera om katalogen finns, skapa den om inte
            if (directory != null && !directory.exists()) {
                directory.mkdirs(); // Skapar katalogen om den inte existerar
            }

            // Skapa filen i katalogen
            File logFile = new File(directory, LOG_FILE_NAME);

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
        if (hasFocus && fullscreenCheckbox.isChecked()) {
            hideSystemUI();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREF_FULLSCREEN, fullscreenCheckbox.isChecked());
        editor.apply();
    }
}