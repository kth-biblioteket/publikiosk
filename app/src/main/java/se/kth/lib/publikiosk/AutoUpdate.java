package se.kth.lib.publikiosk;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

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

                for (int i = 0; i < assets.length(); i++) {

                    JSONObject asset = assets.getJSONObject(i);

                    String name = asset.getString("name");

                    if ("app-release.apk".equals(name)) {

                        downloadUrl =
                                asset.getString("browser_download_url");

                        break;
                    }
                }

                if (downloadUrl == null) {
                    Log.e(TAG, "No release APK found");
                    return null;
                }

                Log.d(TAG,
                        "Downloading: " + downloadUrl);

                downloadAndInstall(downloadUrl);

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

    private void downloadAndInstall(
            String downloadUrl) {

        updateInProgress = true;

        DownloadManager.Request request =
                new DownloadManager.Request(
                        Uri.parse(downloadUrl));

        request.setTitle(
                "Downloading update");

        request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "update.apk");

        DownloadManager manager =
                (DownloadManager)
                        context.getSystemService(
                                Context.DOWNLOAD_SERVICE);

        long downloadId =
                manager.enqueue(request);

        new Thread(() -> {

            boolean downloading = true;

            while (downloading) {

                DownloadManager.Query query =
                        new DownloadManager.Query();

                query.setFilterById(downloadId);

                var cursor =
                        manager.query(query);

                if (cursor.moveToFirst()) {

                    int status =
                            cursor.getInt(
                                    cursor.getColumnIndexOrThrow(
                                            DownloadManager.COLUMN_STATUS));

                    if (status ==
                            DownloadManager.STATUS_SUCCESSFUL) {

                        downloading = false;

                        String uri =
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                DownloadManager.COLUMN_LOCAL_URI));

                        installApk(
                                Uri.parse(uri));

                    }

                    if (status ==
                            DownloadManager.STATUS_FAILED) {

                        downloading = false;

                        updateInProgress = false;

                        Log.e(TAG,
                                "Download failed");
                    }
                }

                cursor.close();

                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
            }
        }).start();
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