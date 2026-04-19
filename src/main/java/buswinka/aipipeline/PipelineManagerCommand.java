package buswinka.aipipeline;

import ij.plugin.PlugIn;

public class PipelineManagerCommand implements PlugIn {
    @Override
    public void run(String arg) {
        PipelineMenuStartup.ensureAutoRunInstalled();
        PipelineManager manager = new PipelineManager();
        manager.reinjectAllMenuItems();
        PipelineManagerWindow.show(null, manager);
    }
}
