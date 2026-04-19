package buswinka.aipipeline;

import ij.Prefs;

/**
 * Gates features by Free/Pro tier.
 * Pro is activated by entering a license key in preferences.
 */
public class TierManager {
    private static final String PREF_LICENSE_KEY = "aipipeline.license.key";

    public enum Tier { FREE, PRO }

    public static Tier currentTier() {
        String key = Prefs.get(PREF_LICENSE_KEY, "");
        if (key != null && validateKey(key)) return Tier.PRO;
        return Tier.FREE;
    }

    public static boolean isPro()         { return currentTier() == Tier.PRO; }
    public static boolean canUsePython()  { return true; }
    public static int dailyRequestLimit() { return isPro() ? Integer.MAX_VALUE : 20; }

    public static String getLicenseKey()          { return Prefs.get(PREF_LICENSE_KEY, ""); }
    public static void setLicenseKey(String key)  { Prefs.set(PREF_LICENSE_KEY, key); }

    /** Any non-empty key is accepted locally; the server validates the actual key. */
    static boolean validateKey(String key) {
        return key != null && !key.trim().isEmpty();
    }
}
