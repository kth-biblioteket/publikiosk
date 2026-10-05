package se.kth.lib.publikiosk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public class AutoUpdate {

    private static final String TAG = "AutoUpdate";

    private static final String API_URL =
            "https://api.github.com/repos/kth-biblioteket/publikiosk/releases/latest";

    private static boolean updateInProgress = false;

    private final Context context;

    public AutoUpdate(Context context) {
        this.context = context;
    }

    public void checkForUpdate() {

        if (updateInProgress) {
            Log.d(TAG, "Update already running");
            return;
        }

        new CheckVersionTask().execute();
    }

    private class CheckVersionTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {

            try {

                URL url = new URL(API_URL);

                HttpURLConnection connection =
                        (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        connection.getInputStream()));

                StringBuilder json = new StringBuilder();

                String line;

                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }

                reader.close();

                JSONObject release =
                        new JSONObject(json.toString());

                String latestVersion =
                        release.getString("tag_name")
                                .replace("v", "");

                String currentVersion =
                        getCurrentVersion();

                Log.d(TAG, "Latest: " + latestVersion);
                Log.d(TAG, "Current: " + currentVersion);

                if (!isNewerVersion(
                        currentVersion,
                        latestVersion)) {

                    Log.d(TAG, "Already latest");

                    return null;
                }

                JSONArray assets = release.getJSONArray("assets");

                String downloadUrl = null;
                String digest = null;

                for (int i = 0; i < assets.length(); i++) {

                    JSONObject asset = assets.getJSONObject(i);

                    String name = asset.getString("name");

                    if ("app-release.apk".equals(name)) {

                        downloadUrl =
                                asset.getString("browser_download_url");
                        // "sha256:<hex>", finns för assets som laddats upp efter juni 2025
                        digest = asset.optString("digest", null);

                        break;
                    }
                }

                if (downloadUrl == null) {
                    Log.e(TAG, "No release APK found");
                    return null;
                }

                Log.d(TAG,
                        "Downloading: " + downloadUrl);

                downloadAndInstall(downloadUrl, digest);

            } catch (Exception e) {

                Log.e(TAG,
                        "Update failed",
                        e);
            }

            return null;
        }
    }

    String getCurrentVersion() {

        try {

            PackageInfo packageInfo =
                    context.getPackageManager()
                            .getPackageInfo(
                                    context.getPackageName(),
                                    0);

            return packageInfo.versionName;

        } catch (Exception e) {

            Log.e(TAG,
                    "Version error",
                    e);

            return "0.0.0";
        }
    }

    private boolean isNewerVersion(
            String current,
            String latest) {

        try {

            String[] c =
                    current.split("\\.");

            String[] l =
                    latest.split("\\.");

            int c1 = Integer.parseInt(c[0]);
            int c2 = Integer.parseInt(c[1]);
            int c3 = Integer.parseInt(c[2]);

            int l1 = Integer.parseInt(l[0]);
            int l2 = Integer.parseInt(l[1]);
            int l3 = Integer.parseInt(l[2]);

            if (l1 > c1) return true;
            if (l1 == c1 && l2 > c2) return true;

            return l1 == c1 &&
                    l2 == c2 &&
                    l3 > c3;

        } catch (Exception e) {

            Log.e(TAG,
                    "Version compare failed",
                    e);

            return false;
        }
    }

    /**
     * Ladda ner APK:n till appens interna cache (inte den externa katalogen, där andra appar
     * kan byta ut filen), kontrollera SHA-256 mot GitHubs digest när den finns, och installera
     * bara om paketnamnet stämmer och versionen är högre än den som körs.
     */
    private void downloadAndInstall(String downloadUrl, String digest) {

        updateInProgress = true;

        new Thread(() -> {
            File dir = new File(context.getCacheDir(), "update");
            File apk = new File(dir, "update.apk");
            try {
                if (!downloadUrl.startsWith("https://")) throw new IllegalStateException("Inte https: " + downloadUrl);
                dir.mkdirs();
                apk.delete();

                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
                connection.setConnectTimeout(30_000);
                connection.setReadTimeout(60_000);
                try (InputStream in = connection.getInputStream();
                     OutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        sha256.update(buffer, 0, read);
                    }
                } finally {
                    connection.disconnect();
                }

                String actual = toHex(sha256.digest());
                if (digest != null && digest.startsWith("sha256:")) {
                    if (!digest.substring(7).equalsIgnoreCase(actual)) {
                        throw new SecurityException("SHA-256 stämmer inte: " + actual + " (väntat " + digest + ")");
                    }
                    Log.d(TAG, "SHA-256 kontrollerad");
                } else {
                    Log.w(TAG, "Releasen anger ingen digest, kontrollerar bara signatur och version");
                }

                PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                PackageInfo current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                if (archive == null || !context.getPackageName().equals(archive.packageName)) {
                    throw new SecurityException("Fel paket i APK:n");
                }
                if (versionCode(archive) <= versionCode(current)) {
                    throw new SecurityException("APK:n är inte nyare (" + archive.versionName + ")");
                }

                installApk(Uri.fromFile(apk));

            } catch (Exception e) {
                updateInProgress = false;
                apk.delete();
                Log.e(TAG, "Download failed", e);
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void installApk(Uri apkUri) {

        try {

            File apkFile =
                    new File(apkUri.getPath());

            PackageInstaller installer =
                    context.getPackageManager()
                            .getPackageInstaller();

            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            // Sessionen får bara installera den här appen
            params.setAppPackageName(context.getPackageName());

            int sessionId =
                    installer.createSession(params);

            PackageInstaller.Session session =
                    installer.openSession(sessionId);

            try (
                    InputStream in =
                            new FileInputStream(apkFile);

                    OutputStream out =
                            session.openWrite(
                                    "update",
                                    0,
                                    apkFile.length())
            ) {

                byte[] buffer =
                        new byte[65536];

                int read;

                while ((read = in.read(buffer))
                        != -1) {

                    out.write(
                            buffer,
                            0,
                            read);
                }

                session.fsync(out);
            }

            // Innehållet ligger nu i sessionen
            apkFile.delete();

            Intent intent =
                    new Intent(
                            context,
                            UpdateReceiver.class);

            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            context,
                            sessionId,
                            intent,
                            PendingIntent.FLAG_IMMUTABLE
                    );

            session.commit(
                    pendingIntent.getIntentSender());

            session.close();

            Log.d(TAG,
                    "Install started");

        } catch (Exception e) {

            updateInProgress = false;

            Log.e(TAG,
                    "Install failed",
                    e);
        }
    }
}