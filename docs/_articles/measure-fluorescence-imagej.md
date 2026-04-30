---
title: "How to Measure Fluorescence Intensity in ImageJ"
description: "Step-by-step guide to measuring mean, integrated, and corrected fluorescence intensity in ImageJ — and how IJPB automates the whole process."
date: 2026-04-29
---

Measuring fluorescence intensity is a core quantification task in cell biology. ImageJ provides precise tools for this, but the workflow has several steps — especially when accounting for background with Corrected Total Cell Fluorescence (CTCF).

## The Manual Method

### 1. Open your image

Go to **File → Open**. Work in a single channel (split channels via **Image → Color → Split Channels** if needed). Use 16-bit or 32-bit images for best precision.

### 2. Set your measurements

Go to **Analyze → Set Measurements**. Check: **Area**, **Mean gray value**, **Integrated density**, **Min & max gray value**. Click **OK**.

### 3. Select a region of interest

Use the **Oval** or **Freehand** selection tool to draw around your cell.

### 4. Measure the ROI

Press `Ctrl+M` or go to **Analyze → Measure**. A row is added to the Results table with area, mean intensity, and integrated density.

### 5. Measure background

Select a cell-free region using the same tool. Press `Ctrl+M` again.

### 6. Calculate CTCF

```
CTCF = Integrated Density − (Area of cell × Mean background intensity)
```

Compute this in the Results table or export to a spreadsheet.

## Measuring Multiple Cells with the ROI Manager

1. Draw an ROI, press `T` to add it to **Analyze → Tools → ROI Manager**.
2. Repeat for each cell.
3. Select all ROIs in the manager and click **Measure**.

## ImageJ Macro for Batch Fluorescence Measurement

```javascript
dir = getDirectory("Choose folder");
list = getFileList(dir);
run("Set Measurements...", "area mean integrated min display redirect=None decimal=3");
for (i = 0; i < list.length; i++) {
    open(dir + list[i]);
    run("Select All");
    run("Measure");
    close();
}
saveAs("Results", dir + "fluorescence_results.csv");
```

<div class="ijpb-callout">
<strong>With IJPB</strong>
Type: <em>"Measure the mean fluorescence intensity of every cell in this image and export a CSV."</em> IJPB handles ROI detection, background subtraction, CTCF calculation, and CSV export automatically. <a href="https://github.com/buswinka/ijpb/releases/latest/download/ImageJPipelineBuilder.jar">Download IJPB free →</a>
</div>
