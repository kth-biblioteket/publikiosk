package se.kth.lib.publikiosk;

import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;

/**
 * En webbsida som bara visas, i en egen WebView: fotradens webbsida och attract loopen. Den kan inte
 * navigera (alla länkar och omdirigeringar nekas), har ingen brygga mot appen, inga filer eller blandat
 * innehåll, och hämtar bara delresurser från tillåtna webbplatser. Skript och tryck är av om inte
 * inställningarna tillåter dem. Sidan har en fast bredd i skisspixlar (1280 liggande, 800 stående) som
 * skalas efter skärmen.
 */
final class SandboxedPage {

    interface Host {
        boolean allows(String url);

        /** Sidan gick inte att ladda (nätfel eller svar 400 eller högre) */
        void failed();

        /** Sidan har laddats utan fel efter ett misslyckat försök */
        void recovered();
    }

    private final WebView view;
    private final Host host;
    private boolean failed = false;
    /** Det pågående försöket har gått fel (Chromiums felsida räknas inte som en lyckad sida) */
    private boolean attemptFailed = false;
    private String loaded = "";
    /** Skylt: omdirigeringar till tillåtna webbplatser följs (länkar och skript som navigerar nekas fortfarande) */
    private boolean followRedirects = false;
    /** Skylt: delresurser (inte själva sidan) får hämtas från vilken https-adress som helst */
    private boolean openResources = false;
    /** Sidan har laddats utan fel (sedan senaste load) och när, så att skylten vet när den är klar att visas */
    private boolean ok = false;
    /** Det senaste felet beror på nätverket (DNS, anslutning, tidsgräns) och inte på sidan */
    private boolean netError = false;
    private long okAt = 0;

    SandboxedPage(WebView view, Host host) {
        this.view = view;
        this.host = host;
        WebSettings ws = view.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setDomStorageEnabled(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setSupportMultipleWindows(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        // Utan ljud startar film av sig själv; med ljud krävs ett tryck, som i resten av appen
        ws.setMediaPlaybackRequiresUserGesture(true);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                // Nekas, utom en omdirigering till en tillåten webbplats när det är inställt
                if (followRedirects && request.isRedirect() && host.allows(request.getUrl().toString())) return false;
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("data:") || host.allows(url)) return null;
                if (openResources && !request.isForMainFrame() && url.startsWith("https://")) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    int code = error.getErrorCode();
                    netError = isNetworkError(code);
                    Log.w("SandboxedPage", "Sidan gick inte att ladda: " + error.getDescription() + " (kod " + code + ", "
                            + (netError ? "nätverksfel" : "sidfel") + ") " + request.getUrl());
                    fail();
                }
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                    netError = false;
                    Log.w("SandboxedPage", "Sidan svarade " + errorResponse.getStatusCode() + " " + request.getUrl());
                    fail();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!attemptFailed && url != null && !url.startsWith("about:")) {
                    ok = true;
                    okAt = System.currentTimeMillis();
                }
                if (failed && !attemptFailed && url != null && !url.startsWith("about:")) {
                    failed = false;
                    host.recovered();
                }
            }
        });
    }

    private void fail() {
        failed = true;
        attemptFailed = true;
        host.failed();
    }

    /** Nätverksfel som räknas som nätverk även om länken är uppe: DNS, anslutning, tidsgräns, in/ut, TLS-handskakning */
    private static boolean isNetworkError(int code) {
        return code == WebViewClient.ERROR_HOST_LOOKUP || code == WebViewClient.ERROR_CONNECT || code == WebViewClient.ERROR_TIMEOUT
                || code == WebViewClient.ERROR_IO || code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE;
    }

    /** Det senaste felet beror på nätverket (inte på själva sidan) */
    boolean netError() {
        return failed && netError;
    }

    boolean hasFailed() {
        return failed;
    }

    /** Laddad utan fel sedan senaste load */
    boolean isOk() {
        return ok;
    }

    long okAt() {
        return okAt;
    }

    void openResources(boolean open) {
        openResources = open;
    }

    void followRedirects(boolean follow) {
        followRedirects = follow;
    }

    /** All inmatning ignoreras: tryck, mushjul, tangenter, ingen zoom och ingen fokus */
    void lockInput() {
        view.setOnTouchListener((v, e) -> true);
        view.setOnGenericMotionListener((v, e) -> true);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.getSettings().setSupportZoom(false);
        view.getSettings().setBuiltInZoomControls(false);
        view.getSettings().setDisplayZoomControls(false);
    }

    /** scalePercent: 100 × skärmbredden / designbredden, så att sidan fyller bredden */
    void configure(boolean scripts, boolean touch, int scalePercent) {
        view.getSettings().setJavaScriptEnabled(scripts);
        view.getSettings().setDomStorageEnabled(scripts);
        view.setOnTouchListener(touch ? null : (v, e) -> true);
        view.setInitialScale(Math.max(1, scalePercent));
    }

    /** Laddar adressen (om den är tillåten), eller laddar om den om den redan visas */
    void load(String url) {
        if (url == null || url.isEmpty() || !host.allows(url)) return;
        attemptFailed = false;
        ok = false;
        netError = false;
        if (loaded.equals(url)) view.reload();
        else {
            loaded = url;
            view.loadUrl(url);
        }
    }

    void clear() {
        if (!loaded.isEmpty()) {
            view.loadUrl("about:blank");
            loaded = "";
        }
        failed = false;
        attemptFailed = false;
        ok = false;
        netError = false;
    }

    void pause() {
        view.onPause();
    }

    void resume() {
        view.onResume();
    }
}
