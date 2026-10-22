package buswinka.aipipeline;

import ij.IJ;
import ij.plugin.BrowserLauncher;
import ij.plugin.PlugIn;

import java.awt.GraphicsEnvironment;
import javax.swing.JOptionPane;

/**
 * Final-release notice: ImageJ Pipeline Builder has been replaced by Nissl.
 *
 * Shown the first time a plugin window is opened in each Fiji session
 * (see showOnLaunch), and on demand from Plugins > Pipelines > Moved to Nissl.
 */
public class MovedToNissl implements PlugIn {

    static final String NISSL_URL = "https://chromatype.bio";

    private static final String MESSAGE =
        "<html><body style='width:340px'>"
        + "<h2>ImageJ Pipeline Builder is now Nissl</h2>"
        + "<p>This plugin is no longer maintained and will not receive further updates.</p><br>"
        + "<p><b>Nissl</b> includes everything ImageJ Pipeline Builder did, and goes further: "
        + "better automations, figure making, interactive analysis, and more.</p><br>"
        + "<p>Get it at <b>chromatype.bio</b>.</p>"
        + "</body></html>";

    private static final String OPEN = "Open chromatype.bio";
    private static final String UNINSTALL = "Uninstall IJPB";
    private static final String CONTINUE = "Continue to IJPB";
    private static final String CLOSE = "Close";

    private static volatile boolean shownThisSession = false;

    @Override
    public void run(String arg) {
        show(new Object[]{OPEN, UNINSTALL, CLOSE});
    }

    /**
     * Call at the top of each plugin entry point. Shows the notice once per
     * Fiji session; returns false if the caller should not open its window.
     */
    static boolean showOnLaunch() {
        if (shownThisSession) return true;
        shownThisSession = true;
        Object picked = show(new Object[]{OPEN, UNINSTALL, CONTINUE});
        return picked != OPEN && picked != UNINSTALL;
    }

    private static Object show(Object[] opts) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("ImageJ Pipeline Builder has moved to Nissl: " + NISSL_URL);
            return null;
        }

        int choice = JOptionPane.showOptionDialog(
            null, MESSAGE, "ImageJ Pipeline Builder has moved",
            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
            null, opts, opts[0]);
        if (choice < 0) return null;
        Object picked = opts[choice];

        if (picked == OPEN) {
            try {
                BrowserLauncher.openURL(NISSL_URL);
            } catch (Exception e) {
                IJ.log("Could not open browser. Visit " + NISSL_URL);
            }
        } else if (picked == UNINSTALL) {
            new UninstallCommand().run("");
        }
        return picked;
    }
}
