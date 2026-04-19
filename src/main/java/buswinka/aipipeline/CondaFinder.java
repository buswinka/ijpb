package buswinka.aipipeline;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Discovers conda/miniconda installations and lists their Python environments.
 * Checks common install locations on macOS, Windows, and Linux.
 */
public class CondaFinder {

    public static class CondaEnv {
        public final String name;
        public final String pythonPath;

        CondaEnv(String name, String pythonPath) {
            this.name = name;
            this.pythonPath = pythonPath;
        }

        @Override
        public String toString() { return name; }
    }

    public static File findConda() {
        List<String> candidates = new ArrayList<>();
        String home = System.getProperty("user.home");
        String os   = System.getProperty("os.name").toLowerCase();

        candidates.add(home + "/miniconda3");
        candidates.add(home + "/anaconda3");
        candidates.add(home + "/miniforge3");
        candidates.add(home + "/mambaforge");

        if (os.contains("win")) {
            String localApp = System.getenv("LOCALAPPDATA");
            if (localApp != null) {
                candidates.add(localApp + "\\miniconda3");
                candidates.add(localApp + "\\anaconda3");
            }
            candidates.add("C:\\ProgramData\\miniconda3");
        } else if (os.contains("mac")) {
            // Homebrew Caskroom installs miniconda under a version subdirectory:
            // /opt/homebrew/Caskroom/miniconda/<version>/base
            addHomebrewCaskCandidates(candidates, "/opt/homebrew/Caskroom/miniconda");
            addHomebrewCaskCandidates(candidates, "/opt/homebrew/Caskroom/anaconda");
            candidates.add("/opt/miniconda3");
        } else {
            candidates.add("/opt/conda");
            candidates.add("/opt/miniconda3");
        }

        DebugLog.log("CondaFinder", "Searching %d candidates on %s", candidates.size(), os);
        for (String path : candidates) {
            if (path == null) continue;
            File dir = new File(path);
            boolean exists = dir.isDirectory();
            boolean hasBin = exists && hasCondaBin(dir, os);
            DebugLog.log("CondaFinder", "  %s — exists=%b hasBin=%b", path, exists, hasBin);
            if (hasBin) {
                DebugLog.log("CondaFinder", "Found conda root: %s", dir.getAbsolutePath());
                return dir;
            }
        }
        DebugLog.log("CondaFinder", "No conda installation found");
        return null;
    }

    /** Scans a Homebrew Caskroom directory for versioned subdirs containing a conda root. */
    private static void addHomebrewCaskCandidates(List<String> candidates, String caskroomPath) {
        File caskroom = new File(caskroomPath);
        if (!caskroom.isDirectory()) return;
        File[] versions = caskroom.listFiles(File::isDirectory);
        if (versions == null) return;
        Arrays.sort(versions, (a, b) -> b.getName().compareTo(a.getName())); // newest first
        for (File v : versions) {
            candidates.add(new File(v, "base").getAbsolutePath());
        }
    }

    static boolean hasCondaBin(File condaDir, String os) {
        String bin = os.contains("win") ? "Scripts/conda.exe" : "bin/conda";
        return new File(condaDir, bin).exists();
    }

    /**
     * Returns all Python environments found under the conda root, including base.
     * Each entry has a display name and the absolute path to the python interpreter.
     * Returns an empty list if no conda installation is found.
     */
    public static List<CondaEnv> listEnvironments() {
        List<CondaEnv> envs = new ArrayList<>();
        File condaRoot = findConda();
        if (condaRoot == null) return envs;

        String os = System.getProperty("os.name").toLowerCase();

        DebugLog.log("CondaFinder", "Listing envs under: %s", condaRoot.getAbsolutePath());

        // Base environment
        String basePython = resolvePython(condaRoot, os);
        DebugLog.log("CondaFinder", "  base python: %s", basePython != null ? basePython : "not found");
        if (basePython != null) {
            envs.add(new CondaEnv("base (" + condaRoot.getName() + ")", basePython));
        }

        // Named environments under envs/
        File envsDir = new File(condaRoot, "envs");
        DebugLog.log("CondaFinder", "  envs dir: %s (exists=%b)", envsDir.getAbsolutePath(), envsDir.isDirectory());
        if (envsDir.isDirectory()) {
            File[] entries = envsDir.listFiles(File::isDirectory);
            if (entries != null) {
                Arrays.sort(entries, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File envDir : entries) {
                    String python = resolvePython(envDir, os);
                    DebugLog.log("CondaFinder", "    env %s -> python: %s", envDir.getName(), python != null ? python : "not found");
                    if (python != null) envs.add(new CondaEnv(envDir.getName(), python));
                }
            }
        }

        DebugLog.log("CondaFinder", "Total envs found: %d", envs.size());
        return envs;
    }

    private static String resolvePython(File envRoot, String os) {
        String rel = os.contains("win") ? "python.exe" : "bin/python";
        File f = new File(envRoot, rel);
        return f.exists() ? f.getAbsolutePath() : null;
    }
}
