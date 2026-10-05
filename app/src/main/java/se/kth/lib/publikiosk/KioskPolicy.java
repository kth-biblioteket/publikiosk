package se.kth.lib.publikiosk;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

/**
 * Låser enheten när appen är device owner, så att en besökare inte kan ta sig ur kiosken
 * eller ändra enheten. "Lämna kioskläge" (bakom PIN) tar bort allt igen, så att IT kan
 * komma åt inställningarna; nästa start låser på nytt.
 *
 * adb stängs inte av här: det görs först när enheten är ansluten till publicomtools,
 * så att den alltid går att nå för underhåll innan dess.
 */
public class KioskPolicy {

    private static final String TAG = "KioskPolicy";

    private static final String[] RESTRICTIONS = {
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
    };

    private final Context context;
    private final DevicePolicyManager dpm;
    private final ComponentName admin;

    public KioskPolicy(Context context) {
        this.context = context;
        this.dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.admin = new ComponentName(context, MyDeviceAdminReceiver.class);
    }

    public boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    /** Anropas innan startLockTask. */
    public void apply() {
        if (!isDeviceOwner()) return;
        dpm.setLockTaskPackages(admin, new String[]{context.getPackageName()});
        // Inget statusfält, ingen hemknapp, ingen översikt, ingen strömmeny i kioskläget
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
        }
        safely(() -> dpm.setStatusBarDisabled(admin, true));
        safely(() -> dpm.setKeyguardDisabled(admin, true));
        // Appen är hemskärmen, så att kiosken kommer tillbaka om något annat skulle ta över
        // (t ex om appen kraschar och lämnar locktask)
        safely(() -> {
            setHomeEnabled(true);
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, home, homeAlias());
        });
        for (String r : RESTRICTIONS) safely(() -> dpm.addUserRestriction(admin, r));
    }

    /** "Lämna kioskläge": ta bort låsningarna så att IT kommer åt enheten. */
    public void release() {
        if (!isDeviceOwner()) return;
        safely(() -> dpm.setStatusBarDisabled(admin, false));
        safely(() -> dpm.setKeyguardDisabled(admin, false));
        safely(() -> dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName()));
        // Avstängd är appen inte längre en hemskärm, så Hem går till enhetens vanliga startskärm
        safely(() -> setHomeEnabled(false));
        for (String r : RESTRICTIONS) safely(() -> dpm.clearUserRestriction(admin, r));
    }

    private ComponentName homeAlias() {
        return new ComponentName(context, context.getPackageName() + ".KioskHome");
    }

    private void setHomeEnabled(boolean enabled) {
        context.getPackageManager().setComponentEnabledSetting(homeAlias(),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            // T ex en begränsning som inte finns på den här Android-versionen: resten ska ändå gälla
            Log.w(TAG, "Kunde inte sätta policy", e);
        }
    }
}
