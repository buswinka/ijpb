package buswinka.aipipeline;

import ij.Prefs;
import java.text.SimpleDateFormat;
import java.util.Date;

public class RateLimiter {
    private static final String KEY_COUNT = "aipipeline.ratelimit.count";
    private static final String KEY_DATE  = "aipipeline.ratelimit.date";
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    private final int dailyLimit;
    private int usedToday;
    private final boolean usePrefs;

    /** Production constructor — reads limit from TierManager. */
    public RateLimiter() {
        this(TierManager.dailyRequestLimit());
    }

    /** Explicit-limit constructor — uses Fiji Prefs. */
    public RateLimiter(int dailyLimit) {
        this.dailyLimit = dailyLimit;
        this.usePrefs = true;
        loadFromPrefs();
    }

    /** Test constructor — in-memory only, no Fiji Prefs. */
    RateLimiter(int dailyLimit, boolean inMemoryOnly) {
        this.dailyLimit = dailyLimit;
        this.usePrefs = false;
        this.usedToday = 0;
    }

    /**
     * Returns the effective daily limit: reads TierManager dynamically when using prefs
     * so a Pro key entered mid-session is picked up immediately without a restart.
     * Falls back to the constructor-supplied limit for in-memory test instances.
     */
    private int effectiveLimit() {
        return usePrefs ? TierManager.dailyRequestLimit() : dailyLimit;
    }

    public boolean tryConsume() {
        if (usePrefs) loadFromPrefs(); // re-check date so daily count resets at midnight
        if (usedToday >= effectiveLimit()) return false;
        usedToday++;
        if (usePrefs) saveToPrefs();
        return true;
    }

    public int getRemaining() { return Math.max(0, effectiveLimit() - usedToday); }

    private void loadFromPrefs() {
        String today = today();
        String storedDate = Prefs.get(KEY_DATE, "");
        if (!today.equals(storedDate)) {
            usedToday = 0;
        } else {
            usedToday = (int) Math.round(Prefs.get(KEY_COUNT, 0.0));
        }
    }

    private void saveToPrefs() {
        Prefs.set(KEY_DATE, today());
        Prefs.set(KEY_COUNT, usedToday);
    }

    private String today() { return new SimpleDateFormat(DATE_FORMAT).format(new Date()); }
}
