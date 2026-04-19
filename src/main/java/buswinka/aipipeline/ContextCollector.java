package buswinka.aipipeline;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;

public class ContextCollector {

    /** Called at runtime to collect live Fiji state. Called before EVERY generate(). */
    public static String collect() {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) return buildNoImageContext();

        Calibration cal = imp.getCalibration();
        RoiManager rm = RoiManager.getInstance();
        int roiCount = (rm != null) ? rm.getCount() : 0;
        ResultsTable rt = ResultsTable.getResultsTable();
        boolean rtOpen = rt != null && rt.size() > 0;
        String[] rtHeadings = rtOpen ? rt.getHeadings() : new String[0];

        return buildContext(
            imp.getTitle(), imp.getWidth(), imp.getHeight(),
            imp.getNChannels(), imp.getNSlices(), imp.getNFrames(),
            imp.getBitDepth(), imp.getType() == ImagePlus.GRAY8 ? "GRAY8" :
                imp.getType() == ImagePlus.GRAY16 ? "GRAY16" :
                imp.getType() == ImagePlus.GRAY32 ? "GRAY32" : "RGB",
            cal.pixelWidth, cal.getUnit(),
            roiCount, rtOpen, rtOpen ? rt.size() : 0, rtHeadings
        );
    }

    public static String buildNoImageContext() {
        return "No image is currently open in Fiji. Scripts can include an open() step.";
    }

    public static String buildContext(String title, int width, int height,
                                      int channels, int slices, int frames,
                                      int bitDepth, String type,
                                      double pixelSize, String unit,
                                      int roiCount, boolean rtOpen, int rtRows,
                                      String[] rtHeadings) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Fiji State ===\n");
        sb.append(String.format("Active image: %s\n", title));
        sb.append(String.format("Dimensions: %dx%d, %d channel(s), %d slice(s), %d frame(s)\n",
            width, height, channels, slices, frames));
        sb.append(String.format("Bit depth: %d-bit (%s)\n", bitDepth, type));
        if (pixelSize > 0 && !unit.trim().isEmpty() && !unit.equals("pixel")) {
            sb.append(String.format("Spatial calibration: %.4f %s/pixel\n", pixelSize, unit));
        } else {
            sb.append("Spatial calibration: uncalibrated (pixels)\n");
        }
        sb.append(String.format("ROI Manager: %d ROI(s) loaded\n", roiCount));
        if (rtOpen) {
            sb.append(String.format("Results Table: open, %d row(s), columns: %s\n",
                rtRows, String.join(", ", rtHeadings)));
        } else {
            sb.append("Results Table: not open\n");
        }
        return sb.toString();
    }
}
