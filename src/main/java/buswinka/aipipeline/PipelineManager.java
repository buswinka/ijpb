package buswinka.aipipeline;

import ij.IJ;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

public class PipelineManager {
    private static final String CONTEXT_MARKER = "__CONTEXT=\"";

    private final File pipelinesDir;

    /**
     * Production: stores pipelines in Fiji.app/AI_Pipeline_Builder/ so Fiji's
     * script-menu scanner never discovers them (it only scans scripts/ and plugins/).
     * On first run, migrates any existing files from scripts/Pipelines/ automatically.
     */
    public PipelineManager() {
        String ijDir = IJ.getDirectory("imagej");
        if (ijDir == null) {
            ijDir = System.getProperty("user.home") + File.separator + ".fiji-pipelines";
            IJ.log("[AI Pipeline Builder] Warning: Fiji directory not found, using " + ijDir);
        }
        this.pipelinesDir = new File(ijDir, "AI_Pipeline_Builder");
        if (!pipelinesDir.exists()) pipelinesDir.mkdirs();
        migrateFromScriptDir(new File(ijDir, "scripts" + File.separator + "Pipelines"));
    }

    /**
     * Moves pipeline files from the legacy scripts/Pipelines/ location to our new
     * AI_Pipeline_Builder/ directory so Fiji stops auto-discovering them.
     * Only moves files that don't already exist at the destination.
     */
    private void migrateFromScriptDir(File oldDir) {
        if (!oldDir.isDirectory()) return;
        File[] candidates = oldDir.listFiles(
            f -> f.getName().endsWith(".ijm") || f.getName().endsWith(".py"));
        if (candidates == null || candidates.length == 0) return;
        for (File src : candidates) {
            File dst = new File(pipelinesDir, src.getName());
            if (!dst.exists()) {
                try {
                    Files.move(src.toPath(), dst.toPath());
                    DebugLog.log("PipelineManager", "Migrated %s → AI_Pipeline_Builder/", src.getName());
                } catch (IOException e) {
                    IJ.log("[AI Pipeline Builder] Warning: could not migrate " + src.getName() + ": " + e.getMessage());
                }
            } else {
                // Destination already exists — just remove the old copy so Fiji stops discovering it
                src.delete();
            }
        }
    }

    /** Test: inject directory. */
    public PipelineManager(File dir) {
        this.pipelinesDir = dir;
        if (!pipelinesDir.exists()) pipelinesDir.mkdirs();
    }

    // ---------------------------------------------------------------------------
    // Save
    // ---------------------------------------------------------------------------

    /** Save without conversation history (history will not be restorable). */
    public File savePipeline(GeneratedPipeline pipeline) throws IOException {
        return savePipeline(pipeline, null);
    }

    /** Save and embed the full conversation history as a trailing comment. */
    public File savePipeline(GeneratedPipeline pipeline, List<String[]> history) throws IOException {
        boolean isPython = pipeline.getLanguage() == ScriptLanguage.PYTHON;
        String ext     = isPython ? ".py" : ".ijm";
        String comment = isPython ? "# "  : "// ";
        String base    = sanitize(pipeline.getTitle());
        File file      = new File(pipelinesDir, base + ext);
        int suffix = 1;
        while (file.exists()) {
            file = new File(pipelinesDir, base + "_" + suffix + ext);
            suffix++;
        }
        try (OutputStreamWriter fw = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            fw.write(comment + "Pipeline: " + pipeline.getTitle() + "\n");
            fw.write(comment + pipeline.getExplanation() + "\n\n");
            fw.write(pipeline.getScript());
            if (history != null && !history.isEmpty()) {
                fw.write("\n" + comment + CONTEXT_MARKER + JsonHelper.escape(serializeHistory(history)) + "\"");
            }
        }
        injectMenuItem(pipeline.getTitle(), file, pipeline.getLanguage());
        return file;
    }

    // ---------------------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------------------

    /**
     * Loads a previously saved pipeline file back into a {@link GeneratedPipeline}.
     * If the file contains an embedded {@code __CONTEXT} block the conversation
     * history is restored and available via {@link GeneratedPipeline#getHistory()}.
     */
    public GeneratedPipeline loadPipeline(File file) throws IOException {
        String name    = file.getName();
        ScriptLanguage lang = name.endsWith(".py") ? ScriptLanguage.PYTHON : ScriptLanguage.IJM;
        String comment = lang == ScriptLanguage.PYTHON ? "# " : "// ";

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        if (lines.isEmpty()) return null;

        // Parse title: "// Pipeline: <title>"
        String titlePrefix = comment + "Pipeline: ";
        String title = lines.get(0).startsWith(titlePrefix)
                ? lines.get(0).substring(titlePrefix.length()) : "";

        // Parse explanation: "// <explanation>"
        String explanation = (lines.size() > 1 && lines.get(1).startsWith(comment))
                ? lines.get(1).substring(comment.length()) : "";

        // Check whether the last line carries an embedded context block
        List<String[]> history = null;
        int scriptEnd = lines.size();
        if (!lines.isEmpty()) {
            String last = lines.get(lines.size() - 1);
            int ctxIdx = last.indexOf(CONTEXT_MARKER);
            if (ctxIdx >= 0) {
                String escaped = last.substring(ctxIdx + CONTEXT_MARKER.length());
                if (escaped.endsWith("\"")) escaped = escaped.substring(0, escaped.length() - 1);
                // Wrap in a fake JSON object so JsonHelper can unescape the value for us
                String histJson = JsonHelper.extractField("{\"x\":\"" + escaped + "\"}", "x");
                if (histJson != null) history = deserializeHistory(histJson);
                scriptEnd = lines.size() - 1; // exclude the context line
            }
        }

        // Script starts at line index 3 (after title, explanation, blank line)
        StringBuilder script = new StringBuilder();
        for (int i = 3; i < scriptEnd; i++) {
            if (i > 3) script.append("\n");
            script.append(lines.get(i));
        }

        return new GeneratedPipeline(title, explanation, script.toString(), lang, history);
    }

    public File getPipelinesDir() { return pipelinesDir; }

    /** Overwrites an existing pipeline file in place without creating a new menu entry. */
    public void updatePipeline(File file, GeneratedPipeline pipeline, List<String[]> history) throws IOException {
        boolean isPython = pipeline.getLanguage() == ScriptLanguage.PYTHON;
        String comment = isPython ? "# " : "// ";
        try (OutputStreamWriter fw = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            fw.write(comment + "Pipeline: " + pipeline.getTitle() + "\n");
            fw.write(comment + pipeline.getExplanation() + "\n\n");
            fw.write(pipeline.getScript());
            if (history != null && !history.isEmpty()) {
                fw.write("\n" + comment + CONTEXT_MARKER + JsonHelper.escape(serializeHistory(history)) + "\"");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Soft-delete (trash)
    // ---------------------------------------------------------------------------

    /**
     * Moves {@code file} into the OS application-support trash folder
     * ({@code ~/Library/Application Support/IJPB/deleted/} on macOS, etc.)
     * instead of permanently deleting it. Falls back to plain deletion if the
     * trash directory cannot be created.
     */
    public void trashPipeline(File file) {
        File trashDir = getTrashDir();
        if (trashDir != null) {
            if (!trashDir.exists()) trashDir.mkdirs();
            File dest = new File(trashDir, file.getName());
            // Avoid collisions: append a counter suffix if a file already exists there
            int suffix = 1;
            while (dest.exists()) {
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                String base = (dot >= 0) ? name.substring(0, dot) : name;
                String ext  = (dot >= 0) ? name.substring(dot)    : "";
                dest = new File(trashDir, base + "_" + suffix + ext);
                suffix++;
            }
            try {
                Files.move(file.toPath(), dest.toPath());
                DebugLog.log("PipelineManager", "Trashed %s → %s", file.getName(), dest.getAbsolutePath());
                return;
            } catch (IOException e) {
                DebugLog.log("PipelineManager", "Could not move to trash (%s), falling back to delete", e.getMessage());
            }
        }
        // Fallback
        file.delete();
    }

    /** Returns the IJPB deleted-pipelines directory, or {@code null} if the OS location cannot be determined. */
    private File getTrashDir() {
        File appSupport = MachineId.appSupportDir();
        return (appSupport != null) ? new File(appSupport, "deleted") : null;
    }

    /** Removes the named entry from the Fiji Pipelines menu, if present. */
    public void removeMenuItem(String title) {
        SwingUtilities.invokeLater(() -> {
            MenuBar mb = findMenuBar();
            if (mb == null) return;
            for (int i = 0; i < mb.getMenuCount(); i++) {
                Menu menu = mb.getMenu(i);
                if ("Pipelines".equals(menu.getLabel())) {
                    for (int j = 0; j < menu.getItemCount(); j++) {
                        if (title.equals(menu.getItem(j).getLabel())) {
                            menu.remove(j);
                            return;
                        }
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------------------
    // History serialisation
    // ---------------------------------------------------------------------------

    private String serializeHistory(List<String[]> history) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(JsonHelper.escape(history.get(i)[0])).append("\"");
            sb.append(",\"content\":\"").append(JsonHelper.escape(history.get(i)[1])).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Parses a JSON array of {@code {"role":"...","content":"..."}} objects.
     * Handles string literals correctly so braces inside content don't confuse the parser.
     */
    private List<String[]> deserializeHistory(String json) {
        List<String[]> result = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int start = json.indexOf('{', i);
            if (start < 0) break;
            // Walk forward tracking string/escape state to find the matching }
            int depth = 0;
            boolean inString = false;
            int j = start;
            while (j < json.length()) {
                char c = json.charAt(j);
                if (inString) {
                    if (c == '\\') j++;          // skip escaped character
                    else if (c == '"') inString = false;
                } else {
                    if      (c == '"') inString = true;
                    else if (c == '{') depth++;
                    else if (c == '}') { if (--depth == 0) break; }
                }
                j++;
            }
            String obj     = json.substring(start, j + 1);
            String role    = JsonHelper.extractField(obj, "role");
            String content = JsonHelper.extractField(obj, "content");
            if (role != null && content != null) result.add(new String[]{role, content});
            i = j + 1;
        }
        return result;
    }

    // ---------------------------------------------------------------------------
    // Menu injection
    // ---------------------------------------------------------------------------

    /** Scans the pipelines directory and re-injects menu items for every saved pipeline.
     *  Call on plugin startup to replace Fiji's auto-discovered script menu items with
     *  our PythonExecutor-routed versions. Also ensures the machine ID is present and
     *  consistent across all fingerprint locations. */
    public void reinjectAllMenuItems() {
        MachineId.ensureMachineId();

        File[] files = pipelinesDir.listFiles(
            f -> f.getName().endsWith(".ijm") || f.getName().endsWith(".py"));
        if (files == null) return;
        for (File f : files) {
            try {
                GeneratedPipeline p = loadPipeline(f);
                if (p != null) injectMenuItem(p.getTitle(), f, p.getLanguage());
            } catch (IOException ignored) {}
        }
    }

    private void injectMenuItem(String title, File scriptFile, ScriptLanguage lang) {
        SwingUtilities.invokeLater(() -> {
            MenuBar mb = findMenuBar();
            if (mb == null) return;
            // Remove any existing item with this title (Fiji auto-discovered or our own old entry)
            Menu pipelinesMenu = findOrCreateMenu(mb, "Pipelines");
            for (int i = pipelinesMenu.getItemCount() - 1; i >= 0; i--) {
                if (title.equals(pipelinesMenu.getItem(i).getLabel())) {
                    pipelinesMenu.remove(i);
                }
            }
            MenuItem item = new MenuItem(title);
            item.addActionListener(e -> {
                try {
                    String script = new String(Files.readAllBytes(scriptFile.toPath()));
                    // Strip trailing __CONTEXT line before running
                    int ctxLine = script.lastIndexOf("\n");
                    if (ctxLine >= 0 && script.indexOf(CONTEXT_MARKER, ctxLine) >= 0) {
                        script = script.substring(0, ctxLine);
                    }
                    if (lang == ScriptLanguage.PYTHON) {
                        if (!TierManager.canUsePython()) {
                            IJ.showMessage("Python pipelines require the Pro tier.");
                            return;
                        }
                        // Snapshot context on the EDT before handing off to the background thread.
                        final String finalScript = script;
                        final PythonExecutor.FijiCtx fijiCtx = PythonExecutor.snapshotFijiCtx(null);
                        Thread t = new Thread(() -> {
                            PythonExecutor exec = new PythonExecutor(
                                line -> IJ.log(line), line -> IJ.log("[stderr] " + line));
                            try {
                                exec.execute(finalScript, fijiCtx);
                            } catch (Exception ex) {
                                SwingUtilities.invokeLater(() ->
                                    IJ.error("Pipeline Error", ex.getMessage()));
                            }
                        });
                        t.setDaemon(true);
                        t.start();
                    } else {
                        IJ.runMacro(script);
                    }
                } catch (Exception ex) {
                    IJ.error("Pipeline Error", ex.getMessage());
                }
            });
            ensureSeparator(pipelinesMenu);
            pipelinesMenu.add(item);
        });
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private String sanitize(String title) {
        if (title == null || title.trim().isEmpty()) return "pipeline";
        String s = title.replaceAll("[^a-zA-Z0-9_\\-]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "pipeline" : s;
    }

    private MenuBar findMenuBar() {
        try {
            Frame ijFrame = IJ.getInstance();
            if (ijFrame != null && ijFrame.getMenuBar() != null) return ijFrame.getMenuBar();
        } catch (Exception ignored) {}
        for (Frame f : Frame.getFrames()) {
            if (f.isVisible() && f.getMenuBar() != null) return f.getMenuBar();
        }
        return null;
    }

    private void ensureSeparator(Menu menu) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            if ("-".equals(menu.getItem(i).getLabel())) return;
        }
        int insertAt = 0;
        for (int i = 0; i < menu.getItemCount(); i++) {
            String label = menu.getItem(i).getLabel();
            if ("New".equals(label) || "Manage".equals(label) || "Batch Run".equals(label) || "Settings".equals(label)) insertAt = i + 1;
        }
        menu.insertSeparator(insertAt);
    }

    private Menu findOrCreateMenu(MenuBar mb, String name) {
        for (int i = 0; i < mb.getMenuCount(); i++) {
            if (mb.getMenu(i).getLabel().equals(name)) return mb.getMenu(i);
        }
        Menu m = new Menu(name);
        mb.add(m);
        return m;
    }
}