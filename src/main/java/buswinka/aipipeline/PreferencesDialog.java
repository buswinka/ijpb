package buswinka.aipipeline;

import ij.IJ;
import ij.Prefs;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Modal preferences dialog for the AI Pipeline Builder.
 * Allows the user to select a backend (Claude, OpenAI, Ollama, Mock),
 * enter API key, model, Pro license key, and timeout.
 * Settings are persisted via {@link ij.Prefs}.
 */
public class PreferencesDialog extends JDialog {

    // ij.Prefs keys
    private static final String PREF_BACKEND         = "aipipeline.backend";
    private static final String PREF_APIKEY          = "aipipeline.apikey";
    private static final String PREF_MODEL           = "aipipeline.model";
    private static final String PREF_TIMEOUT         = "aipipeline.timeout";
    private static final String PREF_LICENSE_KEY     = "aipipeline.license.key";
    private static final String PREF_CLAUDE_THINKING = "aipipeline.claude.thinking";
    private static final String PREF_OLLAMA_TAGS_URL = "aipipeline.ollama.tagsurl";
    private static final String PREF_AUTO_UPDATE     = PluginUpdater.PREF_AUTO_UPDATE;

    // Default models per backend
    private static final String[] BACKENDS = {"IJBP Cloud", "Claude", "OpenAI", "Ollama"};
    private static final Map<String, String> DEFAULT_MODELS = new HashMap<String, String>();
    static {
        DEFAULT_MODELS.put("IJBP Cloud", "");  // model is chosen server-side
        DEFAULT_MODELS.put("Claude", "claude-sonnet-4-6");
        DEFAULT_MODELS.put("OpenAI", "gpt-4o");
        DEFAULT_MODELS.put("Ollama", "llama3.1");
    }

    private JComboBox<String>               backendCombo;
    private JPasswordField                  apiKeyField;
    private JTextField                      modelField;
    private JTextField                      licenseKeyField;
    private JTextField                      timeoutField;
    private JCheckBox                       autoUpdateCheckBox;
    private JCheckBox                       thinkingCheckBox;    // Claude only
    private JLabel                          thinkingLabel;
    private JTextField                      ollamaTagsUrlField;  // Ollama only
    private JLabel                          ollamaTagsLabel;
    private JComboBox<Object>               pythonEnvCombo;  // CondaEnv or "Custom..."
    private JTextField                      pythonCustomField;
    private boolean                         repopulating = false;

    private boolean confirmed = false;
    private String  previousLicenseKey = "";

    public PreferencesDialog(Frame owner) {
        super(owner, "AI Pipeline Builder — Preferences", true);
        buildUI();
        loadPrefs();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 2, 4, 8);
        lc.gridx = 0;
        GridBagConstraints fc = new GridBagConstraints();
        fc.fill   = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets  = new Insets(4, 0, 4, 2);
        fc.gridx   = 1;

        int row = 0;

        // Backend
        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Backend:"), lc);
        backendCombo = new JComboBox<>(BACKENDS);
        backendCombo.setPreferredSize(new Dimension(220, backendCombo.getPreferredSize().height));
        backendCombo.addActionListener(e -> onBackendChanged());
        form.add(backendCombo, fc);

        // API Key
        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("API Key:"), lc);
        apiKeyField = new JPasswordField(24);
        form.add(apiKeyField, fc);

        // Model
        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Model:"), lc);
        modelField = new JTextField(24);
        form.add(modelField, fc);

        // Pro License Key
        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Pro License Key:"), lc);
        licenseKeyField = new JTextField(24);
        form.add(licenseKeyField, fc);

        // Timeout
        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Timeout (ms):"), lc);
        timeoutField = new JTextField("300000", 24);
        form.add(timeoutField, fc);

        // Auto-update
        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Auto-update:"), lc);
        autoUpdateCheckBox = new JCheckBox("Automatically install updates on next restart");
        autoUpdateCheckBox.setSelected(true);
        form.add(autoUpdateCheckBox, fc);

        // Extended Thinking (Claude only)
        lc.gridy = row; fc.gridy = row++;
        thinkingLabel = new JLabel("Extended Thinking:");
        form.add(thinkingLabel, lc);
        thinkingCheckBox = new JCheckBox("Enabled (disable for non-thinking models like Haiku)");
        thinkingCheckBox.setSelected(true);
        form.add(thinkingCheckBox, fc);

        // Ollama Tags URL (Ollama only)
        lc.gridy = row; fc.gridy = row++;
        ollamaTagsLabel = new JLabel("Tags URL:");
        form.add(ollamaTagsLabel, lc);
        ollamaTagsUrlField = new JTextField(OllamaClient.DEFAULT_TAGS_ENDPOINT, 24);
        form.add(ollamaTagsUrlField, fc);

        // Python Interpreter (Pro)
        lc.gridy = row; fc.gridy = row++;
        JLabel pyLabel = new JLabel("Python Interpreter:");
        pyLabel.setForeground(new Color(100, 60, 180));
        form.add(pyLabel, lc);
        pythonEnvCombo = new JComboBox<>();
        pythonEnvCombo.setPreferredSize(new Dimension(220, pythonEnvCombo.getPreferredSize().height));
        populatePythonEnvCombo();
        pythonEnvCombo.addActionListener(e -> onPythonEnvChanged());
        form.add(pythonEnvCombo, fc);

        // Custom path field (shown only when "Custom..." is selected)
        lc.gridy = row; fc.gridy = row++;
        form.add(new JLabel("Custom Path:"), lc);
        pythonCustomField = new JTextField(24);
        pythonCustomField.setVisible(false);
        form.add(pythonCustomField, fc);

        // Detect button
        GridBagConstraints dc = new GridBagConstraints();
        dc.gridx = 1; dc.gridy = row++;
        dc.anchor = GridBagConstraints.WEST;
        dc.insets = new Insets(0, 0, 6, 2);
        JButton detectButton = new JButton("Detect Environments");
        detectButton.addActionListener(e -> populatePythonEnvCombo());
        form.add(detectButton, dc);

        // Test Connection button
        JButton testButton = new JButton("Test Connection");
        testButton.addActionListener(e -> onTestConnection());
        GridBagConstraints tc = new GridBagConstraints();
        tc.gridx = 1; tc.gridy = row++;
        tc.anchor = GridBagConstraints.WEST;
        tc.insets = new Insets(2, 0, 6, 2);
        form.add(testButton, tc);

        // OK / Cancel buttons
        JButton okButton     = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        okButton.addActionListener(e -> onOK());
        cancelButton.addActionListener(e -> onCancel());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 12));
        buttons.add(okButton);
        buttons.add(cancelButton);

        setLayout(new BorderLayout());
        add(form,    BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        // Allow Escape to cancel
        getRootPane().registerKeyboardAction(
            e -> onCancel(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        getRootPane().setDefaultButton(okButton);
    }

    private void loadPrefs() {
        String backend = Prefs.get(PREF_BACKEND, "IJBP Cloud");
        String apiKey  = Prefs.get(PREF_APIKEY,  "");
        String model   = Prefs.get(PREF_MODEL,   "");
        String timeout = Prefs.get(PREF_TIMEOUT,  "300000");
        String licKey  = Prefs.get(PREF_LICENSE_KEY, "");

        backendCombo.setSelectedItem(backend);
        apiKeyField.setText(apiKey);
        timeoutField.setText(timeout);
        licenseKeyField.setText(licKey);

        // Model: use saved value or default for the selected backend
        if (model == null || model.isEmpty()) {
            model = DEFAULT_MODELS.getOrDefault(backend, "");
        }
        modelField.setText(model);

        autoUpdateCheckBox.setSelected(Prefs.get(PREF_AUTO_UPDATE, true));
        thinkingCheckBox.setSelected(!"false".equals(Prefs.get(PREF_CLAUDE_THINKING, "true")));
        ollamaTagsUrlField.setText(Prefs.get(PREF_OLLAMA_TAGS_URL, OllamaClient.DEFAULT_TAGS_ENDPOINT));

        // Remember the key as it was when the dialog opened so we can detect changes.
        previousLicenseKey = licKey != null ? licKey : "";

        // Sync backend-specific field visibility to the loaded backend
        onBackendChanged();

        syncPythonSelection();
    }

    private void savePrefs() {
        String backend = (String) backendCombo.getSelectedItem();
        String apiKey  = new String(apiKeyField.getPassword());
        String model   = modelField.getText().trim();
        String timeout = timeoutField.getText().trim();
        String licKey  = licenseKeyField.getText().trim();

        Prefs.set(PREF_BACKEND, backend);
        Prefs.set(PREF_APIKEY,  apiKey);
        Prefs.set(PREF_MODEL,   model);
        Prefs.set(PREF_TIMEOUT, timeout.isEmpty() ? "60000" : timeout);

        // Save license key via TierManager (always save — empty string clears Pro status)
        TierManager.setLicenseKey(licKey);

        Prefs.set(PREF_AUTO_UPDATE, autoUpdateCheckBox.isSelected());
        Prefs.set(PREF_CLAUDE_THINKING, thinkingCheckBox.isSelected() ? "true" : "false");
        Prefs.set(PREF_OLLAMA_TAGS_URL, ollamaTagsUrlField.getText().trim());

        // Save Python interpreter path
        Object selected = pythonEnvCombo.getSelectedItem();
        if ("Custom...".equals(selected)) {
            String custom = pythonCustomField.getText().trim();
            if (!custom.isEmpty()) PythonExecutor.setPythonPath(custom);
        } else if (selected instanceof CondaFinder.CondaEnv) {
            PythonExecutor.setPythonPath(((CondaFinder.CondaEnv) selected).pythonPath);
        }
    }

    private void onBackendChanged() {
        String backend = (String) backendCombo.getSelectedItem();
        // Auto-fill model default; don't override if user has typed something custom
        String currentModel = modelField.getText().trim();
        boolean isDefault = false;
        for (String def : DEFAULT_MODELS.values()) {
            if (def.equals(currentModel)) { isDefault = true; break; }
        }
        if (currentModel.isEmpty() || isDefault) {
            modelField.setText(DEFAULT_MODELS.getOrDefault(backend, ""));
        }

        // IJBP Cloud needs neither an API key nor a model — the server handles both
        boolean isCloud = "IJBP Cloud".equals(backend);
        boolean needsKey = "Claude".equals(backend) || "OpenAI".equals(backend);
        apiKeyField.setEnabled(needsKey && !isCloud);
        modelField.setEnabled(!isCloud);

        // Show extended thinking toggle only for Claude
        boolean isClaude = "Claude".equals(backend);
        thinkingCheckBox.setVisible(isClaude);
        thinkingLabel.setVisible(isClaude);

        // Show tags URL field only for Ollama
        boolean isOllama = "Ollama".equals(backend);
        ollamaTagsUrlField.setVisible(isOllama);
        ollamaTagsLabel.setVisible(isOllama);

        pack();
    }

    private void onTestConnection() {
        String backend = (String) backendCombo.getSelectedItem();

        // IJBP Cloud: call /status so we can show real quota numbers.
        if ("IJBP Cloud".equals(backend)) {
            String deviceId  = MachineId.getMachineId();
            String licKey    = licenseKeyField.getText().trim();
            String statusMsg = IJBPCloudClient.fetchStatus(deviceId, licKey);
            boolean isError  = statusMsg.startsWith("Error:") || statusMsg.startsWith("Status unavailable:");
            JOptionPane.showMessageDialog(this,
                statusMsg,
                isError ? "Connection Failed" : "IJBP Cloud Status",
                isError ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // All other backends: build a transient client and test isAvailable().
        String apiKey  = new String(apiKeyField.getPassword());
        String model   = modelField.getText().trim();
        String timeout = timeoutField.getText().trim();

        LLMClient client;
        switch (backend) {
            case "Claude": client = new ClaudeClient();  break;
            case "OpenAI": client = new OpenAIClient();  break;
            case "Ollama": client = new OllamaClient();  break;
            default:       client = new MockLLM();       break;
        }

        Map<String, String> settings = new HashMap<String, String>();
        settings.put("apiKey",  apiKey);
        settings.put("model",   model);
        settings.put("timeout", timeout.isEmpty() ? "60000" : timeout);
        if ("Claude".equals(backend)) {
            settings.put("thinking", thinkingCheckBox.isSelected() ? "true" : "false");
        }
        if ("Ollama".equals(backend)) {
            settings.put("tagsUrl", ollamaTagsUrlField.getText().trim());
        }
        client.configure(settings);

        if (client.isAvailable()) {
            JOptionPane.showMessageDialog(this,
                backend + " is reachable.",
                "Connection OK",
                JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this,
                backend + " is NOT reachable.\nCheck your API key and network connection.",
                "Connection Failed",
                JOptionPane.WARNING_MESSAGE);
        }
    }

    private void onOK() {
        String newLicKey = licenseKeyField.getText().trim();
        savePrefs();

        // If the user is on IJBP Cloud, has entered a new non-empty license key,
        // and it differs from the saved value, activate it against the server.
        boolean isCloud      = "IJBP Cloud".equals(backendCombo.getSelectedItem());
        boolean keyChanged   = !newLicKey.equals(previousLicenseKey);
        boolean keyNonEmpty  = !newLicKey.isEmpty();
        if (isCloud && keyNonEmpty && keyChanged) {
            String deviceId = MachineId.getMachineId();
            String error    = IJBPCloudClient.activate(deviceId, newLicKey);
            if (error == null) {
                JOptionPane.showMessageDialog(this,
                    "License activated successfully.",
                    "Activation OK",
                    JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                    "Could not activate license:\n" + error,
                    "Activation Failed",
                    JOptionPane.WARNING_MESSAGE);
                // Don't block the dialog from closing — the user can retry later.
            }
        }

        confirmed = true;
        dispose();
    }

    private void onCancel() {
        confirmed = false;
        dispose();
    }

    /** Returns true if the user confirmed (clicked OK). */
    public boolean isConfirmed() { return confirmed; }

    private void populatePythonEnvCombo() {
        repopulating = true;
        try {
            Object previousSelection = pythonEnvCombo.getSelectedItem();
            pythonEnvCombo.removeAllItems();

            // Managed venv is always first
            pythonEnvCombo.addItem(new CondaFinder.CondaEnv("Plugin Default", ManagedVenv.getPythonPath()));

            for (CondaFinder.CondaEnv env : CondaFinder.listEnvironments()) {
                pythonEnvCombo.addItem(env);
            }
            pythonEnvCombo.addItem("Custom...");
            if (previousSelection != null) pythonEnvCombo.setSelectedItem(previousSelection);
        } finally {
            repopulating = false;
        }
    }

    /** Selects the combo entry whose pythonPath matches the saved pref, or falls back to Custom. */
    private void syncPythonSelection() {
        String saved = PythonExecutor.getPythonPath();

        // First-run: pref is the bare default — auto-select "Plugin Default"
        if (saved == null || saved.trim().isEmpty() || "python3".equals(saved.trim()) || "python".equals(saved.trim())) {
            pythonEnvCombo.setSelectedIndex(0); // "Plugin Default" is always index 0
            pythonCustomField.setVisible(false);
            return;
        }

        for (int i = 0; i < pythonEnvCombo.getItemCount(); i++) {
            Object item = pythonEnvCombo.getItemAt(i);
            if (item instanceof CondaFinder.CondaEnv
                    && ((CondaFinder.CondaEnv) item).pythonPath.equals(saved)) {
                pythonEnvCombo.setSelectedIndex(i);
                pythonCustomField.setVisible(false);
                return;
            }
        }

        // No known env matched — show custom field with saved path
        pythonEnvCombo.setSelectedItem("Custom...");
        pythonCustomField.setText(saved);
        pythonCustomField.setVisible(true);
    }

    private void onPythonEnvChanged() {
        if (repopulating) return;
        boolean isCustom = "Custom...".equals(pythonEnvCombo.getSelectedItem());
        pythonCustomField.setVisible(isCustom);
        pack();
    }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /**
     * Reads preferences and constructs the appropriate {@link LLMClient}.
     * Falls back to {@link MockLLM} if the selected backend is not available.
     *
     * @return a configured, ready-to-use LLMClient
     */
    public static LLMClient buildClientFromPrefs() {
        String backend = Prefs.get(PREF_BACKEND, "IJBP Cloud");
        String apiKey  = Prefs.get(PREF_APIKEY,  "");
        String model   = Prefs.get(PREF_MODEL,   "");
        String timeout = Prefs.get(PREF_TIMEOUT,  "300000");

        LLMClient client;
        switch (backend) {
            case "IJBP Cloud": {
                IJBPCloudClient cloud = new IJBPCloudClient();
                Map<String, String> s = new HashMap<String, String>();
                s.put("timeout", timeout);
                cloud.configure(s);
                // Don't fall back to MockLLM for cloud — a temporary outage shouldn't
                // silently downgrade the user. generate() will surface a clear error.
                return cloud;
            }
            case "Claude": client = new ClaudeClient(); break;
            case "OpenAI": client = new OpenAIClient(); break;
            case "Ollama": client = new OllamaClient(); break;
            default:       return new MockLLM();
        }

        Map<String, String> settings = new HashMap<String, String>();
        settings.put("apiKey", apiKey);
        settings.put("model",  model);
        settings.put("timeout", timeout);
        if ("Claude".equals(backend)) {
            settings.put("thinking", Prefs.get(PREF_CLAUDE_THINKING, "true"));
        }
        if ("Ollama".equals(backend)) {
            settings.put("tagsUrl", Prefs.get(PREF_OLLAMA_TAGS_URL, OllamaClient.DEFAULT_TAGS_ENDPOINT));
        }
        client.configure(settings);

        if (!client.isAvailable()) {
            IJ.log("[AI Pipeline Builder] " + backend + " not available, falling back to MockLLM");
            return new MockLLM();
        }
        return client;
    }
}
