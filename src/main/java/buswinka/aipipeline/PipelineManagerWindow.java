package buswinka.aipipeline;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Modal dialog for browsing, editing, and deleting saved pipelines.
 */
public class PipelineManagerWindow extends JDialog {

    private final PipelineManager manager;
    private final DefaultListModel<File> listModel;
    private final JList<File> fileList;
    private final JButton editButton;
    private final JButton deleteButton;

    public PipelineManagerWindow(Frame owner, PipelineManager manager) {
        super(owner, "Manage Pipelines", true);
        this.manager = manager;

        editButton   = new JButton("Edit / Resume");
        deleteButton = new JButton("Delete");
        JButton closeButton = new JButton("Close");

        editButton.setEnabled(false);
        deleteButton.setEnabled(false);

        listModel = new DefaultListModel<>();
        fileList  = new JList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setCellRenderer(new PipelineCellRenderer());
        fileList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) onEdit();
            }
        });
        fileList.addListSelectionListener(e -> {
            boolean sel = fileList.getSelectedValue() != null;
            editButton.setEnabled(sel);
            deleteButton.setEnabled(sel);
        });

        editButton.addActionListener(e -> onEdit());
        deleteButton.addActionListener(e -> onDelete());
        closeButton.addActionListener(e -> dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttonPanel.add(editButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(closeButton);

        JScrollPane scroll = new JScrollPane(fileList);
        scroll.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));

        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        refreshList();

        setSize(460, 360);
        setLocationRelativeTo(owner);
    }

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    private void onEdit() {
        File selected = fileList.getSelectedValue();
        if (selected == null) return;
        try {
            GeneratedPipeline pipeline = manager.loadPipeline(selected);
            if (pipeline == null) {
                JOptionPane.showMessageDialog(this, "Could not parse pipeline file.",
                        "Load Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (pipeline.getScript() == null || pipeline.getScript().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Pipeline file \"" + selected.getName() + "\" has no script content and cannot be loaded.",
                        "Empty Pipeline", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LLMClient llm         = PreferencesDialog.buildClientFromPrefs();
            RateLimiter rateLimiter = new RateLimiter();
            dispose();
            ChatWindow.showWithPipeline(llm, manager, rateLimiter, pipeline, selected);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to load pipeline: " + e.getMessage(),
                    "Load Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onDelete() {
        File selected = fileList.getSelectedValue();
        if (selected == null) return;
        String displayName = displayName(selected);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete \"" + displayName + "\"? This cannot be undone.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        // Remove from Fiji menu (load title first)
        try {
            GeneratedPipeline p = manager.loadPipeline(selected);
            if (p != null) manager.removeMenuItem(p.getTitle());
        } catch (IOException ignored) {}

        manager.trashPipeline(selected);
        refreshList();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private void refreshList() {
        listModel.clear();
        File dir = manager.getPipelinesDir();
        File[] files = dir.listFiles(f -> f.getName().endsWith(".ijm") || f.getName().endsWith(".py"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : files) listModel.addElement(f);
        }
        editButton.setEnabled(false);
        deleteButton.setEnabled(false);
    }

    private static String displayName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ');
    }

    // ---------------------------------------------------------------------------
    // Cell renderer
    // ---------------------------------------------------------------------------

    private static class PipelineCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            File file  = (File) value;
            String name  = displayName(file);
            boolean isPy = file.getName().endsWith(".py");
            String badge = isPy ? "PY" : "IJM";
            Color badgeColor = isPy ? new Color(100, 60, 180) : new Color(0, 100, 160);
            String badgeHex  = String.format("#%02x%02x%02x",
                    badgeColor.getRed(), badgeColor.getGreen(), badgeColor.getBlue());
            setText("<html>" + name
                    + " &nbsp;<font color='" + badgeHex + "'><small><b>[" + badge + "]</b></small></font></html>");
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            return this;
        }
    }

    // ---------------------------------------------------------------------------
    // Static factory
    // ---------------------------------------------------------------------------

    public static void show(Frame owner, PipelineManager manager) {
        SwingUtilities.invokeLater(() -> new PipelineManagerWindow(owner, manager).setVisible(true));
    }
}