package buswinka.aipipeline;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class PipelineManagerTest {
    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    @Test
    public void testSavesIJMFile() throws Exception {
        PipelineManager mgr = new PipelineManager(tempDir.getRoot());
        GeneratedPipeline p = new GeneratedPipeline(
            "Count Cells", "Counts bright spots.", "run(\"Analyze Particles...\");", ScriptLanguage.IJM);
        File saved = mgr.savePipeline(p);
        assertTrue(saved.exists());
        assertTrue(saved.getName().endsWith(".ijm"));
        String content = new String(java.nio.file.Files.readAllBytes(saved.toPath()));
        assertTrue(content.contains("run(\"Analyze Particles...\")"));
        assertTrue(content.startsWith("//"));
    }

    @Test
    public void testSavesPythonFileWithHashComments() throws Exception {
        PipelineManager mgr = new PipelineManager(tempDir.getRoot());
        GeneratedPipeline p = new GeneratedPipeline(
            "Cellpose Segment", "Segments nuclei.", "import cellpose", ScriptLanguage.PYTHON);
        File saved = mgr.savePipeline(p);
        assertTrue(saved.getName().endsWith(".py"));
        String content = new String(java.nio.file.Files.readAllBytes(saved.toPath()));
        assertTrue(content.startsWith("#"));
        assertFalse(content.startsWith("//"));
    }

    @Test
    public void testSanitizesFilename() throws Exception {
        PipelineManager mgr = new PipelineManager(tempDir.getRoot());
        GeneratedPipeline p = new GeneratedPipeline(
            "My Cool Pipeline!", "desc", "run();", ScriptLanguage.IJM);
        File saved = mgr.savePipeline(p);
        assertFalse(saved.getName().contains(" "));
        assertFalse(saved.getName().contains("!"));
    }

    @Test
    public void testNumericSuffixOnCollision() throws Exception {
        PipelineManager mgr = new PipelineManager(tempDir.getRoot());
        GeneratedPipeline p = new GeneratedPipeline("Test", "d", "run();", ScriptLanguage.IJM);
        File first = mgr.savePipeline(p);
        File second = mgr.savePipeline(p);
        assertNotEquals(first.getName(), second.getName());
    }
}
