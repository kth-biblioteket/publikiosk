package se.kth.lib.publikiosk;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vilka sidor WebView får navigera till. Bara https, och bara startsidans värd plus de
 * värdar som står i "Tillåtna webbplatser" (underdomäner ingår: kth.se tillåter www.kth.se).
 * Allt annat (andra värdar, intent:, file:, content:, javascript:, data:, tel: …) blockeras.
 */
public class UrlPolicy {

    private final List<String> hosts = new ArrayList<>();

    public UrlPolicy(String startUrl, String allowedHosts) {
        String start = host(startUrl);
        if (start != null) hosts.add(start);
        if (allowedHosts != null) {
            for (String h : allowedHosts.split("[,\\s]+")) {
                String n = normalize(h);
                if (n != null && !hosts.contains(n)) hosts.add(n);
            }
        }
    }

    public boolean allows(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String allowed : hosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    /** Startsidans värd (med underdomäner), t ex för JS-bryggan. */
    public static boolean sameSite(String url, String startUrl) {
        return new UrlPolicy(startUrl, null).allows(url);
    }

    static String host(String url) {
        if (url == null) return null;
        String h = Uri.parse(url.trim()).getHost();
        return h == null || h.isEmpty() ? null : h.toLowerCase(Locale.ROOT);
    }

    /** "https://www.kth.se/x" och "www.kth.se" blir båda "www.kth.se"; "*.kth.se" blir "kth.se". */
    static String normalize(String entry) {
        if (entry == null) return null;
        String e = entry.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty()) return null;
        if (e.contains("://")) e = host(e);
        if (e == null) return null;
        if (e.startsWith("*.")) e = e.substring(2);
        int slash = e.indexOf('/');
        if (slash >= 0) e = e.substring(0, slash);
        int colon = e.indexOf(':');
        if (colon >= 0) e = e.substring(0, colon);
        return e.matches("[a-z0-9.-]+\\.[a-z]{2,}") ? e : null;
    }
}
