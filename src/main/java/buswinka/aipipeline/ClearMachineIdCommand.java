package buswinka.aipipeline;

import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.*;

/**
 * Debug-only command: deletes all machine ID cache files so the fingerprinting
 * state can be reset during development and testing.
 *
 * Registered as: Plugins > Utilities > Pipelines > Reset Internal Plugin Cache
 * Only functional when {@link BuildConfig#DEBUG} is true.
 */
public class ClearMachineIdCommand implements PlugIn {

    @Override
    public void run(String arg) {
        if (!BuildConfig.DEBUG) return;

        int choice = JOptionPane.showConfirmDialog(
            IJ.getInstance(),
            "Reset the internal plugin cache?\nThis resets the device fingerprint and cannot be undone.",
            "Reset Internal Plugin Cache",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

        if (choice != JOptionPane.YES_OPTION) return;

        MachineId.clearMachineId();
        JOptionPane.showMessageDialog(
            IJ.getInstance(),
            "Machine ID cleared.",
            "Done",
            JOptionPane.INFORMATION_MESSAGE);
    }
}