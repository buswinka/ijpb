package buswinka.aipipeline;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Discovers and validates Python environments for the Pro tier.
 * Builds a capability manifest (Python version, installed packages, GPU availability)
 * by running a probe script. stderr is kept separate from stdout so that
 * numpy/torch import warnings don't corrupt the JSON output.
 */
public class PythonEnvManager {

    private static final String PROBE_SCRIPT =
        "import sys, json\n" +
        "info = {'python_version': sys.version.split()[0], 'gpu': 'none'}\n" +
        "pkgs = ['numpy', 'scipy', 'skimage', 'cellpose', 'pandas',\n" +
        "        'torch', 'tensorflow', 'PIL', 'cv2', 'pyimagej']\n" +
        "for pkg in pkgs:\n" +
        "    try:\n" +
        "        mod = __import__(pkg)\n" +
        "        info[pkg] = getattr(mod, '__version__', 'unknown')\n" +
        "    except ImportError:\n" +
        "        pass\n" +
        "try:\n" +
        "    import torch\n" +
        "    info['gpu'] = 'CUDA ' + str(torch.version.cuda) if torch.cuda.is_available() else 'none'\n" +
        "except ImportError:\n" +
        "    pass\n" +
        "try:\n" +
        "    import tensorflow as tf\n" +
        "    gpus = tf.config.list_physical_devices('GPU')\n" +
        "    if gpus and info.get('gpu') == 'none':\n" +
        "        info['gpu'] = 'TF GPU available'\n" +
        "except ImportError:\n" +
        "    pass\n" +
        "print(json.dumps(info))\n";

    /**
     * Runs the probe script and returns a capability manifest string.
     * Returns a plain description string (not raw JSON) suitable for the LLM system prompt.
     * Returns a minimal fallback if the probe fails.
     */
    public static String buildCapabilityManifest(String pythonPath) {
        Path tempScript = null;
        try {
            tempScript = Files.createTempFile("aipipeline_probe_", ".py");
            Files.write(tempScript, PROBE_SCRIPT.getBytes("UTF-8"));

            ProcessBuilder pb = new ProcessBuilder(pythonPath, tempScript.toString());
            pb.redirectErrorStream(false); // CRITICAL: keep stderr separate from stdout

            Process process = pb.start();

            // Read stdout only — stderr warnings (e.g. from numpy/torch) are discarded
            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) stdout.append(line);
            }

            // Drain stderr silently (prevents process blocking on full stderr pipe)
            Thread stderrDrain = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    InputStream err = process.getErrorStream();
                    while (err.read(buf) != -1) {} // discard
                } catch (IOException ignored) {}
            });
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            boolean finished = false;
            long deadline = System.currentTimeMillis() + 30_000;
            while (!finished && System.currentTimeMillis() < deadline) {
                try { process.waitFor(); finished = true; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (!finished) process.destroyForcibly();

            return parseManifest(stdout.toString());

        } catch (Exception e) {
            return "Python environment: probe failed (" + e.getMessage() + ")";
        } finally {
            if (tempScript != null) {
                try { Files.deleteIfExists(tempScript); } catch (IOException ignored) {}
            }
        }
    }

    /** Parses the JSON probe output into a human-readable manifest for the LLM. */
    private static String parseManifest(String json) {
        if (json == null || json.trim().isEmpty()) return "Python environment: no output from probe";
        StringBuilder sb = new StringBuilder("=== Python Environment ===\n");
        String version = JsonHelper.extractField(json, "python_version");
        sb.append("Python: ").append(version != null ? version : "unknown").append("\n");

        // Extract GPU info
        String gpu = JsonHelper.extractField(json, "gpu");
        sb.append("GPU: ").append(gpu != null ? gpu : "none").append("\n");

        // List detected packages (packages is a nested object — scan for known package names)
        sb.append("Installed packages:");
        String[] knownPkgs = {"numpy", "scipy", "skimage", "cellpose", "stardist",
                              "torch", "tensorflow", "PIL", "cv2", "pyimagej"};
        boolean any = false;
        for (String pkg : knownPkgs) {
            String ver = JsonHelper.extractField(json, pkg);
            if (ver != null) {
                sb.append("\n  ").append(pkg).append(" ").append(ver);
                any = true;
            }
        }
        if (!any) sb.append(" none detected");
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Returns true if 'conda' is on the system PATH.
     * Used to offer guided environment creation.
     */
    public static boolean hasCondaOnPath() {
        try {
            ProcessBuilder pb = new ProcessBuilder("conda", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates that the given path points to a working Python interpreter.
     * Returns the Python version string on success, or null on failure.
     */
    public static String validateInterpreter(String pythonPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line);
            }
            p.waitFor();
            return p.exitValue() == 0 ? out.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
