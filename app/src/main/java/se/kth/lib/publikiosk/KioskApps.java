package se.kth.lib.publikiosk;

import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Fler webbappar på en enhet. START_URL är hem-appen; APPS listar de övriga, en per post:
 * "Namn|https://adress/|ikon|område|beskrivning". Allt utom namn och adress är valfritt. Området är
 * som APP_SCOPE (värd och sökväg); tomt betyder adressens värd och sökväg som katalog. Beskrivningen
 * visas på förstasidan (HOME_MODE=launcher). I det läget är APPS alla tjänster och START_URL används inte.
 */
final class KioskApps {

    private static final String TAG = "KioskApps";
    /** Högst sex appar: i ramen hem-appen och fem till, på förstasidan sex tjänster */
    static final int MAX_APPS = 6;

    static final class App {
        final String label;
        final String url;
        final String icon;
        final KioskChrome.Scope scope;
        final String desc;

        App(String label, String url, String icon, KioskChrome.Scope scope, String desc) {
            this.label = label;
            this.url = url;
            this.icon = icon;
            this.scope = scope;
            this.desc = desc;
        }
    }

    private KioskApps() {
    }

    /** Poster åtskilda med radbrytning (eller komma, utan radbrytning). Ogiltiga poster hoppas över. */
    static List<App> parse(String raw) {
        List<App> apps = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return apps;
        // En post per rad (som publicomtools sparar den). Utan radbrytning: komma, som äldre värden
        // (och då kan en beskrivning inte innehålla komma)
        String[] entries = raw.contains("\n") ? raw.split("\\r?\\n") : raw.split(",");
        for (String entry : entries) {
            if (entry.trim().isEmpty()) continue;
            String[] parts = entry.split("\\|", -1);
            String label = parts[0].trim();
            String url = parts.length > 1 ? parts[1].trim() : "";
            String icon = parts.length > 2 ? parts[2].trim() : "";
            String scopeText = parts.length > 3 ? parts[3].trim() : "";
            String desc = parts.length > 4 ? parts[4].trim() : "";
            if (label.isEmpty() || !url.startsWith("https://") || Uri.parse(url).getHost() == null) {
                Log.w(TAG, "Ogiltig post i APPS hoppas över: " + entry.trim());
                continue;
            }
            KioskChrome.Scope scope = KioskChrome.Scope.parse(scopeText);
            if (scope == null) scope = KioskChrome.Scope.fromStartUrl(url);
            if (apps.size() >= MAX_APPS) {
                Log.w(TAG, "För många appar i APPS, " + label + " och resten hoppas över");
                break;
            }
            apps.add(new App(label, url, icon, scope, desc));
        }
        return apps;
    }

    /** Värdarna som apparna ligger på, för listan över tillåtna webbplatser */
    static String hosts(List<App> apps) {
        StringBuilder sb = new StringBuilder();
        for (App app : apps) {
            String host = Uri.parse(app.url).getHost();
            if (host != null) sb.append(',').append(host);
        }
        return sb.toString();
    }

    /** Ikonen för en tjänst på förstasidan: den som står i inställningen, annars en informationsikon */
    static int tileIcon(String name) {
        int res = icon(name);
        return res != 0 ? res : R.drawable.ic_lucide_info;
    }

    /** Ikonen med det namn som står i inställningen (Lucide), eller 0 om den saknas */
    static int icon(String name) {
        if (name == null) return 0;
        switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "house": return R.drawable.ic_lucide_house;
            case "search": return R.drawable.ic_lucide_search;
            case "map": return R.drawable.ic_lucide_map;
            case "map-pin": return R.drawable.ic_lucide_map_pin;
            case "calendar": return R.drawable.ic_lucide_calendar;
            case "book-open": return R.drawable.ic_lucide_book_open;
            case "library": return R.drawable.ic_lucide_library;
            case "info": return R.drawable.ic_lucide_info;
            case "circle-help": return R.drawable.ic_lucide_circle_help;
            case "printer": return R.drawable.ic_lucide_printer;
            case "monitor": return R.drawable.ic_lucide_monitor;
            case "user": return R.drawable.ic_lucide_user;
            case "clock": return R.drawable.ic_lucide_clock;
            case "graduation-cap": return R.drawable.ic_lucide_graduation_cap;
            default: return 0;
        }
    }
}
