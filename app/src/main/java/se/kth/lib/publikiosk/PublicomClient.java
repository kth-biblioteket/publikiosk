package se.kth.lib.publikiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Anslutningen till publicomtools: inskrivning med engångskod, inställningar och statusrapporter.
 * Adressen, enhetens id (host) och dess token sparas krypterat (SecurePrefs). Anropen blockerar,
 * så de görs på en bakgrundstråd.
 */
public class PublicomClient {

    public static final String DEFAULT_URL = "https://apps.lib.kth.se/publicomtools";

    private static final String FILE = "publicomtools";
    private static final String KEY_URL = "url";
    private static final String KEY_HOST = "host";
    private static final String KEY_TOKEN = "token";

    private final SharedPreferences prefs;

    public PublicomClient(Context context) {
        prefs = SecurePrefs.open(context, FILE);
    }

    public boolean isEnrolled() {
        return prefs.contains(KEY_TOKEN) && prefs.contains(KEY_HOST);
    }

    public String host() {
        return prefs.getString(KEY_HOST, null);
    }

    public String baseUrl() {
        return prefs.getString(KEY_URL, DEFAULT_URL);
    }

    /** Glöm anslutningen (t ex när token inte längre godtas). */
    public void forget() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_HOST).apply();
    }

    /** Inskriven enhet: id och återställningskoden för menyn (glömd PIN). */
    public static final class Enrollment {
        public final String host;
        public final String recoveryCode;

        Enrollment(String host, String recoveryCode) {
            this.host = host;
            this.recoveryCode = recoveryCode;
        }
    }

    /** Engångskoden från admin byts mot enhetens egen token. Kastar IOException med ett meddelande på svenska. */
    public Enrollment enroll(String baseUrl, String code) throws IOException {
        String url = normalizeBaseUrl(baseUrl);
        JSONObject body = new JSONObject();
        try {
            body.put("code", code.trim());
        } catch (Exception e) {
            throw new IOException("Ogiltig kod");
        }
        Response res = request("POST", url + "/api/device/enroll", null, body.toString());
        if (res.status == 403) throw new IOException("Koden är okänd, redan använd eller för gammal. Skapa en ny i publicomtools.");
        if (res.status == 429) throw new IOException("För många försök. Vänta en stund.");
        if (res.status != 200) throw new IOException("publicomtools svarade " + res.status);
        try {
            JSONObject json = new JSONObject(res.body);
            String host = json.getString("host");
            String token = json.getString("token");
            prefs.edit().putString(KEY_URL, url).putString(KEY_HOST, host).putString(KEY_TOKEN, token).apply();
            return new Enrollment(host, json.optString("recoveryCode", null));
        } catch (Exception e) {
            throw new IOException("Oväntat svar från publicomtools");
        }
    }

    /** Inställningarna som { values, version }. Null om enheten inte är inskriven. */
    public JSONObject fetchConfig() throws IOException {
        if (!isEnrolled()) return null;
        String url = baseUrl() + "/api/device/config?format=json&host=" + Uri.encode(host());
        Response res = request("GET", url, prefs.getString(KEY_TOKEN, null), null);
        checkAuth(res);
        if (res.status != 200) throw new IOException("Inställningar: publicomtools svarade " + res.status);
        try {
            return new JSONObject(res.body);
        } catch (Exception e) {
            throw new IOException("Inställningar: oväntat svar");
        }
    }

    /** Skickar statusrapporten. Svaret: { ok, reload, reboot, … } eller null om enheten inte är inskriven. */
    public JSONObject heartbeat(JSONObject status) throws IOException {
        if (!isEnrolled()) return null;
        Response res = request("POST", baseUrl() + "/api/heartbeat", prefs.getString(KEY_TOKEN, null), status.toString());
        checkAuth(res);
        if (res.status != 200) throw new IOException("Statusrapport: publicomtools svarade " + res.status);
        try {
            return new JSONObject(res.body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Skärmdump (JPEG) av appen, efter "Ta skärmdump" i admin. */
    public void uploadScreenshot(byte[] jpeg) throws IOException {
        if (!isEnrolled()) return;
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + "/api/device/screenshot").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(jpeg.length);
            c.setRequestProperty("Content-Type", "image/jpeg");
            c.setRequestProperty("Authorization", "Bearer " + prefs.getString(KEY_TOKEN, null));
            try (OutputStream out = c.getOutputStream()) {
                out.write(jpeg);
            }
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("Skärmdump: publicomtools svarade " + status);
        } finally {
            c.disconnect();
        }
    }

    /** Token som inte längre godtas (enheten har skrivits in på nytt i admin): koppla från. */
    private void checkAuth(Response res) throws IOException {
        if (res.status == 401) {
            forget();
            throw new IOException("Enhetens nyckel godtas inte längre. Skriv in enheten på nytt.");
        }
    }

    /** "apps.lib.kth.se/publicomtools/" → "https://apps.lib.kth.se/publicomtools" */
    static String normalizeBaseUrl(String url) throws IOException {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) u = DEFAULT_URL;
        if (!u.contains("://")) u = "https://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        Uri uri = Uri.parse(u);
        boolean emulatorHost = "10.0.2.2".equals(uri.getHost());
        // Bara https (http enbart mot datorn som kör emulatorn, och då bara i debug-bygget)
        if (!"https".equals(uri.getScheme()) && !(emulatorHost && "http".equals(uri.getScheme())))
            throw new IOException("Adressen måste börja med https://");
        return u;
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Response request(String method, String url, String token, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(status, in == null ? "" : read(in));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            int total = 0;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > 1024 * 1024) throw new IOException("För stort svar");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
}
