package se.kth.lib.publikiosk;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

public class InstallReceiver extends BroadcastReceiver {
    private static final String TAG = "InstallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Uri packageUri = intent.getData();
        if (packageUri != null) {
            String packageName = packageUri.getSchemeSpecificPart();
            Log.e(TAG, "Package installed: " + packageName);

            if (packageName.equals(context.getPackageName())) {
                Log.e(TAG, "Our app was updated!");
                restartApp(context);
            }
        }
    }

    private void restartApp(Context context) {
        Intent restart = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (restart != null) {
            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(restart);
            Log.e(TAG, "Restarting app...");
        }
    }
}

