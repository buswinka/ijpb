package buswinka.aipipeline;

import ij.plugin.PlugIn;

public class BatchRunnerCommand implements PlugIn {
    @Override
    public void run(String arg) {
        if (!MovedToNissl.showOnLaunch()) return;
        PipelineManager manager = new PipelineManager();
        BatchRunnerWindow.show(null, manager);
    }
}