package buswinka.aipipeline;

import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class UninstallCommand implements PlugIn {

    @Override
    public void run(String arg) {
        Frame owner = IJ.getInstance();
        JDialog dialog = new JDialog(owner, "Uninstall Pipeline Builder", true);

        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 10, 18));

        JLabel message = new JLabel("<html><body style='width:320px'>"
                + "<b>Confirm Uninstallation of Pipeline Builder</b><br><br>"
                + "Your pipelines will <b>not</b> be deleted, but will be inaccessible "
                + "until reinstallation."
                + "</body></html>");
        panel.add(message, BorderLayout.CENTER);

        JButton uninstallButton = new JButton("Uninstall");
        JButton cancelButton    = new JButton("Cancel");
        uninstallButton.setForeground(new Color(160, 30, 30));

        uninstallButton.addActionListener(e -> {
            dialog.dispose();
            doUninstall();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
        buttons.add(uninstallButton);
        buttons.add(cancelButton);
        panel.add(buttons, BorderLayout.SOUTH);

        dialog.getRootPane().registerKeyboardAction(
            ev -> dialog.dispose(),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().setDefaultButton(cancelButton);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private void doUninstall() {
        String ijDir = IJ.getDirectory("imagej");
        List<String> failed = new ArrayList<>();

        // Remove AutoRun macro
        if (ijDir != null) {
            File autoRun = new File(ijDir,
                "scripts" + File.separator + "Plugins" + File.separator
                + "AutoRun" + File.separator + PipelineMenuStartup.AUTORUN_MACRO_NAME);
            deleteFile(autoRun, failed);
        }

        // Remove plugin jar
        if (ijDir != null) {
            File jarsDir = new File(ijDir, "plugins" + File.separator + "jars");
            File[] jars = jarsDir.listFiles(
                f -> f.getName().startsWith("ImageJPipelineBuilder") && f.getName().endsWith(".jar"));
            if (jars != null) {
                for (File jar : jars) deleteFile(jar, failed);
            }
        }

        if (failed.isEmpty()) {
            JOptionPane.showMessageDialog(IJ.getInstance(),
                "Uninstallation complete. Please restart Fiji.",
                "Uninstall Complete", JOptionPane.INFORMATION_MESSAGE);
        } else {
            StringBuilder sb = new StringBuilder(
                "Some files could not be removed automatically.\nPlease delete them manually:\n\n");
            for (String s : failed) sb.append("  ").append(s).append("\n");
            sb.append("\nThen restart Fiji.");
            JOptionPane.showMessageDialog(IJ.getInstance(),
                sb.toString(), "Uninstall Incomplete", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void deleteFile(File f, List<String> failed) {
        if (!f.exists()) return;
        try {
            Files.delete(f.toPath());
        } catch (Exception e) {
            failed.add(f.getAbsolutePath());
        }
    }
}