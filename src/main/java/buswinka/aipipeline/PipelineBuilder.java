package buswinka.aipipeline;

import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class PipelineBuilder implements PlugIn {

    /**
     * Guards against opening more than one ChatWindow at a time.
     * Concurrent PipelineManager instances share the same pipeline directory and AWT menu —
     * concurrent injectMenuItem/removeMenuItem calls can produce duplicate menu entries.
     */
    private static volatile boolean running = false;

    @Override
    public void run(String arg) {
        if (running) {
            IJ.showMessage("AI Pipeline Builder", "AI Pipeline Builder is already open.");
            return;
        }
        running = true;

        PipelineMenuStartup.ensureAutoRunInstalled();
        LLMClient llm = PreferencesDialog.buildClientFromPrefs();
        PipelineManager manager = new PipelineManager();
        manager.reinjectAllMenuItems();
        RateLimiter rateLimiter = new RateLimiter();

        SwingUtilities.invokeLater(() -> {
            ChatWindow w = new ChatWindow(llm, manager, rateLimiter);
            w.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    running = false;
                }
            });
        });
    }
}