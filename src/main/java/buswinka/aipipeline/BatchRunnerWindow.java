// src/main/java/buswinka/aipipeline/BatchRunnerWindow.java
package buswinka.aipipeline;

import ij.IJ;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

public class BatchRunnerWindow extends JDialog {

    private final PipelineManager manager;

    // Pipeline list
    private final DefaultListModel<File> pipelineListModel = new DefaultListModel<>();
    private final JList<File> pipelineList;

    // Folder pickers
    private final JTextField inputFolderField  = new JTextField();
    private final JTextField outputFolderField = new JTextField();
    private final JLabel fileCountLabel        = new JLabel(" ");

    // Options
    private final JRadioButton firstSeriesBtn  = new JRadioButton("First only", true);
    private final JRadioButton allSeriesBtn    = new JRadioButton("All");
    private final JRadioButton overwriteBtn    = new JRadioButton("Overwrite", true);
    private final JRadioButton skipBtn         = new JRadioButton("Skip");

    // Progress + log
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel       statusLabel = new JLabel(" ");
    private final JTextArea    logArea     = new JTextArea(10, 50);

    // Buttons
    private final JButton runButton     = new JButton("Run");
    private final JButton cancelButton  = new JButton("Cancel");
    private final JButton saveLogButton = new JButton("Save Log");
    private final JButton closeButton   = new JButton("Close");

    // Runtime state
    private BatchRunner activeRunner;
    private BatchWorker activeWorker;
    private final StringBuilder logBuffer = new StringBuilder();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public BatchRunnerWindow(Frame owner, PipelineManager manager) {
        super(owner, "Batch Run Pipeline", false);
        this.manager = manager;

        pipelineList = new JList<>(pipelineListModel);
        pipelineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pipelineList.setCellRenderer(new PipelineCellRenderer());
        pipelineList.addListSelectionListener(e -> updateRunButton());

        // Input folder field: read-only
        inputFolderField.setEditable(false);
        outputFolderField.setEditable(false);

        JButton inputBrowse  = new JButton("...");
        JButton outputBrowse = new JButton("...");
        inputBrowse.addActionListener(e -> browseInputFolder());
        outputBrowse.addActionListener(e -> browseOutputFolder());

        // Options radio groups
        ButtonGroup seriesGroup = new ButtonGroup();
        seriesGroup.add(firstSeriesBtn);
        seriesGroup.add(allSeriesBtn);
        ButtonGroup conflictGroup = new ButtonGroup();
        conflictGroup.add(overwriteBtn);
        conflictGroup.add(skipBtn);

        // Progress bar
        progressBar.setStringPainted(false);

        // Log area
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        // Buttons
        runButton.setEnabled(false);
        cancelButton.setEnabled(false);
        runButton.addActionListener(e -> onRun());
        cancelButton.addActionListener(e -> onCancel());
        saveLogButton.addActionListener(e -> onSaveLog());
        closeButton.addActionListener(e -> dispose());

        // Layout
        setLayout(new BorderLayout(6, 6));
        add(buildTopPanel(inputBrowse, outputBrowse), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);

        refreshPipelineList();

        setSize(600, 620);
        setLocationRelativeTo(owner);
    }

    // -----------------------------------------------------------------------
    // Layout helpers
    // -----------------------------------------------------------------------

    private JPanel buildTopPanel(JButton inputBrowse, JButton outputBrowse) {
        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;

        // Pipeline label + list
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; c.weightx = 1.0;
        top.add(new JLabel("Select Pipeline:"), c);

        c.gridy = 1;
        JScrollPane listScroll = new JScrollPane(pipelineList);
        listScroll.setPreferredSize(new Dimension(0, 120));
        top.add(listScroll, c);

        // Input folder
        c.gridy = 2; c.gridwidth = 1; c.weightx = 0;
        top.add(new JLabel("Input Folder:"), c);
        c.gridx = 1; c.weightx = 1.0;
        top.add(inputFolderField, c);
        c.gridx = 2; c.weightx = 0;
        top.add(inputBrowse, c);

        // File count
        c.gridx = 1; c.gridy = 3; c.gridwidth = 2;
        fileCountLabel.setForeground(Color.GRAY);
        top.add(fileCountLabel, c);

        // Output folder
        c.gridx = 0; c.gridy = 4; c.gridwidth = 1;
        top.add(new JLabel("Output Folder:"), c);
        c.gridx = 1; c.weightx = 1.0;
        top.add(outputFolderField, c);
        c.gridx = 2; c.weightx = 0;
        top.add(outputBrowse, c);

        // Options panel
        c.gridx = 0; c.gridy = 5; c.gridwidth = 3;
        top.add(buildOptionsPanel(), c);

        return top;
    }

    private JPanel buildOptionsPanel() {
        JPanel p = new JPanel(new GridLayout(2, 2, 4, 2));
        p.setBorder(new TitledBorder("Options"));
        p.add(new JLabel("Multi-series files:"));
        JPanel seriesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        seriesPanel.add(firstSeriesBtn);
        seriesPanel.add(allSeriesBtn);
        p.add(seriesPanel);
        p.add(new JLabel("If output exists:"));
        JPanel conflictPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        conflictPanel.add(overwriteBtn);
        conflictPanel.add(skipBtn);
        p.add(conflictPanel);
        return p;
    }

    private JPanel buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 8));

        JPanel progressPanel = new JPanel(new BorderLayout(4, 2));
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(statusLabel, BorderLayout.SOUTH);
        center.add(progressPanel, BorderLayout.NORTH);
        center.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return center;
    }

    private JPanel buildButtonPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        p.add(runButton);
        p.add(cancelButton);
        p.add(saveLogButton);
        p.add(closeButton);
        return p;
    }

    // -----------------------------------------------------------------------
    // Pipeline list
    // -----------------------------------------------------------------------

    private void refreshPipelineList() {
        pipelineListModel.clear();
        File dir = manager.getPipelinesDir();
        File[] files = dir.listFiles(f -> f.getName().endsWith(".ijm") || f.getName().endsWith(".py"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : files) pipelineListModel.addElement(f);
        }
    }

    // -----------------------------------------------------------------------
    // Folder browsing
    // -----------------------------------------------------------------------

    private void browseInputFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File dir = chooser.getSelectedFile();
        inputFolderField.setText(dir.getAbsolutePath());

        // Count matching files
        File[] all = dir.listFiles();
        int count = 0;
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && BatchRunner.matchesImageExtension(f)) count++;
            }
        }
        if (count == 0) {
            fileCountLabel.setText("No matching files found");
            fileCountLabel.setForeground(Color.RED);
        } else {
            fileCountLabel.setText("Found " + count + " matching files");
            fileCountLabel.setForeground(new Color(0, 128, 0));
        }

        // Default output folder
        if (outputFolderField.getText().isEmpty()) {
            outputFolderField.setText(dir.getAbsolutePath() + "_results");
        }

        updateRunButton();
    }

    private void browseOutputFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        outputFolderField.setText(chooser.getSelectedFile().getAbsolutePath());
        updateRunButton();
    }

    // -----------------------------------------------------------------------
    // Run button enable/disable logic
    // -----------------------------------------------------------------------

    private void updateRunButton() {
        boolean pipelineSelected = pipelineList.getSelectedValue() != null;
        boolean inputSelected    = !inputFolderField.getText().isEmpty();
        boolean outputSelected   = !outputFolderField.getText().isEmpty();
        boolean hasFiles         = fileCountLabel.getForeground().equals(new Color(0, 128, 0));
        runButton.setEnabled(pipelineSelected && inputSelected && outputSelected && hasFiles);
    }

    // -----------------------------------------------------------------------
    // Cell renderer (same visual style as PipelineManagerWindow)
    // -----------------------------------------------------------------------

    private static class PipelineCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            File file    = (File) value;
            String name  = displayName(file);
            boolean isPy = file.getName().endsWith(".py");
            String badge = isPy ? "PY" : "IJM";
            Color badgeColor = isPy ? new Color(100, 60, 180) : new Color(0, 100, 160);
            String hex = String.format("#%02x%02x%02x",
                badgeColor.getRed(), badgeColor.getGreen(), badgeColor.getBlue());
            setText("<html>" + name
                + " &nbsp;<font color='" + hex + "'><small><b>[" + badge + "]</b></small></font></html>");
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            return this;
        }

        private static String displayName(File f) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ');
        }
    }

    // -----------------------------------------------------------------------
    // Static factory
    // -----------------------------------------------------------------------

    public static void show(Frame owner, PipelineManager manager) {
        SwingUtilities.invokeLater(() -> new BatchRunnerWindow(owner, manager).setVisible(true));
    }

    // -----------------------------------------------------------------------
    // Run / Cancel / Save Log
    // -----------------------------------------------------------------------

    private void onRun() {
        File pipelineFile = pipelineList.getSelectedValue();
        if (pipelineFile == null) return;

        // Pre-run confirmation: warn if images are open
        if (ij.WindowManager.getImageCount() > 0) {
            int choice = JOptionPane.showConfirmDialog(this,
                "Starting a batch run will close all currently open images, ROI sets, and results tables.\nContinue?",
                "Confirm Batch Run",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }

        // Close all open images / clear ROI manager / clear Results table
        IJ.run("Close All");
        ij.plugin.frame.RoiManager rm = ij.plugin.frame.RoiManager.getInstance();
        if (rm != null) rm.runCommand("Reset");
        ij.measure.ResultsTable rt = ij.measure.ResultsTable.getResultsTable();
        if (rt != null && rt.getResultsWindow() != null) {
            rt.reset();
            rt.getResultsWindow().close(false);
        }

        // Load pipeline
        GeneratedPipeline pipeline;
        try {
            pipeline = manager.loadPipeline(pipelineFile);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to load pipeline: " + e.getMessage(),
                "Load Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (pipeline == null) {
            JOptionPane.showMessageDialog(this, "Could not parse pipeline file.",
                "Load Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File inputFolder  = new File(inputFolderField.getText());
        File outputFolder = new File(outputFolderField.getText());

        // Create output folder if needed
        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
            JOptionPane.showMessageDialog(this,
                "Could not create output folder:\n" + outputFolder.getAbsolutePath(),
                "Folder Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Count files for progress bar maximum
        File[] allFiles = inputFolder.listFiles();
        int total = 0;
        if (allFiles != null) {
            for (File f : allFiles) {
                if (f.isFile() && BatchRunner.matchesImageExtension(f)) total++;
            }
        }

        // Reset UI
        logArea.setText("");
        logBuffer.setLength(0);
        progressBar.setMaximum(total);
        progressBar.setValue(0);
        statusLabel.setText(" ");
        runButton.setEnabled(false);
        cancelButton.setEnabled(true);

        final File finalOutputFolder = outputFolder;
        activeRunner = new BatchRunner(
            pipeline,
            inputFolder,
            outputFolder,
            firstSeriesBtn.isSelected(),
            skipBtn.isSelected(),
            logLine -> activeWorker.publishLog(logLine),
            update -> SwingUtilities.invokeLater(() -> {
                progressBar.setValue(update.done);
                String status = update.done + " / " + update.total;
                if (update.currentFilename != null) status += " \u2014 " + update.currentFilename;
                if (update.etaMs > 0) status += " \u2014 ~" + formatEta(update.etaMs) + " left";
                statusLabel.setText(status);
            }),
            () -> {
                // earlyAbortCallback — called on background thread
                final boolean[] cont = {false};
                try {
                    SwingUtilities.invokeAndWait(() -> cont[0] = JOptionPane.showConfirmDialog(
                        BatchRunnerWindow.this,
                        "The first 3 files all failed. The pipeline may not work in batch mode.\nContinue anyway?",
                        "Batch Failure Warning",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                    ) == JOptionPane.YES_OPTION);
                } catch (Exception ignored) {}
                return cont[0];
            },
            () -> {
                // completionCallback — called on background thread when run finishes
                try {
                    SwingUtilities.invokeAndWait(() -> {});
                } catch (Exception ignored) {}
                BatchRunner.autoSaveLog(finalOutputFolder, logBuffer.toString());
            }
        );

        activeWorker = new BatchWorker(activeRunner);
        activeWorker.execute();
    }

    private void onCancel() {
        if (activeRunner != null) activeRunner.cancel();
        statusLabel.setText("Cancelling \u2014 finishing current file...");
    }

    private void onSaveLog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("batch_log.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File dest = chooser.getSelectedFile();
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(dest), StandardCharsets.UTF_8)) {
            w.write(logBuffer.toString());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Could not save log: " + e.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String formatEta(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    // -----------------------------------------------------------------------
    // SwingWorker
    // -----------------------------------------------------------------------

    private class BatchWorker extends SwingWorker<Void, String> {
        private final BatchRunner runner;

        BatchWorker(BatchRunner runner) {
            this.runner = runner;
        }

        void publishLog(String line) { publish(line); }

        @Override
        protected Void doInBackground() {
            runner.run();
            return null;
        }

        @Override
        protected void process(List<String> lines) {
            for (String line : lines) {
                logBuffer.append(line).append("\n");
                logArea.append(line + "\n");
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        @Override
        protected void done() {
            runButton.setEnabled(true);
            cancelButton.setEnabled(false);
            activeRunner = null;
            activeWorker = null;
        }
    }
}