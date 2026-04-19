// src/main/java/buswinka/aipipeline/BatchRunner.java
package buswinka.aipipeline;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BatchRunner {

    // .ome.tif must be checked before .tif
    static final String[] KNOWN_EXTENSIONS = {
        ".ome.tif",
        ".tif", ".tiff", ".png", ".jpg", ".jpeg", ".bmp",
        ".czi", ".lif", ".nd2", ".lsm", ".oib", ".oif",
        ".ims", ".vsi", ".svs", ".ics", ".ids", ".dv"
    };

    private static final Set<String> NATIVE_IJ_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".tif", ".tiff", ".png", ".jpg", ".jpeg", ".bmp"
    ));

    // -----------------------------------------------------------------------
    // Pure static helpers — no IJ dependencies, fully testable
    // -----------------------------------------------------------------------

    /** Strip file extension, treating .ome.tif as a two-part extension. */
    static String extractStem(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".ome.tif")) {
            return filename.substring(0, filename.length() - 8);
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /** True if the file's extension matches any entry in KNOWN_EXTENSIONS (case-insensitive). */
    static boolean matchesImageExtension(File f) {
        String lower = f.getName().toLowerCase();
        for (String ext : KNOWN_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * Returns the output filename for a single image result.
     * @param stem        input stem (e.g. "sample_001")
     * @param imageIndex  1-based index of this image among new images
     * @param total       total number of new images produced
     */
    static String buildOutputName(String stem, int imageIndex, int total) {
        if (total == 1) return stem + ".tif";
        return stem + "_" + imageIndex + ".tif";
    }

    /** True if the file requires Bio-Formats (not a standard single-image format). */
    static boolean isScientificFormat(File f) {
        String lower = f.getName().toLowerCase();
        // .ome.tif is scientific format, even though it ends with .tif
        if (lower.endsWith(".ome.tif")) return true;
        // Check if it's a native ImageJ format
        for (String ext : NATIVE_IJ_EXTENSIONS) {
            if (lower.endsWith(ext)) return false;
        }
        return true;
    }

    /**
     * Returns true if the output folder already contains any file whose name
     * starts with the given stem (e.g. "sample_001.tif", "sample_001_RoiSet.zip").
     * Used by skip-existing logic so pipelines that produce only ROIs or only
     * results are also correctly detected as already-processed.
     */
    static boolean hasExistingOutput(File outputFolder, String stem) {
        File[] existing = outputFolder.listFiles();
        if (existing == null) return false;
        String prefix = stem + ".";      // e.g. "sample_001."
        String prefixUs = stem + "_";    // e.g. "sample_001_" (for _RoiSet.zip, _Results.csv, _1.tif)
        for (File f : existing) {
            String name = f.getName();
            if (name.startsWith(prefix) || name.startsWith(prefixUs)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Progress value object
    // -----------------------------------------------------------------------

    public static class ProgressUpdate {
        public final int done;
        public final int total;
        public final String currentFilename; // null after a file completes
        public final long etaMs;             // -1 if unknown

        public ProgressUpdate(int done, int total, String currentFilename, long etaMs) {
            this.done = done;
            this.total = total;
            this.currentFilename = currentFilename;
            this.etaMs = etaMs;
        }
    }

    // -----------------------------------------------------------------------
    // Instance fields (wired in Task 3+)
    // -----------------------------------------------------------------------

    private final GeneratedPipeline pipeline;
    private final File inputFolder;
    private final File outputFolder;
    private final boolean firstSeriesOnly;
    private final boolean skipExisting;
    private final Consumer<String> logCallback;
    private final Consumer<ProgressUpdate> progressCallback;
    private final Supplier<Boolean> earlyAbortCallback; // returns true = continue, false = stop
    private final Runnable completionCallback; // called on background thread when run finishes

    private volatile boolean cancelled = false;
    private final Deque<Long> recentElapsed = new ArrayDeque<>(); // rolling window for ETA

    public BatchRunner(
            GeneratedPipeline pipeline,
            File inputFolder,
            File outputFolder,
            boolean firstSeriesOnly,
            boolean skipExisting,
            Consumer<String> logCallback,
            Consumer<ProgressUpdate> progressCallback,
            Supplier<Boolean> earlyAbortCallback,
            Runnable completionCallback) {
        this.pipeline = pipeline;
        this.inputFolder = inputFolder;
        this.outputFolder = outputFolder;
        this.firstSeriesOnly = firstSeriesOnly;
        this.skipExisting = skipExisting;
        this.logCallback = logCallback;
        this.progressCallback = progressCallback;
        this.earlyAbortCallback = earlyAbortCallback;
        this.completionCallback = completionCallback;
    }

    public void cancel() { this.cancelled = true; }

    // -----------------------------------------------------------------------
    // State snapshotting
    // -----------------------------------------------------------------------

    /** Returns the current list of open image IDs, or empty array if none. */
    private int[] safeGetIDList() {
        int[] ids = WindowManager.getIDList();
        return ids != null ? ids : new int[0];
    }

    /**
     * Captures the current ROI manager contents as an IdentityHashMap so we
     * can detect new additions by object identity later.
     */
    private IdentityHashMap<Roi, Boolean> snapshotRois() {
        IdentityHashMap<Roi, Boolean> set = new IdentityHashMap<>();
        RoiManager rm = RoiManager.getInstance();
        if (rm != null) {
            Roi[] rois = rm.getRoisAsArray();
            if (rois != null) {
                for (Roi r : rois) set.put(r, Boolean.TRUE);
            }
        }
        return set;
    }

    /** Returns the current Results table row count, or 0 if no table is open. */
    private int snapshotResultsCount() {
        ResultsTable rt = ResultsTable.getResultsTable();
        return rt != null ? rt.size() : 0;
    }

    // -----------------------------------------------------------------------
    // Post-state diffing
    // -----------------------------------------------------------------------

    /**
     * Returns ImagePlus objects for image IDs that are present now but were not
     * in preIds (i.e., images opened by the pipeline).
     */
    private List<ImagePlus> diffImages(int[] preIds) {
        Set<Integer> pre = new HashSet<>();
        for (int id : preIds) pre.add(id);
        int[] postIds = safeGetIDList();
        List<ImagePlus> newImages = new ArrayList<>();
        for (int id : postIds) {
            if (!pre.contains(id)) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) newImages.add(imp);
            }
        }
        return newImages;
    }

    /**
     * Returns ROI objects present in the ROI manager now but not in preRoiSet
     * (identified by object identity, not equality).
     */
    private List<Roi> diffRois(IdentityHashMap<Roi, Boolean> preRoiSet) {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null) return Collections.emptyList();
        Roi[] current = rm.getRoisAsArray();
        if (current == null) return Collections.emptyList();
        List<Roi> newRois = new ArrayList<>();
        for (Roi r : current) {
            if (!preRoiSet.containsKey(r)) newRois.add(r);
        }
        return newRois;
    }

    /**
     * Returns the number of new results rows (rows added beyond preRowCount).
     */
    private int diffResults(int preRowCount) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null) return 0;
        int current = rt.size();
        return Math.max(0, current - preRowCount);
    }

    // -----------------------------------------------------------------------
    // Cleanup helpers
    // -----------------------------------------------------------------------

    /** Closes all images whose IDs are NOT in preIds. */
    private void closeNewImages(int[] preIds) {
        Set<Integer> pre = new HashSet<>();
        for (int id : preIds) pre.add(id);
        int[] postIds = safeGetIDList();
        for (int id : postIds) {
            if (!pre.contains(id)) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) imp.close();
            }
        }
    }

    /**
     * Removes from the ROI manager all ROIs that were not in preRoiSet.
     * Iterates from the end to avoid index-shift bugs.
     */
    private void restoreRois(IdentityHashMap<Roi, Boolean> preRoiSet) {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null) return;
        for (int i = rm.getCount() - 1; i >= 0; i--) {
            Roi r = rm.getRoi(i);
            if (r != null && !preRoiSet.containsKey(r)) {
                rm.select(i);
                rm.runCommand("Delete");
            }
        }
    }

    /**
     * Removes results rows that were added beyond preRowCount.
     * Iterates from the last row downward.
     */
    private void restoreResults(int preRowCount) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null) return;
        for (int i = rt.size() - 1; i >= preRowCount; i--) {
            rt.deleteRow(i);
        }
    }

    // -----------------------------------------------------------------------
    // ETA helpers
    // -----------------------------------------------------------------------

    private void recordElapsed(long ms) {
        recentElapsed.addLast(ms);
        if (recentElapsed.size() > 5) recentElapsed.pollFirst();
    }

    private long computeEta(int remainingFiles) {
        if (recentElapsed.isEmpty() || remainingFiles <= 0) return -1L;
        long sum = 0;
        for (long t : recentElapsed) sum += t;
        long avg = sum / recentElapsed.size();
        return avg * remainingFiles;
    }

    // -----------------------------------------------------------------------
    // Image opening
    // -----------------------------------------------------------------------

    /**
     * Opens the given file and returns an array of ImagePlus objects.
     * Standard formats: returns single-element array via IJ.open().
     * Scientific formats: uses Bio-Formats with the configured series mode.
     * Throws on failure so the caller can log [ERR] and skip.
     */
    private ImagePlus[] openImage(File file) throws Exception {
        if (!isScientificFormat(file)) {
            ImagePlus imp = IJ.openImage(file.getAbsolutePath());
            if (imp == null) throw new Exception("IJ.openImage returned null");
            imp.show();
            return new ImagePlus[]{imp};
        }
        // Bio-Formats path
        try {
            loci.plugins.in.ImporterOptions opts = new loci.plugins.in.ImporterOptions();
            opts.setId(file.getAbsolutePath());
            opts.setWindowless(true);
            if (firstSeriesOnly) {
                opts.setOpenAllSeries(false);
                opts.setSeriesOn(0, true);
            } else {
                opts.setOpenAllSeries(true);
            }
            ImagePlus[] images = loci.plugins.BF.openImagePlus(opts);
            if (images == null || images.length == 0) {
                throw new Exception("Bio-Formats returned no images");
            }
            for (ImagePlus img : images) img.show();
            return images;
        } catch (Exception e) {
            throw new Exception("Bio-Formats open failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Pipeline execution
    // -----------------------------------------------------------------------

    /**
     * Runs the pipeline on the currently active image.
     * For Python: writes a JSON sidecar to a temp file and prepends a load line to the script.
     * For IJM: calls IJ.runMacro directly.
     */
    private void runPipeline(File inputFile, String stem) throws Exception {
        if (pipeline.getLanguage() == ScriptLanguage.PYTHON) {
            if (!TierManager.canUsePython()) {
                throw new Exception("Python pipelines require the Pro tier.");
            }
            runPythonPipeline(inputFile, stem);
        } else {
            String result = IJ.runMacro(pipeline.getScript());
            if ("[aborted]".equals(result)) {
                throw new Exception("Macro aborted");
            }
        }
    }

    /**
     * Snapshots the currently active Fiji image into a FijiCtx (which handles
     * format conversion to TIFF if needed), then executes the script.
     * The context's temp file is cleaned up by PythonExecutor.execute(script, ctx).
     */
    private void runPythonPipeline(File inputFile, String stem) throws Exception {
        // The image is already open and active in Fiji at this point.
        // snapshotFijiCtx saves it to a temp TIFF if the original format is not
        // Python-readable (e.g. .nd2, .czi), so tifffile can always read it.
        PythonExecutor.FijiCtx ctx = PythonExecutor.snapshotFijiCtx(
                outputFolder.getAbsolutePath());

        StringBuilder stderr = new StringBuilder();
        PythonExecutor exec = new PythonExecutor(
            line -> IJ.log("[batch-py] " + line),
            line -> stderr.append(line).append("\n")
        );
        exec.execute(pipeline.getScript(), ctx);

        if (stderr.length() > 0) {
            IJ.log("[batch-py stderr] " + stderr.toString().trim());
        }
    }

    // -----------------------------------------------------------------------
    // Output saving
    // -----------------------------------------------------------------------

    /**
     * Saves all new images, ROIs, and results rows to the output folder.
     * Naming root is stem (already includes series suffix if multi-series).
     * Returns a human-readable summary string for the log (e.g. "1 image, 1 ROI set").
     * Each save failure is logged independently; others continue.
     */
    private String saveOutputs(String stem,
                               List<ImagePlus> newImages,
                               List<Roi> newRois,
                               int newResultsCount) {
        List<String> parts = new ArrayList<>();

        // Save images
        if (newImages.size() == 1) {
            File out = new File(outputFolder, buildOutputName(stem, 1, 1));
            try {
                IJ.saveAsTiff(newImages.get(0), out.getAbsolutePath());
                parts.add("1 image");
            } catch (Exception e) {
                logCallback.accept("[ERR]  save failed: " + out.getName() + " — " + e.getMessage());
            }
        } else if (newImages.size() > 1) {
            int saved = 0;
            for (int i = 0; i < newImages.size(); i++) {
                File out = new File(outputFolder, buildOutputName(stem, i + 1, newImages.size()));
                try {
                    IJ.saveAsTiff(newImages.get(i), out.getAbsolutePath());
                    saved++;
                } catch (Exception e) {
                    logCallback.accept("[ERR]  save failed: " + out.getName() + " — " + e.getMessage());
                }
            }
            if (saved > 0) parts.add(saved + " images");
        }

        // Save ROI set (only the new ROIs, not everything in the manager)
        if (!newRois.isEmpty()) {
            File roiOut = new File(outputFolder, stem + "_RoiSet.zip");
            try {
                RoiManager tempRm = new RoiManager(false); // false = don't show
                for (Roi r : newRois) tempRm.addRoi(r);
                tempRm.runCommand("Save", roiOut.getAbsolutePath());
                tempRm.close();
                parts.add(newRois.size() + " ROI" + (newRois.size() > 1 ? "s" : ""));
            } catch (Exception e) {
                logCallback.accept("[ERR]  save failed: " + roiOut.getName() + " — " + e.getMessage());
            }
        }

        // Save results CSV
        if (newResultsCount > 0) {
            File csvOut = new File(outputFolder, stem + "_Results.csv");
            ResultsTable rt = ResultsTable.getResultsTable();
            if (rt != null) {
                try {
                    rt.saveAs(csvOut.getAbsolutePath());
                    parts.add("results");
                } catch (IOException e) {
                    logCallback.accept("[ERR]  save failed: " + csvOut.getName() + " — " + e.getMessage());
                }
            }
        }

        return String.join(", ", parts);
    }

    // -----------------------------------------------------------------------
    // Main run loop
    // -----------------------------------------------------------------------

    /**
     * Called from a SwingWorker background thread. Processes all matching files
     * in inputFolder sequentially. Progress and log updates are delivered via callbacks.
     */
    public void run() {
        // Collect and sort matching files
        File[] all = inputFolder.listFiles();
        if (all == null) all = new File[0];
        List<File> files = new ArrayList<>();
        for (File f : all) {
            if (f.isFile() && matchesImageExtension(f)) files.add(f);
        }
        files.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        int total = files.size();

        // Log header
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        logCallback.accept("=== Batch Run " + timestamp + " ===");
        logCallback.accept("Pipeline: " + pipeline.getTitle()
            + " [" + (pipeline.getLanguage() == ScriptLanguage.PYTHON ? "PY" : "IJM") + "]");
        logCallback.accept("Input:    " + inputFolder.getAbsolutePath() + " (" + total + " files)");
        logCallback.accept("Output:   " + outputFolder.getAbsolutePath());
        logCallback.accept("Options:  multi-series=" + (firstSeriesOnly ? "first" : "all")
            + ", conflicts=" + (skipExisting ? "skip" : "overwrite"));
        logCallback.accept("---");

        int okCount = 0, warnCount = 0, skipCount = 0, errCount = 0;
        int consecutiveErrors = 0;
        boolean earlyAbortChecked = false;
        long batchStart = System.currentTimeMillis();

        for (int fileIdx = 0; fileIdx < total; fileIdx++) {
            if (cancelled) break;

            File file = files.get(fileIdx);
            String baseStem = extractStem(file.getName());

            // Publish "starting" progress
            progressCallback.accept(new ProgressUpdate(fileIdx, total, file.getName(), computeEta(total - fileIdx)));

            // Skip-if-exists check
            if (skipExisting && hasExistingOutput(outputFolder, baseStem)) {
                logCallback.accept("[SKIP] " + file.getName() + " — output exists");
                skipCount++;
                progressCallback.accept(new ProgressUpdate(fileIdx + 1, total, null, computeEta(total - fileIdx - 1)));
                continue;
            }

            long fileStart = System.currentTimeMillis();

            // Open image(s)
            ImagePlus[] opened;
            try {
                opened = openImage(file);
            } catch (Exception e) {
                logCallback.accept("[ERR]  " + file.getName() + " — " + e.getMessage());
                errCount++;
                consecutiveErrors++;
                recordElapsed(System.currentTimeMillis() - fileStart);
                progressCallback.accept(new ProgressUpdate(fileIdx + 1, total, null, computeEta(total - fileIdx - 1)));
                earlyAbortChecked = checkEarlyAbort(consecutiveErrors, fileIdx, earlyAbortChecked);
                if (cancelled) break;
                continue;
            }

            // Process each series
            boolean fileFailed = false;
            boolean fileHadOutput = false;
            List<String> fileSummaries = new ArrayList<>();

            for (int si = 0; si < opened.length; si++) {
                if (cancelled) break;

                ImagePlus imp = opened[si];
                String stem = (opened.length > 1) ? baseStem + "_s" + si : baseStem;

                // Snapshot pre-state
                int[] preIds = safeGetIDList();
                IdentityHashMap<Roi, Boolean> preRois = snapshotRois();
                int preResults = snapshotResultsCount();

                // Force this image active
                imp.show();
                if (imp.getWindow() != null) WindowManager.setCurrentWindow(imp.getWindow());

                // Execute pipeline
                try {
                    runPipeline(file, stem);
                } catch (Exception e) {
                    logCallback.accept("[ERR]  " + file.getName()
                        + (opened.length > 1 ? " (series " + si + ")" : "")
                        + " — " + e.getMessage());
                    closeNewImages(preIds);
                    for (int ri = si; ri < opened.length; ri++) {
                        if (opened[ri].getWindow() != null) opened[ri].close();
                    }
                    fileFailed = true;
                    break;
                }

                // Diff
                List<ImagePlus> newImages  = diffImages(preIds);
                List<Roi>       newRois    = diffRois(preRois);
                int             newResults = diffResults(preResults);

                boolean hasOutput = !newImages.isEmpty() || !newRois.isEmpty() || newResults > 0;
                if (hasOutput) fileHadOutput = true;

                // Save
                String summary = saveOutputs(stem, newImages, newRois, newResults);
                if (!summary.isEmpty()) fileSummaries.add(summary);

                // Cleanup
                for (ImagePlus img : newImages) img.close();
                if (imp.getWindow() != null) imp.close();
                restoreRois(preRois);
                restoreResults(preResults);
            }

            // Record elapsed and publish progress
            long elapsed = System.currentTimeMillis() - fileStart;
            recordElapsed(elapsed);
            progressCallback.accept(new ProgressUpdate(fileIdx + 1, total, null, computeEta(total - fileIdx - 1)));

            // Log result
            if (fileFailed) {
                errCount++;
                consecutiveErrors++;
            } else if (!fileHadOutput) {
                logCallback.accept("[WARN] " + file.getName() + " — no output detected");
                warnCount++;
                consecutiveErrors = 0;
            } else {
                String combinedSummary = String.join(", ", fileSummaries);
                logCallback.accept("[OK]   " + file.getName() + " \u2192 " + combinedSummary);
                okCount++;
                consecutiveErrors = 0;
            }

            earlyAbortChecked = checkEarlyAbort(consecutiveErrors, fileIdx, earlyAbortChecked);
            if (cancelled) break;
        }

        // Summary footer
        long totalElapsed = System.currentTimeMillis() - batchStart;
        String elapsedStr = formatElapsed(totalElapsed);
        logCallback.accept("---");
        logCallback.accept(String.format(
            "Batch complete: %d OK, %d warning%s, %d skipped, %d error%s (elapsed: %s)",
            okCount,
            warnCount, warnCount == 1 ? "" : "s",
            skipCount,
            errCount, errCount == 1 ? "" : "s",
            elapsedStr));

        if (completionCallback != null) completionCallback.run();
    }

    /**
     * Checks early-abort condition: first 3 consecutive failures on the first 3 files.
     * Calls earlyAbortCallback; if it returns false, sets cancelled = true.
     * Returns true once the check has been performed (so it is never repeated).
     */
    private boolean checkEarlyAbort(int consecutiveErrors, int fileIdx, boolean alreadyChecked) {
        if (alreadyChecked || consecutiveErrors < 3 || fileIdx > 2) return alreadyChecked;
        if (earlyAbortCallback != null) {
            boolean continueRun = earlyAbortCallback.get();
            if (!continueRun) cancelled = true;
        }
        return true;
    }

    private static String formatElapsed(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    /**
     * Appends the full log text to <outputFolder>/batch_log.txt with a separator.
     * Called by BatchRunnerWindow when the run completes.
     */
    public static void autoSaveLog(File outputFolder, String logText) {
        File logFile = new File(outputFolder, "batch_log.txt");
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
            w.write(logText);
            w.write("\n");
        } catch (IOException ignored) {
            // Non-fatal — main run already completed
        }
    }

}