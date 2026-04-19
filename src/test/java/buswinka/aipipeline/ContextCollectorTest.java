package buswinka.aipipeline;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextCollectorTest {
    @Test
    public void testNoImageMessage() {
        String context = ContextCollector.buildNoImageContext();
        assertTrue(context.contains("No image"));
    }

    @Test
    public void testContextContainsRequiredSections() {
        String context = ContextCollector.buildContext(
            "test.tif", 512, 512, 1, 1, 1, 16, "GRAY16",
            0.1625, "µm", 0, false, 0, new String[0]);
        assertTrue(context.contains("test.tif"));
        assertTrue(context.contains("512"));
        assertTrue(context.contains("16-bit"));
        assertTrue(context.contains("0.1625"));
        assertTrue(context.contains("µm"));
    }
}
