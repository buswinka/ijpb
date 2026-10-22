package buswinka.aipipeline;

import ij.plugin.PlugIn;
import java.awt.Frame;

public class PreferencesCommand implements PlugIn {
    @Override
    public void run(String arg) {
        if (!MovedToNissl.showOnLaunch()) return;
        new PreferencesDialog((Frame) null).setVisible(true);
    }
}