package se.kth.lib.publikiosk;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class AutoUpdate extends AsyncTask<Void, Void, File> {
    private Context context;
    private ComponentName admin;

    public AutoUpdate(Context context, ComponentName admin) {
        this.context = context;
        this.admin = admin;
    }

    @Override
    protected File doInBackground(Void... voids) {
        try {
            // Hämta senaste GitHub Release
            URL url = new URL("https://api.github.com/repos/kthbiblioteket/repo/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            InputStream is = conn.getInputStream();
            Scanner scanner = new Scanner(is);
            String response = scanner.useDelimiter("\\A").next();

            JSONObject json = new JSONObject(response);
            JSONArray assets = json.getJSONArray("assets");
            String downloadUrl = assets.getJSONObject(0).getString("browser_download_url");

            // Ladda ner APK
            File apkFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "app-latest.apk");
            downloadFile(downloadUrl, apkFile);

            return apkFile;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    protected void onPostExecute(File apkFile) {
        if (apkFile != null) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                Uri apkUri = Uri.fromFile(apkFile);
                dpm.installPackage(admin, apkUri, DevicePolicyManager.INSTALL_ALLOW_DOWNGRADE);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void downloadFile(String downloadUrl, File file) throws Exception {
        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(file);
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) > 0) {
            fos.write(buffer, 0, length);
        }
        fos.close();
        is.close();
    }
}
