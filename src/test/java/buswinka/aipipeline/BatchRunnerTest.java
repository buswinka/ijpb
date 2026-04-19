// src/test/java/buswinka/aipipeline/BatchRunnerTest.java
package buswinka.aipipeline;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class BatchRunnerTest {

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    // --- extractStem ---

    @Test
    public void extractStem_plainTif() {
        assertEquals("sample_001", BatchRunner.extractStem("sample_001.tif"));
    }

    @Test
    public void extractStem_omeTif() {
        assertEquals("sample_001", BatchRunner.extractStem("sample_001.ome.tif"));
    }

    @Test
    public void extractStem_czi() {
        assertEquals("stack", BatchRunner.extractStem("stack.czi"));
    }

    @Test
    public void extractStem_noExtension() {
        assertEquals("noext", BatchRunner.extractStem("noext"));
    }

    @Test
    public void extractStem_dotInStem() {
        assertEquals("my.file", BatchRunner.extractStem("my.file.tif"));
    }

    // --- matchesImageExtension ---

    @Test
    public void matchesExtension_tif() throws Exception {
        File f = tempDir.newFile("a.tif");
        assertTrue(BatchRunner.matchesImageExtension(f));
    }

    @Test
    public void matchesExtension_omeTif() throws Exception {
        File f = tempDir.newFile("a.ome.tif");
        assertTrue(BatchRunner.matchesImageExtension(f));
    }

    @Test
    public void matchesExtension_czi() throws Exception {
        File f = tempDir.newFile("a.czi");
        assertTrue(BatchRunner.matchesImageExtension(f));
    }

    @Test
    public void matchesExtension_nd2() throws Exception {
        File f = tempDir.newFile("a.nd2");
        assertTrue(BatchRunner.matchesImageExtension(f));
    }

    @Test
    public void matchesExtension_upperCase() throws Exception {
        File f = tempDir.newFile("a.TIF");
        assertTrue(BatchRunner.matchesImageExtension(f));
    }

    @Test
    public void matchesExtension_unknownExtension() throws Exception {
        File f = tempDir.newFile("a.docx");
        assertFalse(BatchRunner.matchesImageExtension(f));
    }

    @Test
    public void matchesExtension_noExtension() throws Exception {
        File f = tempDir.newFile("noext");
        assertFalse(BatchRunner.matchesImageExtension(f));
    }

    // --- buildOutputName ---

    @Test
    public void buildOutputName_singleImage() {
        assertEquals("sample_001.tif", BatchRunner.buildOutputName("sample_001", 1, 1));
    }

    @Test
    public void buildOutputName_multipleImages_first() {
        assertEquals("sample_001_1.tif", BatchRunner.buildOutputName("sample_001", 1, 3));
    }

    @Test
    public void buildOutputName_multipleImages_third() {
        assertEquals("sample_001_3.tif", BatchRunner.buildOutputName("sample_001", 3, 3));
    }

    // --- isScientificFormat ---

    @Test
    public void scientificFormat_czi() throws Exception {
        File f = tempDir.newFile("a.czi");
        assertTrue(BatchRunner.isScientificFormat(f));
    }

    @Test
    public void scientificFormat_tif() throws Exception {
        File f = tempDir.newFile("a.tif");
        assertFalse(BatchRunner.isScientificFormat(f));
    }

    @Test
    public void scientificFormat_lif() throws Exception {
        File f = tempDir.newFile("a.lif");
        assertTrue(BatchRunner.isScientificFormat(f));
    }

    @Test
    public void scientificFormat_omeTif() throws Exception {
        File f = tempDir.newFile("a.ome.tif");
        assertTrue(BatchRunner.isScientificFormat(f));
    }

    // --- hasExistingOutput ---

    @Test
    public void hasExistingOutput_tif() throws Exception {
        File out = tempDir.newFolder("out");
        new File(out, "sample_001.tif").createNewFile();
        assertTrue(BatchRunner.hasExistingOutput(out, "sample_001"));
    }

    @Test
    public void hasExistingOutput_roiSet() throws Exception {
        File out = tempDir.newFolder("out2");
        new File(out, "sample_001_RoiSet.zip").createNewFile();
        assertTrue(BatchRunner.hasExistingOutput(out, "sample_001"));
    }

    @Test
    public void hasExistingOutput_nothing() throws Exception {
        File out = tempDir.newFolder("out3");
        assertFalse(BatchRunner.hasExistingOutput(out, "sample_001"));
    }

    @Test
    public void hasExistingOutput_differentStem() throws Exception {
        File out = tempDir.newFolder("out4");
        new File(out, "sample_002.tif").createNewFile();
        assertFalse(BatchRunner.hasExistingOutput(out, "sample_001"));
    }
}