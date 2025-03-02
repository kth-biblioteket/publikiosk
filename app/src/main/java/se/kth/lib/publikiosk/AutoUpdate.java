package se.kth.lib.publikiosk;

import android.content.Context;
import android.os.AsyncTask;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class AutoUpdate {

    private static final String GITHUB_RELEASES_API_URL = "https://api.github.com/repos/kth-biblioteket/publikiosk/releases/latest";

    // Callback interface to notify MainActivity about the update check result
    public interface UpdateCheckListener {
        void onUpdateAvailable(String newVersion);
    }

    // Method to start checking for updates
    public static void checkForUpdates(Context context, UpdateCheckListener listener) {
        new CheckForUpdateTask(context, listener).execute();
    }

    // AsyncTask to check if there's a new version available on GitHub
    private static class CheckForUpdateTask extends AsyncTask<Void, Void, String> {

        private Context context;
        private UpdateCheckListener listener;

        public CheckForUpdateTask(Context context, UpdateCheckListener listener) {
            this.context = context;
            this.listener = listener;
        }

        @Override
        protected String doInBackground(Void... voids) {
            String latestVersion = null;
            try {
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(GITHUB_RELEASES_API_URL).build();
                Response response = client.newCall(request).execute();

                if (response.isSuccessful()) {
                    String jsonResponse = response.body().string();
                    JSONObject jsonObject = new JSONObject(jsonResponse);
                    latestVersion = jsonObject.getString("tag_name");  // v3.5.2, etc.
                }

            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
            return latestVersion;
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            if (result != null && listener != null) {
                listener.onUpdateAvailable(result);  // Notify listener with the new version
            }
        }
    }

    // Method to download APK from GitHub
    public static void downloadAPK(Context context, String downloadUrl) {
        new AsyncTask<String, Void, Void>() {
            @Override
            protected Void doInBackground(String... urls) {
                try {
                    OkHttpClient client = new OkHttpClient();
                    Request request = new Request.Builder().url(urls[0]).build();
                    Response response = client.newCall(request).execute();

                    if (response.isSuccessful()) {
                        InputStream inputStream = response.body().byteStream();
                        File apkFile = new File(context.getExternalFilesDir(null), "new_app.apk");
                        FileOutputStream fileOutputStream = new FileOutputStream(apkFile);
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = inputStream.read(buffer)) > 0) {
                            fileOutputStream.write(buffer, 0, len);
                        }
                        fileOutputStream.close();
                        inputStream.close();

                        // Install APK after download
                        installAPK(context, apkFile);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }
        }.execute(downloadUrl);
    }

    // Install the downloaded APK
    private static void installAPK(Context context, File apkFile) {
        Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(context, "se.kth.lib.publikiosk.fileprovider", apkFile);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);  // Grant permission for API level 24+
        context.startActivity(intent);
    }
}
