#!/usr/bin/env Rscript
# selftest.R — synthetic end-to-end selftest for the blinded_focus R analysis toolkit.
#
# Mirrors analysis/python/selftest.py: builds 4 sessions on one slide, spanning every accepted
# schema and covering the Phase-2 annotation/cursor additions:
#
# - s1 -- schema/5: 8-element path points ([t,cx,cy,w,h,dsMilli,mouseX,mouseY], varying dsMilli +
#   a known baseMagnification, mouse mostly on-slide with a deliberate off-slide subset) *and* an
#   annotations GeoJSON FeatureCollection (one rectangle covering grid cells rows 1-3 / cols 1-3)
#   that overlaps the session's own dwell/path center, so dwellInAnnotationPct has something to
#   concentrate into.
# - s4 -- schema/4: 6-element path points (dsMilli, no mouse; unknown baseMagnification,
#   exercising point_zoom's ds-only fallback branch), *with* an annotations FeatureCollection (the
#   same rectangle as s1, for cross-user IoU/coincidence) and a scanpath deliberately built to
#   bounce in and out of that rectangle (so annotationReentryCount has a known non-trivial answer).
# - s2 -- schema/3: 5-element path points (no dsMilli, w-proxy zoom fallback), no annotations at all.
# - s3 -- schema/2: no path, no annotations.
#
# One session (s1) is designated as `--reference`. Runs `analyze()` into a temp dir and asserts the
# documented output contract:
#
# - metrics.csv has 4 rows and exactly the spec'd columns, including the Phase-1 zoom/navigation
#   family and the Phase-2 annotation/cursor family (populated for the sessions that have the
#   relevant data, blank -- never a crash -- for the ones that don't).
# - compare_<slug>.csv's cc matrix is symmetric with a 1.0 diagonal, and the similar pair's cc
#   exceeds the dissimilar pair's.
# - consensus_<slug>.png is a valid PNG.
# - reference_<slug>.csv's reference-vs-itself row has high NSS/CC.
# - scanpath_<slug>.csv's levenshtein-similarity diagonal is 1.0 (over the 3 path-carrying sessions
#   s1/s2/s4; s3 is absent).
# - magbands_<slug>.csv is written for the path-carrying sessions (s1, s2, s4).
# - annotations_<slug>.csv's IoU matrix is symmetric, with a 1.0 self-diagonal for the sessions
#   that actually drew an annotation (s1, s4 -- identical rectangles, so their cross-IoU is also
#   1.0), and a coincidence level of exactly 1.0 on the diagonal-reuse row (s1 and s4's identical
#   annotated regions mean every visited cell is shared by both -- impossible under the pre-fix
#   whole-grid-denominator formula, which would instead report ~0.14).
# - raster_from_path produces a non-empty grid for a >=2-point path and NULL for a 0/1-point path;
#   magnificationPercentage is in [0,1]; scanningRatePxPerMin is non-negative; the schema/3
#   5-element (w-proxy) path is handled without crashing.
# - Direct, pipeline-independent regression asserts for the two literature-review bug fixes:
#   magnification_percentage no longer counts held-zoom ties, and coincidence_level normalizes by
#   the visited footprint (>=1 reader), not the whole grid.
# - --figures --res 256 writes at least one valid PNG per session, including a scanpath-rasterized
#   fine heatmap.
# - The same pipeline also works when the input is a .zip archive instead of a directory.
#
# Exits non-zero (`quit(status = 1)`) on any failure.

.args <- commandArgs(trailingOnly = FALSE)
.file_arg <- "--file="
.match <- grep(.file_arg, .args)
.script_dir <- if (length(.match) > 0) {
  dirname(normalizePath(sub(.file_arg, "", .args[.match[1]])))
} else {
  "."
}
source(file.path(.script_dir, "blinded_focus.R"))

GW <- 8L; GH <- 8L
IMG_W <- 2000; IMG_H <- 1500

.gaussian_grid <- function(r0, c0, sigma = 1.3, scale = 1000.0, noise = 20.0, seed = 0) {
  set.seed(seed)
  grid <- matrix(0.0, nrow = GH, ncol = GW)
  for (r in 0:(GH - 1)) {
    for (c in 0:(GW - 1)) {
      d2 <- (r - r0)^2 + (c - c0)^2
      grid[r + 1, c + 1] <- scale * exp(-d2 / (2 * sigma^2))
    }
  }
  grid <- grid + matrix(rnorm(GH * GW, mean = 0, sd = noise), nrow = GH, ncol = GW)
  grid[grid < 0] <- 0
  as.numeric(t(grid)) # row-major flatten
}

#' A synthetic 5-element (schema/3) scanpath dwelling around grid cell (r0, c0), in image px, with
#' jitter. Constant w/h -> constant w-proxy zoom (exercises the fallback without varying it -- the
#' varying-zoom exercise is `.make_path_v4`).
.make_path <- function(r0, c0, n = 30, seed = 42) {
  set.seed(seed)
  cx0 <- (c0 + 0.5) / GW * IMG_W
  cy0 <- (r0 + 0.5) / GH * IMG_H
  path <- matrix(0, nrow = n, ncol = 5)
  t <- 0
  for (i in seq_len(n)) {
    t <- t + 250 # ~4 samples/sec
    cx <- min(max(cx0 + rnorm(1, 0, IMG_W / GW / 4), 0), IMG_W - 1)
    cy <- min(max(cy0 + rnorm(1, 0, IMG_H / GH / 4), 0), IMG_H - 1)
    path[i, ] <- c(t, as.integer(cx), as.integer(cy), 400, 300)
  }
  path
}

#' Shared varying-dsMilli schedule (two "scanning" segments separated by a "drilling" jump) reused
#' by both the schema/4 and schema/5 synthetic-path builders below, so
#' zoomVariance/zoomRange/scanningRatePxPerMin/drillingRatePerMin all have something non-trivial to
#' compute for both.
.ds_schedule <- function(n) {
  c(rep(2000, 12), rep(500, 12), rep(3000, max(n - 24, 0)))
}

#' A synthetic 6-element (schema/4) scanpath dwelling around grid cell (r0, c0), with a varying
#' dsMilli schedule (see `.ds_schedule`) -- no mouse data.
.make_path_v4 <- function(r0, c0, n = 40, seed = 101) {
  set.seed(seed)
  cx0 <- (c0 + 0.5) / GW * IMG_W
  cy0 <- (r0 + 0.5) / GH * IMG_H
  ds_schedule <- .ds_schedule(n)
  path <- matrix(0, nrow = n, ncol = 6)
  t <- 0
  for (i in seq_len(n)) {
    t <- t + 250 # ~4 samples/sec
    ds <- if (i <= length(ds_schedule)) ds_schedule[i] else ds_schedule[length(ds_schedule)]
    cx <- min(max(cx0 + rnorm(1, 0, IMG_W / GW / 4), 0), IMG_W - 1)
    cy <- min(max(cy0 + rnorm(1, 0, IMG_H / GH / 4), 0), IMG_H - 1)
    path[i, ] <- c(t, as.integer(cx), as.integer(cy), 400, 300, ds)
  }
  path
}

#' A synthetic 8-element (schema/5) scanpath: same varying-dsMilli schedule as `.make_path_v4`,
#' dwelling around grid cell (r0, c0), with each point additionally carrying a cursor position
#' (mouseX, mouseY) -- on-slide (near the viewport center, small jitter) for most points, off-slide
#' (-1, -1) sentinel for every 5th point (starting with the first) -- so both cursorOverSlidePct
#' (< 100%) and mouseViewportCouplingPx (computed only over on-slide points) have something
#' non-trivial to exercise.
.make_path_v5 <- function(r0, c0, n = 40, seed = 101) {
  set.seed(seed)
  cx0 <- (c0 + 0.5) / GW * IMG_W
  cy0 <- (r0 + 0.5) / GH * IMG_H
  ds_schedule <- .ds_schedule(n)
  path <- matrix(0, nrow = n, ncol = 8)
  t <- 0
  for (i in seq_len(n)) {
    t <- t + 250 # ~4 samples/sec
    ds <- if (i <= length(ds_schedule)) ds_schedule[i] else ds_schedule[length(ds_schedule)]
    cx <- min(max(cx0 + rnorm(1, 0, IMG_W / GW / 4), 0), IMG_W - 1)
    cy <- min(max(cy0 + rnorm(1, 0, IMG_H / GH / 4), 0), IMG_H - 1)
    if ((i - 1) %% 5 == 0) {
      mouse_x <- -1; mouse_y <- -1 # off-slide sentinel, every 5th tick (1-based i, 0-based tick)
    } else {
      mouse_x <- as.integer(min(max(cx + rnorm(1, 0, 20), 0), IMG_W - 1))
      mouse_y <- as.integer(min(max(cy + rnorm(1, 0, 20), 0), IMG_H - 1))
    }
    path[i, ] <- c(t, as.integer(cx), as.integer(cy), 400, 300, ds, mouse_x, mouse_y)
  }
  path
}

#' A synthetic 6-element (schema/4) scanpath alternating in blocks of `block` samples between a
#' grid cell inside the annotated region (`inside_rc`) and one outside it (`outside_rc`) -- built
#' to exercise `annotation_reentry_count`'s multi-visit run-counting with a known, non-trivial
#' answer (several separate "inside" runs). With n=40, block=5 this produces 8 alternating blocks
#' (4 inside-runs), so the expected reentryCount = 4 - 1 = 3.
.make_path_bouncing <- function(inside_rc, outside_rc, n = 40, seed = 301, block = 5) {
  set.seed(seed)
  ir <- inside_rc[1]; ic <- inside_rc[2]
  orow <- outside_rc[1]; ocol <- outside_rc[2]
  cx_in <- (ic + 0.5) / GW * IMG_W; cy_in <- (ir + 0.5) / GH * IMG_H
  cx_out <- (ocol + 0.5) / GW * IMG_W; cy_out <- (orow + 0.5) / GH * IMG_H
  ds_schedule <- c(rep(2000, n %/% 2), rep(3000, n - n %/% 2))
  path <- matrix(0, nrow = n, ncol = 6)
  t <- 0
  for (i in seq_len(n)) {
    t <- t + 250
    ds <- ds_schedule[i]
    inside <- ((i - 1) %/% block) %% 2 == 0
    if (inside) {
      cx0 <- cx_in; cy0 <- cy_in
    } else {
      cx0 <- cx_out; cy0 <- cy_out
    }
    cx <- min(max(cx0 + rnorm(1, 0, 10), 0), IMG_W - 1)
    cy <- min(max(cy0 + rnorm(1, 0, 10), 0), IMG_H - 1)
    path[i, ] <- c(t, as.integer(cx), as.integer(cy), 400, 300, ds)
  }
  path
}

#' Build a GeoJSON FeatureCollection with one rectangular Polygon annotation covering the
#' inclusive grid-cell range [row0, row1] x [col0, col1], in image-px coordinates -- mirrors the
#' shape QuPath's GsonTools/FeatureCollection.wrap emits (Polygon geometry + minimal properties:
#' name, classification.name), enough to exercise the rasterizer/area functions under test.
.make_annotations_fc <- function(row0, row1, col0, col1) {
  x0 <- col0 / GW * IMG_W
  x1 <- (col1 + 1) / GW * IMG_W
  y0 <- row0 / GH * IMG_H
  y1 <- (row1 + 1) / GH * IMG_H
  list(
    type = "FeatureCollection",
    features = list(list(
      type = "Feature",
      geometry = list(
        type = "Polygon",
        coordinates = list(list(c(x0, y0), c(x1, y0), c(x1, y1), c(x0, y1), c(x0, y0)))
      ),
      properties = list(name = "tumor", classification = list(name = "Tumor"))
    ))
  )
}

.fragment <- function(session_id, schema, grid, duration_ms, sample_count, path = NULL,
                       base_magnification = NULL, path_truncated = NULL, annotations = NULL) {
  d <- list(
    schema = paste0("atlas-focus-contribution/", schema),
    slideKey = "sha256:selftest-slide-0001",
    sessionId = session_id,
    imageWidth = IMG_W, imageHeight = IMG_H,
    gridWidth = GW, gridHeight = GH,
    grid = grid,
    durationMs = duration_ms,
    sampleCount = sample_count,
    date = "2026-07-23"
  )
  if (!is.null(path)) {
    # unclass to a plain matrix-of-rows list so jsonlite serializes it as an array-of-arrays
    d$path <- lapply(seq_len(nrow(path)), function(i) as.numeric(path[i, ]))
  }
  # Only schema/4+ fragments carry these (mirrors the real recorder); s2 (schema/3)/s3 (schema/2)
  # never pass these args, so metrics.csv should render them blank for those sessions.
  if (!is.null(base_magnification)) {
    d$baseMagnification <- base_magnification
  }
  if (!is.null(path_truncated)) {
    d$pathTruncated <- path_truncated
  }
  if (!is.null(annotations)) {
    d$annotations <- annotations
  }
  d
}

build_fragments <- function() {
  grid_s1 <- .gaussian_grid(2, 2, seed = 1)
  grid_s2 <- .gaussian_grid(2, 2, seed = 2) # similar hotspot location to s1
  grid_s3 <- .gaussian_grid(6, 6, sigma = 1.0, seed = 3) # different hotspot location
  grid_s4 <- .gaussian_grid(4, 4, sigma = 1.2, seed = 4) # yet another location, outside the shared annotation rect

  shared_annotation <- .make_annotations_fc(1, 3, 1, 3) # rows 1-3, cols 1-3 -> overlaps s1's dwell/path center

  # s1: schema/5, 8-element path (varying dsMilli + mouse, some off-slide) + a known
  # baseMagnification + an annotation overlapping its own dwell center.
  f1 <- .fragment(
    "s1", 5, grid_s1, 40 * 250, 40,
    path = .make_path_v5(2, 2, n = 40, seed = 101),
    base_magnification = 40.0, path_truncated = FALSE,
    annotations = shared_annotation
  )
  # s2: schema/3, 5-element path (w-proxy zoom fallback; no dsMilli/baseMagnification/annotations).
  f2 <- .fragment("s2", 3, grid_s2, 35 * 250, 35, path = .make_path(2, 2, n = 35, seed = 102))
  # s3: schema/2, no path, no annotations at all.
  f3 <- .fragment("s3", 2, grid_s3, 8000, 32, path = NULL)
  # s4: schema/4, 6-element path (varying dsMilli, no mouse, unknown baseMagnification -> exercises
  # point_zoom's ds-only fallback branch) that deliberately bounces in/out of the SAME annotation
  # rectangle as s1 (for cross-user IoU/coincidence + a known reentry count).
  f4 <- .fragment(
    "s4", 4, grid_s4, 40 * 250, 40,
    path = .make_path_bouncing(c(2, 2), c(6, 6), n = 40, seed = 301),
    path_truncated = FALSE,
    annotations = shared_annotation
  )
  list(f1, f2, f3, f4)
}

write_fragments_to_dir <- function(fragments, d) {
  for (f in fragments) {
    writeLines(jsonlite::toJSON(f, auto_unbox = TRUE), file.path(d, paste0(f$sessionId, ".json")))
  }
}

write_fragments_to_zip <- function(fragments, zip_path) {
  tmp <- tempfile()
  dir.create(tmp)
  names <- c()
  for (f in fragments) {
    fp <- file.path(tmp, paste0(f$sessionId, ".json"))
    writeLines(jsonlite::toJSON(f, auto_unbox = TRUE), fp)
    names <- c(names, fp)
  }
  old_wd <- getwd()
  setwd(tmp)
  on.exit(setwd(old_wd), add = TRUE)
  utils::zip(zip_path, basename(names), flags = "-q")
  unlink(tmp, recursive = TRUE)
}

.assert_png <- function(path) {
  if (!file.exists(path)) stop(sprintf("missing PNG: %s", path))
  con <- file(path, "rb")
  magic <- readBin(con, "raw", 8)
  close(con)
  expected <- as.raw(c(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
  if (!identical(magic, expected)) stop(sprintf("not a valid PNG (bad magic): %s", path))
}

run <- function() {
  tmp <- tempfile(pattern = "bfa-r-selftest-")
  dir.create(tmp)
  on.exit(unlink(tmp, recursive = TRUE), add = TRUE)

  fragments <- build_fragments()

  # --- schema/5 is accepted at load time ---
  stopifnot("s1 fragment should be schema/5" = fragments[[1]]$schema == "atlas-focus-contribution/5")
  stopifnot("SCHEMAS should include schema/5" = "atlas-focus-contribution/5" %in% SCHEMAS)

  in_dir <- file.path(tmp, "in")
  dir.create(in_dir)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out")

  metrics <- analyze(list(in_dir), out_dir, reference = "s1", make_figures = TRUE, res = 256)

  # --- metrics.csv: 4 rows + expected columns (Phase 1 + Phase 2) ---
  stopifnot("expected 4 metrics rows" = nrow(metrics) == 4)
  expected_cols <- c(
    "slide", "session", "durationMs", "sampleCount", "coveragePct", "entropy",
    "comX", "comY", "peakDwell", "nHotspots", "pathPoints", "pathLengthPx",
    "nRevisits", "transitionEntropy",
    "avgZoom", "zoomVariance", "zoomRange", "magnificationPercentage",
    "scanningRatePxPerMin", "drillingRatePerMin", "pathVelocityPxPerSec",
    "linearity", "searchFocusRatio", "baseMagnification", "pathTruncated",
    "nAnnotations", "annotatedAreaPx", "dwellInAnnotationPct", "annotationReentryCount",
    "enrichmentRatio", "cursorOverSlidePct", "mouseViewportCouplingPx"
  )
  stopifnot("metrics.csv columns mismatch" = identical(colnames(metrics), expected_cols))
  stopifnot(
    "dwellInAnnotationPct out of [0,100]" =
      all(metrics$dwellInAnnotationPct >= 0 & metrics$dwellInAnnotationPct <= 100)
  )

  row_s1 <- metrics[metrics$session == "s1", ]
  row_s2 <- metrics[metrics$session == "s2", ]
  row_s3 <- metrics[metrics$session == "s3", ]
  row_s4 <- metrics[metrics$session == "s4", ]

  # --- compare_<slug>.csv: symmetric cc matrix, diagonal 1.0, similar > dissimilar ---
  out_files <- list.files(out_dir)
  compare_files <- out_files[startsWith(out_files, "compare_")]
  stopifnot("expected exactly one compare_ file" = length(compare_files) == 1)
  compare <- utils::read.csv(file.path(out_dir, compare_files[1]), stringsAsFactors = FALSE)

  sessions_sorted <- sort(unique(compare$sessionA))
  cc_mat <- matrix(NA_real_, nrow = length(sessions_sorted), ncol = length(sessions_sorted),
    dimnames = list(sessions_sorted, sessions_sorted))
  for (i in seq_len(nrow(compare))) {
    cc_mat[compare$sessionA[i], compare$sessionB[i]] <- compare$cc[i]
  }
  stopifnot("compare cc matrix not symmetric" = isTRUE(all.equal(cc_mat, t(cc_mat), tolerance = 1e-6)))
  diag_vals <- diag(cc_mat)
  stopifnot("diagonal cc != 1.0" = all(abs(diag_vals - 1.0) < 1e-6))

  cc_s1_s2 <- compare$cc[compare$sessionA == "s1" & compare$sessionB == "s2"][1]
  cc_s1_s3 <- compare$cc[compare$sessionA == "s1" & compare$sessionB == "s3"][1]
  stopifnot("similar pair cc should exceed dissimilar pair cc" = cc_s1_s2 > cc_s1_s3)

  # --- compare_<slug>.csv: coincidenceLevel (one row) + regionCoveragePct (diagonal, per-session) ---
  coincidence_vals <- compare$coincidenceLevel[!is.na(compare$coincidenceLevel)]
  stopifnot("expected exactly one coincidenceLevel row" = length(coincidence_vals) == 1)
  stopifnot("coincidenceLevel out of [0,1]" = coincidence_vals[1] >= 0 && coincidence_vals[1] <= 1)
  diag_region_cov <- compare$regionCoveragePct[compare$sessionA == compare$sessionB]
  stopifnot("regionCoveragePct missing on the diagonal" = all(!is.na(diag_region_cov)))

  # --- consensus PNG ---
  consensus_png <- sub("^compare_", "consensus_", sub("\\.csv$", ".png", compare_files[1]))
  .assert_png(file.path(out_dir, consensus_png))

  # --- reference_<slug>.csv: reference-vs-itself NSS/CC high ---
  ref_files <- out_files[startsWith(out_files, "reference_")]
  stopifnot("expected exactly one reference_ file" = length(ref_files) == 1)
  ref <- utils::read.csv(file.path(out_dir, ref_files[1]), stringsAsFactors = FALSE)
  self_row <- ref[ref$session == "s1", ][1, ]
  stopifnot("reference-vs-itself cc too low" = self_row$cc > 0.99)
  stopifnot("reference-vs-itself nss too low" = self_row$nss > 0.3)

  # --- scanpath_<slug>.csv: levenshtein-sim diagonal 1.0 (s1, s2, s4 -- all have a path) ---
  scan_files <- out_files[startsWith(out_files, "scanpath_")]
  stopifnot("expected exactly one scanpath_ file" = length(scan_files) == 1)
  scan <- utils::read.csv(file.path(out_dir, scan_files[1]), stringsAsFactors = FALSE)
  scan_diag <- scan$levenshteinSim[scan$sessionA == scan$sessionB]
  stopifnot("scanpath diagonal != 1.0" = all(abs(scan_diag - 1.0) < 1e-9))
  stopifnot("schema/2 session should be absent from scanpath output" = !("s3" %in% scan$sessionA))
  stopifnot(
    "scanpath sessions mismatch" =
      setequal(unique(scan$sessionA), c("s1", "s2", "s4"))
  )

  # --- magbands_<slug>.csv: written for the path-carrying sessions (s1, s2, s4) ---
  magband_files <- out_files[startsWith(out_files, "magbands_")]
  stopifnot("expected exactly one magbands_ file" = length(magband_files) == 1)
  magbands_df <- utils::read.csv(file.path(out_dir, magband_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "magbands sessions mismatch" =
      setequal(unique(magbands_df$session), c("s1", "s2", "s4"))
  )

  # --- figures: at least one valid PNG per session, under <out>/<slug>/ ---
  slide_dirs <- out_files[file.info(file.path(out_dir, out_files))$isdir]
  stopifnot("expected exactly one slide figures dir" = length(slide_dirs) == 1)
  session_pngs <- list.files(file.path(out_dir, slide_dirs[1]), pattern = "\\.png$")
  stopifnot("no per-session figures written" = length(session_pngs) >= 1)
  for (png in session_pngs) {
    .assert_png(file.path(out_dir, slide_dirs[1], png))
  }
  stopifnot("missing scanpath overlay figure" = any(grepl("scanpath", session_pngs)))
  stopifnot("missing coverage-over-time figure" = any(grepl("coverage", session_pngs)))
  # Phase 1: scanpath-rasterized fine heatmap at --res (256 here), written per path session
  stopifnot(
    "missing scanpath-rasterized fine heatmap figure" =
      any(grepl("scanpath_raster", session_pngs))
  )

  # --- annotations_<slug>.csv (Phase 2): symmetric IoU, 1.0 diagonal for annotated sessions, 1.0
  # cross-IoU for s1/s4 (identical rectangles), and a coincidence level of exactly 1.0 (the fixed
  # visited-footprint formula; ~0.14 under the old whole-grid one) ---
  ann_files <- out_files[startsWith(out_files, "annotations_")]
  stopifnot("expected exactly one annotations_ file" = length(ann_files) == 1)
  ann <- utils::read.csv(file.path(out_dir, ann_files[1]), stringsAsFactors = FALSE)

  ann_sessions <- sort(unique(ann$sessionA))
  ann_iou_mat <- matrix(NA_real_, nrow = length(ann_sessions), ncol = length(ann_sessions),
    dimnames = list(ann_sessions, ann_sessions))
  for (i in seq_len(nrow(ann))) {
    ann_iou_mat[ann$sessionA[i], ann$sessionB[i]] <- ann$iou[i]
  }
  stopifnot(
    "annotations iou matrix not symmetric" =
      isTRUE(all.equal(ann_iou_mat, t(ann_iou_mat), tolerance = 1e-6))
  )
  # s1/s4 both drew the same non-empty rectangle -> self-IoU and cross-IoU are both 1.0. s2/s3
  # drew nothing -> their self-IoU is 0.0 by iou()'s documented empty-union convention --
  # deliberately NOT asserted to be 1.0 here (that would be a different, unrequested change to
  # iou()'s general semantics).
  for (sid in c("s1", "s4")) {
    self_iou <- ann_iou_mat[sid, sid]
    if (abs(self_iou - 1.0) >= 1e-6) {
      stop(sprintf("%s annotation self-IoU should be 1.0, got %s", sid, self_iou))
    }
  }
  stopifnot(
    "s1/s4 drew the same rectangle -> cross-IoU should be 1.0" =
      abs(ann_iou_mat["s1", "s4"] - 1.0) < 1e-6
  )
  s1_diag <- ann[ann$sessionA == "s1" & ann$sessionB == "s1", ][1, ]
  stopifnot("annotations_<slug>.csv diagonal coincidenceLevel missing" = !is.na(s1_diag$coincidenceLevel))
  stopifnot(
    "expected annotation coincidenceLevel == 1.0 (fixed visited-footprint denominator)" =
      abs(s1_diag$coincidenceLevel - 1.0) < 1e-6
  )

  # --- summary.md written and non-trivial ---
  summary_path <- file.path(out_dir, "summary.md")
  stopifnot("summary.md missing" = file.exists(summary_path))
  stopifnot("summary.md is empty" = file.info(summary_path)$size > 0)

  # --- mixed schema/2 + schema/3 + schema/4 + schema/5 handled: s3 has blank scanpath metrics ---
  stopifnot("schema/2 session should have no pathPoints" = is.na(row_s3$pathPoints))
  stopifnot("schema/5 session pathPoints mismatch" = row_s1$pathPoints == 40)
  stopifnot("schema/3 session pathPoints mismatch" = row_s2$pathPoints == 35)

  # --- Phase 1 zoom/navigation columns: populated for path sessions, blank for s3 ---
  zoom_cols <- c(
    "avgZoom", "zoomVariance", "zoomRange", "magnificationPercentage",
    "scanningRatePxPerMin", "drillingRatePerMin", "pathVelocityPxPerSec",
    "linearity", "searchFocusRatio"
  )
  for (col in zoom_cols) {
    if (!is.na(row_s3[[col]])) {
      stop(sprintf("schema/2 session should have blank %s, got %s", col, row_s3[[col]]))
    }
    if (is.na(row_s1[[col]])) stop(sprintf("schema/5 session (s1) missing %s", col))
    if (is.na(row_s2[[col]])) stop(sprintf("schema/3 session (s2, w-proxy) missing %s", col))
    if (is.na(row_s4[[col]])) stop(sprintf("schema/4 session (s4) missing %s", col))
  }

  for (r in list(row_s1, row_s2, row_s4)) {
    stopifnot(
      "magnificationPercentage out of [0,1]" =
        r$magnificationPercentage >= 0 && r$magnificationPercentage <= 1
    )
    stopifnot("scanningRatePxPerMin negative" = r$scanningRatePxPerMin >= 0)
    stopifnot("drillingRatePerMin negative" = r$drillingRatePerMin >= 0)
  }
  # s1's and s4's dsMilli schedules vary (2000 -> 500 -> 3000, or 2000 -> 3000) -> non-zero zoom
  # spread; s2's w is constant -> zero zoom variance/range (still "handled", just degenerate).
  stopifnot("s1 has varying dsMilli, expected zoomVariance > 0" = row_s1$zoomVariance > 0)
  stopifnot("s1 has varying dsMilli, expected zoomRange > 0" = row_s1$zoomRange > 0)
  stopifnot("s4 has varying dsMilli, expected zoomVariance > 0" = row_s4$zoomVariance > 0)
  stopifnot("s4 has varying dsMilli, expected zoomRange > 0" = row_s4$zoomRange > 0)
  stopifnot("s2 has constant w, expected zoomVariance == 0" = row_s2$zoomVariance == 0)

  # --- baseMagnification / pathTruncated passthrough (schema/4+ only) ---
  stopifnot("s1 baseMagnification mismatch" = row_s1$baseMagnification == 40.0)
  stopifnot("schema/3 has no baseMagnification field" = is.na(row_s2$baseMagnification))
  stopifnot("schema/2 has no baseMagnification field" = is.na(row_s3$baseMagnification))
  stopifnot(
    "s4 deliberately omits baseMagnification to exercise point_zoom's ds-only fallback" =
      is.na(row_s4$baseMagnification)
  )
  stopifnot("schema/5 session should have pathTruncated set" = !is.na(row_s1$pathTruncated))
  stopifnot("schema/3 has no pathTruncated field" = is.na(row_s2$pathTruncated))
  stopifnot("schema/2 has no pathTruncated field" = is.na(row_s3$pathTruncated))
  stopifnot("schema/4 session (s4) should have pathTruncated set" = !is.na(row_s4$pathTruncated))

  # --- Phase 2 annotation columns ---
  # nAnnotations/annotatedAreaPx/dwellInAnnotationPct are grid-only (no path needed) -> populated
  # for every session, including the pathless schema/2 one (0/0.0, no annotations).
  stopifnot("s3 nAnnotations should be 0" = row_s3$nAnnotations == 0)
  stopifnot("s3 annotatedAreaPx should be 0.0" = row_s3$annotatedAreaPx == 0.0)
  stopifnot("s3 dwellInAnnotationPct should be 0.0" = row_s3$dwellInAnnotationPct == 0.0)
  stopifnot("s3 has no annotations -> enrichmentRatio should be blank" = is.na(row_s3$enrichmentRatio))
  # Path-dependent Phase 2 columns: blank without a path at all (schema/2, s3).
  stopifnot(
    "schema/2 (no path) should have blank annotationReentryCount" = is.na(row_s3$annotationReentryCount)
  )
  stopifnot("schema/2 (no path) should have blank cursorOverSlidePct" = is.na(row_s3$cursorOverSlidePct))
  stopifnot(
    "schema/2 (no path) should have blank mouseViewportCouplingPx" = is.na(row_s3$mouseViewportCouplingPx)
  )

  # s2 (schema/3, path present, no annotations, no mouse): reentry is numeric (0, nothing to
  # re-enter since there's no annotation at all), but cursor columns stay blank (5-element path,
  # no mouse data).
  stopifnot("s2 nAnnotations should be 0" = row_s2$nAnnotations == 0)
  stopifnot("s2 annotationReentryCount should be 0" = row_s2$annotationReentryCount == 0)
  stopifnot("schema/3 path has no mouse data -> should be blank" = is.na(row_s2$cursorOverSlidePct))
  stopifnot("schema/3 path has no mouse data -> should be blank" = is.na(row_s2$mouseViewportCouplingPx))

  # s4 (schema/4, path present w/ annotations, no mouse): annotations populated, reentry
  # non-trivial (the bouncing path was built for exactly this), cursor columns still blank.
  stopifnot("s4 nAnnotations should be 1" = row_s4$nAnnotations == 1)
  stopifnot("s4 annotatedAreaPx should be > 0" = row_s4$annotatedAreaPx > 0)
  stopifnot(
    "s4's bouncing path should re-enter its own annotated region at least once" =
      row_s4$annotationReentryCount >= 1
  )
  stopifnot("schema/4 path has no mouse data -> should be blank" = is.na(row_s4$cursorOverSlidePct))
  stopifnot("schema/4 path has no mouse data -> should be blank" = is.na(row_s4$mouseViewportCouplingPx))

  # s1 (schema/5, path present w/ mouse + annotations): everything populated.
  stopifnot("s1 nAnnotations should be 1" = row_s1$nAnnotations == 1)
  stopifnot(
    "expected the 3x3-cell rectangle's shoelace area (750*562.5=421875)" =
      abs(row_s1$annotatedAreaPx - 421875.0) < 1.0
  )
  stopifnot(
    "s1's dwell is centered inside its own annotation -> expected a high dwellInAnnotationPct" =
      row_s1$dwellInAnnotationPct > 50.0
  )
  stopifnot("schema/5 path has mouse data -> should be populated" = !is.na(row_s1$cursorOverSlidePct))
  stopifnot(
    "s1's synthetic path has ~20% off-slide points by design" =
      row_s1$cursorOverSlidePct > 0.0 && row_s1$cursorOverSlidePct < 100.0
  )
  stopifnot(
    "schema/5 path has mouse data -> should be populated" = !is.na(row_s1$mouseViewportCouplingPx)
  )
  stopifnot("mouseViewportCouplingPx should be non-negative" = row_s1$mouseViewportCouplingPx >= 0.0)

  # --- direct metrics-function unit checks (raster_from_path, w-proxy zoom, magPct/scanRate) ---
  s1_path <- fragments[[1]]$path # schema/5, 8-element points, varying dsMilli + mouse
  s2_path <- fragments[[2]]$path # schema/3, 5-element points, constant w

  raster <- raster_from_path(s1_path, IMG_W, IMG_H, 16, 16)
  stopifnot("raster_from_path returned NULL for a >=2-point path" = !is.null(raster))
  stopifnot("unexpected raster size" = length(raster) == 16 * 16)
  stopifnot("raster_from_path grid is all-zero" = sum(raster) > 0)
  stopifnot(
    "raster_from_path should return NULL for an empty path" =
      is.null(raster_from_path(list(), IMG_W, IMG_H, 16, 16))
  )
  stopifnot(
    "raster_from_path should return NULL for a 1-point path" =
      is.null(raster_from_path(s1_path[1], IMG_W, IMG_H, 16, 16))
  )

  magpct <- magnification_percentage(s1_path, 40.0, IMG_W)
  stopifnot("magnificationPercentage out of [0,1]" = magpct >= 0.0 && magpct <= 1.0)
  scan_rate <- scanning_rate_px_per_min(s1_path, 40.0, IMG_W)
  stopifnot("scanningRatePxPerMin negative" = scan_rate >= 0.0)

  # schema/3 5-element path (no dsMilli) -> point_zoom falls back to the w-proxy (img_w/w)
  zoom_5el <- point_zoom(s2_path[[1]], NULL, IMG_W)
  stopifnot("w-proxy zoom should be positive" = zoom_5el > 0)
  stopifnot("s2's path points should be 5-element (schema/3)" = length(s2_path[[1]]) == 5)
  stopifnot("s1's path points should be 8-element (schema/5, incl. mouse)" = length(s1_path[[1]]) == 8)
  stopifnot("s1 (schema/5) should be detected as carrying mouse data" = has_mouse_data(s1_path))
  stopifnot("s2 (schema/3) should NOT be detected as carrying mouse data" = !has_mouse_data(s2_path))

  # --- Fix B targeted assert: magnification_percentage no longer counts held-zoom ties ---
  tie_path <- list(
    c(0, 100, 100, 400, 300, 1000),
    c(250, 100, 100, 400, 300, 1000),
    c(500, 100, 100, 400, 300, 1000)
  )
  magpct_tie <- magnification_percentage(tie_path, NULL, IMG_W)
  stopifnot(
    "a constant-zoom path (all ties) must yield magnificationPercentage == 0.0 under the strict '>' fix" =
      magpct_tie == 0.0
  )

  # --- Fix A targeted assert: coincidence_level uses the visited-footprint denominator ---
  g_a <- c(1.0, 1.0, 0.0, 0.0)
  g_b <- c(1.0, 0.0, 1.0, 0.0)
  # normalise_max(g_a) > 0.1 -> [T,T,F,F]; normalise_max(g_b) > 0.1 -> [T,F,T,F]
  # counts = [2,1,1,0] -> visited footprint (>=1) = 3 cells, coincident (>=2) = 1 cell -> 1/3
  old_broken_value <- 1.0 / 4.0 # what the pre-fix whole-grid-denominator formula returned
  new_value <- coincidence_level(list(g_a, g_b), 0.1)
  stopifnot(
    "expected 1/3 (visited-footprint denominator)" = abs(new_value - (1.0 / 3.0)) < 1e-9
  )
  stopifnot(
    "coincidence_level should no longer match the old whole-grid-denominator formula" =
      abs(new_value - old_broken_value) > 1e-6
  )

  # --- .zip input also works ---
  zip_path <- file.path(tmp, "fragments.zip")
  write_fragments_to_zip(fragments, zip_path)
  zip_out <- file.path(tmp, "out_zip")
  zip_metrics <- analyze(list(zip_path), zip_out, reference = "s1")
  stopifnot("zip input: expected 4 metrics rows" = nrow(zip_metrics) == 4)

  cat("OK: all selftest assertions passed\n")
}

result <- tryCatch(
  {
    run()
    TRUE
  },
  error = function(e) {
    message(sprintf("SELFTEST FAILED: %s", conditionMessage(e)))
    FALSE
  }
)

if (!result) {
  quit(status = 1)
}
