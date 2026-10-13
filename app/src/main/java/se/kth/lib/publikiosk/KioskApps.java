package se.kth.lib.publikiosk;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Webbappar på en enhet. APPS listar dem, en per post, och den första är hem (som i Linux-kiosken); START_URL
 * läses bara för äldre inställningar och läggs då först (se forFrame):
 * "Namn|https://adress/|ikon|område|beskrivning|namn_en|beskrivning_en|adress_en", eller en JSON-lista (se parse)
 * när texterna innehåller | , " eller radbrytning. Allt utom namn och adress är
 * valfritt. Området är som APP_SCOPE (värd och sökväg); tomt betyder adressens värd och sökväg som
 * katalog. Beskrivningen visas på förstasidan (HOME_MODE=launcher), och namn_en/beskrivning_en när
 * besökaren valt engelska (tomma: de svenska). adress_en är adressen som öppnas på engelska, t ex
 * https://adress/?lang=en (tom: samma adress); den måste vara https och ligga på en tillåten webbplats. I förstasidesläget är APPS alla tjänster och
 * START_URL används inte.
 */
final class KioskApps {

    private static final String TAG = "KioskApps";
    /** Högst tolv tjänster (på förstasidan, flera sidor); i ramen högst sex, med hem-appen som första (se TAB_APPS) */
    static final int MAX_APPS = 12;
    static final int TAB_APPS = 6;

    static final class App {
        final String label;
        final String url;
        final String icon;
        final KioskChrome.Scope scope;
        final String desc;
        final String labelEn;
        final String descEn;
        /** Adressen när besökaren valt engelska (tom: samma adress). Tjänster anger språk på olika sätt, så den skrivs ut i sin helhet. */
        final String urlEn;
        /** Området för den engelska adressen, när det inte är samma som det angivna (null: området gäller båda) */
        final KioskChrome.Scope scopeEn;

        App(String label, String url, String icon, KioskChrome.Scope scope, String desc, String labelEn, String descEn,
            String urlEn, KioskChrome.Scope scopeEn) {
            this.label = label;
            this.url = url;
            this.icon = icon;
            this.scope = scope;
            this.desc = desc;
            this.labelEn = labelEn;
            this.descEn = descEn;
            this.urlEn = urlEn;
            this.scopeEn = scopeEn;
        }

        /** Adressen på besökarens språk; saknas den engelska används den vanliga */
        String url(boolean english) {
            return english && !urlEn.isEmpty() ? urlEn : url;
        }

        /** Ligger sidan inne i den här tjänsten, på något av språken? */
        boolean contains(String page) {
            return scope.contains(page) || (scopeEn != null && scopeEn.contains(page));
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
                        o.optString("descEn", "").trim(), o.optString("urlEn", "").trim(), o.toString())) break;
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
                    parts.length > 7 ? parts[7].trim() : "",
                    entry.trim())) break;
        }
        return apps;
    }

    /** Lägger till en post om den är giltig. Returnerar false när listan är full (resten hoppas över). */
    private static boolean add(List<App> apps, String label, String url, String icon, String scopeText,
                               String desc, String labelEn, String descEn, String urlEn, String source) {
        if (label.isEmpty() || !url.startsWith("https://") || Uri.parse(url).getHost() == null) {
            Log.w(TAG, "Ogiltig post i APPS hoppas över: " + source);
            return true;
        }
        if (apps.size() >= MAX_APPS) {
            Log.w(TAG, "För många appar i APPS, " + label + " och resten hoppas över");
            return false;
        }
        if (!urlEn.isEmpty() && (!urlEn.startsWith("https://") || Uri.parse(urlEn).getHost() == null)) {
            Log.w(TAG, "Ogiltig engelsk adress i APPS ignoreras: " + source);
            urlEn = "";
        }
        KioskChrome.Scope scope = KioskChrome.Scope.parse(scopeText);
        KioskChrome.Scope scopeEn = null;
        if (scope == null) {
            scope = KioskChrome.Scope.fromStartUrl(url);
            if (!urlEn.isEmpty()) scopeEn = KioskChrome.Scope.fromStartUrl(urlEn);
        }
        apps.add(new App(label, url, icon, scope, desc, labelEn, descEn, urlEn, scopeEn));
        return true;
    }

    /**
     * Tjänsterna i ramen (HOME_MODE=app): APPS, där den första är hem. Äldre inställningar har startsidan i
     * START_URL: den läggs först när dess adress inte redan finns bland tjänsterna (namn START_LABEL eller Hem /
     * Home, ikon START_ICON, område APP_SCOPE). Högst TAB_APPS.
     */
    static List<App> forFrame(String appsRaw, String startUrl, String startLabel, String startLabelEn, String startIcon, String appScope) {
        List<App> apps = parse(appsRaw);
        String url = startUrl == null ? "" : startUrl.trim();
        if (url.startsWith("https://") && Uri.parse(url).getHost() != null) {
            boolean present = false;
            for (App a : apps) {
                if (UrlPolicy.samePage(a.url, url) || (!a.urlEn.isEmpty() && UrlPolicy.samePage(a.urlEn, url))) present = true;
            }
            if (!present) {
                List<App> withHome = new ArrayList<>();
                String label = startLabel == null ? "" : startLabel.trim();
                String icon = startIcon == null || startIcon.trim().isEmpty() ? "house" : startIcon.trim();
                add(withHome, label.isEmpty() ? "Hem" : label, url, icon, appScope == null ? "" : appScope.trim(),
                        "", label.isEmpty() && (startLabelEn == null || startLabelEn.trim().isEmpty()) ? "Home" : (startLabelEn == null ? "" : startLabelEn.trim()), "", "", "startsidan");
                withHome.addAll(apps);
                apps = withHome;
            }
        }
        if (apps.size() > TAB_APPS) apps = new ArrayList<>(apps.subList(0, TAB_APPS));
        return apps;
    }

    /** Värdarna som apparna ligger på, för listan över tillåtna webbplatser */
    static String hosts(List<App> apps) {
        StringBuilder sb = new StringBuilder();
        for (App app : apps) {
            String host = Uri.parse(app.url).getHost();
            if (host != null) sb.append(',').append(host);
            String hostEn = app.urlEn.isEmpty() ? null : Uri.parse(app.urlEn).getHost();
            if (hostEn != null && !hostEn.equals(host)) sb.append(',').append(hostEn);
        }
        return sb.toString();
    }

    /** Ikonen för en tjänst på förstasidan: den som står i inställningen (valfri Lucide-ikon), annars en informationsikon */
    static android.graphics.drawable.Drawable tileIcon(android.content.Context context, String name) {
        android.graphics.drawable.Drawable d = icon(context, name);
        return d != null ? d : context.getDrawable(R.drawable.ic_lucide_info).mutate();
    }

    /** Ikonen med det namnet (valfri Lucide-ikon, version 1.48.0), eller null om den saknas */
    static android.graphics.drawable.Drawable icon(android.content.Context context, String name) {
        return LucideIcons.get(context, name);
    }
}
