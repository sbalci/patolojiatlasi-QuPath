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
# - Direct, pipeline-independent regression assert for the annotation-mask union fix:
#   rasterize_feature_collection on two overlapping/nested annotation Features (an outer rectangle
#   + a smaller rectangle nested inside it) must classify the overlap zone as INSIDE the union
#   mask -- the pre-fix pooled-rings even-odd test misclassified a point inside two overlapping
#   Features as outside (even parity).
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
                       base_magnification = NULL, path_truncated = NULL, annotations = NULL,
                       decision = NULL, slide_key = NULL) {
  d <- list(
    schema = paste0("atlas-focus-contribution/", schema),
    slideKey = if (!is.null(slide_key)) slide_key else "sha256:selftest-slide-0001",
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
  # Phase 3: a hand-entered decision object (list(diagnosis=, confidence=, decisionMs=)).
  # Deliberately independent of `annotations`/`path` -- a reader can submit a decision on any
  # schema fragment.
  if (!is.null(decision)) {
    d$decision <- decision
  }
  d
}

#' A synthetic hand-entered decision object -- `list(diagnosis=, confidence=, decisionMs=)` --
#' matching what the QuPath extension's blinded-focus recorder writes into a fragment's `decision`
#' field.
.decision <- function(diagnosis, confidence, decision_ms) {
  list(diagnosis = diagnosis, confidence = confidence, decisionMs = decision_ms)
}

build_fragments <- function() {
  grid_s1 <- .gaussian_grid(2, 2, seed = 1)
  grid_s2 <- .gaussian_grid(2, 2, seed = 2) # similar hotspot location to s1
  grid_s3 <- .gaussian_grid(6, 6, sigma = 1.0, seed = 3) # different hotspot location
  grid_s4 <- .gaussian_grid(4, 4, sigma = 1.2, seed = 4) # yet another location, outside the shared annotation rect

  shared_annotation <- .make_annotations_fc(1, 3, 1, 3) # rows 1-3, cols 1-3 -> overlaps s1's dwell/path center

  # s1: schema/5, 8-element path (varying dsMilli + mouse, some off-slide) + a known
  # baseMagnification + an annotation overlapping its own dwell center. Also carries a Phase 3
  # decision (diagnosis="tumor", confidence=4 -> confidenceScaled=(4-1)/4=0.75).
  f1 <- .fragment(
    "s1", 5, grid_s1, 40 * 250, 40,
    path = .make_path_v5(2, 2, n = 40, seed = 101),
    base_magnification = 40.0, path_truncated = FALSE,
    annotations = shared_annotation,
    decision = .decision("tumor", 4, 5000)
  )
  # s2: schema/3, 5-element path (w-proxy zoom fallback; no dsMilli/baseMagnification/annotations).
  # Also carries a Phase 3 decision (diagnosis="benign", confidence=2).
  f2 <- .fragment(
    "s2", 3, grid_s2, 35 * 250, 35, path = .make_path(2, 2, n = 35, seed = 102),
    decision = .decision("benign", 2, 5000)
  )
  # s3: schema/2, no path, no annotations, and (deliberately) no decision either -- exercises the
  # blank-diagnosis/confidence/correct degrade path in decisions.csv.
  f3 <- .fragment("s3", 2, grid_s3, 8000, 32, path = NULL)
  # s4: schema/4, 6-element path (varying dsMilli, no mouse, unknown baseMagnification -> exercises
  # point_zoom's ds-only fallback branch) that deliberately bounces in/out of the SAME annotation
  # rectangle as s1 (for cross-user IoU/coincidence + a known reentry count). Deliberately left
  # undecided (no `decision`), same as s3.
  f4 <- .fragment(
    "s4", 4, grid_s4, 40 * 250, 40,
    path = .make_path_bouncing(c(2, 2), c(6, 6), n = 40, seed = 301),
    path_truncated = FALSE,
    annotations = shared_annotation
  )
  list(f1, f2, f3, f4)
}

GRADED_SLIDE_KEY <- "sha256:selftest-slide-graded-0001"
#: The display-only answer key's correctDx for GRADED_SLIDE_KEY.
GRADED_KEY_DX <- "tumor"

COLLISION_SLIDE_KEY <- "sha256:selftest-slide-collision-0001"
#: Shared display label a coordinator's --labels CSV (realistically) assigns to two DIFFERENT
#: sessions on the same slide -- the exact scenario the Finding-1 regression guards against.
COLLISION_LABEL <- "Reader X"

#' A GW*GH-length grid with the first `n_nonzero` cells set to `value`, the rest 0.0 -- gives
#' `coveragePct` (`count(g>0)/length(g)*100`) a known, hand-derivable value.
.n_nonzero_grid <- function(n_nonzero, value = 100.0) {
  g <- rep(0.0, GW * GH)
  if (n_nonzero > 0) {
    g[seq_len(n_nonzero)] <- value
  }
  g
}

#' Regression fixture for the Finding-1 fix: two sessions on ONE slide that a coordinator's
#' --labels CSV maps to the SAME display label -- a realistic mistake (e.g. two different readers
#' both entered as "Reader X"). `collide-a` is dense (high coveragePct, hand-graded correct=1);
#' `collide-b` is sparse (low coveragePct, hand-graded correct=0).
#'
#' Pre-fix, `.nav_accuracy_rows` recovered each metrics row's sessionId via a (slide,
#' display-label) lookup into decision_rows -- since both sessions share the same label, that
#' lookup collapses to whichever session's decision row was built last (`collide-b`, in
#' fragment/insertion order), so BOTH metrics rows would be (mis)attributed to `collide-b`'s grade
#' (correct=0). The "correct" group vanishes entirely (meanCorrect renders blank) even though
#' `collide-a` was genuinely graded correct. Post-fix (direct sessionId join, no label bridge),
#' each session's own coveragePct lands in its own, correctly-graded group.
#'
#' Returns `list(fragments=, labels_rows=, graded_rows=)`.
build_label_collision_fragments <- function() {
  dense <- .n_nonzero_grid(60)  # 60/64 -> 93.75% coverage
  sparse <- .n_nonzero_grid(4)  # 4/64 -> 6.25% coverage
  frag_a <- .fragment("collide-a", 2, dense, 6000, 30, slide_key = COLLISION_SLIDE_KEY)
  frag_b <- .fragment("collide-b", 2, sparse, 6000, 30, slide_key = COLLISION_SLIDE_KEY)
  labels_rows <- list(c("collide-a", COLLISION_LABEL), c("collide-b", COLLISION_LABEL))
  graded_rows <- list(
    c(COLLISION_SLIDE_KEY, "collide-a", "1"),
    c(COLLISION_SLIDE_KEY, "collide-b", "0")
  )
  list(fragments = list(frag_a, frag_b), labels_rows = labels_rows, graded_rows = graded_rows)
}

#' A second, independent slide (6 sessions, no paths/annotations) purpose-built to exercise the
#' navigation<->accuracy correlation (`.nav_accuracy_rows`):
#'
#' - `coveragePct` (grid-only, always populated) is deliberately separable by outcome: g1-g3
#'   (graded correct=1) get a dense grid (56/64 nonzero cells -> ~87.5% coverage), g4-g6 (graded
#'   correct=0) get a sparse grid (6/64 nonzero cells -> ~9.4% coverage) -- so meanCorrect >
#'   meanIncorrect (a positive meanDiff) is the expected, hand-derivable result.
#' - `avgZoom` (path-only) is blank for every session here (none carry a path) -> n=0, exercising
#'   the "n < 5" blank-pointBiserialR guard.
#' - `dwellInAnnotationPct` (grid-only, always populated) is exactly 0.0 for every session (none
#'   carry `annotations`) -> zero variance, exercising the "zero variance" blank guard distinctly
#'   from the n=0 case above.
#'
#' Diagnoses are chosen so `correct` can NOT be reconstructed by string-matching `diagnosis`
#' against the answer key ("tumor"): g1 (correct=1) is diagnosed "benign" (a MISMATCH that is
#' still graded correct), and g4 (correct=0) is diagnosed "tumor" (an exact MATCH that is still
#' graded incorrect) -- a hand-grade-only pipeline must report exactly the graded value in both
#' cases; a string-matching one would get both backwards.
#'
#' Returns `list(fragments=, graded_rows=, key_rows=)` where `graded_rows` is a list of
#' `c(slideKey, sessionId, correct)` character vectors (for a synthetic --graded CSV) and
#' `key_rows` is a list of `c(slideKey, correctDx)` character vectors (for a synthetic --key CSV).
build_graded_fragments <- function() {
  .sparse_grid <- function(n_nonzero, value = 100.0) {
    g <- rep(0.0, GW * GH)
    if (n_nonzero > 0) {
      g[seq_len(n_nonzero)] <- value
    }
    g
  }

  # (sessionId, diagnosis, confidence, nNonzeroCells, correct)
  spec <- list(
    list(sid = "g1", dx = "benign", conf = 4, n_nonzero = 56, correct = 1), # dense/high-coverage, MISMATCHED diagnosis, graded correct
    list(sid = "g2", dx = "tumor", conf = 5, n_nonzero = 55, correct = 1),  # dense/high-coverage, matched diagnosis, graded correct
    list(sid = "g3", dx = "tumor", conf = 3, n_nonzero = 54, correct = 1),  # dense/high-coverage, matched diagnosis, graded correct
    list(sid = "g4", dx = "tumor", conf = 5, n_nonzero = 6, correct = 0),   # sparse/low-coverage, MATCHED diagnosis, graded INcorrect
    list(sid = "g5", dx = "benign", conf = 2, n_nonzero = 7, correct = 0),  # sparse/low-coverage, matched diagnosis, graded incorrect
    list(sid = "g6", dx = "unknown", conf = 1, n_nonzero = 8, correct = 0)  # sparse/low-coverage, matched diagnosis, graded incorrect
  )
  fragments <- list()
  graded_rows <- list()
  for (i in seq_along(spec)) {
    s <- spec[[i]]
    f <- .fragment(
      s$sid, 2, .sparse_grid(s$n_nonzero), 6000, 30,
      slide_key = GRADED_SLIDE_KEY,
      decision = .decision(s$dx, s$conf, 4000 + (i - 1) * 50)
    )
    fragments[[length(fragments) + 1]] <- f
    graded_rows[[length(graded_rows) + 1]] <- c(GRADED_SLIDE_KEY, s$sid, as.character(s$correct))
  }
  key_rows <- list(c(GRADED_SLIDE_KEY, GRADED_KEY_DX))
  list(fragments = fragments, graded_rows = graded_rows, key_rows = key_rows)
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

#' Write a trivial CSV (no quoting needed -- every fixture field here is plain alnum text) with a
#' header row followed by one row per element of `rows` (each a character vector). Mirrors the
#' Python selftest's `_write_simple_csv`.
.write_simple_csv <- function(path, header, rows) {
  lines <- paste(header, collapse = ",")
  for (r in rows) {
    lines <- c(lines, paste(r, collapse = ","))
  }
  writeLines(lines, path)
}

#' Phase 3: `decisions.csv` exists with one row per (slide, session), hand-grade-only columns
#' populated for the sessions that carry a `decision` (s1, s2) and blank for the ones that don't
#' (s3, s4) -- and, absent `--graded`, every row's `correct` is blank (nothing is ever
#' auto-derived from `diagnosis`). Reads the CSV back with `colClasses = "character"` so blank
#' cells come back as `""` uniformly (mirrors Python's `csv.DictReader`, sidestepping R's
#' blank-becomes-NA `read.csv` auto-typing for numeric/logical columns).
check_decisions <- function(out_dir) {
  path <- file.path(out_dir, "decisions.csv")
  stopifnot("decisions.csv missing" = file.exists(path))
  rows <- utils::read.csv(path, stringsAsFactors = FALSE, colClasses = "character")
  stopifnot("expected 4 decisions rows (one per slide,session)" = nrow(rows) == 4)
  expected_cols <- c(
    "slide", "sessionId", "session", "diagnosis", "confidence", "confidenceScaled",
    "decisionMs", "decisionLatencyMs", "correctDx", "correct"
  )
  stopifnot("decisions.csv columns mismatch" = identical(colnames(rows), expected_cols))

  s1 <- rows[rows$session == "s1", ][1, ]
  stopifnot("s1 diagnosis mismatch" = s1$diagnosis == "tumor")
  stopifnot("s1 confidence mismatch" = s1$confidence == "4")
  stopifnot("s1 confidenceScaled mismatch" = s1$confidenceScaled == "0.75")
  stopifnot("s1 decisionMs should be non-empty" = nzchar(s1$decisionMs))
  stopifnot("s1 decisionLatencyMs should be non-empty" = nzchar(s1$decisionLatencyMs))

  s3 <- rows[rows$session == "s3", ][1, ]
  stopifnot("undecided session should have blank diagnosis" = s3$diagnosis == "")
  stopifnot("undecided session should have blank confidence" = s3$confidence == "")
  stopifnot("undecided session should have blank correct" = s3$correct == "")

  # Without --graded, every row's `correct` is blank -- HAND-GRADE ONLY, never auto-derived.
  stopifnot("correct should be blank without --graded" = all(rows$correct == ""))
  rows
}

#' Phase 3: without `--graded`, `nav_accuracy.csv` is not written and the summary has no
#' "Navigation" section; with `--graded` (the `build_graded_fragments` fixture), the file exists
#' with the documented columns, a metric with n<5 (`avgZoom`, no session here has a path) and a
#' metric with zero variance (`dwellInAnnotationPct`, constant 0.0 -- no session here has an
#' annotation) both have a blank `pointBiserialR`, and the deliberately-separable `coveragePct`
#' metric has a non-blank, positive `meanDiff` (dense/correct sessions have higher coverage than
#' sparse/incorrect ones).
check_nav_accuracy <- function(nongraded_out_dir, graded_out_dir) {
  # --- without --graded: no nav_accuracy.csv, no summary section ---
  stopifnot(
    "nav_accuracy.csv should not be written without --graded" =
      !file.exists(file.path(nongraded_out_dir, "nav_accuracy.csv"))
  )
  nongraded_summary <- paste(readLines(file.path(nongraded_out_dir, "summary.md"), warn = FALSE), collapse = "\n")
  stopifnot(
    "nav-accuracy summary section should be absent without --graded" =
      !grepl("Navigation", nongraded_summary, fixed = TRUE)
  )

  # --- with --graded: nav_accuracy.csv + summary section present ---
  nav_path <- file.path(graded_out_dir, "nav_accuracy.csv")
  stopifnot("nav_accuracy.csv missing when graded decisions exist" = file.exists(nav_path))
  nav_rows <- utils::read.csv(nav_path, stringsAsFactors = FALSE, colClasses = "character")
  expected_cols <- c(
    "metric", "n", "pointBiserialR", "meanCorrect", "meanIncorrect",
    "medianCorrect", "medianIncorrect", "meanDiff"
  )
  stopifnot("nav_accuracy.csv columns mismatch" = identical(colnames(nav_rows), expected_cols))

  .by_metric <- function(name) nav_rows[nav_rows$metric == name, ][1, ]

  # n<5 guard (avgZoom: path-only, no session here has a path -> n=0)
  avg_zoom <- .by_metric("avgZoom")
  stopifnot("avgZoom n mismatch" = avg_zoom$n == "0")
  stopifnot("n=0 should yield a blank pointBiserialR" = avg_zoom$pointBiserialR == "")

  # zero-variance guard (dwellInAnnotationPct: constant 0.0 across all 6 graded sessions)
  dwell <- .by_metric("dwellInAnnotationPct")
  stopifnot("dwellInAnnotationPct n mismatch" = dwell$n == "6")
  stopifnot(
    "zero-variance navMetric should yield a blank pointBiserialR" = dwell$pointBiserialR == ""
  )

  # deliberately-separable metric: non-blank meanDiff, positive sign (correct > incorrect)
  cov <- .by_metric("coveragePct")
  stopifnot("coveragePct n mismatch" = cov$n == "6")
  stopifnot("coveragePct meanDiff should be populated (n=3 in each group)" = nzchar(cov$meanDiff))
  stopifnot(
    "expected a positive meanDiff (dense/correct sessions have higher coveragePct than sparse/incorrect ones)" =
      as.numeric(cov$meanDiff) > 0
  )

  # --- Tier 1 A1: the extended NAV_ACCURACY_COLS + decisionLatencyMs (sourced from decision_rows,
  # not metrics_rows) all appear as rows ---
  for (extra_col in c("durationMs", "cursorOverSlidePct", "mouseViewportCouplingPx", "decisionLatencyMs")) {
    if (!(extra_col %in% nav_rows$metric)) {
      stop(sprintf("%s missing from nav_accuracy.csv (Tier 1 A1)", extra_col))
    }
  }

  # durationMs: grid-only (always populated), but constant (6000) across every graded session
  # here -> zero variance -> blank pointBiserialR (same guard as dwellInAnnotationPct above).
  duration_row <- .by_metric("durationMs")
  stopifnot("durationMs n mismatch" = duration_row$n == "6")
  stopifnot(
    "durationMs is constant across the graded fixture -> zero variance -> blank r" =
      duration_row$pointBiserialR == ""
  )

  # cursorOverSlidePct/mouseViewportCouplingPx: none of the graded fixture's sessions carry a path
  # at all -> n=0, exercising the same n<5 guard as avgZoom above.
  cursor_row <- .by_metric("cursorOverSlidePct")
  stopifnot("cursorOverSlidePct n mismatch" = cursor_row$n == "0")
  stopifnot("cursorOverSlidePct pointBiserialR should be blank" = cursor_row$pointBiserialR == "")
  coupling_row <- .by_metric("mouseViewportCouplingPx")
  stopifnot("mouseViewportCouplingPx n mismatch" = coupling_row$n == "0")

  # decisionLatencyMs: sourced directly from decision_rows (decisionMs=4000+i*50, correct
  # decreasing from 1 to 0) -- a real, non-degenerate n=6 correlation, unlike the two guards
  # above -- and its meanDiff should be negative (higher latency associates with the
  # graded-incorrect group in this synthetic fixture).
  lat_row <- .by_metric("decisionLatencyMs")
  stopifnot("decisionLatencyMs n mismatch" = lat_row$n == "6")
  stopifnot(
    "decisionLatencyMs has n=6 with real variance on both sides -> should be a real r" =
      lat_row$pointBiserialR != ""
  )
  stopifnot(
    "graded fixture's decisionLatencyMs increases while correct decreases -> expected a negative meanDiff" =
      as.numeric(lat_row$meanDiff) < 0
  )

  graded_summary <- paste(readLines(file.path(graded_out_dir, "summary.md"), warn = FALSE), collapse = "\n")
  stopifnot(
    "nav-accuracy summary section missing from summary.md" =
      grepl("## Navigation ↔ diagnostic accuracy", graded_summary, fixed = TRUE)
  )
  nav_rows
}

#' Phase 3 invariant: `correct` in `decisions.csv` is never derivable by string-matching
#' `diagnosis` against `--key`'s `correctDx` -- it comes only from `--graded`. g1 (graded
#' correct=1) is diagnosed "benign", a MISMATCH against the key's "tumor"; g4 (graded correct=0)
#' is diagnosed "tumor", an exact MATCH. A string-matching implementation would report both
#' backwards; a hand-grade-only one reports exactly what `--graded` said.
check_hand_grade_only <- function(graded_out_dir) {
  path <- file.path(graded_out_dir, "decisions.csv")
  rows <- utils::read.csv(path, stringsAsFactors = FALSE, colClasses = "character")
  g1 <- rows[rows$sessionId == "g1", ][1, ]
  g4 <- rows[rows$sessionId == "g4", ][1, ]
  stopifnot("g1 correctDx mismatch" = g1$correctDx == GRADED_KEY_DX)
  stopifnot(
    "g1 should have a diagnosis that MISMATCHES correctDx (regression guard fixture)" =
      g1$diagnosis == "benign" && g1$diagnosis != g1$correctDx
  )
  stopifnot(
    "g1 was hand-graded correct despite a diagnosis/correctDx mismatch" = g1$correct == "1"
  )
  stopifnot("g4 correctDx mismatch" = g4$correctDx == GRADED_KEY_DX)
  stopifnot(
    "g4 should have a diagnosis that MATCHES correctDx (regression guard fixture)" =
      g4$diagnosis == "tumor" && g4$diagnosis == g4$correctDx
  )
  stopifnot(
    "g4 was hand-graded incorrect despite a diagnosis/correctDx match" = g4$correct == "0"
  )
}

#' Finding-1 regression guard: two sessions on one slide sharing a display label via --labels must
#' still be joined to their OWN grade via the stable sessionId, never the label -- see
#' `build_label_collision_fragments`. Pre-fix, both sessions' coveragePct would collapse into
#' whichever session's decision row was built last, emptying the "correct" group entirely
#' (meanCorrect blank) instead of correctly separating the two groups.
check_label_collision_regression <- function(tmp) {
  fixture <- build_label_collision_fragments()
  in_dir <- file.path(tmp, "in_collision")
  dir.create(in_dir)
  write_fragments_to_dir(fixture$fragments, in_dir)

  labels_csv_path <- file.path(tmp, "labels_collision.csv")
  .write_simple_csv(labels_csv_path, c("sessionId", "label"), fixture$labels_rows)
  graded_csv_path <- file.path(tmp, "graded_collision.csv")
  .write_simple_csv(graded_csv_path, c("slideKey", "sessionId", "correct"), fixture$graded_rows)

  out_dir <- file.path(tmp, "out_collision")
  analyze(list(in_dir), out_dir, labels_csv = labels_csv_path, graded_csv = graded_csv_path)

  nav_path <- file.path(out_dir, "nav_accuracy.csv")
  stopifnot("nav_accuracy.csv missing for the label-collision fixture" = file.exists(nav_path))
  nav_rows <- utils::read.csv(nav_path, stringsAsFactors = FALSE, colClasses = "character")
  cov <- nav_rows[nav_rows$metric == "coveragePct", ][1, ]

  expected_dense <- 60.0 / (GW * GH) * 100.0
  expected_sparse <- 4.0 / (GW * GH) * 100.0

  stopifnot("expected both collision sessions joined (n=2)" = cov$n == "2")
  stopifnot(
    "REGRESSION (Finding 1): meanCorrect is blank -- the pre-fix label-based join collapses both same-labeled sessions into the 'incorrect' group" =
      nzchar(cov$meanCorrect)
  )
  stopifnot(
    "REGRESSION (Finding 1): meanIncorrect should not be blank" = nzchar(cov$meanIncorrect)
  )
  stopifnot(
    "expected meanCorrect == collide-a's (graded correct=1) coveragePct -- sessionId join is misattributing groups" =
      abs(as.numeric(cov$meanCorrect) - expected_dense) < 1e-6
  )
  stopifnot(
    "expected meanIncorrect == collide-b's (graded correct=0) coveragePct -- sessionId join is misattributing groups" =
      abs(as.numeric(cov$meanIncorrect) - expected_sparse) < 1e-6
  )
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

  # --- decisions.csv (Phase 3): hand-grade-only, populated for s1/s2, blank for s3/s4, `correct`
  # blank throughout since no --graded was passed to this run ---
  check_decisions(out_dir)

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
    "enrichmentRatio", "cursorOverSlidePct", "mouseViewportCouplingPx",
    "meanAbsTurnAngleDeg", "turnAngleEntropy", "mousePathLengthPx",
    "mouseVelocityPxPerSec", "activeFractionPct"
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

  # --- hotspots_<slug>.csv (Tier 1 A5): written for every session (grid always present), rank
  # ascending + dwellMs descending within each session ---
  hotspot_files <- out_files[startsWith(out_files, "hotspots_")]
  stopifnot("expected exactly one hotspots_ file" = length(hotspot_files) == 1)
  hotspots_df <- utils::read.csv(file.path(out_dir, hotspot_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "hotspots.csv columns mismatch" =
      identical(
        colnames(hotspots_df),
        c("session", "rank", "cellRow", "cellCol", "centerImageX", "centerImageY", "dwellMs", "dwellFrac")
      )
  )
  stopifnot(
    "hotspots should cover every session (grid always present)" =
      setequal(unique(hotspots_df$session), c("s1", "s2", "s3", "s4"))
  )
  stopifnot("dwellFrac out of [0,1]" = all(hotspots_df$dwellFrac >= 0 & hotspots_df$dwellFrac <= 1))
  for (sess in unique(hotspots_df$session)) {
    grp <- hotspots_df[hotspots_df$session == sess, ]
    grp <- grp[order(grp$rank), ]
    if (!identical(grp$rank, seq_len(nrow(grp)))) {
      stop(sprintf("%s: rank should be 1..N", sess))
    }
    dwells <- grp$dwellMs
    if (length(dwells) > 1) {
      for (i in seq_len(length(dwells) - 1)) {
        if (dwells[i] < dwells[i + 1] - 1e-9) {
          stop(sprintf("%s: hotspots not sorted descending by dwellMs", sess))
        }
      }
    }
  }

  # --- transitions_<slug>.csv (Tier 1 A5): path sessions only, <=15 rows/session, count desc ---
  transitions_files <- out_files[startsWith(out_files, "transitions_")]
  stopifnot("expected exactly one transitions_ file" = length(transitions_files) == 1)
  transitions_df <- utils::read.csv(file.path(out_dir, transitions_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "transitions.csv columns mismatch" =
      identical(colnames(transitions_df), c("session", "fromCell", "toCell", "count"))
  )
  stopifnot(
    "transitions sessions should be a subset of path-carrying sessions" =
      all(unique(transitions_df$session) %in% c("s1", "s2", "s4"))
  )
  stopifnot(
    "schema/2 (no path) session should be absent from transitions_<slug>.csv" =
      !("s3" %in% transitions_df$session)
  )
  for (sess in unique(transitions_df$session)) {
    grp <- transitions_df[transitions_df$session == sess, ]
    if (nrow(grp) > 15) {
      stop(sprintf("%s: expected <=15 transitions, got %d", sess, nrow(grp)))
    }
    counts <- grp$count
    if (!identical(counts, sort(counts, decreasing = TRUE))) {
      stop(sprintf("%s: transitions not sorted by count descending", sess))
    }
  }

  # --- overlay_<slug>_scanpaths.png (Tier 1 A6): written when path sessions exist ---
  overlay_files <- out_files[startsWith(out_files, "overlay_")]
  stopifnot("expected exactly one overlay_ file" = length(overlay_files) == 1)
  .assert_png(file.path(out_dir, overlay_files[1]))

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

  # --- Tier 1 A2 (turn-angle) / A3 (mouse kinematics) / A4 (active fraction) columns ---
  for (r in list(row_s1, row_s2, row_s4)) {
    stopifnot("missing meanAbsTurnAngleDeg" = !is.na(r$meanAbsTurnAngleDeg))
    stopifnot(
      "meanAbsTurnAngleDeg out of [0,180]" =
        r$meanAbsTurnAngleDeg >= 0.0 && r$meanAbsTurnAngleDeg <= 180.0
    )
    stopifnot("missing turnAngleEntropy" = !is.na(r$turnAngleEntropy))
    stopifnot(
      "turnAngleEntropy out of [0,1]" = r$turnAngleEntropy >= -1e-6 && r$turnAngleEntropy <= 1.0 + 1e-6
    )
    stopifnot("missing activeFractionPct" = !is.na(r$activeFractionPct))
  }
  stopifnot("schema/2 (no path) should have blank meanAbsTurnAngleDeg" = is.na(row_s3$meanAbsTurnAngleDeg))
  stopifnot("schema/2 (no path) should have blank turnAngleEntropy" = is.na(row_s3$turnAngleEntropy))
  stopifnot("schema/2 (no path) should have blank activeFractionPct" = is.na(row_s3$activeFractionPct))

  # A4 hand-derived, NOT clamped at 100%: s1's synthetic path's first sample sits at tRelMs=250
  # (not 0), so durationMs (== n*250) exceeds the wall-clock span (== (n-1)*250) by construction --
  # a real, not-clamped-away >100% result.
  s1_frag <- fragments[[1]]
  s1_path_full <- s1_frag$path
  n_s1 <- length(s1_path_full)
  span_s1 <- s1_path_full[[n_s1]][1] - s1_path_full[[1]][1]
  expected_active_s1 <- 100.0 * s1_frag$durationMs / span_s1
  stopifnot(
    "expected activeFractionPct matching hand-derived value" =
      abs(row_s1$activeFractionPct - expected_active_s1) < 1e-6
  )
  stopifnot(
    "activeFractionPct should not be clamped at 100%" = row_s1$activeFractionPct > 100.0
  )

  # A3 mouse kinematics: populated only for s1 (schema/5, has mouse data), blank elsewhere.
  stopifnot("s1 missing mousePathLengthPx" = !is.na(row_s1$mousePathLengthPx))
  stopifnot("s1 mousePathLengthPx should be positive" = row_s1$mousePathLengthPx > 0)
  stopifnot("s1 missing mouseVelocityPxPerSec" = !is.na(row_s1$mouseVelocityPxPerSec))
  stopifnot("s1 mouseVelocityPxPerSec should be positive" = row_s1$mouseVelocityPxPerSec > 0)
  for (r in list(row_s2, row_s3, row_s4)) {
    stopifnot("expected blank mousePathLengthPx (no mouse data)" = is.na(r$mousePathLengthPx))
    stopifnot("expected blank mouseVelocityPxPerSec (no mouse data)" = is.na(r$mouseVelocityPxPerSec))
  }

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

  # --- Fix C targeted assert: the annotation mask is the UNION of each Feature's own
  # rasterization, not a single even-odd test over every Feature's rings pooled together. Two
  # overlapping/nested annotation Features -- an outer rectangle (image px [20,80] x [20,80]) and
  # a smaller rectangle nested fully inside it (image px [40,60] x [40,60]) -- on a 10x10 grid over
  # a 100x100 image (cell size 10px, centers at 5,15,...,95). The outer rect's centers fall at
  # grid indices 2-7 (px 25..75); the inner rect's centers fall at indices 4-5 (px 45,55) -- the
  # "overlap zone" that is inside BOTH Features.
  #
  # Hand-derived expected value: since the inner rect is fully nested inside the outer one, the
  # true union of the two Features is just the outer rectangle -- so the overlap-zone cells
  # (rows/cols 4-5) must be classified INSIDE the mask. Concentrating all dwell weight (value 100
  # in each of the 4 overlap cells, 0 everywhere else -- 400 total) exactly on those cells makes
  # dwellInAnnotationPct's hand-derived expected value a clean 100.0%.
  #
  # Under the pre-fix bug (pool every Feature's rings into one flat list, single even-odd test): a
  # point inside both rectangles crosses one edge of each ring set (1 + 1 = 2 crossings) -> EVEN
  # parity -> wrongly classified OUTSIDE. Since all the dwell weight sits exactly on those
  # wrongly-excluded cells, the pre-fix value would instead be 0.0%.
  overlap_gw <- 10L; overlap_gh <- 10L
  overlap_img_w <- 100; overlap_img_h <- 100
  overlap_fc <- list(
    type = "FeatureCollection",
    features = list(
      list(
        type = "Feature",
        geometry = list(
          type = "Polygon",
          coordinates = list(list(c(20, 20), c(80, 20), c(80, 80), c(20, 80), c(20, 20)))
        ),
        properties = list(name = "tumor", classification = list(name = "Tumor"))
      ),
      list(
        type = "Feature",
        geometry = list(
          type = "Polygon",
          coordinates = list(list(c(40, 40), c(60, 40), c(60, 60), c(40, 60), c(40, 40)))
        ),
        properties = list(name = "focus", classification = list(name = "HighGradeFocus"))
      )
    )
  )
  overlap_grid <- rep(0.0, overlap_gw * overlap_gh)
  for (rr in c(4, 5)) {
    for (cc in c(4, 5)) {
      overlap_grid[rr * overlap_gw + cc + 1] <- 100.0 # 0-based (row, col) -> 1-based flat index
    }
  }

  overlap_mask <- rasterize_feature_collection(overlap_fc, overlap_gw, overlap_gh, overlap_img_w, overlap_img_h)
  for (rr in c(4, 5)) {
    for (cc in c(4, 5)) {
      idx <- rr * overlap_gw + cc + 1
      if (!overlap_mask[idx]) {
        stop(sprintf(
          paste(
            "overlap-zone cell (row %d, col %d) -- inside both the outer and inner annotation",
            "Features -- should be inside the UNION mask"
          ),
          rr, cc
        ))
      }
    }
  }

  overlap_pct <- dwell_in_mask_pct(overlap_grid, overlap_mask)
  stopifnot(
    "expected dwellInAnnotationPct == 100.0 (union mask includes the overlap zone; the pre-fix pooled-rings bug would instead yield 0.0)" =
      abs(overlap_pct - 100.0) < 1e-6
  )

  # --- A2 targeted assert: turn-angle metrics on a hand-derivable minimal L-shaped path ---
  # 3 points: (0,0) -> (10,0) -> (10,10) -- one segment heading east (0deg), one heading south
  # (90deg) -- exactly one interior point -> exactly one turn of 90deg.
  l_path <- list(
    c(0, 0, 0, 400, 300),
    c(100, 10, 0, 400, 300),
    c(200, 10, 10, 400, 300)
  )
  mean_turn <- mean_abs_turn_angle_deg(l_path)
  stopifnot(
    "minimal L-shaped path should have a mean abs turn angle of 90deg" = abs(mean_turn - 90.0) < 1e-6
  )
  turn_ent <- turn_angle_entropy(l_path)
  stopifnot(
    "a single-turn path concentrates all mass in one of 8 bins -> entropy ~= 0" = abs(turn_ent) < 1e-6
  )
  # <3 points -> blank (NA), not a crash.
  stopifnot(
    "turn-angle metrics should be blank (NA) for a <3-point path" =
      is.na(mean_abs_turn_angle_deg(l_path[1:2])) && is.na(turn_angle_entropy(l_path[1:2]))
  )
  stopifnot("blank for an empty path" = is.na(mean_abs_turn_angle_deg(list())))

  # --- A3 targeted assert: mouse kinematics skip segments touching the (-1,-1) sentinel, not
  # bridge across them ---
  mk_path <- list(
    c(0, 0, 0, 400, 300, 1000, 0, 0),
    c(100, 10, 0, 400, 300, 1000, 10, 0),   # on-slide segment from point1: dist 10 [counted]
    c(200, 20, 0, 400, 300, 1000, -1, -1),  # sentinel -- both adjoining segments skipped
    c(300, 30, 0, 400, 300, 1000, 40, 0),
    c(400, 40, 0, 400, 300, 1000, 50, 0)    # on-slide segment into point5: dist 10 [counted]
  )
  mplen <- mouse_path_length_px(mk_path)
  stopifnot(
    "expected mousePathLengthPx == 20.0 (sentinel-touching segments skipped, not bridged)" =
      abs(mplen - 20.0) < 1e-6
  )
  mvel <- mouse_velocity_px_per_sec(mk_path)
  stopifnot("mouseVelocityPxPerSec should be positive over the 2 valid segments" = mvel > 0)

  # schema/3 5-element path (no mouse data at all) -> both blank.
  stopifnot(
    "mouse kinematics should be blank for a path with no mouse data" =
      is.na(mouse_path_length_px(s2_path)) && is.na(mouse_velocity_px_per_sec(s2_path))
  )

  # all-off-slide path -> blank (no valid on-slide segment at all, distinct from a "0" path length
  # -- there is nothing measurable, not a measured-and-stationary cursor).
  off_path <- list(
    c(0, 0, 0, 400, 300, 1000, -1, -1),
    c(100, 10, 0, 400, 300, 1000, -1, -1)
  )
  stopifnot(
    "an all-off-slide path should yield a blank mousePathLengthPx, not 0.0" =
      is.na(mouse_path_length_px(off_path))
  )
  stopifnot(
    "an all-off-slide path should yield a blank mouseVelocityPxPerSec" =
      is.na(mouse_velocity_px_per_sec(off_path))
  )

  # --- A4 targeted assert: activeFractionPct, hand-derivable, NOT clamped above 100% ---
  af_path <- list(c(0, 0, 0, 400, 300), c(5000, 10, 0, 400, 300)) # span = 5000ms
  af <- active_fraction_pct(af_path, 7500) # durationMs=7500 -> 150%
  stopifnot("expected activeFractionPct == 150.0 (not clamped)" = abs(af - 150.0) < 1e-6)
  stopifnot(
    "expected blank activeFractionPct for a <2-point path" =
      is.na(active_fraction_pct(af_path[1], 1000))
  )
  stopifnot(
    "expected blank activeFractionPct for a zero-span path" =
      is.na(active_fraction_pct(list(c(0, 0, 0, 400, 300), c(0, 1, 1, 400, 300)), 1000))
  )
  stopifnot(
    "expected blank activeFractionPct for a missing durationMs" =
      is.na(active_fraction_pct(af_path, NULL))
  )

  # --- A5 targeted assert: top_hotspots deterministic tie-break (value desc, flat-index asc) ---
  tie_grid <- c(5.0, 5.0, 3.0, 5.0) # 2x2 grid: ties at value 5.0 in flat cells 0, 1, 3
  top3 <- top_hotspots(tie_grid, 2, 2, n = 3)
  top3_rc <- as.numeric(unlist(lapply(top3, function(h) c(h$row, h$col))))
  expected_rc <- as.numeric(c(0, 0, 0, 1, 1, 1))
  stopifnot("expected value-desc/index-asc tie-break order" = all(top3_rc == expected_rc))

  # --- A5 targeted assert: top_transitions deterministic tie-break (count desc, then
  # fromCell/toCell asc) ---
  seq_tie <- c(0, 1, 0, 2, 0, 1) # transitions: 0->1 (x2), 1->0 (x1), 0->2 (x1), 2->0 (x1)
  tt <- top_transitions(seq_tie, top_n = 10)
  stopifnot(
    "expected the count=2 transition first" =
      tt[[1]]$fromCell == 0 && tt[[1]]$toCell == 1 && tt[[1]]$count == 2
  )
  tt_rest <- as.numeric(unlist(lapply(tt[2:length(tt)], function(t) c(t$fromCell, t$toCell))))
  expected_rest <- as.numeric(c(0, 2, 1, 0, 2, 0))
  stopifnot(
    "expected count-1 ties broken by (fromCell,toCell) ascending" = all(tt_rest == expected_rest)
  )
  stopifnot("top_transitions should be empty for an empty sequence" = length(top_transitions(c())) == 0)
  stopifnot("top_transitions should be empty for a 1-element sequence" = length(top_transitions(c(0))) == 0)

  # --- .zip input also works ---
  zip_path <- file.path(tmp, "fragments.zip")
  write_fragments_to_zip(fragments, zip_path)
  zip_out <- file.path(tmp, "out_zip")
  zip_metrics <- analyze(list(zip_path), zip_out, reference = "s1")
  stopifnot("zip input: expected 4 metrics rows" = nrow(zip_metrics) == 4)

  # --- Phase 3: navigation<->accuracy correlation, on a dedicated graded fixture ---
  graded_fixture <- build_graded_fragments()
  graded_in <- file.path(tmp, "in_graded")
  dir.create(graded_in)
  write_fragments_to_dir(graded_fixture$fragments, graded_in)

  graded_csv_path <- file.path(tmp, "graded.csv")
  .write_simple_csv(graded_csv_path, c("slideKey", "sessionId", "correct"), graded_fixture$graded_rows)
  key_csv_path <- file.path(tmp, "key.csv")
  .write_simple_csv(key_csv_path, c("slideKey", "correctDx"), graded_fixture$key_rows)

  graded_out <- file.path(tmp, "out_graded")
  analyze(list(graded_in), graded_out, key_csv = key_csv_path, graded_csv = graded_csv_path)

  check_nav_accuracy(out_dir, graded_out)
  check_hand_grade_only(graded_out)

  # --- Finding-1 regression: two sessions sharing a --labels display label on one slide ---
  check_label_collision_regression(tmp)

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
