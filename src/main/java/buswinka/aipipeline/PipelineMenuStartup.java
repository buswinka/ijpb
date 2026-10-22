package buswinka.aipipeline;

import ij.IJ;
import ij.plugin.PlugIn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Lightweight startup plugin: re-injects all saved pipeline menu items so they
 * route through PythonExecutor (correct conda env) instead of Fiji's built-in
 * Jython runner.
 *
 * Registered as: Plugins > Utilities > Pipelines > Regenerate Menu Items
 *
 * Triggered automatically at Fiji startup via a one-time-installed macro at
 * scripts/Plugins/AutoRun/AI_Pipeline_Builder_Startup.ijm.
 * That macro file is created the first time any plugin entry point runs.
 */
public class PipelineMenuStartup implements PlugIn {

    static final String AUTORUN_MACRO_NAME = "AI_Pipeline_Builder_Startup.ijm";
    private static final String AUTORUN_CONTENT    = "// AI Pipeline Builder — re-injects pipeline menu items at startup\nrun(\"Regenerate Menu Items\");\n";

    @Override
    public void run(String arg) {
        cleanIjpbTmpDir();
        new PipelineManager().reinjectAllMenuItems();
        PluginUpdater.checkAsync();
        ManagedVenv.ensureAsync();
    }

    /**
     * Deletes all files in the dedicated ijpb temp directory from previous sessions.
     * Called once at startup before any scripts run, so no files from the current
     * session exist yet.
     */
    private static void cleanIjpbTmpDir() {
        File ijpbDir = new File(System.getProperty("java.io.tmpdir"), "ijpb");
        if (!ijpbDir.isDirectory()) return;

        File[] files = ijpbDir.listFiles();
        if (files == null || files.length == 0) return;

        int deleted = 0;
        for (File f : files) {
            if (f.isFile() && f.delete()) deleted++;
        }
        DebugLog.log("PipelineMenuStartup", "Cleaned %d file(s) from ijpb tmp dir", deleted);
    }

    /**
     * Ensures that scripts/Plugins/AutoRun/AI_Pipeline_Builder_Startup.ijm exists
     * so this plugin is called automatically on every Fiji startup.
     * Safe to call repeatedly — does nothing if the file is already present.
     */
    public static void ensureAutoRunInstalled() {
        String ijDir = IJ.getDirectory("imagej");
        if (ijDir == null) return;

        File autoRunDir = new File(ijDir, "scripts" + File.separator
                + "Plugins" + File.separator + "AutoRun");
        File marker = new File(autoRunDir, AUTORUN_MACRO_NAME);
        if (marker.exists()) return;

        try {
            if (!autoRunDir.exists()) autoRunDir.mkdirs();
            Files.write(marker.toPath(), AUTORUN_CONTENT.getBytes(StandardCharsets.UTF_8));
            DebugLog.log("PipelineMenuStartup",
                    "Installed AutoRun macro at %s", marker.getAbsolutePath());
        } catch (IOException e) {
            IJ.log("[AI Pipeline Builder] Warning: could not install AutoRun macro: " + e.getMessage());
        }
    }
}
