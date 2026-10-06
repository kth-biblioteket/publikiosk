package se.kth.lib.publikiosk;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Följer enhetens nätanslutning och säger till när den kommer (t ex när wifi blir klart efter en
 * omstart), så att en sida som inte kunde laddas laddas om utan att någon behöver röra skärmen.
 */
public class NetworkWatcher {

    private static final String TAG = "NetworkWatcher";

    private final ConnectivityManager connectivity;
    private final Runnable onOnline;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;

    public NetworkWatcher(Context context, Runnable onOnline) {
        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.onOnline = onOnline;
    }

    /** Finns det en anslutning som kan nå internet (wifi eller kabel uppe)? */
    public boolean isOnline() {
        if (connectivity == null) return true;
        Network network = connectivity.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public void start() {
        if (connectivity == null || callback != null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                main.post(onOnline);
            }

            // Wifi kan vara "uppe" en stund innan det fungerar: säg till igen när det är verifierat
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) main.post(onOnline);
            }
        };
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback);
        } catch (RuntimeException e) {
            Log.w(TAG, "Kunde inte följa nätanslutningen", e);
            callback = null;
        }
    }

    public void stop() {
        if (connectivity == null || callback == null) return;
        try {
            connectivity.unregisterNetworkCallback(callback);
        } catch (RuntimeException e) {
            Log.w(TAG, "Kunde inte sluta följa nätanslutningen", e);
        }
        callback = null;
    }
}
