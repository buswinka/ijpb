package buswinka.aipipeline;
import org.junit.Test;
import static org.junit.Assert.*;

public class RateLimiterTest {
    @Test
    public void testAllowsUpToLimit() {
        RateLimiter limiter = new RateLimiter(3, true); // in-memory, no Fiji Prefs
        assertTrue(limiter.tryConsume());
        assertTrue(limiter.tryConsume());
        assertTrue(limiter.tryConsume());
        assertFalse(limiter.tryConsume()); // 4th is denied
        assertEquals(0, limiter.getRemaining());
    }

    @Test
    public void testRemainingCountsDown() {
        RateLimiter limiter = new RateLimiter(5, true);
        assertEquals(5, limiter.getRemaining());
        limiter.tryConsume();
        assertEquals(4, limiter.getRemaining());
    }
}
