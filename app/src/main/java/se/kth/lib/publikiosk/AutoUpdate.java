package se.kth.lib.publikiosk;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class AutoUpdate {

    private static final String TAG = "AutoUpdate";
    private static final String API_URL = "https://api.github.com/repos/kth-biblioteket/publikiosk/releases/latest";
    private Context context;

    public AutoUpdate(Context context) {
        this.context = context;
    }

    public void checkForUpdate() {
        new CheckVersionTask().execute();
    }

    private class CheckVersionTask extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... voids) {
            try {
                URL url = new URL(API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                reader.close();

                JSONObject jsonResponse = new JSONObject(json.toString());
                String latestVersion = jsonResponse.getString("tag_name");
                String downloadUrl = jsonResponse.getJSONArray("assets")
                        .getJSONObject(0)
                        .getString("browser_download_url");

                Log.d(TAG, "Latest version: " + latestVersion);
                Log.d(TAG, "Download URL: " + downloadUrl);

                String currentVersion = getCurrentVersion();
                Log.d(TAG, "Current version: " + currentVersion);

                if (isNewerVersion(currentVersion, latestVersion)) {
                    Log.d(TAG, "New version found! Downloading...");
                    downloadAndInstall(downloadUrl);
                } else {
                    Log.d(TAG, "App is already up-to-date");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error checking for update", e);
            }
            return null;
        }
    }

    private String getCurrentVersion() {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to get current version", e);
            return "0.0.0";
        }
    }

    private boolean isNewerVersion(String current, String latest) {
        return latest.compareTo(current) > 0;  // Simple version comparison
    }

    private void downloadAndInstall(String downloadUrl) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        request.setTitle("Downloading Update");
        request.setDescription("Downloading latest version...");
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update.apk");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = manager.enqueue(request);

        // Listen when download completes
        new Thread(() -> {
            boolean downloading = true;

            while (downloading) {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                var cursor = manager.query(query);
                if (cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false;
                        String fileUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                        installApk(Uri.parse(fileUri));
                    }
                }
                cursor.close();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void installApk(Uri apkUri) {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(apkUri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}


