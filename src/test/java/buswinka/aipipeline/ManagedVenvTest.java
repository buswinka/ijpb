package buswinka.aipipeline;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

public class ManagedVenvTest {

    @Test
    public void testVenvDirIsUnderAppSupportDir() {
        File appSupport = MachineId.appSupportDir();
        File venvDir    = ManagedVenv.getVenvDir();
        assertNotNull("appSupportDir() must return a non-null path", appSupport);
        assertEquals(
            "venv dir must be a direct child of appSupportDir",
            appSupport.getAbsolutePath(),
            venvDir.getParentFile().getAbsolutePath()
        );
        assertEquals("env", venvDir.getName());
    }

    @Test
    public void testGetPythonPathIsUnderVenvDir() {
        String pythonPath = ManagedVenv.getPythonPath();
        String venvDir    = ManagedVenv.getVenvDir().getAbsolutePath();
        assertTrue(
            "python path '" + pythonPath + "' must start with venv dir '" + venvDir + "'",
            pythonPath.startsWith(venvDir)
        );
    }

    @Test
    public void testGetPythonPathEndsWithExecutable() {
        String pythonPath = ManagedVenv.getPythonPath();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertTrue("Windows python path must end with python.exe",
                       pythonPath.endsWith("python.exe"));
        } else {
            assertTrue("Unix python path must end with /python",
                       pythonPath.endsWith("/python"));
        }
    }

    @Test
    public void testFindSystemPythonReturnsAbsolutePathOrNull() {
        // Returns null if Python 3 is not installed — acceptable.
        String python = ManagedVenv.findSystemPython();
        if (python != null) {
            assertTrue("System python path must be absolute", new File(python).isAbsolute());
            assertTrue("System python executable must exist on disk", new File(python).exists());
        }
    }

    @Test
    public void testInitialStateIsNotNull() {
        assertNotNull(ManagedVenv.getState());
    }
}