package se.kth.lib.publikiosk;

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
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("data:") || host.allows(url)) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) fail();
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) fail();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
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

    boolean hasFailed() {
        return failed;
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
    }

    void pause() {
        view.onPause();
    }

    void resume() {
        view.onResume();
    }
}
