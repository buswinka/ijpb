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

    // ensureAsync() and doEnsure() come in Task 2.

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
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }
}
