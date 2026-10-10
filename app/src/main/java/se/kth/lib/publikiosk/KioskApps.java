package se.kth.lib.publikiosk;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Fler webbappar på en enhet. START_URL är hem-appen; APPS listar de övriga, en per post:
 * "Namn|https://adress/|ikon|område|beskrivning|namn_en|beskrivning_en", eller en JSON-lista (se parse)
 * när texterna innehåller | , " eller radbrytning. Allt utom namn och adress är
 * valfritt. Området är som APP_SCOPE (värd och sökväg); tomt betyder adressens värd och sökväg som
 * katalog. Beskrivningen visas på förstasidan (HOME_MODE=launcher), och namn_en/beskrivning_en när
 * besökaren valt engelska (tomma: de svenska). I förstasidesläget är APPS alla tjänster och
 * START_URL används inte.
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
        final String labelEn;
        final String descEn;

        App(String label, String url, String icon, KioskChrome.Scope scope, String desc, String labelEn, String descEn) {
            this.label = label;
            this.url = url;
            this.icon = icon;
            this.scope = scope;
            this.desc = desc;
            this.labelEn = labelEn;
            this.descEn = descEn;
        }

        /** Namnet på besökarens språk; saknas det engelska används det svenska */
        String label(boolean english) {
            return english && !labelEn.isEmpty() ? labelEn : label;
        }

        String desc(boolean english) {
            return english && !descEn.isEmpty() ? descEn : desc;
        }
    }

    private KioskApps() {
    }

    /**
     * Två former. En JSON-lista, när någon text innehåller | , " eller radbrytning:
     * [{"name":"Sök","url":"https://…","icon":"search","scope":"","desc":"…","nameEn":"…","descEn":"…"}].
     * Annars en post per rad (eller komma, utan radbrytning), som äldre värden. Ogiltiga poster hoppas
     * över; går JSON-listan inte att läsa används den radbaserade tolkningen.
     */
    static List<App> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        if (raw.trim().startsWith("[")) {
            List<App> json = parseJson(raw.trim());
            if (json != null) return json;
        }
        return parseLines(raw);
    }

    private static List<App> parseJson(String raw) {
        try {
            JSONArray array = new JSONArray(raw);
            List<App> apps = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    Log.w(TAG, "Post " + i + " i APPS är inte ett objekt, hoppas över");
                    continue;
                }
                if (!add(apps, o.optString("name", "").trim(), o.optString("url", "").trim(),
                        o.optString("icon", "").trim(), o.optString("scope", "").trim(),
                        o.optString("desc", "").trim(), o.optString("nameEn", "").trim(),
                        o.optString("descEn", "").trim(), o.toString())) break;
            }
            return apps;
        } catch (JSONException e) {
            Log.w(TAG, "APPS ser ut som JSON men går inte att läsa, tolkas som rader", e);
            return null;
        }
    }

    private static List<App> parseLines(String raw) {
        List<App> apps = new ArrayList<>();
        // En post per rad (som publicomtools sparar den). Utan radbrytning: komma, som äldre värden
        // (och då kan en beskrivning inte innehålla komma)
        String[] entries = raw.contains("\n") ? raw.split("\\r?\\n") : raw.split(",");
        for (String entry : entries) {
            if (entry.trim().isEmpty()) continue;
            String[] parts = entry.split("\\|", -1);
            if (!add(apps, parts[0].trim(),
                    parts.length > 1 ? parts[1].trim() : "",
                    parts.length > 2 ? parts[2].trim() : "",
                    parts.length > 3 ? parts[3].trim() : "",
                    parts.length > 4 ? parts[4].trim() : "",
                    parts.length > 5 ? parts[5].trim() : "",
                    parts.length > 6 ? parts[6].trim() : "",
                    entry.trim())) break;
        }
        return apps;
    }

    /** Lägger till en post om den är giltig. Returnerar false när listan är full (resten hoppas över). */
    private static boolean add(List<App> apps, String label, String url, String icon, String scopeText,
                               String desc, String labelEn, String descEn, String source) {
        if (label.isEmpty() || !url.startsWith("https://") || Uri.parse(url).getHost() == null) {
            Log.w(TAG, "Ogiltig post i APPS hoppas över: " + source);
            return true;
        }
        if (apps.size() >= MAX_APPS) {
            Log.w(TAG, "För många appar i APPS, " + label + " och resten hoppas över");
            return false;
        }
        KioskChrome.Scope scope = KioskChrome.Scope.parse(scopeText);
        if (scope == null) scope = KioskChrome.Scope.fromStartUrl(url);
        apps.add(new App(label, url, icon, scope, desc, labelEn, descEn));
        return true;
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
