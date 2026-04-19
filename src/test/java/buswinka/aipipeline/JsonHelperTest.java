package buswinka.aipipeline;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonHelperTest {
    @Test
    public void testExtractSimpleField() {
        String json = "{\"title\":\"Count Cells\",\"explanation\":\"Counts them.\",\"script\":\"run();\"}";
        assertEquals("Count Cells", JsonHelper.extractField(json, "title"));
        assertEquals("run();", JsonHelper.extractField(json, "script"));
    }

    @Test
    public void testExtractHandlesEscapedQuotes() {
        String json = "{\"script\":\"run(\\\"Analyze Particles...\\\");\"}";
        assertEquals("run(\"Analyze Particles...\");", JsonHelper.extractField(json, "script"));
    }

    @Test
    public void testExtractHandlesNewlines() {
        String json = "{\"script\":\"line1\\nline2\"}";
        assertEquals("line1\nline2", JsonHelper.extractField(json, "script"));
    }

    @Test
    public void testStripCodeFences() {
        String fenced = "Here's the JSON:\n```json\n{\"title\":\"Test\"}\n```";
        String stripped = JsonHelper.stripCodeFences(fenced);
        assertEquals("{\"title\":\"Test\"}", stripped);
    }

    @Test
    public void testStripCodeFencesNoFences() {
        String raw = "{\"title\":\"Test\"}";
        assertEquals(raw, JsonHelper.stripCodeFences(raw));
    }

    @Test
    public void testEscapeJson() {
        assertEquals("line1\\nline2", JsonHelper.escape("line1\nline2"));
        assertEquals("say \\\"hello\\\"", JsonHelper.escape("say \"hello\""));
    }

    @Test
    public void testExtractReturnsNullForMissingField() {
        assertNull(JsonHelper.extractField("{\"title\":\"Test\"}", "missing"));
    }

    @Test
    public void testExtractHandlesUnicodeEscapes() {
        // LLMs (especially Ollama/gemma) frequently escape < and > as \u003c and \u003e
        String json = "{\"script\":\"run(\\\"Image\\u003eAdjust\\u003eEqualize...\\\");\"}";
        assertEquals("run(\"Image>Adjust>Equalize...\");", JsonHelper.extractField(json, "script"));
    }
}
