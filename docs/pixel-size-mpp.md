# Getting the pixel size (µm/px) for atlas DZI slides

The atlas serves slides as Deep Zoom (DZI) pyramids. `vips dzsave` does **not** store
microns-per-pixel in the DZI, so a slide opens **uncalibrated** in QuPath (measurements in pixels,
no scale bar) unless a pixel size is supplied. The extension reads a pixel size from the catalog and
applies it automatically (see the README *Pixel-size calibration* note); this doc is the recipe for
working out the number to put there.

## The pipeline that loses the pixel size

```
Aperio .svs  ──►  ImageScope "Export Image"  ──►  vips dzsave  ──►  .dzi
(has MPP)         (DOWNSAMPLES → smaller)        (drops MPP)       (no MPP)
```

Two things happen that you must undo:

1. The original SVS knows its pixel size (Aperio writes it into the file).
2. ImageScope's **Export Image** reduces the size — i.e. it downsamples. Each exported pixel then
   covers **more** than one scanned pixel, so the exported image's pixel size is **larger** than the
   original.

## The formula

```
mpp_export  =  mpp_original  ×  ( width_original / width_export )
```

- `mpp_original` — the scanned pixel size, from the SVS.
- `width_original / width_export` — the **downsample factor** the export applied (how many original
  pixels wide the slide is, divided by how many pixels wide the export is). Equal to
  `scan_magnification / export_magnification` if you exported by magnification (e.g. 40× → 20× = 2).

Put `mpp_export` into `src/main/resources/catalog.json` (see *Where to put it* below).

## Step 1 — read `mpp_original` from the SVS

Any of these work; pick whichever is handy:

```bash
# vips built with OpenSlide support (reports µm/px directly)
vips header -f openslide.mpp-x slide.svs        # e.g. 0.2521
vips header -f openslide.objective-power slide.svs   # e.g. 40  (scan magnification)

# or read the Aperio tag straight out of the TIFF ImageDescription
tiffinfo slide.svs | grep -i "MPP\|AppMag"      # ...|MPP = 0.2521|AppMag = 40|...
```

In ImageScope itself: **Image → Image Properties** shows *Microns Per Pixel* and *Magnification*.

> **Lab defaults:** the lab's scanners are Leica Aperio **GT450 (~0.26 µm/px)** and **AT2
> (~0.25 µm/px)** at 40×. Use the real per-slide value when you can; these are only fallbacks.

## Step 2 — get the downsample factor

Compare the widths (pixels), or use the magnification ratio:

```bash
vips header -f width slide.svs        # original, e.g. 98304
vips header -f width export.tif       # the file you fed to dzsave, e.g. 49152
# factor = 98304 / 49152 = 2
```

If you exported by magnification (say the ImageScope export was at 20× from a 40× scan), the factor
is simply `40 / 20 = 2`.

## Step 3 — compute and record

```bash
# mpp_export = mpp_original × factor
python3 -c "print(0.2521 * 98304/49152)"        # 0.5042
```

One-shot helper (reads both files, prints the export mpp):

```bash
orig_mpp=$(vips header -f openslide.mpp-x slide.svs)
orig_w=$(vips header -f width slide.svs)
exp_w=$(vips header -f width export.tif)
python3 -c "print(f'{$orig_mpp * $orig_w/$exp_w:.4f}')"
```

## Where to put it

In `src/main/resources/catalog.json` (the bundled image list):

- **Per image** — add an `"mpp"` field to that case's object:

  ```jsonc
  { "reponame": "…", "image": "HE", "dzi": "…/HE.dzi", "mpp": 0.5042 }
  ```

- **One value for all** — if every slide was exported at the same scale from the same scanner, add a
  single `"defaultMpp"` at the catalog root; it applies to any case without its own `"mpp"`:

  ```jsonc
  { "defaultMpp": 0.5042, "cases": [ … ] }
  ```

The extension applies these on open (and it enables QuPath's scale bar). Nothing is calibrated unless
you supply a value, so a wrong pixel size is never imposed by default.

## Tips & gotchas

- **Export at full resolution** and the math disappears: `factor = 1`, so `mpp_export = mpp_original`,
  and a single `defaultMpp` = the scanner's µm/px covers everything.
- **Don't trust the TIFF resolution tags** ImageScope may write (`XResolution`/`YResolution`) — they
  are often missing or wrong for these exports. The `mpp_original × factor` method is the reliable one.
- **A wrong pixel size is worse than none** — every measurement (areas, distances, densities) scales
  with it. Leave `mpp` out rather than guess.
- You can also bake it into a URL for a one-off test: `…/HE.dzi?mpp=0.5042` (the reader honours it).
