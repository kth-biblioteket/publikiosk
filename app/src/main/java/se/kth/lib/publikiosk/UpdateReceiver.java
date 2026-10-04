package se.kth.lib.publikiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

public class UpdateReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoUpdate";

    @Override
    public void onReceive(Context context, Intent intent) {

        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
        );

        String message = intent.getStringExtra(
                PackageInstaller.EXTRA_STATUS_MESSAGE
        );

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

            default:
                Log.e(
                        TAG,
                        "Installation failed. Status="
                                + status
                                + " Message="
                                + message
                );
        }
    }
}