package buswinka.aipipeline;

import ij.IJ;
import ij.Prefs;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Self-updater for ImageJPipelineBuilder.
 *
 * Flow:
 *   1. Fetch manifest JSON from GitHub Pages.
 *   2. Compare semver to CURRENT_VERSION.
 *   3. If newer, prompt user.
 *   4. Download JAR to temp, verify SHA-256, atomic-move into ImageJ update/ folder.
 *   5. ImageJ swaps the JAR on next restart.
 */
public final class PluginUpdater {

    /**
     * Plugin version — bump this string on every release, matching the version
     * field in docs/latest.json and the GitHub release tag.
     */
    public static final String CURRENT_VERSION = "0.1.15";

    /** Must stay constant so ImageJ's update-folder swap logic can find the file. */
    public static final String JAR_NAME = "ImageJPipelineBuilder.jar";

    /** Manifest hosted on GitHub Pages (docs/ folder, branch main). */
    private static final String MANIFEST_URL =
        "https://buswinka.github.io/ijpb/latest.json";

    private static final String PREF_LAST_CHECK = "ijpb.updater.lastCheck";
    private static final String PREF_ENABLED    = "ijpb.updater.enabled";
    private static final String PREF_SKIP_VER   = "ijpb.updater.skipVersion";

    private static final long CHECK_INTERVAL_MS  = TimeUnit.HOURS.toMillis(24);
    private static final int  CONNECT_TIMEOUT_MS = 4_000;
    private static final int  READ_TIMEOUT_MS    = 8_000;

    private PluginUpdater() {}

    /**
     * Call this once from plugin init. Non-blocking — spawns a daemon thread.
     * Never throws; all errors are logged via IJ.log and silently swallowed.
     */
    public static void checkAsync() {
        if (!Prefs.get(PREF_ENABLED, true)) return;

        long last = (long) Prefs.get(PREF_LAST_CHECK, 0.0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;

        Thread t = new Thread(PluginUpdater::runCheck, "IJPB-UpdateCheck");
        t.setDaemon(true);
        t.start();
    }

    // ---- internal ----

    private static void runCheck() {
        try {
            Manifest m = fetchManifest();
            Prefs.set(PREF_LAST_CHECK, (double) System.currentTimeMillis());
            Prefs.savePreferences();

            if (compareSemver(m.version, CURRENT_VERSION) <= 0) return;

            String skip = Prefs.get(PREF_SKIP_VER, "");
            if (m.version.equals(skip) && !m.mandatory) return;

            SwingUtilities.invokeLater(() -> promptAndInstall(m));
        } catch (Exception e) {
            IJ.log("[IJPB updater] check failed: " + e.getMessage());
        }
    }

    private static void promptAndInstall(Manifest m) {
        String msg = String.format(
            "ImageJPipelineBuilder %s is available (you have %s).%n%nInstall on next restart?",
            m.version, CURRENT_VERSION);

        Object[] opts = m.mandatory
            ? new Object[]{"Install"}
            : new Object[]{"Install", "Later", "Skip this version"};

        int choice = JOptionPane.showOptionDialog(
            null, msg, "Plugin update available",
            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
            null, opts, opts[0]);

        if (choice == 2) {
            Prefs.set(PREF_SKIP_VER, m.version);
            Prefs.savePreferences();
            return;
        }
        if (choice != 0) return;

        new Thread(() -> download(m), "IJPB-Download").start();
    }

    private static void download(Manifest m) {
        try {
            Path updateDir = Paths.get(IJ.getDirectory("imagej"), "update");
            Files.createDirectories(updateDir);

            Path tmp    = updateDir.resolve(JAR_NAME + ".part");
            Path finalP = updateDir.resolve(JAR_NAME);

            try (InputStream in = openStream(m.url)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

            String got = sha256(tmp);
            if (!got.equalsIgnoreCase(m.sha256)) {
                Files.deleteIfExists(tmp);
                throw new IOException("SHA-256 mismatch: expected " + m.sha256 + " got " + got);
            }

            Files.move(tmp, finalP,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                null, "Update staged. Restart ImageJ to apply.",
                "Update ready", JOptionPane.INFORMATION_MESSAGE));
        } catch (Exception e) {
            IJ.log("[IJPB updater] download failed: " + e.getMessage());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                null, "Update download failed: " + e.getMessage(),
                "Update failed", JOptionPane.WARNING_MESSAGE));
        }
    }

    // ---- helpers ----

    private static Manifest fetchManifest() throws IOException {
        try (InputStream in = openStream(MANIFEST_URL)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Manifest.parse(out.toString("UTF-8"));
        }
    }

    private static InputStream openStream(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
            "ImageJPipelineBuilder-Updater/" + CURRENT_VERSION);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new IOException("HTTP " + code + " for " + url);
        return c.getInputStream();
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Compares dotted numeric versions. Returns &gt;0 if a&gt;b, &lt;0 if a&lt;b, 0 if equal. */
    static int compareSemver(String a, String b) {
        String[] pa = a.split("[.\\-+]"), pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int ai = i < pa.length ? parseOrZero(pa[i]) : 0;
            int bi = i < pb.length ? parseOrZero(pb[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int parseOrZero(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Minimal manifest parser — avoids adding a JSON dependency. */
    static final class Manifest {
        String version, url, sha256;
        boolean mandatory;

        static Manifest parse(String json) {
            Manifest m = new Manifest();
            m.version   = str(json, "version");
            m.url       = str(json, "url");
            m.sha256    = str(json, "sha256");
            m.mandatory = "true".equalsIgnoreCase(raw(json, "mandatory"));
            if (m.version == null || m.url == null || m.sha256 == null)
                throw new IllegalArgumentException("manifest missing required field");
            return m;
        }

        private static String str(String json, String key) {
            String r = raw(json, key);
            if (r == null) return null;
            return r.replaceAll("^\"|\"$", "");
        }

        private static String raw(String json, String key) {
            java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\"[^\"]*\"|true|false|\\d+)")
                .matcher(json);
            return mm.find() ? mm.group(1) : null;
        }
    }
}
