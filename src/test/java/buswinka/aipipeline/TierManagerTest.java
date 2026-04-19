package buswinka.aipipeline;
import org.junit.Test;
import static org.junit.Assert.*;

public class TierManagerTest {
    @Test
    public void testBlankOrNullKeyReturnsFalse() {
        assertFalse(TierManager.validateKey(""));
        assertFalse(TierManager.validateKey(null));
        assertFalse(TierManager.validateKey("   "));
    }

    @Test
    public void testAnyNonEmptyKeyReturnsTrue() {
        // Format validation is server-side; any non-empty string is accepted locally.
        assertTrue(TierManager.validateKey("random-string"));
        assertTrue(TierManager.validateKey("A292C7F7-6518-43A7-B91A-E4F2B5441BF0"));
        assertTrue(TierManager.validateKey("PRO-ABCDEFGH"));
    }
}
