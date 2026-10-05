package se.kth.lib.publikiosk;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

    /** Gboard, Googles tangentbord (förinstallerat på de flesta enheter) */
    private static final String GBOARD = "com.google.android.inputmethod.latin";

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
        safely(() -> dpm.setApplicationRestrictions(admin, GBOARD, keyboardConfig()));
    }

    /**
     * Gboard utan den övre raden (inställningar, GIF, klistermärken, översättning, teman, mikrofon)
     * och utan urklipp och delning. Gboard läser det som hanterad konfiguration från device owner.
     * Andra tangentbord ignorerar den.
     */
    private static Bundle keyboardConfig() {
        Bundle preferences = new Bundle();
        preferences.putBoolean("show_suggestion_strip", false);
        Bundle config = new Bundle();
        config.putBundle("preferences", preferences);
        config.putBoolean("config_settings_access_point", false);
        config.putBoolean("config_theme_access_point", false);
        config.putBoolean("config_clipboard", false);
        config.putBoolean("config_sharing", false);
        config.putBoolean("enable_text_preview", false);
        return config;
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
        safely(() -> dpm.setApplicationRestrictions(admin, GBOARD, new Bundle()));
    }

    /** Starta om enheten (begärt från publicomtools). Kräver Android 7. */
    public boolean reboot() {
        if (!isDeviceOwner() || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            dpm.reboot(admin);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Omstart misslyckades", e);
            return false;
        }
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
