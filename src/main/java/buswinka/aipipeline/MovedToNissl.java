package buswinka.aipipeline;

import ij.IJ;
import ij.Prefs;
import ij.plugin.BrowserLauncher;
import ij.plugin.PlugIn;

import java.awt.GraphicsEnvironment;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Final-release notice: ImageJ Pipeline Builder has been replaced by Nissl.
 *
 * Shown once per Fiji launch from PipelineMenuStartup until the user chooses
 * "Don't show again". Also registered as Plugins > Pipelines > Moved to Nissl
 * so it can always be reopened.
 */
public class MovedToNissl implements PlugIn {

    static final String NISSL_URL = "https://chromatype.bio";

    private static final String PREF_DISMISSED = "ijpb.movedToNissl.dismissed";
    private static final String TAG = "MovedToNissl";

    private static final String MESSAGE =
        "<html><body style='width:340px'>"
        + "<h2>ImageJ Pipeline Builder is now Nissl</h2>"
        + "<p>This plugin is no longer maintained and will not receive further updates.</p><br>"
        + "<p><b>Nissl</b> includes everything ImageJ Pipeline Builder did, and goes further: "
        + "better automations, figure making, interactive analysis, and more.</p><br>"
        + "<p>Get it at <b>chromatype.bio</b>.</p>"
        + "</body></html>";

    @Override
    public void run(String arg) {
        show(false);
    }

    /** Shows the notice at startup unless the user has opted out. */
    static void showAtStartup() {
        if (Prefs.get(PREF_DISMISSED, false)) {
            DebugLog.log(TAG, "notice previously dismissed — skipping");
            return;
        }
        SwingUtilities.invokeLater(() -> show(true));
    }

    private static void show(boolean atStartup) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("ImageJ Pipeline Builder has moved to Nissl: " + NISSL_URL);
            return;
        }

        final String open = "Open chromatype.bio", uninstall = "Uninstall IJPB";
        final String later = "Later", dismiss = "Don't show again", close = "Close";
        Object[] opts = atStartup
            ? new Object[]{open, uninstall, later, dismiss}
            : new Object[]{open, uninstall, close};

        int choice = JOptionPane.showOptionDialog(
            null, MESSAGE, "ImageJ Pipeline Builder has moved",
            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
            null, opts, opts[0]);
        if (choice < 0) return;
        Object picked = opts[choice];

        if (picked == open) {
            try {
                BrowserLauncher.openURL(NISSL_URL);
            } catch (Exception e) {
                IJ.log("Could not open browser. Visit " + NISSL_URL);
            }
        } else if (picked == uninstall) {
            new UninstallCommand().run("");
        } else if (picked == dismiss) {
            Prefs.set(PREF_DISMISSED, true);
            Prefs.savePreferences();
        }
    }
}
