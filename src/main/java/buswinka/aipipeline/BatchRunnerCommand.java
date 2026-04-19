package buswinka.aipipeline;

import ij.plugin.PlugIn;

public class BatchRunnerCommand implements PlugIn {
    @Override
    public void run(String arg) {
        PipelineManager manager = new PipelineManager();
        BatchRunnerWindow.show(null, manager);
    }
}