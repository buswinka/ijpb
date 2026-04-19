package buswinka.aipipeline;

import ij.IJ;
import ij.Prefs;
import ij.WindowManager;
import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.file.*;
import java.util.function.Consumer;

/**
 * Executes Python scripts as a subprocess, streaming stdout/stderr back to the caller.
 * The interpreter path is read from Fiji Prefs (aipipeline.python.path).
 *
 * Scripts receive a fiji_show(path) helper in their preamble. Any stdout line beginning
 * with FIJI_RESULT: is intercepted — the path is opened in Fiji via IJ.open() on the EDT.
 */
public class PythonExecutor {
    private static final String PREF_PYTHON_PATH  = "aipipeline.python.path";
    private static final int    DEFAULT_TIMEOUT_S = 300; // 5 minutes
    private static final String RESULT_MARKER     = "FIJI_RESULT:";
    private static final String TEXT_MARKER       = "FIJI_TEXT:";
    private static final String TABLE_MARKER      = "FIJI_TABLE:";
    private static final String ROI_MARKER        = "FIJI_ROI:";

    // Injected at the top of every script. Provides fiji_show() and _fiji_path.
    private static final String PREAMBLE =
        "import sys as _sys, os as _os, tempfile as _tempfile\n" +
        "_fiji_ctx = None\n" +
        "_ijpb_dir = _os.path.join(_tempfile.gettempdir(), 'ijpb')\n" +
        "_os.makedirs(_ijpb_dir, exist_ok=True)\n" +
        "def fiji_show(arr_or_path):\n" +
        "    \"\"\"Save a numpy array (or existing file path) as a temp TIFF and signal Fiji to open it.\"\"\"\n" +
        "    import tempfile, os\n" +
        "    if isinstance(arr_or_path, str) or isinstance(arr_or_path, os.PathLike):\n" +
        "        path = str(arr_or_path)\n" +
        "    else:\n" +
        "        import tifffile\n" +
        "        tmp = tempfile.NamedTemporaryFile(suffix='.tif', delete=False, dir=_ijpb_dir)\n" +
        "        tmp.close()\n" +
        "        tifffile.imwrite(tmp.name, arr_or_path)\n" +
        "        path = tmp.name\n" +
        "    print('FIJI_RESULT: ' + path, flush=True)\n" +
        "def fiji_show_text(text, title='Text'):\n" +
        "    import tempfile, os\n" +
        "    if isinstance(text, (str, os.PathLike)) and os.path.isfile(str(text)):\n" +
        "        path = str(text)\n" +
        "    else:\n" +
        "        tmp = tempfile.NamedTemporaryFile(suffix='.txt', delete=False, mode='w', encoding='utf-8', dir=_ijpb_dir)\n" +
        "        tmp.write(str(text))\n" +
        "        tmp.close()\n" +
        "        path = tmp.name\n" +
        "    print('FIJI_TEXT:' + str(title) + '|||' + path, flush=True)\n" +
        "def fiji_show_table(data, title='Results'):\n" +
        "    import tempfile, os\n" +
        "    if isinstance(data, (str, os.PathLike)) and os.path.isfile(str(data)):\n" +
        "        path = str(data)\n" +
        "    elif isinstance(data, str):\n" +
        "        tmp = tempfile.NamedTemporaryFile(suffix='.csv', delete=False, mode='w', encoding='utf-8', dir=_ijpb_dir)\n" +
        "        tmp.write(data)\n" +
        "        tmp.close()\n" +
        "        path = tmp.name\n" +
        "    else:\n" +
        "        tmp = tempfile.NamedTemporaryFile(suffix='.csv', delete=False, mode='w', encoding='utf-8', dir=_ijpb_dir)\n" +
        "        data.to_csv(tmp.name, index=False)\n" +
        "        tmp.close()\n" +
        "        path = tmp.name\n" +
        "    print('FIJI_TABLE:' + str(title) + '|||' + path, flush=True)\n" +
        "def fiji_show_plot(fig=None, dpi=150):\n" +
        "    import matplotlib.pyplot as _plt, tempfile as _tf\n" +
        "    if fig is None:\n" +
        "        fig = _plt.gcf()\n" +
        "    tmp = _tf.NamedTemporaryFile(suffix='.png', delete=False, dir=_ijpb_dir)\n" +
        "    tmp.close()\n" +
        "    fig.savefig(tmp.name, dpi=dpi, bbox_inches='tight')\n" +
        "    print('FIJI_RESULT: ' + tmp.name, flush=True)\n" +
        "def fiji_show_roi(roi_path):\n" +
        "    print('FIJI_ROI:' + str(roi_path), flush=True)\n" +
        "def fiji_show_json(obj, title='JSON'):\n" +
        "    import json, tempfile\n" +
        "    tmp = tempfile.NamedTemporaryFile(suffix='.json', delete=False, mode='w', encoding='utf-8', dir=_ijpb_dir)\n" +
        "    json.dump(obj, tmp, indent=2)\n" +
        "    tmp.close()\n" +
        "    print('FIJI_TEXT:' + str(title) + '|||' + tmp.name, flush=True)\n";

    private final Consumer<String> stdoutConsumer;
    private final Consumer<String> stderrConsumer;
    private volatile Process activeProcess;

    public PythonExecutor(Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
        this.stdoutConsumer = stdoutConsumer;
        this.stderrConsumer = stderrConsumer;
    }

    /**
     * Assembles the complete script passed to Python. All parts are joined here so
     * ordering is explicit and not dependent on which execute() overload calls which.
     *
     * Final order:
     *   1. _fiji_path assignment (captures ImageJ directory at call time)
     *   2. PREAMBLE (helper definitions; sets _fiji_ctx = None as a default)
     *   3. ctx assignment line (overrides _fiji_ctx when ctx is provided)
     *   4. user script
     */
    private static String buildFullScript(String userScript, FijiCtx ctx) {
        String fijiDir = IJ.getDirectory("imagej");
        String fijiPathLine = "_fiji_path = " + (fijiDir != null
            ? "r\"" + fijiDir.replace("\\", "\\\\") + "\""
            : "None") + "\n";
        String ctxLine = (ctx != null) ? ctx.toPythonLine() : "";
        return fijiPathLine + PREAMBLE + ctxLine + userScript;
    }

    /**
     * Executes a pre-assembled script string.
     * Writes it to a temp file, spawns the configured Python interpreter,
     * streams stdout/stderr on daemon threads, waits with timeout, cleans up in finally.
     * Throws if the process exits non-zero.
     */
    private void runScript(String fullScript) throws Exception {
        String pythonPath = Prefs.get(PREF_PYTHON_PATH, "python3");
        if (pythonPath == null || pythonPath.trim().isEmpty()) pythonPath = "python3";

        Path tempScript = null;
        try {
            // Write script to temp file
            tempScript = Files.createTempFile("aipipeline_", ".py");
            Files.write(tempScript, fullScript.getBytes("UTF-8"));

            ProcessBuilder pb = buildProcessBuilder(pythonPath, tempScript.toString());
            pb.redirectErrorStream(false);

            Process process = pb.start();
            activeProcess = process;

            // Stream stdout — intercept FIJI_RESULT: lines to open in Fiji
            final InputStream stdoutStream = process.getInputStream();
            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stdoutStream, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(RESULT_MARKER)) {
                            final String path = line.substring(RESULT_MARKER.length()).trim();
                            DebugLog.log("PythonExecutor", "Opening result in Fiji: %s", path);
                            SwingUtilities.invokeLater(() -> IJ.open(path));
                        } else if (line.startsWith(TEXT_MARKER)) {
                            final String payload = line.substring(TEXT_MARKER.length()).trim();
                            DebugLog.log("PythonExecutor", "Opening text window: %s", payload);
                            SwingUtilities.invokeLater(() -> openTextWindow(payload));
                        } else if (line.startsWith(TABLE_MARKER)) {
                            final String payload = line.substring(TABLE_MARKER.length()).trim();
                            DebugLog.log("PythonExecutor", "Opening results table: %s", payload);
                            SwingUtilities.invokeLater(() -> openResultsTable(payload));
                        } else if (line.startsWith(ROI_MARKER)) {
                            final String path = line.substring(ROI_MARKER.length()).trim();
                            DebugLog.log("PythonExecutor", "Opening ROIs in Fiji: %s", path);
                            SwingUtilities.invokeLater(() -> openRoisInManager(path));
                        } else {
                            stdoutConsumer.accept(line);
                        }
                    }
                } catch (IOException e) {
                    // Process was killed; normal during cancel
                }
            });
            stdoutThread.setDaemon(true);
            stdoutThread.start();

            // Stream stderr separately so warnings don't corrupt stdout
            final InputStream stderrStream = process.getErrorStream();
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stderrStream, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrConsumer.accept(line);
                    }
                } catch (IOException e) {
                    // Process was killed; normal during cancel
                }
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            // Wait with timeout
            long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_S * 1000L;
            boolean timedOut = false;
            while (process.isAlive()) {
                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly();
                    timedOut = true;
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    throw new Exception("Python execution interrupted");
                }
            }

            stdoutThread.join(2000);
            stderrThread.join(2000);

            if (timedOut) {
                throw new Exception("Python script timed out after " + DEFAULT_TIMEOUT_S + "s");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new Exception("Python process exited with code " + exitCode);
            }
        } finally {
            activeProcess = null;
            if (tempScript != null) {
                try { Files.deleteIfExists(tempScript); } catch (IOException ignored) {}
            }
        }
    }

    /** Executes a script string with no image context injected. */
    public void execute(String script) throws Exception {
        runScript(buildFullScript(script, null));
    }

    /**
     * Builds the ProcessBuilder for the given python path and script.
     * If the pythonPath looks like a conda environment, runs via
     * "conda run --no-capture-output -p <envPath> python <script>"
     * so the environment is properly activated (shared libs, PATH, etc.).
     * Otherwise runs the interpreter directly.
     */
    private static ProcessBuilder buildProcessBuilder(String pythonPath, String scriptPath) {
        java.io.File envPath = condaEnvRoot(pythonPath);
        if (envPath != null) {
            String conda = resolveCondaBin(envPath);
            if (conda != null) {
                DebugLog.log("PythonExecutor", "Running via conda: %s run -p %s python %s",
                    conda, envPath.getAbsolutePath(), scriptPath);
                return new ProcessBuilder(
                    conda, "run", "--no-capture-output",
                    "-p", envPath.getAbsolutePath(),
                    "python", "-u", scriptPath);
            }
            DebugLog.log("PythonExecutor", "Conda env root found (%s) but no conda binary — falling back to direct: %s",
                envPath.getAbsolutePath(), pythonPath);
        } else {
            DebugLog.log("PythonExecutor", "No conda env detected — running directly: %s", pythonPath);
        }
        return new ProcessBuilder(pythonPath, "-u", scriptPath);
    }

    private static java.io.File condaEnvRoot(String pythonPath) {
        java.io.File f = new java.io.File(pythonPath);
        java.io.File bin = f.getParentFile();
        if (bin == null) return null;
        java.io.File envRoot = bin.getParentFile();
        if (envRoot == null) return null;
        if (new java.io.File(envRoot, "conda-meta").isDirectory()) return envRoot;
        return null;
    }

    private static String resolveCondaBin(java.io.File envRoot) {
        String os = System.getProperty("os.name").toLowerCase();
        String condaBin = os.contains("win") ? "Scripts/conda.exe" : "bin/conda";

        java.io.File candidate = envRoot;
        for (int i = 0; i < 3; i++) {
            java.io.File conda = new java.io.File(candidate, condaBin);
            if (conda.exists()) return conda.getAbsolutePath();
            java.io.File parent = candidate.getParentFile();
            if (parent == null) break;
            candidate = parent.getName().equals("envs") ? parent.getParentFile() : parent;
            if (candidate == null) break;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // FijiCtx — unified image context for interactive and batch execution
    // -----------------------------------------------------------------------

    /**
     * Snapshot of the currently active Fiji image passed to every Python script as _fiji_ctx.
     * inputPath  — path to a TIFF Python can read with tifffile (temp file if the original
     *              format is not Python-readable, e.g. .nd2 or .czi).
     * outputDir  — output folder (batch mode) or null (interactive).
     * inputStem  — filename without extension.
     * ownsTempFile — true when we created a temp TIFF that must be deleted after the run.
     */
    public static class FijiCtx {
        public final String inputPath;
        public final String outputDir;
        public final String inputStem;
        private final File tempFile;

        FijiCtx(String inputPath, String outputDir, String inputStem, File tempFile) {
            this.inputPath = inputPath;
            this.outputDir = outputDir;
            this.inputStem = inputStem;
            this.tempFile  = tempFile;
        }

        /** Returns the Python assignment line to prepend to the script. */
        String toPythonLine() {
            String pathLit = inputPath != null
                ? "r\"" + inputPath.replace("\\", "\\\\") + "\""
                : "None";
            String outLit = outputDir != null
                ? "r\"" + outputDir.replace("\\", "\\\\") + "\""
                : "None";
            String stemLit = inputStem != null
                ? "\"" + inputStem.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : "None";
            return "_fiji_ctx = {'inputPath': " + pathLit
                 + ", 'outputDir': " + outLit
                 + ", 'inputStem': " + stemLit + "}\n";
        }

        /** Deletes the temp TIFF if this context owns it. */
        public void cleanup() {
            if (tempFile != null && tempFile.exists()) tempFile.delete();
        }
    }

    /**
     * Snapshots the currently active Fiji image into a FijiCtx.
     * If the image is already on disk in a Python-readable format (TIFF/PNG/JPG/BMP),
     * the original path is used directly (no copy). Otherwise the ImagePlus is saved
     * to a temp TIFF so that tifffile can always read it.
     *
     * Safe to call from any thread.
     *
     * @param outputDir  output folder path, or null for interactive runs
     */
    public static FijiCtx snapshotFijiCtx(String outputDir) {
        ij.ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            return new FijiCtx(null, outputDir, null, null);
        }

        // Prefer the original file path when Python can read the format directly
        ij.io.FileInfo fi = imp.getOriginalFileInfo();
        if (fi != null && fi.fileName != null && !fi.fileName.isEmpty()
                && fi.directory != null && !fi.directory.isEmpty()
                && isPythonReadable(fi.fileName)) {
            File onDisk = new File(fi.directory, fi.fileName);
            if (onDisk.exists()) {
                return new FijiCtx(onDisk.getAbsolutePath(), outputDir,
                                   stemFromName(fi.fileName), null);
            }
        }

        // Image is in-memory or an unreadable format — save a temp TIFF
        try {
            File tmp = File.createTempFile("fiji_ctx_", ".tif");
            IJ.save(imp, tmp.getAbsolutePath());
            return new FijiCtx(tmp.getAbsolutePath(), outputDir,
                               stemFromName(imp.getTitle()), tmp);
        } catch (Exception e) {
            IJ.log("PythonExecutor: could not snapshot active image: " + e.getMessage());
            return new FijiCtx(null, outputDir, null, null);
        }
    }

    private static boolean isPythonReadable(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".tif") || lower.endsWith(".tiff")
            || lower.endsWith(".png") || lower.endsWith(".jpg")
            || lower.endsWith(".jpeg") || lower.endsWith(".bmp");
    }

    private static String stemFromName(String name) {
        if (name == null || name.isEmpty()) return "image";
        String lower = name.toLowerCase();
        if (lower.endsWith(".ome.tif")) return name.substring(0, name.length() - 8);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Executes the script with a FijiCtx injected as _fiji_ctx.
     * The context's temp file (if any) is always cleaned up after execution.
     */
    public void execute(String script, FijiCtx ctx) throws Exception {
        try {
            runScript(buildFullScript(script, ctx));
        } finally {
            if (ctx != null) ctx.cleanup();
        }
    }

    /** Opens a TextWindow from a FIJI_TEXT: payload ("title|||/path/to/file"). */
    private static void openTextWindow(String payload) {
        String[] parts = payload.split("\\|\\|\\|", 2);
        String title = parts.length == 2 ? parts[0] : "Text";
        String path  = parts.length == 2 ? parts[1] : parts[0];
        try {
            String content = new String(Files.readAllBytes(Paths.get(path)),
                                        java.nio.charset.StandardCharsets.UTF_8);
            new ij.text.TextWindow(title, content, 600, 400);
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            IJ.log("fiji_show_text error: " + e.getMessage());
        }
    }

    /** Opens a ResultsTable from a FIJI_TABLE: payload ("title|||/path/to/csv"). */
    private static void openResultsTable(String payload) {
        String[] parts = payload.split("\\|\\|\\|", 2);
        String title = parts.length == 2 ? parts[0] : "Results";
        String path  = parts.length == 2 ? parts[1] : parts[0];
        try {
            ij.measure.ResultsTable rt = ij.measure.ResultsTable.open2(path);
            if (rt != null) {
                rt.show(title);
            } else {
                IJ.log("fiji_show_table: could not parse CSV at " + path);
            }
            Files.deleteIfExists(Paths.get(path));
        } catch (Exception e) {
            IJ.log("fiji_show_table error: " + e.getMessage());
        }
    }

    /** Loads a .roi or .zip ROI file from a FIJI_ROI: payload into the RoiManager. */
    private static void openRoisInManager(String path) {
        ij.plugin.frame.RoiManager rm = ij.plugin.frame.RoiManager.getInstance();
        if (rm == null) rm = new ij.plugin.frame.RoiManager();
        rm.runCommand("Open", path);
    }

    /** Kills the active Python subprocess. Safe to call from any thread. */
    public void cancel() {
        Process p = activeProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    /** Returns the configured Python interpreter path (or "python3" default). */
    public static String getPythonPath() {
        String path = Prefs.get(PREF_PYTHON_PATH, "python3");
        return (path == null || path.trim().isEmpty()) ? "python3" : path;
    }

    /** Saves the Python interpreter path to Fiji Prefs. */
    public static void setPythonPath(String path) {
        Prefs.set(PREF_PYTHON_PATH, path);
    }
}