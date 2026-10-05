package se.kth.lib.publikiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PIN till den dolda inställningsmenyn.
 *
 * - PIN:en sparas aldrig i klartext, bara som PBKDF2-hash med salt.
 * - Efter MAX_ATTEMPTS fel spärras menyn en stund. Spärren är alltid tillfällig (högst
 *   MAX_LOCK_MS), så enheten kan aldrig låsas permanent. Kiosken fungerar som vanligt under tiden.
 * - En äldre klartext-PIN (MyPrefs "pin") flyttas hit vid första start efter uppdateringen.
 *   Var den 1234 eller kortare än MIN_LENGTH måste en ny väljas nästa gång menyn öppnas.
 */
public class PinStore {

    private static final String TAG = "PinStore";

    public static final int MIN_LENGTH = 6;
    static final int MAX_ATTEMPTS = 5;
    static final long FIRST_LOCK_MS = 60_000;
    static final long MAX_LOCK_MS = 15 * 60_000;

    private static final String FILE = "pin";
    private static final String KEY_HASH = "hash";
    private static final String KEY_SALT = "salt";
    private static final String KEY_ALGORITHM = "algorithm";
    private static final String KEY_ITERATIONS = "iterations";
    private static final String KEY_MUST_CHANGE = "mustChange";
    private static final String KEY_FAILED = "failed";
    private static final String KEY_LOCKS = "locks";
    private static final String KEY_LOCKED_UNTIL = "lockedUntil";
    private static final String KEY_RECOVERY_HASH = "recoveryHash";
    private static final String KEY_RECOVERY_SALT = "recoverySalt";
    private static final String KEY_EVENT = "event";

    private static final String LEGACY_PREFS = "MyPrefs";
    private static final String LEGACY_PIN = "pin";

    private static final int ITERATIONS = 50_000;

    private final SharedPreferences prefs;

    public PinStore(Context context) {
        prefs = SecurePrefs.open(context, FILE);
        migrateLegacy(context.getApplicationContext());
    }

    private void migrateLegacy(Context context) {
        SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String old = legacy.getString(LEGACY_PIN, null);
        if (old == null) return;
        if (!isSet()) {
            store(old);
            boolean weak = "1234".equals(old) || old.length() < MIN_LENGTH;
            prefs.edit().putBoolean(KEY_MUST_CHANGE, weak).apply();
        }
        legacy.edit().remove(LEGACY_PIN).apply();
    }

    public boolean isSet() {
        return prefs.contains(KEY_HASH);
    }

    public boolean mustChange() {
        return prefs.getBoolean(KEY_MUST_CHANGE, false);
    }

    /** Millisekunder kvar av spärren, 0 om menyn inte är spärrad. */
    public long lockedForMs() {
        long until = prefs.getLong(KEY_LOCKED_UNTIL, 0);
        long left = until - System.currentTimeMillis();
        // Har klockan ställts om kan spärren se längre ut än den får vara: släpp den då.
        if (left <= 0 || left > MAX_LOCK_MS) return 0;
        return left;
    }

    public enum Result { OK, WRONG, LOCKED }

    public Result verify(String pin) {
        if (lockedForMs() > 0) return Result.LOCKED;
        if (pin != null && matches(pin)) {
            prefs.edit().putInt(KEY_FAILED, 0).putInt(KEY_LOCKS, 0).remove(KEY_LOCKED_UNTIL).apply();
            return Result.OK;
        }
        return failure();
    }

    /** Ett fel försök (PIN eller återställningskod): efter MAX_ATTEMPTS spärras menyn en stund. */
    private Result failure() {
        int failed = prefs.getInt(KEY_FAILED, 0) + 1;
        SharedPreferences.Editor e = prefs.edit();
        if (failed >= MAX_ATTEMPTS) {
            event("spärrad efter " + MAX_ATTEMPTS + " fel");
            // 1 min, 2, 4, 8, sedan högst 15 min. Räknaren nollställs efter varje spärr.
            int locks = prefs.getInt(KEY_LOCKS, 0);
            long ms = Math.min(MAX_LOCK_MS, FIRST_LOCK_MS << Math.min(locks, 10));
            e.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + ms).putInt(KEY_LOCKS, locks + 1).putInt(KEY_FAILED, 0);
            Log.w(TAG, "Fel PIN " + MAX_ATTEMPTS + " gånger, menyn spärrad i " + ms / 1000 + " s");
        } else {
            e.putInt(KEY_FAILED, failed);
        }
        e.apply();
        return lockedForMs() > 0 ? Result.LOCKED : Result.WRONG;
    }

    public int attemptsLeft() {
        return MAX_ATTEMPTS - prefs.getInt(KEY_FAILED, 0);
    }

    public static boolean isValidNewPin(String pin) {
        return pin != null && pin.matches("[0-9]{" + MIN_LENGTH + ",12}") && !pin.matches("(\\d)\\1+");
    }

    /** Ny PIN. Tar också bort spärren. */
    public void set(String pin) {
        store(pin);
        event("PIN bytt");
        prefs.edit().putBoolean(KEY_MUST_CHANGE, false).putInt(KEY_FAILED, 0).putInt(KEY_LOCKS, 0)
                .remove(KEY_LOCKED_UNTIL).apply();
    }

    /** Tar bort spärren (t ex från publicomtools). */
    public void unlock() {
        prefs.edit().putInt(KEY_FAILED, 0).putInt(KEY_LOCKS, 0).remove(KEY_LOCKED_UNTIL).apply();
        event("upplåst från publicomtools");
    }

    /**
     * Återställningskoden från inskrivningen (glömd PIN). Bara hashen sparas; IT ser koden i
     * publicomtools under Teknik.
     */
    public void setRecoveryCode(String code) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String algorithm = algorithm();
        byte[] hash = pbkdf2(normalizeCode(code), salt, algorithm, ITERATIONS);
        prefs.edit()
                .putString(KEY_RECOVERY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putString(KEY_RECOVERY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_ALGORITHM + "Recovery", algorithm)
                .apply();
    }

    public boolean hasRecoveryCode() {
        return prefs.contains(KEY_RECOVERY_HASH);
    }

    /**
     * Glömd PIN: rätt återställningskod tar bort spärren, och sedan väljs en ny PIN. Fel kod
     * räknas som ett fel PIN (samma spärr), så koden går inte att gissa sig till.
     */
    public Result verifyRecovery(String code) {
        if (lockedForMs() > 0) return Result.LOCKED;
        String hash = prefs.getString(KEY_RECOVERY_HASH, null);
        String salt = prefs.getString(KEY_RECOVERY_SALT, null);
        if (hash != null && salt != null && code != null) {
            byte[] actual = pbkdf2(normalizeCode(code), Base64.decode(salt, Base64.NO_WRAP),
                    prefs.getString(KEY_ALGORITHM + "Recovery", algorithm()), ITERATIONS);
            if (actual != null && MessageDigest.isEqual(Base64.decode(hash, Base64.NO_WRAP), actual)) {
                prefs.edit().putInt(KEY_FAILED, 0).putInt(KEY_LOCKS, 0).remove(KEY_LOCKED_UNTIL).apply();
                event("återställningskoden användes");
                return Result.OK;
            }
        }
        return failure();
    }

    /** Senaste händelsen i menyn, till statusrapporten: "PIN bytt 2026-10-05 21:03" */
    public String lastEvent() {
        return prefs.getString(KEY_EVENT, null);
    }

    private void event(String what) {
        String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(new java.util.Date());
        prefs.edit().putString(KEY_EVENT, what + " " + when).apply();
    }

    /** "k7qm 2xpa" → "K7QM2XPA" */
    private static String normalizeCode(String code) {
        return code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private void store(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String algorithm = algorithm();
        byte[] hash = pbkdf2(pin, salt, algorithm, ITERATIONS);
        prefs.edit()
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_ALGORITHM, algorithm)
                .putInt(KEY_ITERATIONS, ITERATIONS)
                .apply();
    }

    private boolean matches(String pin) {
        String hash = prefs.getString(KEY_HASH, null);
        String salt = prefs.getString(KEY_SALT, null);
        if (hash == null || salt == null) return false;
        byte[] expected = Base64.decode(hash, Base64.NO_WRAP);
        byte[] actual = pbkdf2(pin, Base64.decode(salt, Base64.NO_WRAP),
                prefs.getString(KEY_ALGORITHM, algorithm()), prefs.getInt(KEY_ITERATIONS, ITERATIONS));
        return actual != null && MessageDigest.isEqual(expected, actual);
    }

    /** PBKDF2WithHmacSHA256 finns från Android 8; äldre enheter använder SHA-1-varianten. */
    private static String algorithm() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
    }

    private static byte[] pbkdf2(String pin, byte[] salt, String algorithm, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            Log.e(TAG, "PBKDF2 misslyckades", e);
            return null;
        }
    }
}
