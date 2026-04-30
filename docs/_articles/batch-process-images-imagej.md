---
title: "How to Batch Process Images in ImageJ"
description: "How to run the same analysis pipeline across a folder of images in ImageJ using macros and the built-in batch processor — and how IJPB handles it in one step."
date: 2026-04-29
---

Running the same processing pipeline on hundreds of images by hand is impractical. ImageJ provides two approaches: the **Batch Process** tool for simple operations, and **macros** for anything more complex.

## Method 1: Built-In Batch Processor

For straightforward operations like converting formats or applying a single filter:

1. Go to **Process → Batch → Macro**.
2. Set the **Input** and **Output** folders.
3. Choose the output format.
4. Paste your processing steps in the macro text box.
5. Click **Process**.

This works for single-step operations but has limited flexibility.

## Method 2: Macro with a Loop

For multi-step pipelines, write a macro that loops over all files in a directory:

```javascript
dir = getDirectory("Choose input folder");
outDir = getDirectory("Choose output folder");
list = getFileList(dir);

for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif") && !endsWith(list[i], ".png")) continue;

    open(dir + list[i]);

    // Your processing steps here
    run("8-bit");
    run("Gaussian Blur...", "sigma=2");
    setAutoThreshold("Otsu dark");
    run("Convert to Mask");

    saveAs("Tiff", outDir + list[i]);
    close();
}
```

The `if (!endsWith(...))` check skips non-image files. `saveAs()` writes the result to the output folder.

## Collecting Results Across Images

```javascript
dir = getDirectory("Choose folder");
list = getFileList(dir);
results = dir + "results.csv";
File.saveString("Filename,Mean,Area\n", results);

for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif")) continue;
    open(dir + list[i]);
    run("Measure");
    mean = getResult("Mean", nResults - 1);
    area = getResult("Area", nResults - 1);
    File.append(list[i] + "," + mean + "," + area + "\n", results);
    close();
}
```

## Common Problems

- **Out of memory:** Add `run("Collect Garbage");` inside the loop after each `close()`.
- **Wrong file types processed:** Add `endsWith()` checks for your specific extensions.
- **Results table grows indefinitely:** Call `run("Clear Results");` at the start of each iteration if you only need per-image stats.

<div class="ijpb-callout">
<strong>With IJPB</strong>
Type: <em>"Run my cell counting pipeline on all images in this folder and save results to a CSV."</em> IJPB generates the batch macro, handles file iteration, and exports results automatically. <a href="https://github.com/buswinka/ijpb/releases/latest/download/ImageJPipelineBuilder.jar">Download IJPB free →</a>
</div>
