package buswinka.aipipeline;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class MockLLMTest {
    @Test
    public void testMockCyclesThroughMacros() throws Exception {
        MockLLM llm = new MockLLM();
        GeneratedPipeline first = llm.generate("", Collections.emptyList(), "count cells", ScriptLanguage.IJM);
        GeneratedPipeline second = llm.generate("", Collections.emptyList(), "measure", ScriptLanguage.IJM);
        assertNotNull(first.getScript());
        assertNotEquals(first.getTitle(), second.getTitle());
        assertEquals(ScriptLanguage.IJM, first.getLanguage());
    }
}
