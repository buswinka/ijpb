---
title: "How to Count Cells in ImageJ"
description: "Step-by-step guide to counting cells in ImageJ using thresholding and Analyze Particles — and how IJPB does it in one sentence."
date: 2026-04-29
---

Counting cells in ImageJ is one of the most common tasks in biological image analysis. The standard approach uses thresholding to separate cells from background, then **Analyze Particles** to count distinct objects.

## The Manual Method

### 1. Open your image

Go to **File → Open** and select your image. For best results use a grayscale image, or convert first via **Image → Type → 8-bit**.

### 2. Apply a threshold

Go to **Image → Adjust → Threshold** (or press `Ctrl+Shift+T`). Drag the sliders until cells are highlighted in red. Click **Apply**.

### 3. Convert to a binary mask

Go to **Process → Binary → Make Binary**. This converts your image to pure black and white — cells are black, background is white.

### 4. Run Analyze Particles

Go to **Analyze → Analyze Particles**. Set:

- **Size (pixel²):** A minimum that filters out noise (e.g. `50-Infinity`)
- **Circularity:** `0.5-1.0` for roughly round cells
- **Show:** `Outlines` to preview detections
- Check **Display results** and **Summarize**

Click **OK**. The **Summary** table shows the total count.

## Common Problems

- **Too many small objects:** Increase the minimum size in Analyze Particles.
- **Cells merged together:** Run **Process → Binary → Watershed** before Analyze Particles to split touching cells.
- **Inconsistent threshold across images:** Use **Image → Adjust → Auto Threshold** with `Otsu` for reproducible results.

## ImageJ Macro for Batch Cell Counting

```javascript
dir = getDirectory("Choose folder");
list = getFileList(dir);
for (i = 0; i < list.length; i++) {
    open(dir + list[i]);
    run("8-bit");
    setAutoThreshold("Otsu dark");
    run("Convert to Mask");
    run("Watershed");
    run("Analyze Particles...", "size=50-Infinity circularity=0.5-1.0 display summarize");
    close();
}
```

<div class="ijpb-callout">
<strong>With IJPB</strong>
Open the IJPB chat window in Fiji and type: <em>"Count all cells in this image and show me the total."</em> IJPB writes and runs the full pipeline for you — threshold, watershed, and Analyze Particles — adjusting parameters to your image automatically. <a href="https://github.com/buswinka/ijpb/releases/latest/download/ImageJPipelineBuilder.jar">Download IJPB free →</a>
</div>
