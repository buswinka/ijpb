package buswinka.aipipeline;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class ManagedVenv {

    public enum State { UNINITIALIZED, CREATING, INSTALLING, READY, FAILED }

    private static volatile State   state   = State.UNINITIALIZED;
    private static volatile boolean started = false;

    private static final String[] BASELINE_PACKAGES = {
        "numpy", "matplotlib", "scikit-image", "pillow", "tifffile"
    };

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static State getState() { return state; }

    public static File getVenvDir() {
        return new File(MachineId.appSupportDir(), "env");
    }

    public static String getPythonPath() {
        String os  = System.getProperty("os.name", "").toLowerCase();
        String rel = os.contains("win") ? "Scripts\\python.exe" : "bin/python";
        return new File(getVenvDir(), rel).getAbsolutePath();
    }

    public static synchronized void ensureAsync() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() { public void run() { doEnsure(); } }, "ijpb-managed-venv");
        t.setDaemon(true);
        t.start();
    }

    private static void doEnsure() {
        try {
            String systemPython = findSystemPython();
            if (systemPython == null) {
                DebugLog.log("ManagedVenv", "No system Python found — managed venv unavailable");
                state = State.FAILED;
                return;
            }
            DebugLog.log("ManagedVenv", "System Python: %s", systemPython);

            state = State.CREATING;
            File venvDir = getVenvDir();
            if (!new File(getPythonPath()).exists()) {
                DebugLog.log("ManagedVenv", "Creating venv at %s", venvDir.getAbsolutePath());
                if (venvDir.getParentFile() != null) venvDir.getParentFile().mkdirs();
                int venvExit = runDrain(systemPython, "-m", "venv", venvDir.getAbsolutePath());
                if (venvExit != 0) {
                    DebugLog.log("ManagedVenv", "venv creation failed (exit %d)", venvExit);
                    state = State.FAILED;
                    return;
                }
            }

            state = State.INSTALLING;
            DebugLog.log("ManagedVenv", "pip installing baseline packages");
            List<String> cmd = new ArrayList<>();
            cmd.add(getPythonPath());
            cmd.add("-m"); cmd.add("pip"); cmd.add("install"); cmd.add("-q");
            for (String pkg : BASELINE_PACKAGES) cmd.add(pkg);
            int pipExit = runDrain(cmd.toArray(new String[0]));
            if (pipExit != 0) {
                DebugLog.log("ManagedVenv", "pip install failed (exit %d)", pipExit);
                state = State.FAILED;
                return;
            }

            state = State.READY;
            DebugLog.log("ManagedVenv", "Managed venv READY: %s", getPythonPath());

            // First-run: auto-save managed venv as the configured interpreter
            String saved = PythonExecutor.getPythonPath();
            if (saved == null || saved.trim().isEmpty() || "python3".equals(saved.trim()) || "python".equals(saved.trim())) {
                PythonExecutor.setPythonPath(getPythonPath());
                try { ij.Prefs.savePreferences(); } catch (Exception e2) {
                    DebugLog.log("ManagedVenv", "Could not save preferences: %s", e2.getMessage());
                }
            }

        } catch (Exception e) {
            DebugLog.log("ManagedVenv", "Unexpected error in doEnsure: %s", e.toString());
            state = State.FAILED;
        }
    }

    // -------------------------------------------------------------------------
    // System Python discovery
    // -------------------------------------------------------------------------

    static String findSystemPython() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? findWindowsPython() : findUnixPython();
    }

    private static String findUnixPython() {
        String which = runCapture("which", "python3");
        if (which != null && new File(which).exists()) return which;
        for (String c : new String[]{
                "/usr/bin/python3",
                "/usr/local/bin/python3",
                "/opt/homebrew/bin/python3"}) {
            if (new File(c).exists()) return c;
        }
        return null;
    }

    private static String findWindowsPython() {
        String result = runCapture("py", "-3", "-c", "import sys; print(sys.executable)");
        if (result != null) return result;
        String localApp = System.getenv("LOCALAPPDATA");
        if (localApp != null) {
            File pythonRoot = new File(localApp, "Programs\\Python");
            if (pythonRoot.isDirectory()) {
                File[] versions = pythonRoot.listFiles(new FileFilter() {
                    public boolean accept(File f) { return f.isDirectory(); }
                });
                if (versions != null) {
                    for (File v : versions) {
                        File exe = new File(v, "python.exe");
                        if (exe.exists()) return exe.getAbsolutePath();
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Process helpers
    // -------------------------------------------------------------------------

    static String runCapture(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line.trim());
            }
            p.waitFor();
            String out = sb.toString().trim();
            return (p.exitValue() == 0 && !out.isEmpty()) ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    static int runDrain(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Thread drain = new Thread(new Runnable() {
                public void run() {
                    try (InputStream in = p.getInputStream()) {
                        byte[] buf = new byte[4096];
                        while (in.read(buf) != -1) {}
                    } catch (IOException ignored) {}
                }
            });
            drain.setDaemon(true);
            drain.start();
            p.waitFor();
            try { drain.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }
}
