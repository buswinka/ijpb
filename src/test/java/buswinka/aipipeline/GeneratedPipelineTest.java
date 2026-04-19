package buswinka.aipipeline;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeneratedPipelineTest {
    @Test
    public void testPipelineHoldsValues() {
        GeneratedPipeline p = new GeneratedPipeline(
            "Count Cells", "Uses particle analysis", "run(\"Analyze Particles...\");", ScriptLanguage.IJM);
        assertEquals("Count Cells", p.getTitle());
        assertEquals(ScriptLanguage.IJM, p.getLanguage());
        assertFalse(p.getScript().isEmpty());
    }
}
