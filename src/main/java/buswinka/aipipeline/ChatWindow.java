package buswinka.aipipeline;

import ij.IJ;
import ij.WindowManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.awt.datatransfer.DataFlavor;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Main Swing chat UI for the AI Pipeline Builder.
 * Provides a conversational interface for generating, running, and saving
 * ImageJ/Fiji macro pipelines via an LLM backend.
 */
public class ChatWindow extends JFrame {

    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    private static final int MAX_HISTORY_PAIRS = 10;

    // Palette — keeps the ImageJ gray-and-blue aesthetic, just cleaner
    private static final Color USER_BG    = new Color(218, 234, 252);
    private static final Color USER_FG    = new Color(14, 52, 112);
    private static final Color USER_BORDER = new Color(170, 208, 245);
    private static final Color AI_BG      = new Color(247, 247, 247);
    private static final Color AI_FG      = new Color(25, 25, 25);
    private static final Color CODE_BG    = new Color(232, 232, 232);
    private static final Color ERROR_BG   = new Color(255, 232, 232);
    private static final Color ERROR_FG   = new Color(165, 0, 0);
    private static final Color SYSTEM_FG  = new Color(130, 130, 130);
    private static final Color DIVIDER    = new Color(208, 208, 208);
    private static final Color PYTHON_ACCENT     = new Color(178, 106, 12);
    private static final Color PYTHON_OUTPUT_BG  = new Color(255, 255, 255);//Color(255, 243, 220); // light warm amber
    private static final Color PYTHON_SCRIPT  = new Color(32, 150, 83);   // jewel green
    private static final Color IJM_SCRIPT     = new Color(102, 51, 153);  // rich purple

    // ---------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------

    private final LLMClient llm;
    private final PipelineManager manager;
    private final RateLimiter rateLimiter;
    private final List<String[]> conversationHistory = new ArrayList<>();
    private final List<String[]> messageRecords = new ArrayList<>();  // [text, styleName] for rebuild

    private GeneratedPipeline lastPipeline;
    private File sourceFile;
    private SwingWorker<?, ?> currentWorker;
    private PythonExecutor activePythonExecutor;

    // Chat area
    private JPanel messagesPanel;
    private JScrollPane scrollPane;

    // Input bar
    private JTextArea inputField;
    private JScrollPane inputScrollPane;
    private JButton sendButton;
    private JButton cancelButton;

    // Action strip (shown when a pipeline is available)
    private JPanel actionPanel;
    private JButton runButton;
    private JButton saveButton;
    private JButton updateButton;

    private JLabel tierLabel;

    // File attachments
    private final Map<String, String> pendingFiles = new LinkedHashMap<>(); // in chip strip, not yet sent
    private final Map<String, String> pinnedFiles  = new LinkedHashMap<>(); // pinned to system prompt

    // Snapshot captured at send time so cancel can restore the pre-send UI state
    private String              preSendInputText    = null;
    private Map<String, String> preSendPendingFiles = null;
    private Map<String, String> preSendPinnedFiles  = null;
    private int                 preSendMessageCount = 0;
    private JPanel attachmentPanel;
    private JPanel chipRow;

    // Thinking indicator
    private JPanel thinkingWrapper;
    private JLabel thinkingLabel;
    private Timer thinkingTimer;
    private int thinkingDotCount = 0;
    private static final String[] THINKING_FRAMES = {"Thinking.", "Thinking..", "Thinking..."};

    // Live Python output bubble (streamed line-by-line into a single block)
    private JTextArea pythonOutputArea   = null;
    private JPanel   pythonOutputWrapper = null;
    private final StringBuilder pythonOutputBuffer = new StringBuilder();

    // ---------------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------------

    public ChatWindow(LLMClient llm, PipelineManager manager, RateLimiter rateLimiter) {
        super("AI Pipeline Builder");
        this.llm = llm;
        this.manager = manager;
        this.rateLimiter = rateLimiter;

        initComponents();
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(720, 560);
        setLocationRelativeTo(null);
        setVisible(true);

        // For the cloud backend, fetch live quota in the background so the tier
        // label shows real numbers as soon as possible (without blocking the UI).
        if (llm instanceof IJBPCloudClient) {
            final IJBPCloudClient cloudClient = (IJBPCloudClient) llm;
            new Thread(() -> {
                cloudClient.refreshStatus(MachineId.getMachineId(), TierManager.getLicenseKey());
                SwingUtilities.invokeLater(() -> updateTierLabel());
            }, "ijbp-status-prefetch").start();
        }
    }

    // ---------------------------------------------------------------------------
    // UI initialisation
    // ---------------------------------------------------------------------------

    private void initComponents() {
        // ---- Message list ----
        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(Color.WHITE);
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        scrollPane = new JScrollPane(messagesPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getViewport().setBackground(Color.WHITE);
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);

        // Rebuild bubbles on resize so they reflow to the new width
        Timer resizeTimer = new Timer(150, e -> rebuildMessages());
        resizeTimer.setRepeats(false);
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeTimer.restart();
            }
        });

        // ---- Input field (auto-growing JTextArea, Enter sends, Shift+Enter for newline) ----
        inputField = new JTextArea();
        inputField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);
        inputField.setRows(1);

        // Enter = send, Shift+Enter = newline
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    onSend();
                }
            }
        });

        // Auto-grow/shrink up to 5 rows, accounting for visual word-wrap (not just newline count)
        inputField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() {
                SwingUtilities.invokeLater(() -> {
                    int width = inputField.getWidth();
                    if (width <= 0) width = inputScrollPane.getWidth() - 4;
                    if (width <= 0) return;

                    // Measure in a scratch textarea so we don't mutate inputField's size
                    JTextArea scratch = new JTextArea(inputField.getText());
                    scratch.setFont(inputField.getFont());
                    scratch.setLineWrap(true);
                    scratch.setWrapStyleWord(true);
                    scratch.setSize(width, Short.MAX_VALUE);
                    int contentHeight = scratch.getPreferredSize().height;

                    int rowHeight = inputField.getFontMetrics(inputField.getFont()).getHeight();
                    int visualRows = Math.max(1, Math.min(contentHeight / rowHeight, 5));

                    if (inputField.getRows() != visualRows) {
                        inputField.setRows(visualRows);
                        inputField.revalidate();
                        Window w = SwingUtilities.getWindowAncestor(inputField);
                        if (w != null) w.validate();
                    }
                });
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        });

        inputScrollPane = new JScrollPane(inputField,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScrollPane.setBorder(BorderFactory.createLineBorder(DIVIDER));

        // ---- Buttons ----
        sendButton   = new JButton("Send");
        cancelButton = new JButton("\u2715 Cancel");
        runButton    = new JButton("\u25B6 Run");
        saveButton   = new JButton("Save");
        updateButton = new JButton("Update");
        JButton settingsButton = new JButton("\u2699 Settings");

        // Cancel is hidden until a request is in-flight; swapped in via BorderLayout.WEST
        cancelButton.setVisible(false);
        runButton.setEnabled(false);
        saveButton.setEnabled(false);
        updateButton.setEnabled(false);

        sendButton.addActionListener(e -> onSend());
        cancelButton.addActionListener(e -> onCancel());
        runButton.addActionListener(e -> onRun());
        saveButton.addActionListener(e -> onSave());
        updateButton.addActionListener(e -> onUpdate());
        settingsButton.addActionListener(e -> onSettings());

        // ---- Tier label (warning only — hidden until remaining < 5) ----
        tierLabel = new JLabel();
        tierLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        tierLabel.setVisible(false);
        updateTierLabel();

        // ---- Action strip (always visible; Run/Save/Update disabled until pipeline ready) ----
        actionPanel = new JPanel(new BorderLayout(4, 0));
        actionPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, DIVIDER));

        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        leftButtons.setOpaque(false);
        leftButtons.add(runButton);
        leftButtons.add(saveButton);
        leftButtons.add(updateButton);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        rightControls.setOpaque(false);
        rightControls.add(tierLabel);
        rightControls.add(settingsButton);

        actionPanel.add(leftButtons, BorderLayout.CENTER);
        actionPanel.add(rightControls, BorderLayout.EAST);

        // ---- Attachment strip (shown when files are pending) ----
        chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        chipRow.setOpaque(false);

        JLabel attachLabel = new JLabel("Files:");
        attachLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        attachLabel.setForeground(SYSTEM_FG);

        attachmentPanel = new JPanel(new BorderLayout(6, 0));
        attachmentPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, DIVIDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        attachmentPanel.add(attachLabel, BorderLayout.WEST);
        attachmentPanel.add(chipRow, BorderLayout.CENTER);
        attachmentPanel.setVisible(false);

        // ---- Input row ----
        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, DIVIDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        inputRow.add(cancelButton, BorderLayout.WEST);
        inputRow.add(inputScrollPane, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);

        // ---- Bottom panel ----
        JPanel upperStrip = new JPanel();
        upperStrip.setLayout(new BoxLayout(upperStrip, BoxLayout.Y_AXIS));
        upperStrip.add(actionPanel);
        upperStrip.add(attachmentPanel);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(upperStrip, BorderLayout.NORTH);
        bottomPanel.add(inputRow, BorderLayout.SOUTH);

        // ---- Main layout ----
        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        setupDragAndDrop();

        appendToChat("Welcome! Describe what you need and I'll build it for you.\n", "system");
    }

    // ---------------------------------------------------------------------------
    // Message display
    // ---------------------------------------------------------------------------

    /**
     * Thread-safe entry point for adding a message to the chat.
     * Strips legacy sender prefixes ("You: ", "AI: ") and trailing newlines.
     * Skips the redundant "end of script" delimiter.
     *
     * @param rawText   text to display
     * @param styleName one of "user", "ai", "code", "error", "system"
     */
    public void appendToChat(final String rawText, final String styleName) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendToChat(rawText, styleName));
            return;
        }
        String text = rawText.replaceAll("\\n+$", "").trim();
        if (text.isEmpty()) return;
        // Strip legacy sender prefixes — bubbles now show sender in a header
        if ("user".equals(styleName) && text.startsWith("You: ")) text = text.substring(5).trim();
        if ("ai".equals(styleName)   && text.startsWith("AI: "))  text = text.substring(4).trim();
        // The closing "--- end of script ---" delimiter is redundant in the bubble UI
        if ("system".equals(styleName) && text.startsWith("--- end of")) return;

        addMessageEntry(text, styleName);
    }

    /** Wraps a built entry with a bottom strut and appends it to the messages panel. */
    private void addMessageEntry(String text, String styleName) {
        messageRecords.add(new String[]{text, styleName});

        Component entry = buildEntry(text, styleName);

        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(entry);
        wrapper.add(Box.createVerticalStrut(6));

        messagesPanel.add(wrapper);
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    /** Rebuilds all message bubbles from stored records (called on viewport resize). */
    private void rebuildMessages() {
        messagesPanel.removeAll();
        for (String[] rec : messageRecords) {
            Component entry = buildEntry(rec[0], rec[1]);
            JPanel wrapper = new JPanel();
            wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
            wrapper.setOpaque(false);
            wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
            wrapper.add(entry);
            wrapper.add(Box.createVerticalStrut(6));
            messagesPanel.add(wrapper);
        }
        // Re-add live output bubble and thinking indicator if active
        if (pythonOutputWrapper != null) messagesPanel.add(pythonOutputWrapper);
        if (thinkingWrapper != null) messagesPanel.add(thinkingWrapper);
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    /** Dispatches to the appropriate bubble builder based on style. */
    private Component buildEntry(String text, String styleName) {
        int viewWidth = Math.max(320, scrollPane.getViewport().getWidth() - 20);
        switch (styleName) {
            case "system":       return buildSystemEntry(text, viewWidth, FlowLayout.CENTER);
            case "system-left":  return buildSystemEntry(text, viewWidth, FlowLayout.LEFT);
            case "code":         return buildCodeEntry(text, viewWidth);
            case "code-python":  return buildCodeEntry(text, viewWidth, PYTHON_SCRIPT);
            case "code-ijm":     return buildCodeEntry(text, viewWidth, IJM_SCRIPT);
            case "attachment":   return buildAttachmentEntry(text);
            case "subscribe":    return buildSubscribeEntry(text, viewWidth);
            default:
                boolean isUser  = "user".equals(styleName);
                boolean isError = "error".equals(styleName);
                return buildBubbleEntry(text, isUser, isError, viewWidth);
        }
    }

    /**
     * Small italic text area for status / delimiter messages. Wraps long text at viewWidth.
     * @param alignment FlowLayout.CENTER or FlowLayout.LEFT
     */
    private Component buildSystemEntry(String text, int viewWidth, int alignment) {
        JTextArea area = new JTextArea(text);
        area.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        area.setForeground(SYSTEM_FG);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(null);

        // Measure natural (unwrapped) text width, capped at the viewport width
        FontMetrics fm = area.getFontMetrics(area.getFont());
        int naturalWidth = 0;
        for (String line : text.split("\n", -1)) {
            naturalWidth = Math.max(naturalWidth, fm.stringWidth(line));
        }
        int componentWidth = alignment == FlowLayout.LEFT
                ? viewWidth
                : Math.min(naturalWidth + 16, viewWidth);

        area.setSize(componentWidth, Short.MAX_VALUE);
        int textHeight = area.getPreferredSize().height;
        area.setPreferredSize(new Dimension(componentWidth, textHeight));
        area.setMaximumSize(new Dimension(componentWidth, textHeight + 4));

        JPanel row = new JPanel(new FlowLayout(alignment, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, textHeight + 4));
        row.add(area);
        return row;
    }

    /** Monospaced code block — plain text area, no scrolling. */
    private Component buildCodeEntry(String text, int viewWidth) {
        return buildCodeEntry(text, viewWidth, DIVIDER);
    }

    private Component buildCodeEntry(String text, int viewWidth, Color borderColor) {
        JTextArea area = new JTextArea(text);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setBackground(CODE_BG);
        area.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        // Measure at the available width so it sizes naturally
        area.setSize(viewWidth, Short.MAX_VALUE);
        int textHeight = area.getPreferredSize().height;
        area.setPreferredSize(new Dimension(viewWidth, textHeight));
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, textHeight));
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    /**
     * Builds a chat bubble for user, AI, or error messages.
     * Short messages get a tight-fitting bubble; longer text wraps at 72% of view width.
     * User bubbles align right; AI/error bubbles align left.
     */
    private Component buildBubbleEntry(String text, boolean isUser, boolean isError, int viewWidth) {
        int maxBubbleWidth = (int) (viewWidth * 0.72);

        Color bg          = isError ? ERROR_BG   : isUser ? USER_BG  : AI_BG;
        Color fg          = isError ? ERROR_FG   : isUser ? USER_FG  : AI_FG;
        Color borderColor = isUser  ? USER_BORDER : DIVIDER;
        Color senderColor = isError ? ERROR_FG : (isUser ? new Color(10, 45, 100) : new Color(70, 70, 70));
        String senderName = isUser  ? "You" : (isError ? "AI" : llm.getProviderName());

        JLabel senderLabel = new JLabel(senderName);
        senderLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        senderLabel.setForeground(senderColor);
        int senderHeight = senderLabel.getPreferredSize().height;
        int senderWidth  = senderLabel.getPreferredSize().width;

        // Insets: border(1) + padding(6,10,7,10)
        int hInsets = 1 + 10 + 10 + 1;
        int vInsets = 1 + 6 + 7 + 1;
        int maxContentWidth = maxBubbleWidth - hInsets;

        Font msgFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);

        // Use FontMetrics to find the widest line in the text
        FontMetrics fm = getFontMetrics(msgFont);
        int naturalWidth = 0;
        for (String line : text.split("\n", -1)) {
            naturalWidth = Math.max(naturalWidth, fm.stringWidth(line));
        }

        // Pick the narrower of natural width and max, but ensure sender label fits
        int contentWidth = Math.min(naturalWidth + 4, maxContentWidth); // +4 for caret/rounding
        contentWidth = Math.max(contentWidth, senderWidth);
        int bubbleWidth = contentWidth + hInsets;

        JTextArea msgArea = new JTextArea(text);
        msgArea.setFont(msgFont);
        msgArea.setEditable(false);
        msgArea.setFocusable(false);
        msgArea.setLineWrap(true);
        msgArea.setWrapStyleWord(true);
        msgArea.setOpaque(false);
        msgArea.setForeground(fg);
        msgArea.setBorder(null);

        // Measure height at the final contentWidth (after senderWidth adjustment) so the
        // fixed totalHeight matches the actual reflow width of the rendered bubble.
        msgArea.setSize(contentWidth, Short.MAX_VALUE);
        int textHeight = msgArea.getPreferredSize().height;

        int totalHeight = vInsets + senderHeight + 3 + textHeight;

        JPanel bubble = new JPanel(new BorderLayout(0, 3));
        bubble.setBackground(bg);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1, true),
                BorderFactory.createEmptyBorder(6, 10, 7, 10)));
        bubble.add(senderLabel, BorderLayout.NORTH);
        bubble.add(msgArea, BorderLayout.CENTER);

        bubble.setPreferredSize(new Dimension(bubbleWidth, totalHeight));
        bubble.setMinimumSize(new Dimension(bubbleWidth, totalHeight));
        bubble.setMaximumSize(new Dimension(bubbleWidth, totalHeight));

        JPanel row = new JPanel(new FlowLayout(isUser ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, totalHeight + 4));
        row.add(bubble);
        return row;
    }

    /** AI-style bubble with a Subscribe button that opens the checkout page in the browser. */
    private Component buildSubscribeEntry(String text, int viewWidth) {
        int maxBubbleWidth = (int) (viewWidth * 0.72);

        Color senderColor = new Color(70, 70, 70);
        JLabel senderLabel = new JLabel(llm.getProviderName());
        senderLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        senderLabel.setForeground(senderColor);
        int senderHeight = senderLabel.getPreferredSize().height;
        int senderWidth  = senderLabel.getPreferredSize().width;

        int hInsets = 1 + 10 + 10 + 1;
        int vInsets = 1 + 6 + 7 + 1;
        int maxContentWidth = maxBubbleWidth - hInsets;

        Font msgFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        FontMetrics fm = getFontMetrics(msgFont);
        int naturalWidth = 0;
        for (String line : text.split("\n", -1)) {
            naturalWidth = Math.max(naturalWidth, fm.stringWidth(line));
        }
        int contentWidth = Math.min(naturalWidth + 4, maxContentWidth);
        contentWidth = Math.max(contentWidth, senderWidth);
        int bubbleWidth = contentWidth + hInsets;

        JTextArea msgArea = new JTextArea(text);
        msgArea.setFont(msgFont);
        msgArea.setEditable(false);
        msgArea.setFocusable(false);
        msgArea.setLineWrap(true);
        msgArea.setWrapStyleWord(true);
        msgArea.setOpaque(false);
        msgArea.setForeground(AI_FG);
        msgArea.setBorder(null);
        msgArea.setSize(contentWidth, Short.MAX_VALUE);
        int textHeight = msgArea.getPreferredSize().height;

        JButton subscribeBtn = new JButton("Subscribe Here \u2192");
        subscribeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        subscribeBtn.setForeground(Color.WHITE);
        subscribeBtn.setBackground(new Color(30, 100, 200));
        subscribeBtn.setOpaque(true);
        subscribeBtn.setBorderPainted(false);
        subscribeBtn.setFocusPainted(false);
        subscribeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        subscribeBtn.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI("https://checkout.example.com"));
            } catch (Exception ex) {
                appendToChat("Could not open browser: " + ex.getMessage(), "error");
            }
        });
        int btnHeight = subscribeBtn.getPreferredSize().height;

        int totalHeight = vInsets + senderHeight + 3 + textHeight + 10 + btnHeight;

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        msgArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(msgArea);
        content.add(Box.createVerticalStrut(10));
        subscribeBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(subscribeBtn);

        JPanel bubble = new JPanel(new BorderLayout(0, 3));
        bubble.setBackground(AI_BG);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIVIDER, 1, true),
                BorderFactory.createEmptyBorder(6, 10, 7, 10)));
        bubble.add(senderLabel, BorderLayout.NORTH);
        bubble.add(content, BorderLayout.CENTER);

        bubble.setPreferredSize(new Dimension(bubbleWidth, totalHeight));
        bubble.setMinimumSize(new Dimension(bubbleWidth, totalHeight));
        bubble.setMaximumSize(new Dimension(bubbleWidth, totalHeight));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, totalHeight + 4));
        row.add(bubble);
        return row;
    }

    /** Small right-aligned pill shown in chat for each attached file (filename only, no content). */
    private Component buildAttachmentEntry(String filename) {
        Font font = new Font(Font.SANS_SERIF, Font.ITALIC, 11);
        FontMetrics fm = getFontMetrics(font);
        int textW = fm.stringWidth(filename);
        int textH = fm.getHeight();

        JLabel label = new JLabel(filename);
        label.setFont(font);
        label.setForeground(USER_FG);

        JPanel pill = new JPanel(new BorderLayout());
        pill.setBackground(new Color(230, 241, 255));
        pill.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(USER_BORDER, 1, true),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        pill.add(label, BorderLayout.CENTER);

        int ph = textH + 8;
        int pw = textW + 20;
        pill.setPreferredSize(new Dimension(pw, ph));
        pill.setMinimumSize(new Dimension(pw, ph));
        pill.setMaximumSize(new Dimension(pw, ph));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ph + 4));
        row.add(pill);
        return row;
    }

    // ---------------------------------------------------------------------------
    // File attachment support
    // ---------------------------------------------------------------------------

    private void setupDragAndDrop() {
        TransferHandler dropHandler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override
            public boolean importData(TransferSupport support) {
                return handleFileDrop(support);
            }
        };
        messagesPanel.setTransferHandler(dropHandler);
        scrollPane.getViewport().setTransferHandler(dropHandler);

        // Wrap inputField's handler so text drag-and-drop still works
        TransferHandler original = inputField.getTransferHandler();
        inputField.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true;
                return original != null && original.canImport(support);
            }
            @Override
            public boolean importData(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    return handleFileDrop(support);
                }
                return original != null && original.importData(support);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private boolean handleFileDrop(TransferHandler.TransferSupport support) {
        try {
            List<File> dropped = (List<File>) support.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);
            List<File> textFiles = new ArrayList<>();
            for (File f : dropped) collectTextFiles(f, textFiles);
            if (textFiles.isEmpty()) return false;
            for (File f : textFiles) addPendingFile(f);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void collectTextFiles(File f, List<File> out) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) collectTextFiles(child, out);
            }
        } else if (f.isFile() && isTextFile(f)) {
            out.add(f);
        }
    }

    private static boolean isTextFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void addPendingFile(File f) {
        String name = f.getName();
        if (pendingFiles.containsKey(name)) return;
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            pendingFiles.put(name, sb.toString());
            SwingUtilities.invokeLater(this::refreshChips);
        } catch (IOException e) {
            // skip unreadable file silently
        }
    }

    private void refreshChips() {
        chipRow.removeAll();
        for (String name : pendingFiles.keySet()) {
            chipRow.add(buildChip(name));
        }
        attachmentPanel.setVisible(!pendingFiles.isEmpty());
        chipRow.revalidate();
        chipRow.repaint();
        attachmentPanel.revalidate();
        attachmentPanel.repaint();
    }

    private JPanel buildChip(String name) {
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        nameLabel.setForeground(USER_FG);

        JButton removeBtn = new JButton("\u00d7");
        removeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        removeBtn.setBorderPainted(false);
        removeBtn.setContentAreaFilled(false);
        removeBtn.setFocusPainted(false);
        removeBtn.setForeground(new Color(100, 130, 170));
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.setMargin(new Insets(0, 3, 0, 0));
        removeBtn.addActionListener(e -> {
            pendingFiles.remove(name);
            refreshChips();
        });

        JPanel chip = new JPanel(new BorderLayout(3, 0));
        chip.setBackground(new Color(230, 241, 255));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(USER_BORDER, 1, true),
                BorderFactory.createEmptyBorder(2, 6, 2, 4)));
        chip.add(nameLabel, BorderLayout.CENTER);
        chip.add(removeBtn, BorderLayout.EAST);
        return chip;
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() ->
                SwingUtilities.invokeLater(() -> {
                    JScrollBar vsb = scrollPane.getVerticalScrollBar();
                    vsb.setValue(vsb.getMaximum());
                }));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    /** Reads the input field, validates, then dispatches an LLM generation request. */
    public void onSend() {
        String userText = inputField.getText().trim();
        if (userText.isEmpty()) return;

        // Debug commands: /debug <style> <message>
        // Styles: user, ai, error, system, system-left, code, code-python, code-ijm, attachment, subscribe
        if (userText.startsWith("/debug ")) {
            String rest = userText.substring(7).trim();
            int spaceIdx = rest.indexOf(' ');
            if (spaceIdx > 0) {
                String style = rest.substring(0, spaceIdx).trim();
                String msg   = rest.substring(spaceIdx + 1).trim();
                inputField.setText("");
                appendToChat(msg, style);
            } else {
                appendToChat("Usage: /debug <style> <message>\n"
                        + "Styles: user, ai, error, system, system-left, code, code-python, code-ijm, attachment, subscribe",
                        "system-left");
                inputField.setText("");
            }
            return;
        }

        // /help — list available chat commands
        if (userText.equals("/help")) {
            inputField.setText("");
            appendToChat(
                "Available commands:\n"
                + "  /help                       Show this help message\n"
                + "  /debug <style> <message>    Inject a bubble of any style\n"
                + "    Styles: user, ai, error, system, system-left,\n"
                + "            code, code-python, code-ijm, attachment, subscribe",
                "system-left");
            return;
        }

        if (isCloudFreeQuotaExhausted()) {
            appendToChat("You've used all 25 free messages. Subscribe to keep building, or add your own API key in Settings to continue for free.", "subscribe");
            return;
        }

        // Capture pre-send state so cancel can restore it exactly
        preSendInputText    = userText;
        preSendPendingFiles = new LinkedHashMap<>(pendingFiles);
        preSendPinnedFiles  = new LinkedHashMap<>(pinnedFiles);
        preSendMessageCount = messageRecords.size();

        inputField.setText("");

        if (llm instanceof IJBPCloudClient && !rateLimiter.tryConsume()) {
            appendToChat("Daily request limit reached. Please try again tomorrow.\n", "system");
            updateTierLabel();
            return;
        }
        updateTierLabel();

        // Pin any pending files to the system prompt and show them in chat
        if (!pendingFiles.isEmpty()) {
            pinnedFiles.putAll(pendingFiles);
            for (String name : pendingFiles.keySet()) {
                appendToChat(name, "attachment");
            }
            pendingFiles.clear();
            refreshChips();
        }

        appendToChat("You: " + userText + "\n", "user");

        sendButton.setEnabled(false);
        cancelButton.setVisible(true);
        runButton.setEnabled(false);
        saveButton.setEnabled(false);

        startThinking();

        final String capturedUserText = userText;

        SwingWorker<GeneratedPipeline, Void> worker = new SwingWorker<GeneratedPipeline, Void>() {
            @Override
            protected GeneratedPipeline doInBackground() throws Exception {
                String systemPrompt = buildSystemPrompt();
                return llm.generate(systemPrompt, trimmedHistory(), capturedUserText, ScriptLanguage.IJM);
            }

            @Override
            protected void done() {
                stopThinking();
                sendButton.setEnabled(true);
                cancelButton.setVisible(false);

                if (isCancelled()) return;

                try {
                    GeneratedPipeline pipeline = get();
                    conversationHistory.add(new String[]{"user", capturedUserText});
                    conversationHistory.add(new String[]{"assistant", pipeline.getExplanation()});
                    updateTierLabel(); // refresh quota display after each cloud round-trip

                    if (pipeline.isConversational()) {
                        appendToChat("AI: " + pipeline.getExplanation() + "\n", "ai");
                    } else {
                        lastPipeline = pipeline;
                        appendToChat("AI: " + pipeline.getExplanation() + "\n", "ai");
                        appendToChat("\n--- " + pipeline.getLanguage().name() + " script ---\n", "system");
                        appendToChat(pipeline.getScript() + "\n", pipeline.getLanguage() == ScriptLanguage.PYTHON ? "code-python" : "code-ijm");
                        appendToChat("--- end of script ---\n\n", "system");
                        runButton.setEnabled(true);
                        saveButton.setEnabled(true);
                        updateButton.setEnabled(sourceFile != null);
                        actionPanel.setVisible(true);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendToChat("Error: generation was interrupted.\n", "error");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    appendToChat("Error: " + cause.getMessage() + "\n", "error");
                }
            }
        };

        currentWorker = worker;
        worker.execute();
    }

    /** Routes a run request to the appropriate executor based on pipeline language. */
    public void onRun() {
        if (lastPipeline == null) return;
        // Hard gate: Python execution is Pro-only regardless of how the pipeline was generated
        if (lastPipeline.getLanguage() == ScriptLanguage.PYTHON && !TierManager.canUsePython()) {
            JOptionPane.showMessageDialog(this,
                    "Python pipelines require a Pro subscription.\nPlease enter a valid Pro license key in Preferences.",
                    "Pro Feature Required",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        switch (lastPipeline.getLanguage()) {
            case IJM:    onRunIJM();    break;
            case PYTHON: onRunPython(); break;
            default:
                appendToChat("Unknown script language: " + lastPipeline.getLanguage() + "\n", "error");
        }
    }

    /** Runs an IJM macro, detecting errors via a polled WindowManager check. */
    public void onRunIJM() {
        if (currentWorker != null && !currentWorker.isDone()) currentWorker.cancel(true);

        sendButton.setEnabled(false);
        runButton.setEnabled(false);
        // Note: cancelButton NOT shown for IJM — IJ.runMacro() is not interruptible

        final String script = lastPipeline.getScript();
        appendToChat("Running IJM script…\n", "system");

        // Poll for the "Macro Error" frame using ImageJ's WindowManager.
        // A Swing Timer fires on the EDT even during a modal dialog's nested event loop,
        // so this reliably catches the error while IJ.runMacro() is still blocked.
        final StringBuilder macroErrorBuf = new StringBuilder();
        final Timer[] watcherRef = {null};
        Timer dialogWatcher = new Timer(50, e -> {
            // WindowManager.getFrame searches ImageJ-registered windows by title.
            Frame ijFrame = WindowManager.getFrame("Macro Error");
            Window target = (ijFrame != null && ijFrame.isVisible()) ? ijFrame : null;

            // Fall back to scanning all AWT windows in case ImageJ didn't register it.
            if (target == null) {
                for (Window w : Window.getWindows()) {
                    if (!w.isVisible()) continue;
                    String title = (w instanceof Frame)  ? ((Frame)  w).getTitle()
                                 : (w instanceof Dialog) ? ((Dialog) w).getTitle() : null;
                    if ("Macro Error".equals(title)) { target = w; break; }
                }
            }

            if (target == null) return;

            // Capture the error text, then dispose the window so IJ.runMacro() unblocks.
            String text = gatherTextFromContainer((Container) target).trim();
            if (!text.isEmpty()) macroErrorBuf.append(text);
            target.dispose();
            if (watcherRef[0] != null) watcherRef[0].stop();
        });
        watcherRef[0] = dialogWatcher;
        dialogWatcher.start();

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                IJ.runMacro(script);
                return null;
            }

            @Override
            protected void done() {
                dialogWatcher.stop();

                String macroError = macroErrorBuf.toString().trim();
                if (!macroError.isEmpty()) {
                    // Don't re-enable buttons here — fireSilentLLMRequest will do so when it finishes,
                    // preventing a brief flash where buttons are enabled then immediately disabled again.
                    appendToChat(macroError, "error");
                    String errorPrompt = "The IJM macro failed with the following error:\n" + macroError
                            + "\n\nBriefly describe what went wrong and ask if I would like you to fix it.";
                    fireSilentLLMRequest(errorPrompt);
                } else {
                    sendButton.setEnabled(true);
                    runButton.setEnabled(lastPipeline != null);
                    appendToChat("Script finished successfully.\n", "system");
                }
            }
        };

        currentWorker = worker;
        worker.execute();
    }

    /**
     * Recursively collects text from a Container's component tree.
     * Handles standard Swing/AWT types directly. For ij.gui.MultiLineLabel —
     * which extends Canvas and stores the message in a package-private "label" field —
     * we use field reflection since it exposes no getText()/getLabel() accessor.
     */
    private static String gatherTextFromContainer(Container c) {
        StringBuilder sb = new StringBuilder();
        for (Component comp : c.getComponents()) {
            String text = null;
            if (comp instanceof JLabel) {
                text = ((JLabel) comp).getText();
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                text = ((javax.swing.text.JTextComponent) comp).getText();
            } else if (comp instanceof TextComponent) {
                text = ((TextComponent) comp).getText();
            } else if (comp instanceof Label) {
                text = ((Label) comp).getText();
            } else if ("ij.gui.MultiLineLabel".equals(comp.getClass().getName())) {
                // MultiLineLabel stores text in a package-private String[] field named "lines".
                try {
                    java.lang.reflect.Field f = comp.getClass().getDeclaredField("lines");
                    f.setAccessible(true);
                    Object val = f.get(comp);
                    if (val instanceof String[]) {
                        text = String.join("\n", (String[]) val);
                    }
                } catch (Exception ignored) {}
            }
            if (text != null && !text.trim().isEmpty()) sb.append(text.trim()).append('\n');
            if (comp instanceof Container) {
                sb.append(gatherTextFromContainer((Container) comp));
            }
        }
        return sb.toString();
    }

    /** Runs a Python script via PythonExecutor (Pro tier only). */
    public void onRunPython() {
        if (!TierManager.canUsePython()) {
            JOptionPane.showMessageDialog(this,
                    "Python requires Pro tier. Please enter a valid Pro license key in Settings.",
                    "Pro Feature",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (currentWorker != null && !currentWorker.isDone()) currentWorker.cancel(true);

        sendButton.setEnabled(false);
        runButton.setEnabled(false);
        cancelButton.setVisible(true);

        final String script = lastPipeline.getScript();
        // Snapshot the active Fiji image on the EDT before handing off to the worker thread.
        final PythonExecutor.FijiCtx fijiCtx = PythonExecutor.snapshotFijiCtx(null);
        appendToChat("Running Python script…\n", "system");

        // Collect stderr into a buffer so we can show it as one block and feed it to the LLM.
        final StringBuilder stderrBuf = new StringBuilder();

        activePythonExecutor = new PythonExecutor(
                line -> appendToPythonOutputBubble(line),
                line -> stderrBuf.append(line).append('\n')
        );

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                activePythonExecutor.execute(script, fijiCtx);
                return null;
            }

            @Override
            protected void done() {
                activePythonExecutor = null;
                cancelButton.setVisible(false);

                boolean interrupted = false;
                boolean scriptFailed = false;
                try {
                    get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                } catch (ExecutionException e) {
                    scriptFailed = true;
                }

                final boolean wasInterrupted = interrupted;
                final boolean failed = scriptFailed;
                final String stderr = stderrBuf.toString().trim();

                // Nested invokeLater ensures all pending appendToPythonOutputBubble callbacks
                // are processed before we finalize the bubble and show the result message.
                SwingUtilities.invokeLater(() -> {
                    finalizePythonOutputBubble();

                    if (wasInterrupted) {
                        sendButton.setEnabled(true);
                        runButton.setEnabled(lastPipeline != null);
                        return;
                    }

                    if (failed) {
                        // Don't re-enable buttons here — fireSilentLLMRequest will do so when it finishes,
                        // preventing a brief flash where buttons are enabled then immediately disabled again.
                        String errorText = stderr.isEmpty() ? "Script exited with a non-zero exit code." : stderr;
                        appendToChat(errorText, "error");
                        String errorPrompt = "The Python script failed with the following error:\n" + errorText
                                + "\n\nBriefly describe what went wrong and ask if I would like you to fix it.";
                        fireSilentLLMRequest(errorPrompt);
                    } else {
                        sendButton.setEnabled(true);
                        runButton.setEnabled(lastPipeline != null);
                        appendToChat("Python script finished.\n", "system");
                        if (!stderr.isEmpty()) {
                            // Script succeeded but produced warnings — show as a system note, no LLM trigger.
                            appendToChat("Script warnings:\n" + stderr, "system-left");
                        }
                    }
                });
            }
        };

        currentWorker = worker;
        worker.execute();
    }

    /**
     * Fires an LLM request with the given message as the effective user turn.
     * Unlike onSend(), this does NOT display a user bubble — the message is injected
     * directly into conversationHistory and the AI response is shown normally.
     * Used for automated error acknowledgment after a Python script failure.
     */
    private void fireSilentLLMRequest(String userMessage) {
        if (llm instanceof IJBPCloudClient && !rateLimiter.tryConsume()) {
            appendToChat("Daily request limit reached. Please try again tomorrow.\n", "system");
            updateTierLabel();
            return;
        }
        updateTierLabel();

        sendButton.setEnabled(false);
        cancelButton.setVisible(true);
        runButton.setEnabled(false);
        saveButton.setEnabled(false);

        startThinking();

        // Capture history snapshot before the call; userMessage is passed as the final turn
        // and added to history only on success so it isn't duplicated in the API request.
        final List<String[]> historyCopy = trimmedHistory();

        SwingWorker<GeneratedPipeline, Void> worker = new SwingWorker<GeneratedPipeline, Void>() {
            @Override
            protected GeneratedPipeline doInBackground() throws Exception {
                String systemPrompt = buildSystemPrompt();
                return llm.generate(systemPrompt, historyCopy, userMessage, ScriptLanguage.IJM);
            }

            @Override
            protected void done() {
                stopThinking();
                sendButton.setEnabled(true);
                cancelButton.setVisible(false);

                if (isCancelled()) return;

                try {
                    GeneratedPipeline pipeline = get();
                    // Now persist both turns so future sends have full context.
                    conversationHistory.add(new String[]{"user", userMessage});
                    conversationHistory.add(new String[]{"assistant", pipeline.getExplanation()});

                    if (pipeline.isConversational()) {
                        appendToChat("AI: " + pipeline.getExplanation() + "\n", "ai");
                    } else {
                        // Auto-fix produced a new script — clear sourceFile so Update can't silently
                        // overwrite the original file. User must explicitly Save to commit.
                        sourceFile = null;
                        lastPipeline = pipeline;
                        appendToChat("AI: " + pipeline.getExplanation() + "\n", "ai");
                        appendToChat("\n--- " + pipeline.getLanguage().name() + " script ---\n", "system");
                        appendToChat(pipeline.getScript() + "\n", pipeline.getLanguage() == ScriptLanguage.PYTHON ? "code-python" : "code-ijm");
                        appendToChat("--- end of script ---\n\n", "system");
                        runButton.setEnabled(true);
                        saveButton.setEnabled(true);
                        updateButton.setEnabled(false);
                        actionPanel.setVisible(true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendToChat("Error: generation was interrupted.\n", "error");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    appendToChat("Error: " + cause.getMessage() + "\n", "error");
                }
            }
        };

        currentWorker = worker;
        worker.execute();
    }

    /** Cancels any in-flight LLM generation or script execution. */
    public void onCancel() {
        if (currentWorker != null) currentWorker.cancel(true);
        if (activePythonExecutor != null) {
            activePythonExecutor.cancel();
            activePythonExecutor = null;
        }
        stopThinking();
        sendButton.setEnabled(true);
        cancelButton.setVisible(false);
        runButton.setEnabled(lastPipeline != null);

        // If we have a pre-send snapshot, restore the UI to its exact pre-send state:
        // message text back in the input field, attachment chips back in the strip.
        if (preSendInputText != null) {
            inputField.setText(preSendInputText);
            pendingFiles.clear();
            pendingFiles.putAll(preSendPendingFiles);
            pinnedFiles.clear();
            pinnedFiles.putAll(preSendPinnedFiles);
            messageRecords.subList(preSendMessageCount, messageRecords.size()).clear();
            rebuildMessages();
            refreshChips();
            preSendInputText    = null;
            preSendPendingFiles = null;
            preSendPinnedFiles  = null;
        } else {
            appendToChat("Generation cancelled.\n", "system");
        }

        if (!conversationHistory.isEmpty() &&
                "user".equals(conversationHistory.get(conversationHistory.size() - 1)[0])) {
            conversationHistory.remove(conversationHistory.size() - 1);
        }
    }

    /** Prompts for a pipeline name then saves via PipelineManager. */
    public void onSave() {
        if (lastPipeline == null) return;

        String name = JOptionPane.showInputDialog(this,
                "Enter a name for this pipeline:",
                lastPipeline.getTitle());

        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();

        GeneratedPipeline toSave = new GeneratedPipeline(
                name,
                lastPipeline.getExplanation(),
                lastPipeline.getScript(),
                lastPipeline.getLanguage()
        );

        try {
            File saved = manager.savePipeline(toSave, conversationHistory);
            sourceFile = saved;
            updateButton.setEnabled(true);
            appendToChat("Pipeline saved: " + name + "\n", "system");
        } catch (Exception e) {
            appendToChat("Failed to save pipeline: " + e.getMessage() + "\n", "error");
        }
    }

    /**
     * Loads a previously saved pipeline into the chat window, restoring the
     * conversation history so the user can continue editing from where they left off.
     */
    public void loadPipeline(GeneratedPipeline pipeline, File file) {
        lastPipeline = pipeline;
        sourceFile = file;
        conversationHistory.clear();

        List<String[]> history = pipeline.getHistory();
        if (history != null) conversationHistory.addAll(history);

        appendToChat("── Resumed: " + pipeline.getTitle() + " ──\n", "system");

        if (history != null && !history.isEmpty()) {
            for (int i = 0; i < history.size(); i++) {
                String[] entry  = history.get(i);
                boolean isUser  = "user".equals(entry[0]);
                boolean isLast  = (i == history.size() - 1);
                if (isUser) {
                    appendToChat("You: " + entry[1] + "\n", "user");
                } else {
                    appendToChat("AI: " + entry[1] + "\n", "ai");
                    if (isLast) {
                        appendToChat("\n--- " + pipeline.getLanguage().name() + " script ---\n", "system");
                        appendToChat(pipeline.getScript() + "\n", pipeline.getLanguage() == ScriptLanguage.PYTHON ? "code-python" : "code-ijm");
                        appendToChat("--- end of script ---\n\n", "system");
                    }
                }
            }
        } else {
            appendToChat("AI: " + pipeline.getExplanation() + "\n", "ai");
            appendToChat("\n--- " + pipeline.getLanguage().name() + " script ---\n", "system");
            appendToChat(pipeline.getScript() + "\n", pipeline.getLanguage() == ScriptLanguage.PYTHON ? "code-python" : "code-ijm");
            appendToChat("--- end of script ---\n\n", "system");
        }

        runButton.setEnabled(true);
        saveButton.setEnabled(true);
        updateButton.setEnabled(true);
        actionPanel.setVisible(true);
    }

    /** Overwrites the original saved file with the current pipeline and conversation history. */
    public void onUpdate() {
        if (lastPipeline == null || sourceFile == null) return;
        try {
            manager.updatePipeline(sourceFile, lastPipeline, conversationHistory);
            appendToChat("Pipeline updated: " + sourceFile.getName() + "\n", "system");
        } catch (Exception e) {
            appendToChat("Failed to update pipeline: " + e.getMessage() + "\n", "error");
        }
    }

    /** Opens the preferences dialog for backend selection, API key, and license key. */
    private void onSettings() {
        PreferencesDialog dlg = new PreferencesDialog(this);
        dlg.setVisible(true);
        if (dlg.isConfirmed()) {
            updateTierLabel();
            if (TierManager.isPro()) {
                appendToChat("Pro license activated.\n", "system");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Thinking indicator
    // ---------------------------------------------------------------------------

    /** Inserts an animated "Thinking…" bubble and starts the dot-cycling timer. */
    private void startThinking() {
        thinkingDotCount = 0;
        int viewWidth = Math.max(320, scrollPane.getViewport().getWidth() - 20);

        thinkingLabel = new JLabel("Thinking.");
        thinkingLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
        thinkingLabel.setForeground(new Color(90, 90, 90));

        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(AI_BG);
        inner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIVIDER),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        inner.add(thinkingLabel, BorderLayout.CENTER);
        int bw = (int) (viewWidth * 0.35);
        inner.setPreferredSize(new Dimension(bw, 34));
        inner.setMaximumSize(new Dimension(bw, 34));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(inner);

        thinkingWrapper = new JPanel();
        thinkingWrapper.setLayout(new BoxLayout(thinkingWrapper, BoxLayout.Y_AXIS));
        thinkingWrapper.setOpaque(false);
        thinkingWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        thinkingWrapper.add(row);
        thinkingWrapper.add(Box.createVerticalStrut(6));

        messagesPanel.add(thinkingWrapper);
        messagesPanel.revalidate();
        scrollToBottom();

        thinkingTimer = new Timer(500, e -> {
            thinkingDotCount = (thinkingDotCount + 1) % THINKING_FRAMES.length;
            thinkingLabel.setText(THINKING_FRAMES[thinkingDotCount]);
        });
        thinkingTimer.start();
    }

    /** Stops the timer and removes the thinking bubble. */
    private void stopThinking() {
        if (thinkingTimer != null) {
            thinkingTimer.stop();
            thinkingTimer = null;
        }
        if (thinkingWrapper != null) {
            messagesPanel.remove(thinkingWrapper);
            thinkingWrapper = null;
            thinkingLabel = null;
            messagesPanel.revalidate();
            messagesPanel.repaint();
        }
    }

    // ---------------------------------------------------------------------------
    // Live Python output bubble
    // ---------------------------------------------------------------------------

    /** Creates the live output bubble and adds it to the messages panel. Must be called on EDT. */
    private void startPythonOutputBubble() {
        pythonOutputArea = new JTextArea();
        pythonOutputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pythonOutputArea.setEditable(false);
        pythonOutputArea.setLineWrap(true);
        pythonOutputArea.setWrapStyleWord(false); // hard-wrap long tokens (e.g. stack trace paths)
        pythonOutputArea.setBackground(PYTHON_OUTPUT_BG);
        pythonOutputArea.setForeground(PYTHON_ACCENT);
        pythonOutputArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

//        JLabel titleLabel = new JLabel("Python Standard Output");
        JLabel titleLabel = new JLabel("");
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        titleLabel.setForeground(PYTHON_ACCENT);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PYTHON_OUTPUT_BG);
//        panel.setBorder(BorderFactory.createLineBorder(PYTHON_ACCENT));
        panel.setBorder(BorderFactory.createLineBorder(new Color(255,255,255)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(pythonOutputArea, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        pythonOutputWrapper = new JPanel();
        pythonOutputWrapper.setLayout(new BoxLayout(pythonOutputWrapper, BoxLayout.Y_AXIS));
        pythonOutputWrapper.setOpaque(false);
        pythonOutputWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        pythonOutputWrapper.add(panel);
        pythonOutputWrapper.add(Box.createVerticalStrut(6));

        messagesPanel.add(pythonOutputWrapper);
        messagesPanel.revalidate();
        scrollToBottom();
    }

    /** Appends a line to the live output bubble, creating it on first call. Thread-safe. */
    private void appendToPythonOutputBubble(String line) {
        SwingUtilities.invokeLater(() -> {
            if (pythonOutputArea == null) startPythonOutputBubble();
            pythonOutputBuffer.append(line).append('\n');
            pythonOutputArea.setText(pythonOutputBuffer.toString());

            // Measure height at the current viewport width and set explicit size so
            // BoxLayout allocates the right height (mirrors buildCodeEntry's approach).
            // Must clear the preferred-size override first; once setPreferredSize() has been
            // called, getPreferredSize() returns that stale value instead of recalculating
            // from the current text content, causing the bubble to stop growing after line 1.
            int w = Math.max(320, scrollPane.getViewport().getWidth() - 20);
            pythonOutputArea.setPreferredSize(null);
            pythonOutputArea.setSize(w, Short.MAX_VALUE);
            int h = pythonOutputArea.getPreferredSize().height;
            pythonOutputArea.setPreferredSize(new Dimension(w, h));
            pythonOutputArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));

            messagesPanel.revalidate();
            scrollToBottom();
        });
    }

    /**
     * Finalises the live output bubble: saves its content to messageRecords so it
     * survives a rebuild, then clears the live-bubble state. Must be called on EDT.
     */
    private void finalizePythonOutputBubble() {
        String text = pythonOutputBuffer.toString().trim();
        if (!text.isEmpty()) {
            messageRecords.add(new String[]{text, "code"});
        }
        // The wrapper is already in messagesPanel; just clear the live references.
        pythonOutputArea   = null;
        pythonOutputWrapper = null;
        pythonOutputBuffer.setLength(0);
    }

    // ---------------------------------------------------------------------------
    // History management
    // ---------------------------------------------------------------------------

    public List<String[]> trimmedHistory() {
        int maxEntries = MAX_HISTORY_PAIRS * 2;
        int fromIndex = Math.max(0, conversationHistory.size() - maxEntries);
        List<String[]> trimmed = new ArrayList<>();
        for (int i = fromIndex; i < conversationHistory.size(); i++) {
            String[] entry = conversationHistory.get(i);
            trimmed.add(new String[]{entry[0], entry[1]});
        }
        return trimmed;
    }

    // ---------------------------------------------------------------------------
    // System prompt
    // ---------------------------------------------------------------------------

    public String buildSystemPrompt() {
        String context = ContextCollector.collect();
        String template = loadResource("/system_prompt.txt");
        String pythonSection = loadResource("/system_prompt_python.txt");
        String languageField = "    \"language\": \"ijm\" or \"python\",\n";

        String base = template
                .replace("{{FIJI_CONTEXT}}", context)
                .replace("{{PYTHON_SECTION}}", pythonSection)
                .replace("{{LANGUAGE_FIELD}}", languageField);

        if (pinnedFiles.isEmpty()) return base;

        StringBuilder sb = new StringBuilder(base);
        sb.append("\n\n=== Context Files (attached by user) ===\n");
        sb.append("The user has attached the following files for context. ")
          .append("Reference their content when generating or modifying scripts.\n\n");
        for (Map.Entry<String, String> e : pinnedFiles.entrySet()) {
            sb.append("--- ").append(e.getKey()).append(" ---\n");
            sb.append(e.getValue()).append("\n\n");
        }
        return sb.toString();
    }

    private static String loadResource(String path) {
        InputStream is = ChatWindow.class.getResourceAsStream(path);
        if (is == null) throw new RuntimeException("Prompt resource not found: " + path);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt resource: " + path, e);
        }
    }

    // ---------------------------------------------------------------------------
    // Tier label
    // ---------------------------------------------------------------------------

    /**
     * Returns true when the user is on the cloud free tier and has exhausted their
     * lifetime message allowance. Safe to call before the prefetch completes —
     * lastRemaining is -1 until the server confirms a value, so this never fires
     * prematurely.
     */
    private boolean isCloudFreeQuotaExhausted() {
        if (!(llm instanceof IJBPCloudClient)) return false;
        IJBPCloudClient cloud = (IJBPCloudClient) llm;
        return cloud.getLastRemaining() == 0 && !"paid".equals(cloud.getLastTier());
    }

    private void updateTierLabel() {
        int remaining = -1;
        if (llm instanceof IJBPCloudClient) {
            remaining = ((IJBPCloudClient) llm).getLastRemaining();
        } else if (!TierManager.isPro()) {
            remaining = rateLimiter.getRemaining();
        }

        if (remaining >= 0 && remaining < 5) {
            tierLabel.setText("Messages remaining: " + remaining);
            tierLabel.setForeground(new Color(230, 120, 0));
            tierLabel.setVisible(true);
        } else {
            tierLabel.setVisible(false);
        }
    }

    // ---------------------------------------------------------------------------
    // Static factories
    // ---------------------------------------------------------------------------

    public static void show(LLMClient llm, PipelineManager manager, RateLimiter rateLimiter) {
        SwingUtilities.invokeLater(() -> new ChatWindow(llm, manager, rateLimiter));
    }

    public static void showWithPipeline(LLMClient llm, PipelineManager manager, RateLimiter rateLimiter,
                                        GeneratedPipeline pipeline, File sourceFile) {
        SwingUtilities.invokeLater(() -> {
            ChatWindow w = new ChatWindow(llm, manager, rateLimiter);
            w.loadPipeline(pipeline, sourceFile);
        });
    }
}