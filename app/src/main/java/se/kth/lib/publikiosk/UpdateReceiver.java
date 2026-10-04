package se.kth.lib.publikiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.util.Log;

public class UpdateReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoUpdate";

    @Override
    public void onReceive(Context context, Intent intent) {

        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                -999
        );

        String message = intent.getStringExtra(
                PackageInstaller.EXTRA_STATUS_MESSAGE
        );

        Log.d(TAG, "========== INSTALL CALLBACK ==========");
        Log.d(TAG, "Status: " + status);
        Log.d(TAG, "Message: " + message);

        Bundle extras = intent.getExtras();
        if (extras != null) {
            Log.d(TAG, "Extras: " + extras);
        }

        switch (status) {

            case PackageInstaller.STATUS_SUCCESS:
                Log.d(TAG, "Update installed successfully");
                break;

            case PackageInstaller.STATUS_PENDING_USER_ACTION:

                Log.d(TAG, "User action required");

                Intent confirmIntent =
                        intent.getParcelableExtra(Intent.EXTRA_INTENT);

                if (confirmIntent != null) {
                    confirmIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    );
                    context.startActivity(confirmIntent);
                }

                break;

            case PackageInstaller.STATUS_FAILURE:
                Log.e(TAG, "Generic install failure");
                break;

            default:
                Log.e(TAG,
                        "Install result. Status="
                                + status
                                + " Message="
                                + message);
        }
    }
}