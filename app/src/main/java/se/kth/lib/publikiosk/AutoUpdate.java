package se.kth.lib.publikiosk;

package com.example.autoupdate;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

import android.app.admin.DevicePolicyManager;

public class AutoUpdate {

    private Context context;
    private DevicePolicyManager dpm;
    private ComponentName admin;
    private String githubDownloadUrl = "https://api.github.com/repos/kth-bibliotekete/publikiosk/actions/artifacts"; // <-- Ändra till ditt repo
    private String apkName = "app-debug.apk"; // Standard APK namn

    public AutoUpdate(Context context) {
        this.context = context;
        dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(context, MyDeviceAdminReceiver.class);
    }

    public void checkForUpdate() {
        try {
            URL url = new URL(githubDownloadUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            if (connection.getResponseCode() == 200) {
                Log.d("AutoUpdate", "Ny version hittades!");
                downloadAPK();
            } else {
                Log.d("AutoUpdate", "Ingen ny version");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void downloadAPK() {
        String downloadUrl = "https://github.com/kth-biblioteket/publikiosk/releases/latest/download/" + apkName;

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, apkName);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = manager.enqueue(request);

        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                installAPK(downloadId);
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void installAPK(long downloadId) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = manager.getUriForDownloadedFile(downloadId);

        if (uri != null) {
            File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkName);
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);

            if (dpm.isDeviceOwnerApp(context.getPackageName())) {
                dpm.setPermissionGrantState(
                        admin,
                        context.getPackageName(),
                        android.Manifest.permission.REQUEST_INSTALL_PACKAGES,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                );

                Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                installIntent.setData(apkUri);
                installIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(installIntent);
                Log.d("AutoUpdate", "Installerar APK...");
            } else {
                Log.e("AutoUpdate", "Appen är inte Device Owner!");
            }
        }
    }
}
