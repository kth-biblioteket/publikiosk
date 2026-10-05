package se.kth.lib.publikiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Krypterade SharedPreferences (nyckeln i Android Keystore) för PIN-hash och enhetens token.
 * Fungerar inte nyckellagret på enheten används en vanlig privat fil: appens filer kan ändå
 * inte läsas av andra appar och säkerhetskopieras inte (allowBackup=false).
 */
final class SecurePrefs {

    private static final String TAG = "SecurePrefs";

    private SecurePrefs() {
    }

    static SharedPreferences open(Context context, String name) {
        Context app = context.getApplicationContext();
        try {
            MasterKey key = new MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            return EncryptedSharedPreferences.create(app, name + "_secure", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences fungerar inte, använder privat fil", e);
            return app.getSharedPreferences(name, Context.MODE_PRIVATE);
        }
    }
}
