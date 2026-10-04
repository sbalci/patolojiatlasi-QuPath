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
#' field. `prompt_shown_ms` (Tier 3 C5, added 2026-07-23) is OPTIONAL and omitted by default -- a
#' decision built without it is deliberately old-style, exercising the absent-`promptShownMs`
#' blank-degrade path in `decisions.csv`.
.decision <- function(diagnosis, confidence, decision_ms, prompt_shown_ms = NULL) {
  d <- list(diagnosis = diagnosis, confidence = confidence, decisionMs = decision_ms)
  if (!is.null(prompt_shown_ms)) {
    d$promptShownMs <- prompt_shown_ms
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
  # baseMagnification + an annotation overlapping its own dwell center. Also carries a Phase 3
  # decision (diagnosis="tumor", confidence=4 -> confidenceScaled=(4-1)/4=0.75), now WITH a Tier 3
  # C5 promptShownMs=1200 -> responseLatencyMs = 5000-1200 = 3800.
  f1 <- .fragment(
    "s1", 5, grid_s1, 40 * 250, 40,
    path = .make_path_v5(2, 2, n = 40, seed = 101),
    base_magnification = 40.0, path_truncated = FALSE,
    annotations = shared_annotation,
    decision = .decision("tumor", 4, 5000, prompt_shown_ms = 1200)
  )
  # s2: schema/3, 5-element path (w-proxy zoom fallback; no dsMilli/baseMagnification/annotations).
  # Also carries a Phase 3 decision (diagnosis="benign", confidence=2) -- deliberately OLD-STYLE
  # (no promptShownMs), exercising the C5 blank-degrade path for a fragment recorded before the
  # recorder gained promptShownMs.
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

IDLE_SLIDE_KEY <- "sha256:selftest-slide-idle-0001"
#' Tier 2 B1/B2/B3/B4 fixture: a schema/4, 5-point/4-step path with ONE >60s idle gap (step1,
#' p1->p2) that is deliberately ZOOM-UNCHANGED (exercises B1's pan-exclusion of an idle-but-
#' unchanged step, distinct from a zoom-change step), plus a real non-idle zoom-change step (step2)
#' so drillingRatePerMin/drillingRateOctavesPerMin are non-trivial post-exclusion, not just "goes
#' to 0". Every number below is hand-derivable from these exact points -- mirrors the Python
#' toolkit's `build_idle_fragment` exactly; see docs/superpowers/sdd/t2-report.md for the full
#' derivation.
#'
#' steps (path[i] -> path[i+1]):
#'   step0 (i=0): dt=1000 (active),  zoom 25->25  (unchanged), dist=100
#'   step1 (i=1): dt=70000 (IDLE),   zoom 25->25  (unchanged), dist=50
#'   step2 (i=2): dt=1000 (active),  zoom 25->100 (CHANGED),   dist=50
#'   step3 (i=3): dt=1000 (active),  zoom 100->100 (unchanged), dist=60
#' totalSpan=73000ms, idleMs=70000ms, activeSpanMs=3000ms (0.05 active-minutes).
build_idle_fragment <- function() {
  grid <- .n_nonzero_grid(10)
  path <- matrix(
    c(
      0, 0, 0, 400, 300, 1600,
      1000, 100, 0, 400, 300, 1600,
      71000, 100, 50, 400, 300, 1600,
      72000, 150, 50, 400, 300, 400,
      73000, 210, 50, 400, 300, 400
    ),
    nrow = 5, ncol = 6, byrow = TRUE
  )
  .fragment(
    "idle1", 4, grid, 73000, 5, path = path,
    base_magnification = 40.0, path_truncated = FALSE,
    slide_key = IDLE_SLIDE_KEY
  )
}

ZOOMFID_SLIDE_KEY <- "sha256:selftest-slide-zoomfid-0001"
#' Tier 2 B2 fixture, isolated from B1's idle complexity (no idle gap): a schema/4, 3-point/2-step
#' path with NO `baseMagnification` (exercises `point_zoom`'s ds-only fallback -- B4
#' "proxy-downsample" -- and B3's per-session tercile auto-fallback, since a canonical scheme needs
#' baseMagnification too), and a clean doubling-each-step dsMilli schedule so
#' avgZoomLog2W/drillingRateOctavesPerMin are simple, hand-derivable numbers -- mirrors the Python
#' toolkit's `build_zoom_fidelity_fragment` exactly:
#'
#'   zoom (base_mag=NULL -> 1000/dsMilli): point0=1.0, point1=2.0, point2=4.0
#'   step0 (i=0): dt=1000, zoom0=1.0 -> log2=0.0
#'   step1 (i=1): dt=1000, zoom1=2.0 -> log2=1.0
#'   avgZoomLog2W = (1000*0.0 + 1000*1.0) / 2000 = 0.5
#'   drillingRateOctavesPerMin = (|1-0| + |2-1|) / (2000ms/60000) = 2.0 / (1/30) = 60.0
build_zoom_fidelity_fragment <- function() {
  grid <- .n_nonzero_grid(10)
  path <- matrix(
    c(
      0, 0, 0, 400, 300, 1000,
      1000, 0, 0, 400, 300, 500,
      2000, 0, 0, 400, 300, 250
    ),
    nrow = 3, ncol = 6, byrow = TRUE
  )
  .fragment(
    "zoomfid1", 4, grid, 2000, 3, path = path,
    slide_key = ZOOMFID_SLIDE_KEY
  )
}

FIXATION_SLIDE_KEY <- "sha256:selftest-slide-fixation-0001"
#' Tier 3 C1 fixture: a schema/3, 6-point hand-constructed path with a KNOWN fixation structure --
#' "3 tight points spanning 300ms = one fixation, then a jump, then 3 more (a second fixation)".
#' Mirrors the Python toolkit's `build_fixation_fragment` exactly; see
#' docs/superpowers/sdd/t3-report.md for the full derivation.
#'
#' points (t, cx, cy, w, h):
#'   p0=(0,   100,100,400,300)
#'   p1=(100, 102, 99,400,300)
#'   p2=(300, 101,101,400,300)   -- span p0->p2 = 300ms >= MIN_FIXATION_MS(250); dispersion
#'                                  (102-100)+(101-99)=4 <= threshold(0.25*400=100) -> candidate OK
#'   p3=(350, 900,900,400,300)   -- THE JUMP: expanding [p0..p3] would have dispersion (900-100)+
#'                                  (900-99)=1601 > threshold -> expansion stops at p2.
#'     => FIXATION 1: startMs=0, durationMs=300, centerX=(100+102+101)/3=101.0,
#'        centerY=(100+99+101)/3=100.0, nPoints=3. start advances to p3.
#'   p4=(450, 899,902,400,300)
#'   p5=(650, 901,898,400,300)   -- from start=p3: span p3->p5=300ms>=250; w_start=400,threshold=100;
#'                                  dispersion (901-899)+(902-898)=6<=100 -> candidate OK; no more
#'                                  points to expand into.
#'     => FIXATION 2: startMs=350, durationMs=300, centerX=(900+899+901)/3=900.0,
#'        centerY=(900+902+898)/3=900.0, nPoints=3. start advances past the end -> STOP.
#'
#' nFixations=2, both durationMs=300.0 -> meanFixationMs=300.0, medianFixationMs=300.0,
#' sdFixationMs=0.0. No idle gap -> activeSpanMs == total span == 650ms -> fixationsPerMin =
#' 2/(650/60000) = 184.61538461538458.
build_fixation_fragment <- function() {
  grid <- .n_nonzero_grid(10)
  path <- matrix(
    c(
      0, 100, 100, 400, 300,
      100, 102, 99, 400, 300,
      300, 101, 101, 400, 300,
      350, 900, 900, 400, 300,
      450, 899, 902, 400, 300,
      650, 901, 898, 400, 300
    ),
    nrow = 6, ncol = 5, byrow = TRUE
  )
  .fragment(
    "fix1", 3, grid, 650, 6, path = path,
    slide_key = FIXATION_SLIDE_KEY
  )
}

MOUSE_SLIDE_KEY <- "sha256:selftest-slide-mouse-0001"
#' Tier 3 C2 fixture: 2 schema/5 sessions on one slide, hand-constructed mouse-cursor paths whose
#' resulting dwell grids are exactly hand-derivable. Mirrors the Python toolkit's
#' `build_mouse_fixture` exactly; see docs/superpowers/sdd/t4-report.md for the full derivation.
#' Native grid is GW=GH=8 (module constants), so a cell spans (IMG_W/8=250) x (IMG_H/8=187.5)
#' image px.
#'
#' mouse1: 3 points, mouse cursor dwells in cell(0,0) for both steps -> grid: cell(0,0)=2000, rest
#'   0. mouseCoveragePct = 1/64*100 = 1.5625; mouseEntropy ~= 0.0 (single nonzero cell).
#' mouse2: 3 points, step0 mouse at (50,50) [cell(0,0)], step1 mouse at (300,250) [cell(1,1)] ->
#'   grid: cell(0,0)=1000, cell(1,1)=1000, rest 0. mouseCoveragePct = 2/64*100 = 3.125;
#'   mouseEntropy ~= 1.0 (2 equal-mass cells).
#'
#' Cross-reader (mouse_<slug>.csv): iou(mouse1,mouse2,thresh=0.1) = 0.5 EXACTLY (union={cell(0,0),
#' cell(1,1)}, intersection={cell(0,0)}); coincidenceLevel = 0.5 EXACTLY (visited footprint=2
#' cells, coincident=1 cell) -- matches iou here by construction, not a formula coincidence.
build_mouse_fixture <- function() {
  grid <- .n_nonzero_grid(10)
  path1 <- matrix(
    c(
      0, 1000, 750, 400, 300, 1000, 50, 50,
      1000, 1000, 750, 400, 300, 1000, 60, 60,
      2000, 1000, 750, 400, 300, 1000, 70, 70
    ),
    nrow = 3, ncol = 8, byrow = TRUE
  )
  path2 <- matrix(
    c(
      0, 1000, 750, 400, 300, 1000, 50, 50,
      1000, 1000, 750, 400, 300, 1000, 300, 250,
      2000, 1000, 750, 400, 300, 1000, 310, 260
    ),
    nrow = 3, ncol = 8, byrow = TRUE
  )
  f1 <- .fragment("mouse1", 5, grid, 2000, 3, path = path1, slide_key = MOUSE_SLIDE_KEY)
  f2 <- .fragment("mouse2", 5, grid, 2000, 3, path = path2, slide_key = MOUSE_SLIDE_KEY)
  list(f1, f2)
}

MOUSE_ICC_SLIDE_KEY <- "sha256:selftest-slide-mouse-icc-0001"
#' PT2 fixture (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2): 2 schema/5 sessions
#' with a BYTE-IDENTICAL mouse path -- their post-resample mouse-dwell grids are therefore
#' identical arrays. Hand-derivation of ICC(2,1) for 2 identical columns in the two-way-ANOVA
#' formula `icc()` uses: col_means both == grand_mean -> ss_cols == 0 -> ms_cols == 0; row_means
#' == the (shared) per-cell value -> ss_rows == ss_total exactly -> ss_error == 0 -> ms_error == 0
#' -> denom == ms_rows == (ms_rows - ms_error) -> ICC == 1.0 EXACTLY (as long as the grid itself
#' isn't perfectly uniform, i.e. ms_rows != 0 -- true here since only cell(0,0) is dwelled on).
#' meanPairwiseMouseCC is also exactly 1.0 (cc of a grid against an identical copy of itself, with
#' nonzero variance, is a perfect Pearson correlation). Mirrors the Python toolkit's
#' `build_mouse_icc_fixture` exactly.
build_mouse_icc_fixture <- function() {
  grid <- .n_nonzero_grid(10)
  path <- matrix(
    c(
      0, 1000, 750, 400, 300, 1000, 50, 50,
      1000, 1000, 750, 400, 300, 1000, 60, 60,
      2000, 1000, 750, 400, 300, 1000, 70, 70
    ),
    nrow = 3, ncol = 8, byrow = TRUE
  )
  f1 <- .fragment("micc1", 5, grid, 2000, 3, path = path, slide_key = MOUSE_ICC_SLIDE_KEY)
  f2 <- .fragment("micc2", 5, grid, 2000, 3, path = path, slide_key = MOUSE_ICC_SLIDE_KEY)
  list(f1, f2)
}

SEGLIN_SLIDE_KEY <- "sha256:selftest-slide-seglin-0001"
#' Tier 3 C4 fixture: a single schema/3, 5-point path with a KNOWN hotspot split -- one clean
#' segment of exactly linearity==1.0 between two of the grid's top-5 hotspot cells, bracketed by
#' non-hotspot wandering points (excluded from the segment). Mirrors the Python toolkit's
#' `build_seglin_fragment` exactly; see docs/superpowers/sdd/t4-report.md for the full derivation.
#'
#' Native grid (GW=GH=8): cell(0,0)=1000, cell(3,3)=900, cell(7,4)=800, cell(7,5)=700,
#' cell(7,6)=600, everything else 0 -- these 5 cells are top_hotspots(grid,8,8,5) (no ties).
#'
#' path: p0=cell(5,4) non-hotspot, p1=cell(0,0)==hotspot [BOUNDARY 1], p2=cell(1,1) non-hotspot
#' (EXACT midpoint of p1/p3, colinear), p3=cell(3,3)==hotspot [BOUNDARY 2], p4=cell(6,4)
#' non-hotspot. boundary_idx=[2,4] (1-based) -> exactly 1 segment=path[2:4]=[p1,p2,p3]; p2 being
#' the exact midpoint of p1/p3 makes the segment perfectly colinear -> linearity==1.0 EXACTLY ->
#' meanSegmentLinearity == 1.0 EXACTLY.
build_seglin_fragment <- function() {
  grid <- rep(0.0, 64)
  grid[1] <- 1000.0   # row0, col0 (flat idx0 -> 1-based 1)
  grid[28] <- 900.0   # row3, col3 (flat idx27 -> 1-based 28)
  grid[61] <- 800.0   # row7, col4 (flat idx60 -> 1-based 61)
  grid[62] <- 700.0   # row7, col5
  grid[63] <- 600.0   # row7, col6
  path <- matrix(
    c(
      0, 1000, 1000, 400, 300,
      250, 50, 50, 400, 300,
      500, 425, 325, 400, 300,
      750, 800, 600, 400, 300,
      1000, 1200, 1200, 400, 300
    ),
    nrow = 5, ncol = 5, byrow = TRUE
  )
  .fragment(
    "seglin1", 3, grid, 1000, 5, path = path,
    slide_key = SEGLIN_SLIDE_KEY
  )
}

SEGLIN_DWELL_SLIDE_KEY <- "sha256:selftest-slide-seglin-dwell-0001"
#' C4 dedup-fix regression fixture: a single schema/3, 5-point path with a DWELL RUN of 3
#' consecutive samples inside ONE hotspot cell, followed by a genuine transit to a SECOND hotspot
#' cell. Mirrors the Python toolkit's `build_seglin_dwell_fragment` exactly; see
#' docs/superpowers/sdd/t4-report.md "C4 dedup fix" section for the full derivation.
#'
#' Reuses the same native grid as `build_seglin_fragment` (GW=GH=8, IMG_W=2000, IMG_H=1500 -> cell
#' = 250x187.5 image px): cell(0,0)=1000, cell(3,3)=900, cell(7,4)=800, cell(7,5)=700,
#' cell(7,6)=600 -- these 5 cells are `top_hotspots(grid, 8, 8, 5)`.
#'
#' path (t, cx, cy, w, h):
#'   p0=(0,   50, 50,  400,300) -- cell(0,0) == hotspot -> BOUNDARY 1 (dwell tick 1, first hit)
#'   p1=(100, 60, 55,  400,300) -- cell(0,0) == hotspot -- SAME as last boundary -> collapsed
#'   p2=(200, 70, 60,  400,300) -- cell(0,0) == hotspot -- SAME again -> collapsed
#'   p3=(300, 400,100, 400,300) -- cell(0,1), NOT a hotspot (transit intermediate)
#'   p4=(400, 800,700, 400,300) -- cell(3,3) == hotspot, DIFFERENT cell -> BOUNDARY 2
#'
#' PRE-FIX: boundary_idx=[1,2,3,5] (1-based) -> 3 segments: seg(p0,p1)=1.0 EXACTLY,
#' seg(p1,p2)=1.0 EXACTLY, seg(p2,p3,p4)=net(p2->p4)/[dist(p2,p3)+dist(p3,p4)]=0.921500472912134
#' -> meanSegmentLinearity_PREFIX = mean([1.0,1.0,0.921500472912134]) == 0.9738334909707113.
#'
#' POST-FIX: boundary_idx=[1,5] (1-based) -> ONE segment spanning all 5 points:
#'   net(p0->p4)=sqrt((800-50)^2+(700-50)^2)=sqrt(985000)
#'   total = sqrt(125)+sqrt(125)+sqrt(110500)+sqrt(520000)
#'   -> meanSegmentLinearity_POSTFIX = net/total == 0.9224688773734908
#' (hand-derived directly from raw coordinates -- independent of calling mean_segment_linearity
#' itself -- then confirmed bit-identical against the fixed implementation, and against Python).
#'
#' 0.9738334909707113 != 0.9224688773734908: the pre-fix value is measurably inflated toward 1.0
#' by the two trivial within-dwell 2-point segments -- exactly the bug this fixture guards against.
build_seglin_dwell_fragment <- function() {
  grid <- rep(0.0, 64)
  grid[1] <- 1000.0   # row0, col0
  grid[28] <- 900.0   # row3, col3
  grid[61] <- 800.0   # row7, col4
  grid[62] <- 700.0   # row7, col5
  grid[63] <- 600.0   # row7, col6
  path <- matrix(
    c(
      0, 50, 50, 400, 300,
      100, 60, 55, 400, 300,
      200, 70, 60, 400, 300,
      300, 400, 100, 400, 300,
      400, 800, 700, 400, 300
    ),
    nrow = 5, ncol = 5, byrow = TRUE
  )
  .fragment(
    "seglindwell1", 3, grid, 400, 5, path = path,
    slide_key = SEGLIN_DWELL_SLIDE_KEY
  )
}

ROI_SEGLIN_SLIDE_KEY <- "sha256:selftest-slide-roi-seglin-0001"
#' PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3) fixture: a single schema/3
#' path (5-element points) + one annotation Feature exercising `mean_segment_linearity_roi`'s
#' dedup (a 3-point dwell run inside the ROI collapses to ONE boundary, not 3) and its
#' 2-boundary/1-segment case. Mirrors the Python toolkit's `build_roi_seglin_fragment` exactly.
#' Its own small custom grid dims (gw=gh=4, img_w=img_h=400 -> cell = 100x100 image px),
#' deliberately NOT the shared module GW/GH/IMG_W/IMG_H (8/8/2000/1500) -- every coordinate/cell
#' mapping below is exactly hand-derivable (independently verified against the implementation
#' before this fixture was written; see docs/superpowers/sdd/pt3-report.md).
#'
#' Annotation: one rectangle covering exactly grid cell (row1, col1) -- x in [100,200], y in
#' [100,200] -- so cell(1,1)'s center (150,150) is the ONLY cell center inside it. The rasterized
#' union mask therefore has exactly 1 TRUE cell, flat index 1*4+1=5 (0-based).
#'
#' path (t, cx, cy, w, h), 7 points, mapped to grid cells (col=floor(cx/400*4), row=floor(cy/400*4)):
#'   p0=(0,   50, 50)   -- cell(0,0)=0, OUTSIDE
#'   p1=(100, 150,150)  -- cell(1,1)=5, INSIDE -> BOUNDARY 1 (prev OUTSIDE)
#'   p2=(200, 160,140)  -- cell(1,1)=5, INSIDE -> dwell run, dedup (prev INSIDE -> NOT a new boundary)
#'   p3=(300, 140,160)  -- cell(1,1)=5, INSIDE -> dwell run, dedup (same)
#'   p4=(400, 350,50)   -- cell(0,3)=3, OUTSIDE -> exit
#'   p5=(500, 50,350)   -- cell(3,0)=12, OUTSIDE -> wander (detour, not a straight line back)
#'   p6=(600, 180,120)  -- cell(1,1)=5, INSIDE -> BOUNDARY 2 (prev OUTSIDE)
#'
#' boundary_idx (1-based) = c(2, 7) (only 2 boundaries -> exactly 1 segment, pm[2:7,] = p1..p6, 6
#' points). meanSegmentLinearityROI = linearity(p1..p6), hand-derived directly from the raw
#' coordinates (net-displacement / total-path-length), independent of calling
#' mean_segment_linearity_roi itself:
#'   net   = dist(p1, p6) = dist((150,150),(180,120)) = sqrt(30^2+30^2) = 30*sqrt(2)
#'         = 42.42640687119285
#'   total = dist(p1,p2)+dist(p2,p3)+dist(p3,p4)+dist(p4,p5)+dist(p5,p6)
#'         = sqrt(200)+sqrt(800)+sqrt(56200)+sqrt(180000)+sqrt(69800)
#'         = 967.9527636781734
#'   -> meanSegmentLinearityROI = 42.42640687119285 / 967.9527636781734 = 0.0438310715803678
#'   (confirmed bit-identical against the implementation before this fixture was committed, and
#'   against the Python toolkit's own fixture/asserts).
#'
#' Dedup-bite check: a NAIVE implementation with no run-dedup (every INSIDE point its own
#' boundary: boundary_idx (1-based) = c(2,3,4,7)) would instead produce 3 segments -- (p1,p2) and
#' (p2,p3), both trivial 2-point runs with linearity==1.0 EXACTLY, plus (p3,p4,p5,p6) with
#' linearity==0.061120401465477904 -- mean([1.0, 1.0, 0.061120401465477904]) ==
#' 0.687040133821826, measurably INFLATED toward 1.0 by the two trivial within-dwell segments (the
#' same C4-dedup failure mode `mean_segment_linearity` was already fixed for -- see
#' build_seglin_dwell_fragment). This fixture's correct value (0.0438310715803678) is clearly < 1
#' and clearly != the naive 0.687... value, so a regression to per-sample (undeduped) boundaries
#' is caught, not silently masked.
build_roi_seglin_fragment <- function() {
  rs_gw <- 4L; rs_gh <- 4L
  rs_img_w <- 400; rs_img_h <- 400
  grid <- rep(0.0, 16) # the recorded dwell grid is unrelated to this fixture's ROI-entry metric
  path <- matrix(
    c(
      0, 50, 50, 400, 300,
      100, 150, 150, 400, 300,
      200, 160, 140, 400, 300,
      300, 140, 160, 400, 300,
      400, 350, 50, 400, 300,
      500, 50, 350, 400, 300,
      600, 180, 120, 400, 300
    ),
    nrow = 7, ncol = 5, byrow = TRUE
  )
  ann_fc <- list(
    type = "FeatureCollection",
    features = list(
      list(
        type = "Feature",
        geometry = list(
          type = "Polygon",
          coordinates = list(list(c(100, 100), c(200, 100), c(200, 200), c(100, 200), c(100, 100)))
        ),
        properties = list(name = "roi")
      )
    )
  )
  list(
    schema = "atlas-focus-contribution/3",
    slideKey = ROI_SEGLIN_SLIDE_KEY,
    sessionId = "roiseglin1",
    imageWidth = rs_img_w, imageHeight = rs_img_h,
    gridWidth = rs_gw, gridHeight = rs_gh,
    grid = grid,
    durationMs = 600,
    sampleCount = 7,
    date = "2026-07-25",
    path = lapply(seq_len(nrow(path)), function(i) as.numeric(path[i, ])),
    annotations = ann_fc
  )
}

MAGBAND_AGREEMENT_SLIDE_KEY <- "sha256:selftest-slide-magband-agreement-0001"
#' PT4 fixture (docs/superpowers/specs/2026-07-25-enrichment-polish.md P4): 2 schema/4 sessions
#' with a BYTE-IDENTICAL 6-element (t,cx,cy,w,h,dsMilli) path and the SAME known
#' `baseMagnification` (40.0) -- their per-band dwell rasters are therefore identical arrays, the
#' same "hand-verifiable CC=1.0" design PT2's `build_mouse_icc_fixture` uses. Mirrors the Python
#' toolkit's `build_magband_agreement_fixture` exactly.
#'
#' Own small custom grid/image dims (gw=gh=4, img_w=img_h=400 -> cell = 100x100 image px). 3-point
#' path (t, cx, cy, w, h, dsMilli):
#'   p0=(0,    50,50, 100,100, 8000) -> step0: true_mag = 40/(8000/1000) = 5.0  -> band index 3
#'     ("4-10x", MAG_BAND_CUTS=[1,2,4,10,20,40], findInterval(5.0,cuts)==3). Viewport rect
#'     (cx=50,cy=50,w=h=100) -> image px [0,100]x[0,100] -> native cell(0,0) EXACTLY.
#'   p1=(1000, 50,50, 100,100, 2000) -> step1: true_mag = 40/(2000/1000) = 20.0 -> band index 5
#'     ("20-40x", findInterval(20.0,cuts)==5). Same viewport position -> native cell(0,0) again.
#'   p2=(2000, 50,50, 100,100, 2000) -> endpoint (no owned step).
#'
#' Native gw=gh=4 == this slide's common (tw,th) (both sessions share the same grid dims), so
#' resample_nn is a no-op identity copy. Both sessions' path/baseMagnification are BYTE-IDENTICAL
#' -> for EACH band, session1's resampled raster == session2's resampled raster exactly (nonzero
#' variance: one cell=1000.0, rest 0.0) -> cc(g,g) == 1.0 EXACTLY -> mean_pairwise_cc([g,g]) == 1.0.
#' coincidence_level([g,g], IOU_THRESH=0.1) == 1.0 EXACTLY (identical grids -> every above-threshold
#' cell is shared by both).
#'
#' Expected magband_agreement_<slug>.csv: exactly 2 rows, band ascending --
#'   ("4-10x", nSessions=2, meanPairwiseCC=1.0, coincidenceLevel=1.0)
#'   ("20-40x", nSessions=2, meanPairwiseCC=1.0, coincidenceLevel=1.0)
build_magband_agreement_fixture <- function() {
  mba_gw <- 4L; mba_gh <- 4L
  mba_img_w <- 400; mba_img_h <- 400
  grid <- rep(0.0, 16) # the recorded dwell grid is unrelated to this fixture's PT4 metric
  path <- matrix(
    c(
      0, 50, 50, 100, 100, 8000,
      1000, 50, 50, 100, 100, 2000,
      2000, 50, 50, 100, 100, 2000
    ),
    nrow = 3, ncol = 6, byrow = TRUE
  )
  path_list <- lapply(seq_len(nrow(path)), function(i) as.numeric(path[i, ]))
  .frag <- function(session_id) {
    list(
      schema = "atlas-focus-contribution/4",
      slideKey = MAGBAND_AGREEMENT_SLIDE_KEY,
      sessionId = session_id,
      imageWidth = mba_img_w, imageHeight = mba_img_h,
      gridWidth = mba_gw, gridHeight = mba_gh,
      grid = grid,
      durationMs = 2000,
      sampleCount = 3,
      date = "2026-07-25",
      path = path_list,
      baseMagnification = 40.0
    )
  }
  list(.frag("mba1"), .frag("mba2"))
}

MAGBAND_AGREEMENT_FRAC_SLIDE_KEY <- "sha256:selftest-slide-magband-agreement-frac-0001"
#' PT4 parity-robustness fixture (per advisor review): 3 schema/4 sessions on ONE slide with a
#' FRACTIONAL (non-1.0/non-0.0) meanPairwiseCC, AND a native grid resolution that differs from the
#' slide's common (tw, th) grid for 2 of the 3 sessions -- so resample_nn's nearest-neighbour
#' UPSAMPLE path is actually exercised. Mirrors the Python toolkit's
#' `build_magband_agreement_frac_fixture` exactly.
#'
#' mbaf1, mbaf2: native gw=gh=4, img_w=img_h=400 (cell=100px). mbaf3: native gw=gh=8, img_w=img_h=400
#' (cell=50px) -- the LARGEST native grid on this slide, so the common (tw,th)=(8,8) and
#' mbaf1/mbaf2's native (4,4) grids get NN-upsampled (each native cell's value is COPIED, unscaled,
#' into a 2x2 block of the 8x8 target).
#'
#' All 3 share baseMagnification=40.0 and the SAME 2-band ds schedule (8000ms -> "4-10x" band
#' index3; 2000ms -> "20-40x" band index5), each dwelling at its OWN distinct image position (so
#' the 3 post-resample rasters have DISJOINT nonzero support at the (8,8) resolution):
#'   mbaf1 dwells at native (4,4) cell(0,0) (cx=cy=50, w=h=100) -> upsampled to (8,8) rows{0,1} x
#'     cols{0,1} (flat idx 0-based {0,1,8,9}), value=1000.0 (=dt) at all 4 cells.
#'   mbaf2 dwells at native (4,4) cell(0,1) (cx=150,cy=50, w=h=100) -> upsampled to (8,8) rows{0,1}
#'     x cols{2,3} (flat idx 0-based {2,3,10,11}), value=1000.0 at all 4 cells.
#'   mbaf3 dwells at native (8,8) cell(4,4) directly (cx=cy=225, w=h=50, no resampling needed) ->
#'     flat idx 0-based {36}, value=1000.0.
#'
#' Hand-derived (raw Pearson-correlation formula, verified independently of `cc()` before this
#' fixture was written; N=64 cells each; mirrors the Python toolkit's docstring derivation exactly):
#'   cc(mbaf1, mbaf2) == -1/15 == -0.06666666666666667 EXACTLY.
#'   cc(mbaf1, mbaf3) == cc(mbaf2, mbaf3) == -0.03253000243161777.
#'   meanPairwiseCC == mean(-0.06666666666666667, -0.03253000243161777, -0.03253000243161777)
#'                  == -0.0439088905099674 (both bands identical value, since every session uses
#'                  the SAME dwell position for both step0/step1 -- only the ds/band differs).
#'   coincidenceLevel == 0.0 EXACTLY: each grid's above-threshold cells are entirely disjoint from
#'     the other two -> no cell is ever shared by >=2 readers.
build_magband_agreement_frac_fixture <- function() {
  img_w <- 400; img_h <- 400
  ds_schedule <- c(8000, 2000, 2000) # step0 -> "4-10x" (idx3), step1 -> "20-40x" (idx5)
  .path_at <- function(cx, cy, w, h) {
    m <- matrix(
      c(
        0, cx, cy, w, h, ds_schedule[1],
        1000, cx, cy, w, h, ds_schedule[2],
        2000, cx, cy, w, h, ds_schedule[3]
      ),
      nrow = 3, ncol = 6, byrow = TRUE
    )
    lapply(seq_len(nrow(m)), function(i) as.numeric(m[i, ]))
  }
  f1 <- list(
    schema = "atlas-focus-contribution/4",
    slideKey = MAGBAND_AGREEMENT_FRAC_SLIDE_KEY,
    sessionId = "mbaf1",
    imageWidth = img_w, imageHeight = img_h,
    gridWidth = 4L, gridHeight = 4L,
    grid = rep(0.0, 16),
    durationMs = 2000,
    sampleCount = 3,
    date = "2026-07-25",
    path = .path_at(50, 50, 100, 100), # native cell(0,0)
    baseMagnification = 40.0
  )
  f2 <- list(
    schema = "atlas-focus-contribution/4",
    slideKey = MAGBAND_AGREEMENT_FRAC_SLIDE_KEY,
    sessionId = "mbaf2",
    imageWidth = img_w, imageHeight = img_h,
    gridWidth = 4L, gridHeight = 4L,
    grid = rep(0.0, 16),
    durationMs = 2000,
    sampleCount = 3,
    date = "2026-07-25",
    path = .path_at(150, 50, 100, 100), # native cell(0,1)
    baseMagnification = 40.0
  )
  f3 <- list(
    schema = "atlas-focus-contribution/4",
    slideKey = MAGBAND_AGREEMENT_FRAC_SLIDE_KEY,
    sessionId = "mbaf3",
    imageWidth = img_w, imageHeight = img_h,
    gridWidth = 8L, gridHeight = 8L,
    grid = rep(0.0, 64),
    durationMs = 2000,
    sampleCount = 3,
    date = "2026-07-25",
    path = .path_at(225, 225, 50, 50), # native (8,8) cell(4,4) directly
    baseMagnification = 40.0
  )
  list(f1, f2, f3)
}

MAGBAND_AGREEMENT_PARTIAL_SLIDE_KEY <- "sha256:selftest-slide-magband-agreement-partial-0001"
#' PT4 row-skip fixture (per advisor review): the spec's hard-constraints list explicitly names "a
#' band with only 1 dwelling session (row skipped)" as a blank-not-crash case -- neither
#' `build_magband_agreement_fixture` nor `build_magband_agreement_frac_fixture` above exercises it
#' (every canonical session in both dwells in BOTH bands). This fixture is built so exactly ONE
#' band has 2 dwelling sessions (row emitted) and the OTHER has only 1 (row skipped). Mirrors the
#' Python toolkit's `build_magband_agreement_partial_fixture` exactly.
#'
#' 2 schema/4 sessions, same small custom grid dims as `build_magband_agreement_fixture` (gw=gh=4,
#' img_w=img_h=400), same baseMagnification=40.0, same dwell position (native cell(0,0), cx=cy=50,
#' w=h=100) so the shared band's per-session rasters are identical:
#'   mbap1: 3-point path, 2 steps -- step0 -> band3 "4-10x", step1 -> band5 "20-40x". Dwells in
#'     BOTH bands.
#'   mbap2: 2-point path, 1 step ONLY -- step0 -> band3 "4-10x" ONLY. Dwells ONLY in band3.
#'
#' band index3 ("4-10x"): grids = [mbap1's, mbap2's], both = cell(0,0) dwell dt=1000ms, rest 0 --
#'   IDENTICAL -> nSessions=2, meanPairwiseCC=1.0, coincidenceLevel=1.0 (row EMITTED).
#' band index5 ("20-40x"): only mbap1 dwells there -> grids list length 1 < 2 -> row SKIPPED.
#' n_canonical_sessions = 2 -> file-level gate passes -> file IS written, but with exactly 1 row.
build_magband_agreement_partial_fixture <- function() {
  gw <- 4L; gh <- 4L
  img_w <- 400; img_h <- 400
  grid <- rep(0.0, 16)
  path1 <- matrix(
    c(
      0, 50, 50, 100, 100, 8000,
      1000, 50, 50, 100, 100, 2000,
      2000, 50, 50, 100, 100, 2000
    ),
    nrow = 3, ncol = 6, byrow = TRUE
  )
  path2 <- matrix(
    c(
      0, 50, 50, 100, 100, 8000,
      1000, 50, 50, 100, 100, 8000
    ),
    nrow = 2, ncol = 6, byrow = TRUE
  )
  f1 <- list(
    schema = "atlas-focus-contribution/4",
    slideKey = MAGBAND_AGREEMENT_PARTIAL_SLIDE_KEY,
    sessionId = "mbap1",
    imageWidth = img_w, imageHeight = img_h,
    gridWidth = gw, gridHeight = gh,
    grid = grid,
    durationMs = 2000,
    sampleCount = 3,
    date = "2026-07-25",
    path = lapply(seq_len(nrow(path1)), function(i) as.numeric(path1[i, ])),
    baseMagnification = 40.0
  )
  f2 <- list(
    schema = "atlas-focus-contribution/4",
    slideKey = MAGBAND_AGREEMENT_PARTIAL_SLIDE_KEY,
    sessionId = "mbap2",
    imageWidth = img_w, imageHeight = img_h,
    gridWidth = gw, gridHeight = gh,
    grid = grid,
    durationMs = 1000,
    sampleCount = 2,
    date = "2026-07-25",
    path = lapply(seq_len(nrow(path2)), function(i) as.numeric(path2[i, ])),
    baseMagnification = 40.0
  )
  list(f1, f2)
}

C6_SLIDE_KEY <- "sha256:selftest-slide-c6-0001"
#' Tier 3 C6 fixture: its own small custom grid/image dims (GW=GH=4, IMG_W=IMG_H=400 -> cell =
#' 100x100 image px), deliberately NOT the shared module GW/GH/IMG_W/IMG_H (8/8/2000/1500) -- every
#' documented number below is exactly hand-derivable at this resolution. Mirrors the Python
#' toolkit's `build_c6_fixture` exactly; see docs/superpowers/sdd/t6-report.md for the full
#' derivation.
#'
#' 2 sessions on one slide (>=2 needed for compare_<slug>.csv's jsDivergence + consensus_count):
#'
#' c6a (schema/3, HAS a path + an overlapping-annotation pair): grid = 100.0 at flat idx0 (row0,
#'   col0) and idx1 (row0,col1), 0.0 elsewhere.
#'   - precisionAtTopK/recall (frac=0.10, n=16 -> k=2): cutoff=100 (idx0/idx1 tie) ->
#'     topK={idx0,idx1}. ROI (below) = row0's 4 cells {idx0..idx3} -> intersection={idx0,idx1}
#'     (size2) -> precisionAtTopK=2/2=1.0, recall=2/4=0.5.
#'   - path alternates cell1 (row0,col1, center (150,50)) / cell8 (row2,col0, center (50,250)), 9
#'     points -> visited_sequence = [1,8,1,8,1,8,1,8,1] (0-based) -> visit_count_grid: idx1=5,
#'     idx8=4. DIRECT-unit visit_count_jaccard(top_n=2): dwell top2={idx0,idx1}, visit
#'     top2={idx1,idx8} -> jaccard=1/3 EXACTLY. (Pipeline uses HOTSPOT_TOP_N=5 -- only
#'     bounds-checked there; exactness is carried by the Python<->R parity diff.)
#'   - annotations: outer rect [100,100]-[300,300] (area 40000) + nested inner rect
#'     [140,140]-[260,260] (area 14400). Both rasterize (native gw=gh=4) to the SAME 4 cells
#'     (rows/cols {1,2}) -> annotatedAreaUnionPx = 4*(100*100) = 40000.0 vs sum-based
#'     annotatedAreaPx = 40000+14400 = 54400.0 -> union < sum EXACTLY.
#'
#' c6b (schema/2, NO path -- exercises visitCountJaccard's path-required blank): grid = 100.0 at
#'   idx1 (row0,col1) and idx10 (row2,col2). No annotations (both area columns 0.0).
#'   - precisionAtTopK/recall: cutoff=100 -> topK={idx1,idx10}. Intersection with
#'     ROI({idx0..idx3})={idx1} -> precisionAtTopK=1/2=0.5, recall=1/4=0.25.
#'
#' compare_<slug>.csv jsDivergence(c6a,c6b) = 0.5 EXACTLY (2 distributions sharing exactly one
#' support cell out of a 2-cell support each -- see js_divergence's own hand-derived unit test for
#' the full derivation). Diagonal rows are exactly 0.0.
#'
#' consensus_count_<slug>.csv (HOTSPOT_THRESH_FRAC=0.5, normalise_max per session): nReaders>0.5:
#' idx0 -> c6a only -> 1; idx1 -> BOTH -> 2; idx10 -> c6b only -> 1; else 0 (omitted). Exactly 3
#' rows: (row0,col0,1), (row0,col1,2), (row2,col2,1).
#'
#' ROI (passed via --roi, NOT --reference -- both sessions appear in reference_<slug>.csv): a
#' rectangle x in [-10,410], y in [-10,140] -- rasterizes to exactly row0's 4 cells.
.c6_fragment <- function(session_id, schema, grid, gw, gh, img_w, img_h, duration_ms, sample_count,
                          path = NULL, annotations = NULL) {
  d <- list(
    schema = paste0("atlas-focus-contribution/", schema),
    slideKey = C6_SLIDE_KEY,
    sessionId = session_id,
    imageWidth = img_w, imageHeight = img_h,
    gridWidth = gw, gridHeight = gh,
    grid = grid,
    durationMs = duration_ms,
    sampleCount = sample_count,
    date = "2026-07-24"
  )
  if (!is.null(path)) {
    d$path <- lapply(seq_len(nrow(path)), function(i) as.numeric(path[i, ]))
  }
  if (!is.null(annotations)) {
    d$annotations <- annotations
  }
  d
}

build_c6_fixture <- function() {
  c6_gw <- 4L; c6_gh <- 4L
  c6_img_w <- 400; c6_img_h <- 400

  grid_c6a <- rep(0.0, 16)
  grid_c6a[1] <- 100.0   # idx0 (row0, col0)
  grid_c6a[2] <- 100.0   # idx1 (row0, col1)
  path_rows <- list()
  t <- 0
  for (i in 0:8) {
    if (i %% 2 == 0) {
      path_rows[[length(path_rows) + 1]] <- c(t, 150, 50, 400, 300)   # cell1 (row0, col1)
    } else {
      path_rows[[length(path_rows) + 1]] <- c(t, 50, 250, 400, 300)   # cell8 (row2, col0)
    }
    t <- t + 250
  }
  path_c6a <- do.call(rbind, path_rows)

  ann_fc_c6a <- list(
    type = "FeatureCollection",
    features = list(
      list(
        type = "Feature",
        geometry = list(
          type = "Polygon",
          coordinates = list(list(c(100, 100), c(300, 100), c(300, 300), c(100, 300), c(100, 100)))
        ),
        properties = list(name = "outer")
      ),
      list(
        type = "Feature",
        geometry = list(
          type = "Polygon",
          coordinates = list(list(c(140, 140), c(260, 140), c(260, 260), c(140, 260), c(140, 140)))
        ),
        properties = list(name = "inner")
      )
    )
  )
  f_c6a <- .c6_fragment(
    "c6a", 3, grid_c6a, c6_gw, c6_gh, c6_img_w, c6_img_h, 2000, 9,
    path = path_c6a, annotations = ann_fc_c6a
  )

  grid_c6b <- rep(0.0, 16)
  grid_c6b[2] <- 100.0    # idx1 (row0, col1)
  grid_c6b[11] <- 100.0   # idx10 (row2, col2)
  f_c6b <- .c6_fragment(
    "c6b", 2, grid_c6b, c6_gw, c6_gh, c6_img_w, c6_img_h, 5000, 1
  )

  list(f_c6a, f_c6b)
}

#' The ROI FeatureCollection for `build_c6_fixture` -- see that function's docs for the exact
#' rasterization derivation (rasterizes to exactly row0's 4 cells).
build_c6_roi_fc <- function() {
  list(
    type = "FeatureCollection",
    features = list(list(
      type = "Feature",
      geometry = list(
        type = "Polygon",
        coordinates = list(list(c(-10, -10), c(410, -10), c(410, 140), c(-10, 140), c(-10, -10)))
      ),
      properties = list()
    ))
  )
}

# ---------------------------------------------------------------------------
# Final review (2026-07-24, docs/superpowers/sdd/enrichment-finalfix-report.md): pipeline-level
# fixtures for the 4 CONFIRMED findings -- mirrors analysis/python/selftest.py's own final-review
# section function-for-function/fixture-for-fixture.
# ---------------------------------------------------------------------------

#' Minimal fragment builder for the final-review fixtures below -- unlike `.fragment` (which
#' hardcodes the module's GW/GH/IMG_W/IMG_H constants), every grid/image dimension is an explicit
#' parameter, since Findings 1/2's zero-size-grid fixtures need gridWidth/gridHeight of 0 -- a
#' shape the module constants never exercise.
.finalfix_fragment <- function(session_id, schema, grid, gw, gh, img_w, img_h, duration_ms,
                                sample_count, path = NULL, slide_key = NULL) {
  d <- list(
    schema = paste0("atlas-focus-contribution/", schema),
    slideKey = slide_key,
    sessionId = session_id,
    imageWidth = img_w, imageHeight = img_h,
    gridWidth = gw, gridHeight = gh,
    grid = grid,
    durationMs = duration_ms,
    sampleCount = sample_count,
    date = "2026-07-24"
  )
  if (!is.null(path)) {
    if (is.matrix(path)) {
      d$path <- lapply(seq_len(nrow(path)), function(i) as.numeric(path[i, ]))
    } else {
      d$path <- path
    }
  }
  d
}

ZEROGRID_PATH_SLIDE_KEY <- "sha256:selftest-slide-finalfix-zerogrid-path-0001"
#' Finding 1 fixture: a single schema/3 session recording gridWidth=0/gridHeight=5 (grid=numeric(0),
#' schema-valid per `.is_valid_fragment`'s `length(grid)==gw*gh` check: `0*5==0`) WITH a real path
#' -- the combination the pre-fix code crashed on: `visited_sequence` clamps every path point's
#' cell index to -1 at this (gw=0, gh=5) resolution, so `visit_count_grid`'s assignment loop on a
#' genuinely size-0 vector would (in a naive port) raise/abort, propagating up through
#' `visit_count_jaccard` into `analyze()`.
build_zerogrid_path_fragment <- function() {
  path <- do.call(rbind, lapply(0:9, function(t) c(t * 250, 100 + t, 100 + t, 400, 300)))
  .finalfix_fragment(
    "zg-path1", 3, numeric(0), 0L, 5L, 2000, 1500, 2250, 10, path = path,
    slide_key = ZEROGRID_PATH_SLIDE_KEY
  )
}

ZEROGRID_MOUSE_SLIDE_KEY <- "sha256:selftest-slide-finalfix-zerogrid-mouse-0001"
#' Finding 2 fixture: a single schema/5 session recording gridWidth=0/gridHeight=5 WITH on-slide
#' mouse data -- exercises `mouse_raster_from_path`'s zero-size-grid guard through the full
#' `analyze()` pipeline.
build_zerogrid_mouse_fragment <- function() {
  path <- matrix(
    c(
      0, 100, 100, 400, 300, 1000, 50, 50,
      1000, 100, 100, 400, 300, 1000, 60, 60
    ),
    nrow = 2, ncol = 8, byrow = TRUE
  )
  .finalfix_fragment(
    "zg-mouse1", 5, numeric(0), 0L, 5L, 2000, 1500, 1000, 2, path = path,
    slide_key = ZEROGRID_MOUSE_SLIDE_KEY
  )
}

TOPK_SPARSE_SLIDE_KEY <- "sha256:selftest-slide-finalfix-topk-sparse-0001"
#' Finding 3 fixture: a 10x10 (n=100) dwell grid where only 5 cells (flat indices 1-5, row0
#' col0-4) are nonzero (~100 each) -- a focused/sparse reader on a fine grid. `frac=0.10` wants
#' `k=ceiling(0.10*100)=10`, but only 5 cells are nonzero, so the 10th-largest value (the cutoff)
#' is 0.0. The ROI (see `build_topk_sparse_roi_fc`) is the DISJOINT bottom half (rows 5-9) -- zero
#' overlap with the reader's 5 attended cells by construction.
build_topk_sparse_fragment <- function() {
  gw <- 10L; gh <- 10L
  grid <- rep(0.0, gw * gh)
  grid[1:5] <- 100.0
  .finalfix_fragment(
    "topk-sparse1", 2, grid, gw, gh, 1000, 1000, 1000, 5,
    slide_key = TOPK_SPARSE_SLIDE_KEY
  )
}

#' ROI = the bottom half of `build_topk_sparse_fragment`'s 10x10 grid over a 1000x1000 image (rows
#' 5-9, y in [500, 1000]) -- disjoint from the 5 nonzero cells in row 0.
build_topk_sparse_roi_fc <- function() {
  list(
    type = "FeatureCollection",
    features = list(list(
      type = "Feature",
      geometry = list(
        type = "Polygon",
        coordinates = list(list(c(0, 500), c(1000, 500), c(1000, 1000), c(0, 1000), c(0, 500)))
      ),
      properties = list()
    ))
  )
}

IDLE_FIXATION_SLIDE_KEY <- "sha256:selftest-slide-finalfix-idle-fixation-0001"
#' Finding 4 fixture: a schema/3, 4-point path with a KNOWN idle-boundary fixation split -- "4s
#' dwell, then a 66s away-gap (idle, >IDLE_GAP_MS=60000ms) with an otherwise near-stationary
#' viewport either side, then a 1s dwell". Mirrors the Python toolkit's
#' `build_idle_fixation_fragment` -- see its docstring (and
#' docs/superpowers/sdd/enrichment-finalfix-report.md) for the full hand derivation.
build_idle_fixation_fragment <- function() {
  grid <- .n_nonzero_grid(10)
  path <- matrix(
    c(
      0, 100, 100, 400, 300,
      4000, 100, 100, 400, 300,
      70000, 101, 101, 400, 300,
      71000, 100, 100, 400, 300
    ),
    nrow = 4, ncol = 5, byrow = TRUE
  )
  .finalfix_fragment(
    "idlefix1", 3, grid, GW, GH, IMG_W, IMG_H, 71000, 4, path = path,
    slide_key = IDLE_FIXATION_SLIDE_KEY
  )
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
#'
#' Tier 3 C5: `promptShownMs`/`responseLatencyMs` are populated only for s1 (the one fixture
#' decision carrying a synthetic `promptShownMs`); s2's decision is deliberately old-style (no
#' `promptShownMs`) and must degrade both new columns to blank, never crash.
check_decisions <- function(out_dir) {
  path <- file.path(out_dir, "decisions.csv")
  stopifnot("decisions.csv missing" = file.exists(path))
  rows <- utils::read.csv(path, stringsAsFactors = FALSE, colClasses = "character")
  stopifnot("expected 4 decisions rows (one per slide,session)" = nrow(rows) == 4)
  expected_cols <- c(
    "slide", "sessionId", "session", "diagnosis", "confidence", "confidenceScaled",
    "decisionMs", "decisionLatencyMs", "correctDx", "correct",
    "promptShownMs", "responseLatencyMs"
  )
  stopifnot("decisions.csv columns mismatch" = identical(colnames(rows), expected_cols))

  s1 <- rows[rows$session == "s1", ][1, ]
  stopifnot("s1 diagnosis mismatch" = s1$diagnosis == "tumor")
  stopifnot("s1 confidence mismatch" = s1$confidence == "4")
  stopifnot("s1 confidenceScaled mismatch" = s1$confidenceScaled == "0.75")
  stopifnot("s1 decisionMs should be non-empty" = nzchar(s1$decisionMs))
  stopifnot("s1 decisionLatencyMs should be non-empty" = nzchar(s1$decisionLatencyMs))
  # Tier 3 C5: s1 carries a synthetic promptShownMs=1200 (decisionMs=5000) -> responseLatencyMs =
  # decisionMs - promptShownMs = 3800.
  stopifnot("s1 promptShownMs mismatch" = identical(s1$promptShownMs, "1200"))
  stopifnot("s1 responseLatencyMs mismatch" = identical(s1$responseLatencyMs, "3800"))

  s2 <- rows[rows$session == "s2", ][1, ]
  stopifnot("s2 diagnosis mismatch" = s2$diagnosis == "benign")
  # s2's decision is deliberately old-style (no promptShownMs) -- both new columns must degrade to
  # blank, never crash, and decisionMs/decisionLatencyMs must stay unaffected.
  stopifnot("s2 decisionMs should be non-empty (unaffected by C5)" = nzchar(s2$decisionMs))
  stopifnot("s2 (old-style, no promptShownMs) should be blank" = identical(s2$promptShownMs, ""))
  stopifnot(
    "s2 (old-style, no promptShownMs) should have blank responseLatencyMs" =
      identical(s2$responseLatencyMs, "")
  )

  s3 <- rows[rows$session == "s3", ][1, ]
  stopifnot("undecided session should have blank diagnosis" = s3$diagnosis == "")
  stopifnot("undecided session should have blank confidence" = s3$confidence == "")
  stopifnot("undecided session should have blank correct" = s3$correct == "")
  # No decision at all -> both C5 columns blank too, never crash.
  stopifnot("undecided session should have blank promptShownMs" = identical(s3$promptShownMs, ""))
  stopifnot(
    "undecided session should have blank responseLatencyMs" = identical(s3$responseLatencyMs, "")
  )

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

# ---------------------------------------------------------------------------
# Tier 2 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md, B1-B4)
# ---------------------------------------------------------------------------

#' Direct, pipeline-independent unit checks for the new Tier 2 B1/B2/B3 metric functions -- TDD-
#' style asserts on hand-built inputs, bypassing the full `analyze()` pipeline entirely. Mirrors
#' the Python toolkit's `check_tier2_direct_unit_asserts` exactly.
check_tier2_direct_unit_asserts <- function() {
  # --- B1: idle_step_mask/idle_ms/active_span_ms on a minimal 3-point/2-step path with one
  # >60s gap ---
  idle_path <- matrix(
    c(
      0, 0, 0, 400, 300,
      1000, 10, 0, 400, 300,
      65000, 20, 0, 400, 300
    ),
    nrow = 3, ncol = 5, byrow = TRUE
  )
  stopifnot("idle_step_mask mismatch" = identical(idle_step_mask(idle_path), c(FALSE, TRUE)))
  stopifnot("idle_ms mismatch" = idle_ms(idle_path) == 64000.0)
  stopifnot("active_span_ms mismatch" = active_span_ms(idle_path) == 1000.0)
  stopifnot(
    "idle_step_mask should be logical(0) for an empty path" =
      length(idle_step_mask(matrix(numeric(0), ncol = 5))) == 0
  )
  one_pt <- matrix(c(0, 0, 0, 400, 300), nrow = 1, ncol = 5, byrow = TRUE)
  stopifnot("idle_ms should be 0.0 for a 1-point path" = idle_ms(one_pt) == 0.0)
  stopifnot("active_span_ms should be 0.0 for a 1-point path" = active_span_ms(one_pt) == 0.0)
  no_gap_path <- matrix(
    c(0, 0, 0, 400, 300, 1000, 10, 0, 400, 300, 2000, 20, 0, 400, 300),
    nrow = 3, ncol = 5, byrow = TRUE
  )
  stopifnot("idle_ms should be 0.0 with no idle gap" = idle_ms(no_gap_path) == 0.0)
  stopifnot("active_span_ms should equal total span with no idle gap" = active_span_ms(no_gap_path) == 2000.0)

  # --- B1 targeted assert: search_focus_ratio's idle-exclusion, via an INVARIANCE property
  # rather than a hand-predicted numeric outcome (the "focused" rule is an OR of two
  # median-split conditions, so hand-predicting its exact value for an arbitrary extra point is
  # fragile -- an invariance check is not). Mirrors the Python toolkit's baseline/idle/active
  # construction exactly, so the two toolkits' baseline_ratio must agree to 1e-6.
  focus_baseline_path <- matrix(
    c(
      0, 0, 0, 400, 300, 1000,
      1000, 1000, 0, 400, 300, 10,
      2000, 1001, 0, 400, 300, 1000,
      3000, 2001, 0, 400, 300, 10,
      4000, 2002, 0, 400, 300, 10
    ),
    nrow = 5, ncol = 6, byrow = TRUE
  )
  baseline_ratio <- search_focus_ratio(focus_baseline_path, NULL, IMG_W)
  stopifnot(
    "expected a genuine partial focus split (not all-focused/all-unfocused) on the baseline path" =
      baseline_ratio > 0.0 && baseline_ratio < 1.0
  )

  extreme_point <- c(999999999, -999999999, 400, 300, 1)

  idle_extended_path <- rbind(focus_baseline_path, c(4000 + 70000, extreme_point))
  ratio_with_idle_extra <- search_focus_ratio(idle_extended_path, NULL, IMG_W)
  if (ratio_with_idle_extra != baseline_ratio) {
    stop(sprintf(
      "appending an IDLE step, however extreme, must leave search_focus_ratio unchanged from the baseline (%s) -- got %s",
      baseline_ratio, ratio_with_idle_extra
    ), call. = FALSE)
  }

  active_extended_path <- rbind(focus_baseline_path, c(4000 + 59999, extreme_point))
  ratio_with_active_extra <- search_focus_ratio(active_extended_path, NULL, IMG_W)
  if (ratio_with_active_extra == baseline_ratio) {
    stop(sprintf(
      "appending the SAME extreme point as a NON-idle step must change search_focus_ratio from the baseline (%s) -- got the same value, exclusion may not be taking effect",
      baseline_ratio
    ), call. = FALSE)
  }

  # --- B2 targeted assert: avg_zoom_log2_w / drilling_rate_octaves_per_min degenerate (all-idle)
  # -> blank (NaN), not 0.0 (distinct from avg_zoom's/drilling_rate_per_min's 0.0-for-degenerate
  # convention -- a 0/0 weighted mean/rate has no defensible value) ---
  all_idle_path <- matrix(
    c(
      0, 0, 0, 400, 300, 1000,
      70000, 10, 0, 400, 300, 1000,
      140000, 20, 0, 400, 300, 1000
    ),
    nrow = 3, ncol = 6, byrow = TRUE
  )
  stopifnot(
    "avg_zoom_log2_w should be blank (NaN) when every step is idle" =
      is.nan(avg_zoom_log2_w(all_idle_path, NULL, IMG_W))
  )
  stopifnot(
    "drilling_rate_octaves_per_min should be blank (NaN) when every step is idle" =
      is.nan(drilling_rate_octaves_per_min(all_idle_path, NULL, IMG_W))
  )
  stopifnot("blank for an empty path" = is.nan(avg_zoom_log2_w(matrix(numeric(0), ncol = 6), NULL, IMG_W)))
  stopifnot(
    "blank for a 1-point path" =
      is.nan(avg_zoom_log2_w(matrix(c(0, 0, 0, 400, 300, 1000), nrow = 1, ncol = 6), NULL, IMG_W))
  )
  # But scanning/drilling/velocity/searchFocus KEEP their existing 0.0-for-degenerate convention
  # (unaffected by B2's NaN choice for the two new metrics).
  stopifnot(scanning_rate_px_per_min(all_idle_path, NULL, IMG_W) == 0.0)
  stopifnot(drilling_rate_per_min(all_idle_path, NULL, IMG_W) == 0.0)
  stopifnot(path_velocity_px_per_sec(all_idle_path) == 0.0)
  stopifnot(search_focus_ratio(all_idle_path, NULL, IMG_W) == 0.0)
  raster_all_idle <- raster_from_path(all_idle_path, IMG_W, IMG_H, 4, 4)
  stopifnot(
    "raster_from_path should return an all-zero (not NULL) grid for an all-idle >=2-point path" =
      !is.null(raster_all_idle) && sum(raster_all_idle) == 0.0
  )

  # --- B3 targeted assert: true_magnification / canonical_mag_band_labels / auto-fallback ---
  stopifnot(true_magnification(c(0, 0, 0, 400, 300, 2000), 40.0) == 20.0)
  stopifnot(
    "true_magnification should be NULL for a 5-element (schema/3, no dsMilli) point" =
      is.null(true_magnification(c(0, 0, 0, 400, 300), 40.0))
  )
  stopifnot(
    "true_magnification should be NULL without a baseMagnification" =
      is.null(true_magnification(c(0, 0, 0, 400, 300, 2000), NULL))
  )
  stopifnot("empty path -> integer(0)" = length(canonical_mag_band_labels(matrix(numeric(0), ncol = 5), 40.0)) == 0)
  stopifnot(
    "5-element (no dsMilli) points -> NULL (not computable, signal to fall back)" =
      is.null(canonical_mag_band_labels(
        matrix(c(0, 0, 0, 400, 300, 100, 10, 0, 400, 300), nrow = 2, ncol = 5, byrow = TRUE), 40.0
      ))
  )
  bands_ok <- canonical_mag_band_labels(
    matrix(c(0, 0, 0, 400, 300, 2000, 100, 0, 0, 400, 300, 1000, 200, 0, 0, 400, 300, 500),
      nrow = 3, ncol = 6, byrow = TRUE
    ),
    40.0
  )
  stopifnot("canonical band labels mismatch" = identical(bands_ok, c(5L, 6L)))

  tercile_result <- magband_labels_for_scheme(
    matrix(c(0, 0, 0, 400, 300, 100, 10, 0, 400, 300, 200, 20, 0, 400, 300),
      nrow = 3, ncol = 5, byrow = TRUE
    ),
    NULL, IMG_W, 3, scheme = "canonical"
  )
  stopifnot(
    "5-element (no dsMilli) path should auto-fall back to tercile even under the canonical default" =
      identical(tercile_result$scheme, "tercile")
  )
  stopifnot(length(tercile_result$bands) == 2)

  canon_result <- magband_labels_for_scheme(
    matrix(c(0, 0, 0, 400, 300, 2000, 100, 0, 0, 400, 300, 1000, 200, 0, 0, 400, 300, 500),
      nrow = 3, ncol = 6, byrow = TRUE
    ),
    40.0, IMG_W, 3, scheme = "canonical"
  )
  stopifnot(identical(canon_result$scheme, "canonical"))
  stopifnot(identical(canon_result$bands, c(5L, 6L)))

  forced_result <- magband_labels_for_scheme(
    matrix(c(0, 0, 0, 400, 300, 2000, 100, 0, 0, 400, 300, 1000, 200, 0, 0, 400, 300, 500),
      nrow = 3, ncol = 6, byrow = TRUE
    ),
    40.0, IMG_W, 3, scheme = "tercile"
  )
  stopifnot(
    "--magband-scheme tercile should force tercile even when canonical IS computable" =
      identical(forced_result$scheme, "tercile")
  )
  stopifnot(length(forced_result$bands) == 2)
}

#' Tier 2 B1+B2+B3+B4 pipeline-level check: runs the hand-derivable idle fixture (see
#' `build_idle_fragment`) through the full `analyze()` pipeline and asserts every documented number
#' against its hand-derived expected value. Mirrors the Python toolkit's `check_tier2_idle_fixture`.
check_tier2_idle_fixture <- function(tmp) {
  frag <- build_idle_fragment()
  in_dir <- file.path(tmp, "in_idle")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_idle")
  metrics <- analyze(list(in_dir), out_dir)

  stopifnot("expected 1 metrics row" = nrow(metrics) == 1)
  row <- metrics[1, ]

  # --- B1: idleMs/activeSpanMs ---
  stopifnot("expected idleMs == 70000.0" = row$idleMs == 70000.0)
  stopifnot("expected activeSpanMs == 3000.0" = row$activeSpanMs == 3000.0)

  # --- B1: scanningRatePxPerMin/drillingRatePerMin/pathVelocityPxPerSec, hand-derived (see
  # build_idle_fragment's docstring for the step-by-step derivation) ---
  stopifnot(
    "expected scanningRatePxPerMin == 3200.0" = abs(row$scanningRatePxPerMin - 3200.0) < 1e-6
  )
  stopifnot("expected drillingRatePerMin == 20.0" = abs(row$drillingRatePerMin - 20.0) < 1e-6)
  stopifnot("expected pathVelocityPxPerSec == 60.0" = abs(row$pathVelocityPxPerSec - 60.0) < 1e-6)

  # --- B2: avgZoomLog2W/drillingRateOctavesPerMin, hand-derived ---
  zoom25 <- 40.0 / (1600 / 1000.0); zoom100 <- 40.0 / (400 / 1000.0)
  stopifnot(abs(zoom25 - 25.0) < 1e-9 && abs(zoom100 - 100.0) < 1e-9)
  expected_avg_zoom_log2_w <- (
    1000.0 * log2(zoom25) + 1000.0 * log2(zoom25) + 1000.0 * log2(zoom100)
  ) / 3000.0
  stopifnot(
    "avgZoomLog2W mismatch" = abs(row$avgZoomLog2W - expected_avg_zoom_log2_w) < 1e-6
  )
  # Only step2 (path[2]->path[3], the sole zoom-change step) contributes a non-zero |Δlog2| term
  # among the 3 ACTIVE steps (step0/step3 are zoom-unchanged, each contributing 0); step1 (the
  # idle step, also zoom-unchanged here) is excluded from the sum regardless.
  expected_drilling_octaves <- abs(log2(zoom100) - log2(zoom25)) / 0.05
  stopifnot(
    "drillingRateOctavesPerMin mismatch" =
      abs(row$drillingRateOctavesPerMin - expected_drilling_octaves) < 1e-6
  )

  # --- B4: magnificationSource ---
  stopifnot("magnificationSource mismatch" = row$magnificationSource == "true")

  # --- raster_from_path (direct): total dwell-weight sum equals the ACTIVE dt sum (3000), not
  # the total span (73000) -- the idle step contributes zero weight to any cell ---
  raster <- raster_from_path(frag$path, IMG_W, IMG_H, 16, 16)
  stopifnot(!is.null(raster))
  stopifnot(
    "expected raster_from_path total weight == 3000.0 (active dt only)" =
      abs(sum(raster) - 3000.0) < 1e-6
  )

  # --- B3: magbands_<slug>.csv -- canonical scheme (baseMagnification + dsMilli present), 7
  # bands, band5/band6 dwell hand-derived, idle step excluded from the sum ---
  out_files <- list.files(out_dir)
  magband_files <- out_files[startsWith(out_files, "magbands_")]
  stopifnot("expected exactly one magbands_ file" = length(magband_files) == 1)
  magbands_df <- utils::read.csv(file.path(out_dir, magband_files[1]), stringsAsFactors = FALSE)
  stopifnot(all(magbands_df$bandScheme == "canonical"))
  stopifnot(nrow(magbands_df) == 7)
  by_band <- setNames(magbands_df$bandTimeMs, magbands_df$band)
  stopifnot(
    "expected band5 (20-40x) bandTimeMs == 2000.0" = abs(by_band[["5"]] - 2000.0) < 1e-6
  )
  stopifnot(
    "expected band6 (>=40x) bandTimeMs == 1000.0" = abs(by_band[["6"]] - 1000.0) < 1e-6
  )
  for (b in c("0", "1", "2", "3", "4")) {
    stopifnot(abs(by_band[[b]]) < 1e-9)
  }
  by_band_pct <- setNames(magbands_df$bandTimePct, magbands_df$band)
  stopifnot(abs(by_band_pct[["5"]] - (2000.0 / 3000.0 * 100.0)) < 1e-6)
  stopifnot(abs(by_band_pct[["6"]] - (1000.0 / 3000.0 * 100.0)) < 1e-6)
}

#' Tier 2 B2 pipeline-level check (isolated from B1's idle complexity): runs the clean doubling-
#' zoom fixture (see `build_zoom_fidelity_fragment`) through the full `analyze()` pipeline and
#' asserts `avgZoomLog2W`/`drillingRateOctavesPerMin` against their hand-derived values, plus B3's
#' tercile auto-fallback (no `baseMagnification`) and B4's "proxy-downsample" flag. Mirrors the
#' Python toolkit's `check_tier2_zoom_fidelity_fixture`.
check_tier2_zoom_fidelity_fixture <- function(tmp) {
  frag <- build_zoom_fidelity_fragment()
  in_dir <- file.path(tmp, "in_zoomfid")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_zoomfid")
  metrics <- analyze(list(in_dir), out_dir)

  stopifnot("expected 1 metrics row" = nrow(metrics) == 1)
  row <- metrics[1, ]

  stopifnot("expected avgZoomLog2W == 0.5" = abs(row$avgZoomLog2W - 0.5) < 1e-9)
  stopifnot(
    "expected drillingRateOctavesPerMin == 60.0" = abs(row$drillingRateOctavesPerMin - 60.0) < 1e-6
  )
  stopifnot("no idle gap in this fixture" = row$idleMs == 0.0)
  stopifnot(abs(row$activeSpanMs - 2000.0) < 1e-9)
  stopifnot(
    "no baseMagnification on this fixture -> proxy-downsample" =
      row$magnificationSource == "proxy-downsample"
  )

  out_files <- list.files(out_dir)
  magband_files <- out_files[startsWith(out_files, "magbands_")]
  stopifnot("expected exactly one magbands_ file" = length(magband_files) == 1)
  magbands_df <- utils::read.csv(file.path(out_dir, magband_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "no baseMagnification -> canonical not computable -> auto-fallback to tercile" =
      all(magbands_df$bandScheme == "tercile")
  )
  stopifnot(nrow(magbands_df) == 3)
}

#' Tier 2 B3 CLI check: `--magband-scheme tercile` (`magband_scheme="tercile"`) FORCES the tercile
#' scheme even for the idle fixture, whose `baseMagnification`/`dsMilli` make the canonical scheme
#' computable and therefore the DEFAULT choice (see `check_tier2_idle_fixture`). Mirrors the Python
#' toolkit's `check_tier2_magband_scheme_cli`.
check_tier2_magband_scheme_cli <- function(tmp) {
  frag <- build_idle_fragment()
  in_dir <- file.path(tmp, "in_idle_tercile")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_idle_tercile")
  analyze(list(in_dir), out_dir, magband_scheme = "tercile")

  out_files <- list.files(out_dir)
  magband_files <- out_files[startsWith(out_files, "magbands_")]
  stopifnot("expected exactly one magbands_ file" = length(magband_files) == 1)
  magbands_df <- utils::read.csv(file.path(out_dir, magband_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "--magband-scheme tercile should force tercile even when canonical is computable" =
      all(magbands_df$bandScheme == "tercile")
  )
  stopifnot(
    "tercile scheme should emit the default 3 bands" = nrow(magbands_df) == 3
  )
}

# ---------------------------------------------------------------------------
# Tier 3 C1 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md, I-DT fixations).
# Mirrors the Python toolkit's check_tier3_* functions 1:1.
# ---------------------------------------------------------------------------

#' Direct, pipeline-independent unit checks for the new Tier 3 C1 `fixations_idt` (and its
#' summary-statistic wrappers) -- TDD-style asserts on hand-built inputs, bypassing the full
#' `analyze()` pipeline entirely. Mirrors the Python toolkit's `check_tier3_direct_unit_asserts`.
check_tier3_direct_unit_asserts <- function() {
  # --- degenerate inputs: not computable at all (NULL), never a crash ---
  stopifnot(
    "fixations_idt should be NULL for an empty path" = is.null(fixations_idt(list())),
    "fixations_idt should be NULL for a 1-point path" =
      is.null(fixations_idt(matrix(c(0, 0, 0, 400), nrow = 1)))
  )
  stopifnot(
    "n_fixations should be blank (NaN) for NULL" = is.nan(n_fixations(NULL)),
    is.nan(mean_fixation_ms(NULL)),
    is.nan(median_fixation_ms(NULL)),
    is.nan(sd_fixation_ms(NULL))
  )
  stopifnot(is.nan(fixations_per_min(NULL, matrix(c(0, 0, 0, 400), nrow = 1))))

  # --- >=2 points but NO window ever reaches MIN_FIXATION_MS at all (too-short total span) -> a
  # real, well-defined list() (zero fixations found), NOT NULL/blank ---
  too_short_path <- matrix(c(0, 0, 0, 400, 100, 0, 0, 400), nrow = 2, ncol = 4, byrow = TRUE)
  fx_short <- fixations_idt(too_short_path)
  stopifnot(
    "expected list() (zero fixations, not NULL) for a too-short path" = length(fx_short) == 0,
    !is.null(fx_short)
  )
  stopifnot("n_fixations should be 0 (not blank) for list()" = identical(n_fixations(fx_short), 0L))
  stopifnot(
    "mean_fixation_ms should be blank for zero fixations found" = is.nan(mean_fixation_ms(fx_short)),
    is.nan(median_fixation_ms(fx_short)),
    is.nan(sd_fixation_ms(fx_short))
  )
  # active span IS positive here (100ms, no idle gap) -> fixationsPerMin is a well-defined 0.0, not
  # blank (a real "zero events over a real duration" rate).
  stopifnot(
    "fixationsPerMin should be 0.0 (not blank) for zero fixations over a positive active span" =
      fixations_per_min(fx_short, too_short_path) == 0.0
  )

  # --- >=2 points, total span DOES reach MIN_FIXATION_MS, but dispersion is always over threshold
  # for every candidate window (a jittery/noisy path) -> also a real list() ---
  always_over_threshold_path <- matrix(
    c(
      0, 0, 0, 10,      # threshold = 0.25*10 = 2.5
      125, 50, 50, 10,
      250, 100, 100, 10  # span p0->p2=250>=250; dispersion (100-0)+(100-0)=200 > 2.5 -> reject,
                          # advance start by 1; from p1, span p1->p2=125<250, no more points -> STOP.
    ),
    nrow = 3, ncol = 4, byrow = TRUE
  )
  fx_disp <- fixations_idt(always_over_threshold_path)
  stopifnot(
    "expected list() for a path whose dispersion never drops to/below threshold" =
      length(fx_disp) == 0 && !is.null(fx_disp)
  )

  # --- zero ACTIVE SPAN (duplicate timestamps): fixations_idt still returns list() (well-defined,
  # not NULL -- the path has >=2 points), but fixationsPerMin must be blank (0/0 rate is
  # undefined), distinct from the too-short-path case above where active span was positive ---
  dup_ts_path <- matrix(c(0, 0, 0, 400, 0, 0, 0, 400), nrow = 2, ncol = 4, byrow = TRUE)
  fx_dup <- fixations_idt(dup_ts_path)
  stopifnot(
    "expected list() for a zero-duration 2-point path" = length(fx_dup) == 0 && !is.null(fx_dup)
  )
  stopifnot(
    "fixationsPerMin should be blank (NaN) when active span is zero, even with fixations=list()" =
      is.nan(fixations_per_min(fx_dup, dup_ts_path))
  )

  # --- step 4 ("advance start by ONE, not past the whole rejected window"): the first candidate
  # window [p0,p1] is over threshold and rejected; the NEXT window must be allowed to start at p1
  # (not p2) -- i.e. p1 is reused as a fixation's first point despite having been part of the
  # rejected window. ---
  advance_by_one_path <- matrix(
    c(
      0, 0, 0, 400,        # threshold(from p0) = 100
      260, 1000, 0, 400,   # span p0->p1=260>=250; dispersion (1000-0)+0=1000 > 100 -> reject;
                            # advance start to p1 (index 2), NOT to index 3.
      520, 1001, 1, 400     # from start=p1: span p1->p2=260>=250; w_start=w[p1]=400, threshold=100;
                            # dispersion (1001-1000)+(1-0)=2<=100 -> candidate OK; no more points.
    ),
    nrow = 3, ncol = 4, byrow = TRUE
  )
  fx_adv <- fixations_idt(advance_by_one_path)
  stopifnot(
    "expected exactly 1 fixation (starting at p1, after rejecting [p0,p1])" = length(fx_adv) == 1
  )
  fxn <- fx_adv[[1]]
  stopifnot(
    "expected the fixation to start at p1 (startMs=260, nPoints=2)" =
      fxn$startMs == 260.0 && fxn$nPoints == 2L
  )
  stopifnot(abs(fxn$durationMs - 260.0) < 1e-9)
  stopifnot(abs(fxn$centerImageX - 1000.5) < 1e-9)
  stopifnot(abs(fxn$centerImageY - 0.5) < 1e-9)

  # --- sdFixationMs: blank for exactly 1 fixation (not 0.0) -- distinct from the 0.0 sd of TWO
  # identical-duration fixations (see check_tier3_fixation_fixture) ---
  stopifnot(
    "sd_fixation_ms should be blank (NaN) for a single fixation, not 0.0" = is.nan(sd_fixation_ms(fx_adv))
  )
  stopifnot(n_fixations(fx_adv) == 1)
  stopifnot(mean_fixation_ms(fx_adv) == 260.0)
  stopifnot(median_fixation_ms(fx_adv) == 260.0)

  # --- mean/median/sd over a THREE-fixation list with genuinely different durations, to exercise
  # the actual arithmetic (not just trivial all-equal/degenerate cases) ---
  synth_fixations <- list(
    list(durationMs = 300.0, centerImageX = 0.0, centerImageY = 0.0, startMs = 0.0, nPoints = 3L),
    list(durationMs = 400.0, centerImageX = 0.0, centerImageY = 0.0, startMs = 500.0, nPoints = 3L),
    list(durationMs = 500.0, centerImageX = 0.0, centerImageY = 0.0, startMs = 1000.0, nPoints = 3L)
  )
  stopifnot(n_fixations(synth_fixations) == 3)
  stopifnot(abs(mean_fixation_ms(synth_fixations) - 400.0) < 1e-9)
  stopifnot(abs(median_fixation_ms(synth_fixations) - 400.0) < 1e-9)
  # sample sd (ddof=1) of [300,400,500]: mean=400, sq devs=[10000,0,10000], sum=20000, var=10000,
  # sd=100.0
  stopifnot(abs(sd_fixation_ms(synth_fixations) - 100.0) < 1e-9)

  # --- Final-review Finding 4 regression: an idle-flagged step (dt > IDLE_GAP_MS=60000) is a HARD
  # fixation-window boundary -- a window may never bridge it. Hand-derived (see
  # docs/superpowers/sdd/enrichment-finalfix-report.md): 4s dwell at (100,100), a 66s away-gap
  # (idle) with an otherwise near-stationary viewport either side, then a 1s dwell at ~(100,100).
  # PRE-FIX this bridged into ONE 71000ms fixation (nPoints=4); POST-FIX it must split into exactly
  # 2 fixations at the idle boundary.
  idle_gap_path <- matrix(
    c(
      0, 100, 100, 400, 300,
      4000, 100, 100, 400, 300,
      70000, 101, 101, 400, 300,   # step2 (p2->p3) dt=66000 > IDLE_GAP_MS=60000 -> idle
      71000, 100, 100, 400, 300
    ),
    nrow = 4, ncol = 5, byrow = TRUE
  )
  fx_idle <- fixations_idt(idle_gap_path)
  stopifnot(
    "expected the idle gap to SPLIT the path into exactly 2 fixations (not 1 bridged 71000ms fixation)" =
      !is.null(fx_idle) && length(fx_idle) == 2
  )
  fxi1 <- fx_idle[[1]]; fxi2 <- fx_idle[[2]]
  stopifnot(abs(fxi1$startMs - 0.0) < 1e-9, abs(fxi1$durationMs - 4000.0) < 1e-9)
  stopifnot(fxi1$nPoints == 2L, abs(fxi1$centerImageX - 100.0) < 1e-9)
  stopifnot(abs(fxi2$startMs - 70000.0) < 1e-9, abs(fxi2$durationMs - 1000.0) < 1e-9)
  stopifnot(fxi2$nPoints == 2L, abs(fxi2$centerImageX - 100.5) < 1e-9)
  stopifnot(n_fixations(fx_idle) == 2)
  stopifnot(abs(mean_fixation_ms(fx_idle) - 2500.0) < 1e-9)
  stopifnot(abs(median_fixation_ms(fx_idle) - 2500.0) < 1e-9)
  # sample sd (ddof=1) of [4000, 1000]: mean=2500, sq devs=[2250000,2250000], sum=4500000,
  # var=4500000, sd=sqrt(4500000)=2121.320343559643
  stopifnot(abs(sd_fixation_ms(fx_idle) - 2121.320343559643) < 1e-6)
  # activeSpanMs = (71000-0) - idleMs(66000) = 5000 -> fixationsPerMin = 2 / (5000/60000) = 24.0
  stopifnot(abs(idle_ms(idle_gap_path) - 66000.0) < 1e-9)
  stopifnot(abs(active_span_ms(idle_gap_path) - 5000.0) < 1e-9)
  stopifnot(abs(fixations_per_min(fx_idle, idle_gap_path) - 24.0) < 1e-9)
  # No-drift check: the pre-existing non-idle fixtures above contain no step whose dt exceeds
  # IDLE_GAP_MS, so `idle_step_mask` is all-FALSE for each and this fix is a no-op for all of them
  # -- already re-verified by the unmodified stopifnot()s above still passing.
}

#' Tier 3 C1 pipeline-level check: runs the hand-derivable fixation fixture (see
#' `build_fixation_fragment`) through the full `analyze()` pipeline and asserts every documented
#' number -- metrics.csv's summary columns AND fixations_<slug>.csv's per-fixation rows -- against
#' its hand-derived expected values. Mirrors the Python toolkit's `check_tier3_fixation_fixture`.
check_tier3_fixation_fixture <- function(tmp) {
  frag <- build_fixation_fragment()
  in_dir <- file.path(tmp, "in_fixation")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_fixation")
  analyze(list(in_dir), out_dir)

  metrics <- utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
  stopifnot(nrow(metrics) == 1)
  row <- metrics[1, ]

  stopifnot("expected nFixations == 2" = row$nFixations == 2)
  stopifnot(abs(row$meanFixationMs - 300.0) < 1e-9)
  stopifnot(abs(row$medianFixationMs - 300.0) < 1e-9)
  stopifnot(
    "expected sdFixationMs == 0.0 (both fixations are exactly 300ms)" =
      abs(row$sdFixationMs - 0.0) < 1e-9
  )
  expected_fpm <- 2.0 / (650.0 / 60000.0)
  stopifnot(abs(row$fixationsPerMin - expected_fpm) < 1e-6)

  out_files <- list.files(out_dir)
  fixation_files <- out_files[startsWith(out_files, "fixations_")]
  stopifnot("expected exactly one fixations_ file" = length(fixation_files) == 1)
  fixations_df <- utils::read.csv(file.path(out_dir, fixation_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "fixations_<slug>.csv columns mismatch" = identical(
      colnames(fixations_df),
      c("session", "idx", "startMs", "durationMs", "centerImageX", "centerImageY", "nPoints")
    )
  )
  stopifnot("expected 2 fixation rows" = nrow(fixations_df) == 2)

  f1 <- fixations_df[fixations_df$idx == 1, ]
  stopifnot(f1$session == "fix1")
  stopifnot(abs(f1$startMs - 0.0) < 1e-9)
  stopifnot(abs(f1$durationMs - 300.0) < 1e-9)
  stopifnot(abs(f1$centerImageX - 101.0) < 1e-9)
  stopifnot(abs(f1$centerImageY - 100.0) < 1e-9)
  stopifnot(f1$nPoints == 3)

  f2 <- fixations_df[fixations_df$idx == 2, ]
  stopifnot(abs(f2$startMs - 350.0) < 1e-9)
  stopifnot(abs(f2$durationMs - 300.0) < 1e-9)
  stopifnot(abs(f2$centerImageX - 900.0) < 1e-9)
  stopifnot(abs(f2$centerImageY - 900.0) < 1e-9)
  stopifnot(f2$nPoints == 3)
}

#' Direct, pipeline-independent unit checks for the new Tier 3 C2 (`mouse_raster_from_path`), C3
#' (`dtw_distance`), and C4 (`mean_segment_linearity`) functions -- TDD-style asserts on hand-built
#' inputs, bypassing the full `analyze()` pipeline entirely. Mirrors
#' `check_tier4_direct_unit_asserts` in the Python toolkit exactly (same hand-built inputs, same
#' expected values).
check_tier4_direct_unit_asserts <- function() {
  # ---- C2: mouse_raster_from_path degenerate + sentinel-skip cases ----
  path_no_mouse <- list(c(0, 0, 0, 400, 300), c(100, 10, 0, 400, 300)) # 5-element, no mouse data
  stopifnot(
    "mouse_raster_from_path should be NULL for a path with no schema/5 mouse data" =
      is.null(mouse_raster_from_path(path_no_mouse, 100, 100, 2, 2))
  )
  path_all_off <- list(
    c(0, 0, 0, 400, 300, 1000, -1, -1),
    c(100, 10, 0, 400, 300, 1000, -1, -1)
  )
  stopifnot(
    "mouse_raster_from_path should be NULL when every sample is off-slide" =
      is.null(mouse_raster_from_path(path_all_off, 100, 100, 2, 2))
  )
  # Sentinel-skip regression: point1's own dt-owning step is skipped because the step touches
  # the sentinel at point2 -- even though point1 ITSELF is on-slide.
  path_mixed <- list(
    c(0, 0, 0, 400, 300, 1000, 10, 10),    # on-slide, cell(0,0) [100x100 img, gw=gh=2 -> 50x50 cells]
    c(100, 0, 0, 400, 300, 1000, 60, 60),  # on-slide, cell(1,1)
    c(200, 0, 0, 400, 300, 1000, -1, -1),  # OFF-SLIDE sentinel
    c(300, 0, 0, 400, 300, 1000, 20, 20)   # on-slide, cell(0,0)
  )
  grid <- mouse_raster_from_path(path_mixed, 100, 100, 2, 2)
  stopifnot(!is.null(grid))
  stopifnot(
    "expected cell(0,0)==100 (step0's dt, owned by point0's on-slide cursor)" =
      abs(grid[1] - 100.0) < 1e-9
  )
  stopifnot(
    "expected cell(1,1)==0 -- step1's dt must be dropped (touches the sentinel at point2)" =
      abs(grid[4] - 0.0) < 1e-9
  )
  stopifnot(abs(grid[2]) < 1e-9 && abs(grid[3]) < 1e-9)

  # Idle-exclusion regression (Tier 2 B1, reused via idle_step_mask): a step whose dt exceeds
  # IDLE_GAP_MS is skipped even though both its endpoints are on-slide -- the user stepped away,
  # so that interval's cursor position should contribute no dwell weight at all.
  path_idle <- list(
    c(0, 0, 0, 400, 300, 1000, 10, 10),       # on-slide, cell(0,0)
    c(70000, 0, 0, 400, 300, 1000, 60, 60),   # on-slide, cell(1,1) -- dt=70000 > IDLE_GAP_MS -> idle
    c(70100, 0, 0, 400, 300, 1000, 20, 20)    # on-slide, cell(0,0) -- dt=100, active
  )
  grid_idle <- mouse_raster_from_path(path_idle, 100, 100, 2, 2)
  stopifnot(!is.null(grid_idle))
  stopifnot(
    "expected cell(0,0)==0 -- the idle step0 must contribute no weight" =
      abs(grid_idle[1] - 0.0) < 1e-9
  )
  stopifnot(
    "expected cell(1,1)==100 -- the active step1's dt, owned by point1's on-slide cursor" =
      abs(grid_idle[4] - 100.0) < 1e-9
  )

  # ---- Final-review Finding 2 regression: mouse_raster_from_path must NOT crash on a zero-size
  # grid (gw=0/gh=0) -- the column/row clamp resolves to -1 for a zero-size axis, so `grid[row+1,
  # col+1]` (col+1L == 0L) would touch R's index-0 no-op quirk rather than crash, but the guard is
  # added anyway for explicit Python<->R parity (see mouse_raster_from_path's docs).
  path_zero_grid <- list(
    c(0, 0, 0, 400, 300, 1000, 10, 10),
    c(1000, 0, 0, 400, 300, 1000, 20, 20)
  )
  stopifnot(
    "mouse_raster_from_path should return NULL (not crash) for a zero-size grid (gw=0)" =
      is.null(mouse_raster_from_path(path_zero_grid, 100, 100, 0L, 5L))
  )
  stopifnot(
    "mouse_raster_from_path should return NULL (not crash) for a zero-size grid (gh=0)" =
      is.null(mouse_raster_from_path(path_zero_grid, 100, 100, 5L, 0L))
  )

  # ---- C3: dtw_distance ----
  stopifnot(
    "dtw_distance should be blank when path_a is empty" =
      is.nan(dtw_distance(list(), list(c(0, 0, 0, 400))))
  )
  stopifnot(
    "dtw_distance should be blank when path_b is NULL" =
      is.nan(dtw_distance(list(c(0, 0, 0, 400)), NULL))
  )
  # Invariance: pathB is pathA's (cx, cy) affine-transformed per axis with a POSITIVE scale
  # -> z-normalization removes both mean and scale, so pathB z-normalizes IDENTICALLY to pathA ->
  # the diagonal alignment (cost 0.0 everywhere) is also the DP's global minimum -> EXACTLY 0.0.
  path_a <- list(c(0, 0, 0, 400), c(100, 10, 5, 400), c(200, 20, 10, 400), c(300, 30, 15, 400))
  path_b <- lapply(path_a, function(p) c(p[1], 100 + 2 * p[2], -50 + 3 * p[3], p[4]))
  dtw_inv <- dtw_distance(path_a, path_b)
  stopifnot(
    "expected dtwDistance == 0.0 (affine invariance)" = abs(dtw_inv - 0.0) < 1e-9
  )
  # Differing-shape known value: a horizontal 3-point line vs a vertical 3-point line --
  # independently verified against the implementation before this fixture was written:
  # dtwDistance == 2*sqrt(2) == 2.8284271247461903.
  path_horiz <- list(c(0, 0, 0, 400), c(100, 1, 0, 400), c(200, 2, 0, 400))
  path_vert <- list(c(0, 0, 0, 400), c(100, 0, 1, 400), c(200, 0, 2, 400))
  dtw_cross <- dtw_distance(path_horiz, path_vert)
  stopifnot(
    "expected dtwDistance == 2*sqrt(2) == 2.8284271247461903" =
      abs(dtw_cross - 2.8284271247461903) < 1e-9
  )
  # Constant-axis z-norm guard: a path with cy all identical (sd==0 on that axis) must not
  # raise/NaN -- z-normalizes to all-0.0 on that axis.
  path_const_y <- list(c(0, 0, 5, 400), c(100, 10, 5, 400), c(200, 20, 5, 400))
  dtw_const <- dtw_distance(path_const_y, path_const_y)
  stopifnot(
    "self-comparison of a constant-y path should still be exactly 0.0" = abs(dtw_const - 0.0) < 1e-9
  )

  # ---- C4: mean_segment_linearity degenerate cases ----
  grid8 <- rep(0.0, 64)
  grid8[1] <- 1000.0; grid8[28] <- 900.0; grid8[61] <- 800.0; grid8[62] <- 700.0; grid8[63] <- 600.0
  IMG_W_T <- 2000; IMG_H_T <- 1500
  # <2 hotspots: a grid with only 1 cell total.
  stopifnot(
    "mean_segment_linearity should be blank for a grid with <2 cells" =
      is.nan(mean_segment_linearity(
        list(c(0, 0, 0, 400), c(100, 1, 1, 400)), c(5.0), 1, 1, 100, 100
      ))
  )
  # <2 path points.
  stopifnot(
    "mean_segment_linearity should be blank for a <2-point path" =
      is.nan(mean_segment_linearity(list(c(0, 0, 0, 400)), grid8, 8, 8, IMG_W_T, IMG_H_T))
  )
  # 0 boundary points: path never visits a hotspot cell.
  p_no_hit <- list(c(0, 1400, 1000, 400), c(100, 1410, 1010, 400))
  stopifnot(
    "mean_segment_linearity should be blank when the path never touches a hotspot cell" =
      is.nan(mean_segment_linearity(p_no_hit, grid8, 8, 8, IMG_W_T, IMG_H_T))
  )
  # exactly 1 boundary point: only 1 hotspot hit -> no "between consecutive boundaries" pair.
  p_one_hit <- list(c(0, 1400, 1000, 400), c(100, 50, 50, 400), c(200, 1410, 1010, 400))
  stopifnot(
    "mean_segment_linearity should be blank with only 1 boundary point" =
      is.nan(mean_segment_linearity(p_one_hit, grid8, 8, 8, IMG_W_T, IMG_H_T))
  )

  # ---- C4 dedup-fix regression: a dwell run (3 consecutive samples in ONE hotspot cell) followed
  # by a genuine transit to a SECOND hotspot must NOT inflate meanSegmentLinearity toward 1.0 via
  # trivial within-dwell 2-point segments -- see build_seglin_dwell_fragment's docstring for the
  # full hand derivation (independently derived from raw coordinates, not by calling this
  # function). PRE-FIX this fixture would have returned 0.9738334909707113 (mean of
  # [1.0, 1.0, 0.921500472912134] over 3 undeduped segments); POST-FIX it must collapse the dwell
  # run to a single boundary and return the ONE real transit segment's linearity instead.
  p_dwell <- list(
    c(0, 50, 50, 400, 300),
    c(100, 60, 55, 400, 300),
    c(200, 70, 60, 400, 300),
    c(300, 400, 100, 400, 300),
    c(400, 800, 700, 400, 300)
  )
  v_dwell <- mean_segment_linearity(p_dwell, grid8, 8, 8, IMG_W_T, IMG_H_T)
  stopifnot(
    "expected meanSegmentLinearity == 0.9224688773734908 (single collapsed transit segment)" =
      abs(v_dwell - 0.9224688773734908) < 1e-9
  )
  stopifnot(
    "post-fix value must differ from the pre-fix-inflated 0.9738334909707113" =
      abs(v_dwell - 0.9738334909707113) > 1e-6
  )
}

#' Tier 3 C2 pipeline-level check: runs `build_mouse_fixture` (2 schema/5 sessions, hand-derivable
#' mouse dwell grids) through the full `analyze()` pipeline and asserts every documented number --
#' `metrics.csv`'s `mouseCoveragePct`/`mouseEntropy` AND `mouse_<slug>.csv`'s pairwise
#' `iou`/`coincidenceLevel` -- against their hand-derived expected values.
check_tier4_mouse_fixture <- function(tmp) {
  fragments <- build_mouse_fixture()
  in_dir <- file.path(tmp, "in_mouse")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out_mouse")
  analyze(list(in_dir), out_dir)

  metrics <- utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
  stopifnot(nrow(metrics) == 2)
  row1 <- metrics[metrics$session == "mouse1", ]
  row2 <- metrics[metrics$session == "mouse2", ]

  stopifnot(abs(row1$mouseCoveragePct - (1.0 / 64.0 * 100.0)) < 1e-6)
  stopifnot(abs(row1$mouseEntropy - 0.0) < 1e-6)
  stopifnot(abs(row2$mouseCoveragePct - (2.0 / 64.0 * 100.0)) < 1e-6)
  stopifnot(abs(row2$mouseEntropy - 1.0) < 1e-6)

  out_files <- list.files(out_dir)
  mouse_files <- out_files[startsWith(out_files, "mouse_")]
  stopifnot("expected exactly one mouse_ file" = length(mouse_files) == 1)
  mouse_df <- utils::read.csv(file.path(out_dir, mouse_files[1]), stringsAsFactors = FALSE)
  stopifnot("expected 2x2=4 pairwise rows" = nrow(mouse_df) == 4)

  cross <- mouse_df[mouse_df$sessionA == "mouse1" & mouse_df$sessionB == "mouse2", ]
  stopifnot("expected iou(mouse1,mouse2) == 0.5" = abs(cross$iou - 0.5) < 1e-9)

  diag1 <- mouse_df[mouse_df$sessionA == "mouse1" & mouse_df$sessionB == "mouse1", ]
  stopifnot(
    "expected coincidenceLevel == 0.5 (diagonal-reuse row, first session)" =
      abs(diag1$coincidenceLevel - 0.5) < 1e-9
  )
  diag2 <- mouse_df[mouse_df$sessionA == "mouse2" & mouse_df$sessionB == "mouse2", ]
  stopifnot(
    "coincidenceLevel should be blank on every diagonal row except the first session's" =
      is.na(diag2$coincidenceLevel)
  )
}

#' PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2) pipeline-level check: runs
#' `build_mouse_icc_fixture` (2 schema/5 sessions with an identical mouse path, so their resampled
#' mouse-dwell grids are identical arrays) through the full `analyze()` pipeline and asserts
#' `mouse_<slug>.csv`'s new `mouseICC` column is exactly `1.0` on the diagonal-reuse row (the
#' first session) and blank on every other row -- plus the mirrored `summary.md` "cursor
#' agreement" line.
check_mouse_icc_fixture <- function(tmp) {
  fragments <- build_mouse_icc_fixture()
  in_dir <- file.path(tmp, "in_mouse_icc")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out_mouse_icc")
  analyze(list(in_dir), out_dir)

  out_files <- list.files(out_dir)
  mouse_files <- out_files[startsWith(out_files, "mouse_")]
  stopifnot("expected exactly one mouse_ file" = length(mouse_files) == 1)
  mouse_df <- utils::read.csv(file.path(out_dir, mouse_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "mouse_<slug>.csv columns mismatch" =
      identical(colnames(mouse_df), c("sessionA", "sessionB", "cc", "iou", "coincidenceLevel", "mouseICC"))
  )
  stopifnot("expected 2x2=4 pairwise rows" = nrow(mouse_df) == 4)

  diag1 <- mouse_df[mouse_df$sessionA == "micc1" & mouse_df$sessionB == "micc1", ]
  stopifnot(
    "expected mouseICC == 1.0 (identical mouse-dwell grids, diagonal-reuse row)" =
      abs(diag1$mouseICC - 1.0) < 1e-9
  )
  diag2 <- mouse_df[mouse_df$sessionA == "micc2" & mouse_df$sessionB == "micc2", ]
  stopifnot(
    "mouseICC should be blank on every diagonal row except the first session's" =
      is.na(diag2$mouseICC)
  )
  cross <- mouse_df[mouse_df$sessionA == "micc1" & mouse_df$sessionB == "micc2", ]
  stopifnot("mouseICC should be blank on off-diagonal rows" = is.na(cross$mouseICC))

  summary_text <- paste(readLines(file.path(out_dir, "summary.md"), warn = FALSE), collapse = "\n")
  stopifnot(
    "expected the new PT2 'cursor agreement' summary.md line with meanPairwiseMouseCC/mouseICC both 1.000" =
      grepl("cursor agreement: mean pairwise mouse CC = 1.000, mouse ICC(2,1) = 1.000", summary_text, fixed = TRUE)
  )
}

#' Tier 3 C4 pipeline-level check: runs `build_seglin_fragment` (a single path with a known
#' 2-boundary hotspot split, colinear segment) through the full `analyze()` pipeline and asserts
#' `meanSegmentLinearity == 1.0` exactly.
check_tier4_seglin_fixture <- function(tmp) {
  frag <- build_seglin_fragment()
  in_dir <- file.path(tmp, "in_seglin")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_seglin")
  analyze(list(in_dir), out_dir)

  metrics <- utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
  stopifnot(nrow(metrics) == 1)
  row <- metrics[1, ]
  stopifnot(
    "expected meanSegmentLinearity == 1.0 (single colinear segment)" =
      abs(row$meanSegmentLinearity - 1.0) < 1e-9
  )
  # Bonus sanity (not exact-value asserted): the whole-path linearity is materially lower than the
  # segment's, illustrating the Roa-Pena whole-vs-segment contrast the spec cites.
  stopifnot(row$linearity < row$meanSegmentLinearity)
}

#' C4 dedup-fix pipeline-level check: runs `build_seglin_dwell_fragment` (a dwell run of 3
#' consecutive samples in one hotspot cell, then a genuine transit to a second hotspot) through the
#' full `analyze()` pipeline and asserts `meanSegmentLinearity` equals the hand-derived POST-fix
#' value (single collapsed transit segment), NOT the pre-fix-inflated value (mean of two trivial
#' 1.0 within-dwell segments plus the real transit segment) -- see the fixture's docstring for the
#' full derivation of both numbers.
check_tier4_seglin_dwell_fixture <- function(tmp) {
  frag <- build_seglin_dwell_fragment()
  in_dir <- file.path(tmp, "in_seglin_dwell")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_seglin_dwell")
  analyze(list(in_dir), out_dir)

  metrics <- utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
  stopifnot(nrow(metrics) == 1)
  row <- metrics[1, ]
  stopifnot(
    "expected meanSegmentLinearity == 0.9224688773734908 (post-fix, dwell run collapsed to a single boundary)" =
      abs(row$meanSegmentLinearity - 0.9224688773734908) < 1e-9
  )
  stopifnot(
    "must differ from the pre-fix-inflated value 0.9738334909707113" =
      abs(row$meanSegmentLinearity - 0.9738334909707113) > 1e-6
  )
}

#' PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): direct, pipeline-independent
#' unit checks for `mean_segment_linearity_roi` -- TDD-style asserts on hand-built inputs,
#' bypassing the full `analyze()` pipeline entirely. Mirrors the Python toolkit's
#' `check_pt3_direct_unit_asserts` exactly. Masks are hand-built logical vectors (NOT run through
#' `rasterize_feature_collection`), so these asserts are independent of the rasterizer's own
#' correctness -- the rasterizer's cell-center-containment behaviour is verified separately in
#' `check_pt3_roi_seglin_fixture`'s full-pipeline run.
check_pt3_direct_unit_asserts <- function() {
  gw <- 4L; gh <- 4L
  img_w <- 400; img_h <- 400
  mask_cell5 <- rep(FALSE, 16)
  mask_cell5[6] <- TRUE # cell(row1, col1), 0-based idx5 -> 1-based idx6

  # ---- no annotations at all (empty mask) -> blank ----
  p_any <- matrix(c(0, 50, 50, 400, 100, 150, 150, 400), nrow = 2, ncol = 4, byrow = TRUE)
  stopifnot(
    "mean_segment_linearity_roi should be blank when the mask has no TRUE cells at all" =
      is.nan(mean_segment_linearity_roi(p_any, rep(FALSE, 16), gw, gh, img_w, img_h))
  )

  # ---- <2 path points -> blank ----
  p_one <- matrix(c(0, 150, 150, 400), nrow = 1, ncol = 4, byrow = TRUE)
  stopifnot(
    "mean_segment_linearity_roi should be blank for a <2-point path" =
      is.nan(mean_segment_linearity_roi(p_one, mask_cell5, gw, gh, img_w, img_h))
  )
  stopifnot(
    "mean_segment_linearity_roi should be blank for an empty/NULL path" =
      is.nan(mean_segment_linearity_roi(NULL, mask_cell5, gw, gh, img_w, img_h))
  )

  # ---- path never enters the annotated region -> 0 boundaries -> blank ----
  p_never_enters <- matrix(
    c(0, 50, 50, 400, 100, 350, 50, 400, 200, 50, 350, 400),
    nrow = 3, ncol = 4, byrow = TRUE
  )
  stopifnot(
    "mean_segment_linearity_roi should be blank when the path never enters the ROI" =
      is.nan(mean_segment_linearity_roi(p_never_enters, mask_cell5, gw, gh, img_w, img_h))
  )

  # ---- exactly 1 ROI-entry boundary (enters once, never re-enters) -> blank ----
  p_one_entry <- matrix(
    c(0, 50, 50, 400, 100, 150, 150, 400, 200, 160, 140, 400),
    nrow = 3, ncol = 4, byrow = TRUE
  )
  stopifnot(
    "mean_segment_linearity_roi should be blank with only 1 ROI-entry boundary (no segment pair)" =
      is.nan(mean_segment_linearity_roi(p_one_entry, mask_cell5, gw, gh, img_w, img_h))
  )

  # ---- i==0-already-inside boundary case: the path STARTS inside the ROI (no preceding OUTSIDE
  # point exists at all), then exits, then re-enters -> boundary_idx (1-based)=c(1,3), 1 segment.
  # None of the fixtures above exercise this branch (every other path starts OUTSIDE) --
  # `inside && !prev_inside` is TRUE at i=1 because `prev_inside` is initialized FALSE, so this is
  # a real, distinct code path (spec: "i==0, or the previous point's cell was OUTSIDE"). Hand-
  # derived (mirrors the Python toolkit's fixture exactly): net=dist(p0,p2)=
  # dist((150,150),(180,120))=sqrt(30^2+30^2)=42.42640687119285;
  # total=dist(p0,p1)+dist(p1,p2)=dist((150,150),(50,50))+dist((50,50),(180,120))
  #      =sqrt(20000)+sqrt(21800)=289.06958683964353 -> linearity=0.14676883630351673.
  p_start_inside <- matrix(
    c(
      0, 150, 150, 400,
      100, 50, 50, 400,
      200, 180, 120, 400
    ),
    nrow = 3, ncol = 4, byrow = TRUE
  )
  v_start_inside <- mean_segment_linearity_roi(p_start_inside, mask_cell5, gw, gh, img_w, img_h)
  stopifnot(
    "expected meanSegmentLinearityROI == 0.14676883630351673 (i==0-already-inside boundary case)" =
      abs(v_start_inside - 0.14676883630351673) < 1e-9
  )

  # ---- dedup regression + exact-value assert: see build_roi_seglin_fragment's docstring for the
  # full hand derivation (independently derived from raw coordinates, not by calling this
  # function). A dwell run of 3 consecutive inside-cell samples, followed by a genuine transit out
  # and back in, must collapse to exactly 2 boundaries (1 segment), NOT 4 boundaries (3 segments,
  # 2 of them trivial 2-point 1.0-linearity runs) ----
  p_dwell_roi <- matrix(
    c(
      0, 50, 50, 400, 300,
      100, 150, 150, 400, 300,
      200, 160, 140, 400, 300,
      300, 140, 160, 400, 300,
      400, 350, 50, 400, 300,
      500, 50, 350, 400, 300,
      600, 180, 120, 400, 300
    ),
    nrow = 7, ncol = 5, byrow = TRUE
  )
  v_roi <- mean_segment_linearity_roi(p_dwell_roi, mask_cell5, gw, gh, img_w, img_h)
  stopifnot(
    "expected meanSegmentLinearityROI == 0.0438310715803678 (single collapsed-dedup transit segment p1..p6)" =
      abs(v_roi - 0.0438310715803678) < 1e-9
  )
  stopifnot(
    "value must differ from the naive no-dedup mean 0.687040133821826 -- if this fails, the ROI-entry boundary detection regressed to per-sample (undeduped) boundaries" =
      abs(v_roi - 0.687040133821826) > 1e-6
  )
}

#' PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3) pipeline-level check: runs
#' `build_roi_seglin_fragment` (a single path + one rectangular annotation) through the full
#' `analyze()` pipeline and asserts `meanSegmentLinearityROI` equals the hand-derived value,
#' exercising both the dwell-run dedup (3 consecutive inside-cell points collapse to ONE boundary)
#' and the rasterizer's cell-center-containment behaviour end-to-end (unlike
#' `check_pt3_direct_unit_asserts`, which hand-builds the mask directly). Mirrors the Python
#' toolkit's `check_pt3_roi_seglin_fixture` exactly.
check_pt3_roi_seglin_fixture <- function(tmp) {
  frag <- build_roi_seglin_fragment()
  in_dir <- file.path(tmp, "in_roi_seglin")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_roi_seglin")
  analyze(list(in_dir), out_dir)

  metrics <- utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
  stopifnot(nrow(metrics) == 1)
  row <- metrics[1, ]
  stopifnot(
    "expected meanSegmentLinearityROI == 0.0438310715803678 (single collapsed-dedup transit segment p1..p6)" =
      abs(row$meanSegmentLinearityROI - 0.0438310715803678) < 1e-9
  )
  stopifnot(
    "value must differ from the naive no-dedup mean 0.687040133821826" =
      abs(row$meanSegmentLinearityROI - 0.687040133821826) > 1e-6
  )
  stopifnot(
    "meanSegmentLinearity should still be populated" = !is.na(row$meanSegmentLinearity)
  )
}

#' PT4 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P4) pipeline-level check: runs
#' `build_magband_agreement_fixture` (2 sessions, BYTE-IDENTICAL path/baseMagnification) through
#' the full `analyze()` pipeline and asserts `magband_agreement_<slug>.csv` has exactly the 2
#' expected bands, ascending, each with the hand-derived exact `meanPairwiseCC == 1.0` /
#' `coincidenceLevel == 1.0` (identical per-band dwell grids). Mirrors the Python toolkit's
#' `check_pt4_magband_agreement_fixture` exactly.
check_pt4_magband_agreement_fixture <- function(tmp) {
  fragments <- build_magband_agreement_fixture()
  in_dir <- file.path(tmp, "in_magband_agreement")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out_magband_agreement")
  analyze(list(in_dir), out_dir)

  out_files <- list.files(out_dir)
  files <- out_files[startsWith(out_files, "magband_agreement_")]
  stopifnot("expected exactly one magband_agreement_ file" = length(files) == 1)
  df <- utils::read.csv(file.path(out_dir, files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "magband_agreement.csv columns mismatch" =
      identical(colnames(df), c("band", "nSessions", "meanPairwiseCC", "coincidenceLevel"))
  )
  stopifnot(
    "expected exactly bands c('4-10x', '20-40x') in ascending-magnification order" =
      identical(df$band, c("4-10x", "20-40x"))
  )
  for (i in seq_len(nrow(df))) {
    r <- df[i, ]
    stopifnot("expected nSessions == 2" = r$nSessions == 2)
    stopifnot(
      "expected meanPairwiseCC == 1.0 (identical per-band dwell grids)" =
        abs(r$meanPairwiseCC - 1.0) < 1e-9
    )
    stopifnot("expected coincidenceLevel == 1.0" = abs(r$coincidenceLevel - 1.0) < 1e-9)
  }
}

#' PT4 parity-robustness check (per advisor review): runs `build_magband_agreement_frac_fixture`
#' (3 sessions, disjoint per-session dwell positions, 2 of 3 native grids resampled up to the
#' slide's common (8,8) grid) through the full `analyze()` pipeline and asserts the hand-derived
#' FRACTIONAL `meanPairwiseCC` (not 1.0, not 0.0) and the exact `coincidenceLevel == 0.0` (disjoint
#' supports -- no cell is ever shared by >=2 readers). Mirrors the Python toolkit's
#' `check_pt4_magband_agreement_frac_fixture` exactly.
check_pt4_magband_agreement_frac_fixture <- function(tmp) {
  fragments <- build_magband_agreement_frac_fixture()
  in_dir <- file.path(tmp, "in_magband_agreement_frac")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out_magband_agreement_frac")
  analyze(list(in_dir), out_dir)

  out_files <- list.files(out_dir)
  files <- out_files[startsWith(out_files, "magband_agreement_")]
  stopifnot("expected exactly one magband_agreement_ file" = length(files) == 1)
  df <- utils::read.csv(file.path(out_dir, files[1]), stringsAsFactors = FALSE)
  stopifnot(identical(df$band, c("4-10x", "20-40x")))
  for (i in seq_len(nrow(df))) {
    r <- df[i, ]
    stopifnot("expected nSessions == 3" = r$nSessions == 3)
    stopifnot(
      "expected meanPairwiseCC == -0.0439088905099674 (hand-derived disjoint-support Pearson correlation across 3 sessions)" =
        abs(r$meanPairwiseCC - (-0.0439088905099674)) < 1e-9
    )
    # Bite check (per advisor review): a regression that collapsed disjoint-support grids to a
    # degenerate 1.0/0.0 value must be caught, not silently pass a loose bounds check.
    stopifnot("meanPairwiseCC must not degenerate to 1.0" = abs(r$meanPairwiseCC - 1.0) > 1e-6)
    stopifnot("meanPairwiseCC must not degenerate to 0.0" = abs(r$meanPairwiseCC) > 1e-6)
    stopifnot(
      "expected coincidenceLevel == 0.0 (disjoint per-session dwell, no shared cell)" =
        abs(r$coincidenceLevel - 0.0) < 1e-9
    )
  }
}

#' PT4 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P4): running
#' `build_magband_agreement_fixture` (2 sessions that WOULD be canonical-scheme by default) under
#' `--magband-scheme tercile` must yield NO `magband_agreement_<slug>.csv` at all -- every
#' session's scheme becomes "tercile" under the forced CLI flag, so `n_canonical_sessions == 0 < 2`
#' and the file-level gate fails, exactly like the null-`baseMagnification` case. Mirrors the
#' Python toolkit's `check_pt4_tercile_scheme_no_file` exactly.
check_pt4_tercile_scheme_no_file <- function(tmp) {
  fragments <- build_magband_agreement_fixture()
  in_dir <- file.path(tmp, "in_magband_agreement_tercile")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out_magband_agreement_tercile")
  analyze(list(in_dir), out_dir, magband_scheme = "tercile")

  out_files <- list.files(out_dir)
  stopifnot(
    "magband_agreement_<slug>.csv should NOT be written under --magband-scheme tercile, even when >=2 sessions would otherwise qualify" =
      length(out_files[startsWith(out_files, "magband_agreement_")]) == 0
  )
  magband_files <- out_files[startsWith(out_files, "magbands_")]
  stopifnot("expected exactly one magbands_ file" = length(magband_files) == 1)
  magbands_df <- utils::read.csv(file.path(out_dir, magband_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "--magband-scheme tercile should force tercile for every session" =
      all(magbands_df$bandScheme == "tercile")
  )
}

#' PT4 row-skip check (per advisor review -- the hard-constraints list explicitly names "a band
#' with only 1 dwelling session (row skipped)" as a blank-not-crash case): runs
#' `build_magband_agreement_partial_fixture` (2 sessions, one dwelling in both canonical bands, the
#' other in only one) through the full `analyze()` pipeline and asserts
#' `magband_agreement_<slug>.csv` has EXACTLY 1 row ("4-10x", the shared band) -- the "20-40x" band,
#' dwelled on by only 1 session, must be silently dropped, not emitted as a row of NAs. Mirrors the
#' Python toolkit's `check_pt4_magband_agreement_partial_fixture` exactly.
check_pt4_magband_agreement_partial_fixture <- function(tmp) {
  fragments <- build_magband_agreement_partial_fixture()
  in_dir <- file.path(tmp, "in_magband_agreement_partial")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out_magband_agreement_partial")
  analyze(list(in_dir), out_dir)

  out_files <- list.files(out_dir)
  files <- out_files[startsWith(out_files, "magband_agreement_")]
  stopifnot("expected exactly one magband_agreement_ file" = length(files) == 1)
  df <- utils::read.csv(file.path(out_dir, files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "expected exactly 1 row (band '4-10x'; '20-40x' has only 1 dwelling session and must be dropped)" =
      identical(df$band, "4-10x")
  )
  row <- df[1, ]
  stopifnot("expected nSessions == 2" = row$nSessions == 2)
  stopifnot(
    "expected meanPairwiseCC == 1.0 (identical per-band dwell grids)" =
      abs(row$meanPairwiseCC - 1.0) < 1e-9
  )
  stopifnot("expected coincidenceLevel == 1.0" = abs(row$coincidenceLevel - 1.0) < 1e-9)
}

#' Direct, pipeline-independent unit checks for the new Tier 3 C6 functions (`js_divergence`,
#' `top_k_frac_mask`/`precision_recall_at_topk`, `visit_count_grid`/`visit_count_jaccard`,
#' `annotated_area_union_px`) -- TDD-style asserts on hand-built inputs, bypassing the full
#' `analyze()` pipeline entirely. Mirrors the Python toolkit's `check_tier6_direct_unit_asserts`
#' exactly.
check_tier6_direct_unit_asserts <- function() {
  # ---- js_divergence: base-2, symmetric, bounded [0,1] ----
  stopifnot(js_divergence(c(3.0, 1.0, 0.0, 6.0), c(3.0, 1.0, 0.0, 6.0)) == 0.0)
  jsd_disjoint <- js_divergence(c(1.0, 0.0), c(0.0, 1.0))
  stopifnot("expected JSD([1,0],[0,1]) == 1.0" = abs(jsd_disjoint - 1.0) < 1e-12)
  jsd_partial <- js_divergence(c(1.0, 1.0, 0.0, 0.0), c(0.0, 1.0, 1.0, 0.0))
  stopifnot("expected JSD == 0.5" = abs(jsd_partial - 0.5) < 1e-12)
  jsd_rev <- js_divergence(c(0.0, 1.0, 1.0, 0.0), c(1.0, 1.0, 0.0, 0.0))
  stopifnot("js_divergence must be symmetric" = abs(jsd_partial - jsd_rev) < 1e-12)
  stopifnot(js_divergence(c(0.0, 0.0), c(0.0, 0.0)) == 0.0)
  jsd_one_zero <- js_divergence(c(0.0, 0.0), c(1.0, 0.0))
  stopifnot(
    "expected JSD(all-zero, point-mass) == 0.5" = abs(jsd_one_zero - 0.5) < 1e-12
  )

  # ---- top_k_frac_mask / precision_recall_at_topk ----
  tie_grid <- c(5.0, 5.0, 5.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
  tie_mask <- top_k_frac_mask(tie_grid, 0.10)
  stopifnot(identical(tie_mask, c(TRUE, TRUE, TRUE, rep(FALSE, 7))))
  distinct_grid <- c(10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0)
  roi_first_two <- c(TRUE, TRUE, rep(FALSE, 8))
  pr <- precision_recall_at_topk(distinct_grid, roi_first_two, 0.10)
  stopifnot(abs(pr$precision - 1.0) < 1e-12, abs(pr$recall - 0.5) < 1e-12)
  pr_blank <- precision_recall_at_topk(distinct_grid, rep(FALSE, 10), 0.10)
  stopifnot(is.nan(pr_blank$precision), is.nan(pr_blank$recall))
  pr_empty <- precision_recall_at_topk(numeric(0), logical(0), 0.10)
  stopifnot(is.nan(pr_empty$precision), is.nan(pr_empty$recall))

  # ---- Final-review Finding 3 regression: sparse/focused grid where fewer than k=ceil(0.10*n)
  # cells are nonzero -> cutoff falls to 0.0. PRE-FIX, `grid >= 0.0` matched EVERY cell (the whole
  # grid), so a reader whose top-K region never touched the ROI at all still reported recall=1.0.
  # A 10x10 (n=100) grid, only 5 cells nonzero (~100, flat indices 1-5 i.e. row0 col0-4), ROI = the
  # DISJOINT bottom half (row 5-9, flat indices 51-100) -- zero overlap by construction.
  n_sparse <- 100L
  k_sparse <- 10L  # ceiling(0.10 * 100)
  sparse_grid <- rep(0.0, n_sparse)
  sparse_grid[1:5] <- 100.0
  sparse_roi <- rep(FALSE, n_sparse)
  sparse_roi[51:100] <- TRUE
  sparse_mask <- top_k_frac_mask(sparse_grid, 0.10)
  stopifnot(
    "expected top_k_frac_mask to select exactly the 5 strictly-positive cells, not the whole grid" =
      sum(sparse_mask) == 5
  )
  pr_sparse <- precision_recall_at_topk(sparse_grid, sparse_roi, 0.10)
  stopifnot(
    "expected precisionAtTopK == 0.0 (disjoint ROI)" = abs(pr_sparse$precision - 0.0) < 1e-12
  )
  stopifnot(
    "expected recall == 0.0 (reader never touched the ROI) -- pre-fix bug reported 1.0" =
      abs(pr_sparse$recall - 0.0) < 1e-12
  )
  # No-drift check for the DENSE/non-degenerate case (cutoff > 0, guard is a no-op): reassert the
  # distinct_grid/roi_first_two case above unchanged.
  pr_dense_recheck <- precision_recall_at_topk(distinct_grid, roi_first_two, 0.10)
  stopifnot(
    "dense/non-degenerate precisionAtTopK/recall must NOT drift after the strictly-positive guard" =
      abs(pr_dense_recheck$precision - 1.0) < 1e-12 && abs(pr_dense_recheck$recall - 0.5) < 1e-12
  )

  # ---- visit_count_grid / visit_count_jaccard ----
  dwell4 <- c(100.0, 10.0, 5.0, 1.0)
  visit_seq <- c(0, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2)
  vgrid <- visit_count_grid(visit_seq, 2, 2)
  stopifnot(identical(vgrid, c(1.0, 5.0, 5.0, 0.0)))
  jaccard <- visit_count_jaccard(dwell4, 2, 2, visit_seq, top_n = 2)
  stopifnot("expected visitCountJaccard == 1/3" = abs(jaccard - (1.0 / 3.0)) < 1e-12)
  stopifnot(is.nan(visit_count_jaccard(numeric(0), 0, 0, integer(0), top_n = 5)))

  # ---- Final-review Finding 1 regression: visit_count_grid must NOT crash on a zero-size grid
  # (gw=0) fed a NON-EMPTY visited-cell sequence -- this is exactly what analyze() produces when a
  # schema-valid fragment records gridWidth=0: visited_sequence's col-clamp resolves every entry to
  # -1, and (unlike the existing `visit_count_jaccard(numeric(0), 0, 0, integer(0), ...)` assert
  # above, whose EMPTY seq never touches the assignment loop at all) this non-empty seq DOES.
  crash_seq <- c(-1L, -1L, -1L)  # visited_sequence's actual output shape for gw=0
  zero_grid_counts <- visit_count_grid(crash_seq, 0L, 5L)
  stopifnot(
    "expected an EMPTY vector (not a crash/growth) for visit_count_grid on a zero-size grid" =
      length(zero_grid_counts) == 0
  )
  stopifnot(is.nan(visit_count_jaccard(numeric(0), 0L, 5L, crash_seq, top_n = 5)))

  # ---- Incidental parity fix confirmation (surfaced by the Finding 1/2 zero-size-grid guards
  # above, previously unreachable behind their crash): cc() on two zero-length grids -- e.g. the
  # self-comparison diagonal row in compare_<slug>.csv for a single zero-size-grid session -- must
  # return 0.0 ("correlation undefined"). R's `cc()` already guards `length(a) < 2` (no code
  # change needed on this side); the Python port lacked the equivalent guard (`.std()` of an empty
  # array is `nan`, not exactly `0.0`, so its old `a.std() == 0` check alone missed this one
  # degenerate case) -- fixed there for parity.
  stopifnot(
    "expected cc(numeric(0), numeric(0)) == 0.0 (correlation undefined for an empty grid)" =
      cc(numeric(0), numeric(0)) == 0.0
  )

  # ---- annotated_area_union_px: reuses the same outer/inner nested-rectangle geometry as the
  # Python toolkit's regression fixture (10x10 grid over a 100x100 image). ----
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
        properties = list(name = "tumor")
      ),
      list(
        type = "Feature",
        geometry = list(
          type = "Polygon",
          coordinates = list(list(c(40, 40), c(60, 40), c(60, 60), c(40, 60), c(40, 40)))
        ),
        properties = list(name = "focus")
      )
    )
  )
  overlap_mask <- rasterize_feature_collection(overlap_fc, overlap_gw, overlap_gh, overlap_img_w, overlap_img_h)
  union_area <- annotated_area_union_px(overlap_mask, overlap_gw, overlap_gh, overlap_img_w, overlap_img_h)
  sum_area <- annotations_area_px(overlap_fc)
  stopifnot(
    "expected annotatedAreaUnionPx == 3600.0" = abs(union_area - 3600.0) < 1e-6
  )
  stopifnot(
    "expected sum-based annotatedAreaPx == 4000.0" = abs(sum_area - 4000.0) < 1e-6
  )
  stopifnot(union_area < sum_area)
  stopifnot(
    annotated_area_union_px(rep(FALSE, overlap_gw * overlap_gh), overlap_gw, overlap_gh, overlap_img_w, overlap_img_h) == 0.0
  )
}

#' Tier 3 C6 pipeline-level check: runs `build_c6_fixture` (2 sessions, an overlapping annotation
#' pair, a path, and a `--roi`-driven reference mask) through the full `analyze()` pipeline and
#' asserts every hand-derived number documented in the fixture's own docs: `compare_<slug>.csv`'s
#' `jsDivergence`, `reference_<slug>.csv`'s `precisionAtTopK`/`recall`, `metrics.csv`'s
#' `annotatedAreaUnionPx`/`annotatedAreaPx`, and `consensus_count_<slug>.csv`'s per-cell reader
#' counts. `visitCountJaccard` is only bounds-checked here (`[0, 1]`) -- the pipeline's
#' `HOTSPOT_TOP_N=5` value is NOT the same hand-derived `top_n=2` case asserted exactly in
#' `check_tier6_direct_unit_asserts`; its exactness is instead carried by the Python<->R parity
#' diff (see t6-report.md).
check_tier6_c6_fixture <- function(tmp) {
  fragments <- build_c6_fixture()
  in_dir <- file.path(tmp, "in_c6")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  roi_path <- file.path(tmp, "c6_roi.geojson")
  writeLines(jsonlite::toJSON(build_c6_roi_fc(), auto_unbox = TRUE), roi_path)
  out_dir <- file.path(tmp, "out_c6")
  analyze(list(in_dir), out_dir, roi = roi_path)

  metrics <- utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
  stopifnot(nrow(metrics) == 2)
  row_a <- metrics[metrics$session == "c6a", ]
  row_b <- metrics[metrics$session == "c6b", ]

  stopifnot(abs(row_a$annotatedAreaUnionPx - 40000.0) < 1e-6)
  stopifnot(abs(row_a$annotatedAreaPx - 54400.0) < 1e-6)
  stopifnot(row_a$annotatedAreaUnionPx < row_a$annotatedAreaPx)
  stopifnot(abs(row_b$annotatedAreaUnionPx - 0.0) < 1e-9)
  stopifnot(abs(row_b$annotatedAreaPx - 0.0) < 1e-9)

  stopifnot("c6a visitCountJaccard should be populated" = !is.na(row_a$visitCountJaccard))
  stopifnot(row_a$visitCountJaccard >= 0.0 && row_a$visitCountJaccard <= 1.0)
  stopifnot("c6b visitCountJaccard should be blank (no path)" = is.na(row_b$visitCountJaccard))

  out_files <- list.files(out_dir)
  compare_files <- out_files[startsWith(out_files, "compare_")]
  stopifnot(length(compare_files) == 1)
  compare <- utils::read.csv(file.path(out_dir, compare_files[1]), stringsAsFactors = FALSE)
  stopifnot("jsDivergence" %in% colnames(compare))
  diag_a <- compare[compare$sessionA == "c6a" & compare$sessionB == "c6a", ]
  diag_b <- compare[compare$sessionA == "c6b" & compare$sessionB == "c6b", ]
  stopifnot(abs(diag_a$jsDivergence - 0.0) < 1e-9)
  stopifnot(abs(diag_b$jsDivergence - 0.0) < 1e-9)
  cross_ab <- compare[compare$sessionA == "c6a" & compare$sessionB == "c6b", ]
  cross_ba <- compare[compare$sessionA == "c6b" & compare$sessionB == "c6a", ]
  stopifnot("expected jsDivergence(c6a,c6b) == 0.5" = abs(cross_ab$jsDivergence - 0.5) < 1e-9)
  stopifnot("expected jsDivergence(c6b,c6a) == 0.5" = abs(cross_ba$jsDivergence - 0.5) < 1e-9)

  ref_files <- out_files[startsWith(out_files, "reference_")]
  stopifnot(length(ref_files) == 1)
  ref <- utils::read.csv(file.path(out_dir, ref_files[1]), stringsAsFactors = FALSE)
  stopifnot(all(c("precisionAtTopK", "recall") %in% colnames(ref)))
  stopifnot(setequal(ref$session, c("c6a", "c6b")))
  ref_a <- ref[ref$session == "c6a", ]
  ref_b <- ref[ref$session == "c6b", ]
  stopifnot(abs(ref_a$precisionAtTopK - 1.0) < 1e-9)
  stopifnot(abs(ref_a$recall - 0.5) < 1e-9)
  stopifnot(abs(ref_b$precisionAtTopK - 0.5) < 1e-9)
  stopifnot(abs(ref_b$recall - 0.25) < 1e-9)

  cc_files <- out_files[startsWith(out_files, "consensus_count_")]
  stopifnot(length(cc_files) == 1)
  cc <- utils::read.csv(file.path(out_dir, cc_files[1]), stringsAsFactors = FALSE)
  stopifnot(identical(colnames(cc), c("cellRow", "cellCol", "nReaders")))
  stopifnot(nrow(cc) == 3)
  got <- setNames(as.numeric(cc$nReaders), paste(cc$cellRow, cc$cellCol))
  expected <- c("0 0" = 1, "0 1" = 2, "2 2" = 1)
  stopifnot(all(names(expected) %in% names(got)))
  stopifnot(all(abs(got[names(expected)] - expected) < 1e-9))
}

#' Polish final-review root-cause fix (docs/superpowers/sdd/polish-finalfix-report.md): a
#' schema-valid gridWidth=0/gridHeight=5 fragment WITH a path is now REJECTED AT LOAD by
#' `.is_valid_fragment`'s `gw>0 && gh>0` guard -- it never enters the batch at all, so the
#' `visit_count_grid` crash this fixture used to trigger is unreachable by construction. Confirms
#' both the direct load-time rejection and that a batch consisting ONLY of this fragment completes
#' with zero rows (no crash, no metrics.csv row).
check_finalfix_zerogrid_path_fixture <- function(tmp) {
  frag <- build_zerogrid_path_fragment()
  in_dir <- file.path(tmp, "in_zerogrid_path")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)

  loaded <- load_fragments(list(in_dir))
  stopifnot("a gridWidth=0 fragment must be rejected at load" = length(loaded) == 0)

  out_dir <- file.path(tmp, "out_zerogrid_path")
  metrics <- analyze(list(in_dir), out_dir)  # must not raise
  stopifnot(
    "a batch with only a zero-dim-grid fragment must yield 0 rows" = nrow(metrics) == 0
  )
}

#' Polish final-review root-cause fix (docs/superpowers/sdd/polish-finalfix-report.md): a schema/5
#' gridWidth=0/gridHeight=5 fragment WITH on-slide mouse data is now REJECTED AT LOAD by the same
#' `gw>0 && gh>0` guard -- the `mouse_raster_from_path` zero-size-grid crash this fixture used to
#' trigger (Python-only; R's own matrix indexing silently no-ops on this shape, see
#' `mouse_raster_from_path`'s docstring) is unreachable by construction. Mirrors
#' `check_finalfix_zerogrid_path_fixture`'s two-level assert.
check_finalfix_zerogrid_mouse_fixture <- function(tmp) {
  frag <- build_zerogrid_mouse_fragment()
  in_dir <- file.path(tmp, "in_zerogrid_mouse")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)

  loaded <- load_fragments(list(in_dir))
  stopifnot("a gridWidth=0 fragment must be rejected at load" = length(loaded) == 0)

  out_dir <- file.path(tmp, "out_zerogrid_mouse")
  metrics <- analyze(list(in_dir), out_dir)  # must not raise
  stopifnot(
    "a batch with only a zero-dim-grid fragment must yield 0 rows" = nrow(metrics) == 0
  )
}

DEGENERATE_PLUS_NORMAL_SLIDE_KEY <- "sha256:selftest-slide-polish-degenerate-plus-normal-0001"
#' Polish final-review fixture (Finding 2's realistic trigger): ONE gridWidth=0 fragment sharing a
#' slideKey with ONE normal, full-grid session -- exercises the partial-skip path: the degenerate
#' fragment is silently dropped at `load_fragments` time, while the normal session on the SAME
#' slide processes completely unaffected. Mirrors the Python toolkit's
#' `build_degenerate_plus_normal_fragments` exactly.
build_degenerate_plus_normal_fragments <- function() {
  path <- do.call(rbind, lapply(0:9, function(t) c(t * 250, 100 + t, 100 + t, 400, 300)))
  degenerate <- .finalfix_fragment(
    "degen1", 3, numeric(0), 0L, 5L, 2000, 1500, 2250, 10, path = path,
    slide_key = DEGENERATE_PLUS_NORMAL_SLIDE_KEY
  )
  normal_grid <- .n_nonzero_grid(10)
  normal <- .finalfix_fragment(
    "normal1", 3, normal_grid, GW, GH, IMG_W, IMG_H, 2500, 10, path = path,
    slide_key = DEGENERATE_PLUS_NORMAL_SLIDE_KEY
  )
  list(degenerate, normal)
}

#' Polish final-review pipeline-level check: a slide carrying ONE degenerate (gridWidth=0)
#' fragment ALONGSIDE one normal fragment must complete with exactly 1 row (the normal session) --
#' the degenerate fragment is dropped at load, and its presence must not perturb or abort
#' processing of its normal slide-mate.
check_polish_degenerate_plus_normal_fixture <- function(tmp) {
  frags <- build_degenerate_plus_normal_fragments()
  in_dir <- file.path(tmp, "in_degenerate_plus_normal")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(frags, in_dir)

  loaded <- load_fragments(list(in_dir))
  stopifnot(
    "expected only the normal session to survive load" =
      length(loaded) == 1 && loaded[[1]]$sessionId == "normal1"
  )

  out_dir <- file.path(tmp, "out_degenerate_plus_normal")
  metrics <- analyze(list(in_dir), out_dir)  # must not raise
  stopifnot("expected exactly 1 row (normal session only)" = nrow(metrics) == 1)
  stopifnot(metrics$session[1] == "normal1")
  stopifnot(metrics$pathPoints[1] == 10)
}

CANONICAL_DEGENERATE_SLIDE_KEY <- "sha256:selftest-slide-polish-canonical-degenerate-0001"
#' Polish final-review fixture (Finding 3's realistic trigger, now moot post-root-fix but locked in
#' as a regression guard): ONE gridWidth=0 fragment sharing a slide with TWO normal,
#' canonical-scheme-computable sessions. Mirrors the Python toolkit's
#' `build_canonical_degenerate_fragments` exactly.
build_canonical_degenerate_fragments <- function() {
  degenerate <- .finalfix_fragment(
    "cdegen1", 4, numeric(0), 0L, 5L, 2000, 1500, 10000, 40,
    path = .make_path_v4(2, 2, n = 40, seed = 501),
    slide_key = CANONICAL_DEGENERATE_SLIDE_KEY
  )
  f1 <- .fragment(
    "cnorm1", 4, .n_nonzero_grid(10), 10000, 40,
    path = .make_path_v4(2, 2, n = 40, seed = 601), base_magnification = 40.0,
    slide_key = CANONICAL_DEGENERATE_SLIDE_KEY
  )
  f2 <- .fragment(
    "cnorm2", 4, .n_nonzero_grid(10), 10000, 40,
    path = .make_path_v4(2, 2, n = 40, seed = 602), base_magnification = 40.0,
    slide_key = CANONICAL_DEGENERATE_SLIDE_KEY
  )
  list(degenerate, f1, f2)
}

#' Polish final-review pipeline-level check: a slide with a degenerate (gridWidth=0) fragment PLUS
#' two normal canonical-scheme sessions must complete, write `magband_agreement_<slug>.csv` from
#' the two normal sessions alone, and not crash.
check_polish_canonical_degenerate_fixture <- function(tmp) {
  frags <- build_canonical_degenerate_fragments()
  in_dir <- file.path(tmp, "in_canonical_degenerate")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(frags, in_dir)
  out_dir <- file.path(tmp, "out_canonical_degenerate")
  metrics <- analyze(list(in_dir), out_dir)  # must not raise
  stopifnot(
    "expected 2 rows (degenerate session dropped at load)" = nrow(metrics) == 2
  )
  stopifnot(setequal(metrics$session, c("cnorm1", "cnorm2")))

  slug <- slug(CANONICAL_DEGENERATE_SLIDE_KEY)
  agreement_path <- file.path(out_dir, paste0("magband_agreement_", slug, ".csv"))
  stopifnot("expected magband_agreement_<slug>.csv" = file.exists(agreement_path))
  agreement <- utils::read.csv(agreement_path, stringsAsFactors = FALSE)
  stopifnot(nrow(agreement) >= 1)
  stopifnot(
    "every emitted band should reflect exactly the 2 normal sessions" =
      all(agreement$nSessions == 2)
  )
}

NONNUMERIC_BASEMAG_SLIDE_KEY <- "sha256:selftest-slide-polish-nonnumeric-basemag-0001"
#' Polish final-review fixture (Finding 1): a schema/4 session with a VALID grid, a real
#' dsMilli-carrying path (the actual crash path -- see `point_zoom`'s docstring), and a
#' `baseMagnification` that is present but NON-NUMERIC (`"unknown"`). Mirrors the Python toolkit's
#' `build_nonnumeric_basemag_fragment` exactly.
build_nonnumeric_basemag_fragment <- function() {
  .fragment(
    "nonnum1", 4, .n_nonzero_grid(10), 10000, 40,
    path = .make_path_v4(2, 2, n = 40, seed = 701), base_magnification = "unknown",
    slide_key = NONNUMERIC_BASEMAG_SLIDE_KEY
  )
}

#' Polish final-review Finding 1 pipeline-level check: a fragment with a valid grid and a
#' dsMilli-carrying path but a non-numeric `baseMagnification` ("unknown") must complete the full
#' `analyze()` pipeline (no crash anywhere in the `point_zoom`-derived metric chain or the
#' `magnificationSource`/canonical-band assignment), with `magnificationSource=="proxy-downsample"`
#' and the canonical scheme auto-falling back to `"tercile"`.
check_polish_nonnumeric_basemag_fixture <- function(tmp) {
  frag <- build_nonnumeric_basemag_fragment()
  in_dir <- file.path(tmp, "in_nonnumeric_basemag")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_nonnumeric_basemag")
  metrics <- analyze(list(in_dir), out_dir)  # must not raise
  stopifnot(nrow(metrics) == 1)
  stopifnot(metrics$magnificationSource[1] == "proxy-downsample")
  # Both toolkits render a non-numeric `baseMagnification` as blank: R numerically coerces it
  # (`suppressWarnings(as.numeric(base_mag))` -> NA -> blank), and Python now guards the passthrough
  # column to blank on a non-numeric value too (previously it passed the raw string through -- that
  # Python-string vs R-blank divergence is now closed). This is a
  # cosmetic passthrough-column divergence only; it does not affect magnificationSource/bandScheme,
  # which both toolkits compute identically (see the cross-language parity check).
  stopifnot(
    "R's baseMagnification passthrough numerically coerces a non-numeric value to NA" =
      is.na(metrics$baseMagnification[1])
  )

  slug <- slug(NONNUMERIC_BASEMAG_SLIDE_KEY)
  magbands_path <- file.path(out_dir, paste0("magbands_", slug, ".csv"))
  stopifnot("expected magbands_<slug>.csv" = file.exists(magbands_path))
  magbands <- utils::read.csv(magbands_path, stringsAsFactors = FALSE)
  stopifnot(
    "a non-numeric baseMagnification is not canonical-computable -> must fall back to tercile" =
      all(magbands$bandScheme == "tercile")
  )
}

MOUSE_ICC_ALLZERO_SLIDE_KEY <- "sha256:selftest-slide-polish-mouse-icc-allzero-0001"
#' Polish final-review fixture (refuted-but-worth-a-guard): 2 schema/5 sessions on one slide --
#' one whose cursor is the off-viewer sentinel (-1, -1) at EVERY tick (a legitimate ALL-ZERO
#' point-based mouse-dwell grid, not NULL) alongside one normal session with real on-slide mouse
#' movement. Locks in that Python and R compute the IDENTICAL `mouseICC` for this input. Mirrors
#' the Python toolkit's `build_mouse_icc_allzero_fixture` exactly.
build_mouse_icc_allzero_fixture <- function() {
  grid <- .n_nonzero_grid(10)
  real_path <- matrix(
    c(
      0, 500, 400, 400, 300, 1000, 60, 50,
      1000, 600, 450, 400, 300, 1000, 90, 80,
      2000, 700, 500, 400, 300, 1000, 120, 110,
      3000, 650, 470, 400, 300, 1000, 100, 90
    ),
    nrow = 4, ncol = 8, byrow = TRUE
  )
  offslide_path <- matrix(
    c(
      0, 500, 400, 400, 300, 1000, -1, -1,
      1000, 600, 450, 400, 300, 1000, -1, -1,
      2000, 700, 500, 400, 300, 1000, -1, -1,
      3000, 650, 470, 400, 300, 1000, -1, -1
    ),
    nrow = 4, ncol = 8, byrow = TRUE
  )
  f_real <- .fragment(
    "micc-real", 5, grid, 3000, 4, path = real_path, slide_key = MOUSE_ICC_ALLZERO_SLIDE_KEY
  )
  f_zero <- .fragment(
    "micc-zero", 5, grid, 3000, 4, path = offslide_path, slide_key = MOUSE_ICC_ALLZERO_SLIDE_KEY
  )
  list(f_real, f_zero)
}

#' Polish final-review pipeline-level check: a slide with one all-off-slide-cursor session
#' (all-zero mouse-dwell grid) and one normal-mouse session must complete without crash, and
#' `mouse_<slug>.csv`'s `mouseICC` (diagonal-reuse row) must be a finite number close to 0.0
#' (numerically compared against Python's output separately, in the cross-language parity check).
check_polish_mouse_icc_allzero_fixture <- function(tmp) {
  frags <- build_mouse_icc_allzero_fixture()
  in_dir <- file.path(tmp, "in_mouse_icc_allzero")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(frags, in_dir)
  out_dir <- file.path(tmp, "out_mouse_icc_allzero")
  analyze(list(in_dir), out_dir)  # must not raise

  slug <- slug(MOUSE_ICC_ALLZERO_SLIDE_KEY)
  mouse_path <- file.path(out_dir, paste0("mouse_", slug, ".csv"))
  stopifnot("expected mouse_<slug>.csv" = file.exists(mouse_path))
  mouse_df <- utils::read.csv(mouse_path, stringsAsFactors = FALSE)
  diag_rows <- mouse_df[!is.na(mouse_df$mouseICC), ]
  stopifnot("expected exactly 1 non-blank mouseICC row" = nrow(diag_rows) == 1)
  icc_val <- as.numeric(diag_rows$mouseICC[1])
  stopifnot("mouseICC should be finite" = is.finite(icc_val))
  stopifnot(
    "expected mouseICC near 0.0 for one all-zero + one real grid" = abs(icc_val) < 0.5
  )
}

#' Final-review Finding 3 pipeline-level check: runs `build_topk_sparse_fragment` (a focused/
#' sparse reader whose top-K cutoff falls to 0.0) through the full `analyze()` pipeline with
#' `--roi` set to a region DISJOINT from every attended cell, and asserts
#' `reference_<slug>.csv`'s `precisionAtTopK`/`recall` are the correct 0.0 -- NOT the pre-fix
#' bug's 1.0/ROI-area-fraction values.
check_finalfix_topk_sparse_fixture <- function(tmp) {
  frag <- build_topk_sparse_fragment()
  in_dir <- file.path(tmp, "in_topk_sparse")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  roi_path <- file.path(tmp, "topk_sparse_roi.geojson")
  writeLines(jsonlite::toJSON(build_topk_sparse_roi_fc(), auto_unbox = TRUE), roi_path)
  out_dir <- file.path(tmp, "out_topk_sparse")
  analyze(list(in_dir), out_dir, roi = roi_path)

  out_files <- list.files(out_dir)
  ref_files <- out_files[startsWith(out_files, "reference_")]
  stopifnot(length(ref_files) == 1)
  ref <- utils::read.csv(file.path(out_dir, ref_files[1]), stringsAsFactors = FALSE)
  row <- ref[ref$session == "topk-sparse1", ]
  stopifnot(
    "expected precisionAtTopK == 0.0 (disjoint ROI)" = abs(row$precisionAtTopK - 0.0) < 1e-9
  )
  stopifnot(
    "expected recall == 0.0 (reader never touched the ROI) -- pre-fix bug reported 1.0" =
      abs(row$recall - 0.0) < 1e-9
  )
}

#' Final-review Finding 4 pipeline-level check: runs the hand-derivable idle-gap fixation fixture
#' (`build_idle_fixation_fragment`) through the full `analyze()` pipeline and asserts every
#' documented number -- `metrics.csv`'s summary columns AND `fixations_<slug>.csv`'s per-fixation
#' rows -- against the hand-derived split (2 fixations), NOT the pre-fix bridged single 71000ms
#' fixation.
check_finalfix_idle_fixation_fixture <- function(tmp) {
  frag <- build_idle_fixation_fragment()
  in_dir <- file.path(tmp, "in_idle_fixation")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(list(frag), in_dir)
  out_dir <- file.path(tmp, "out_idle_fixation")
  analyze(list(in_dir), out_dir)

  metrics <- utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
  stopifnot(nrow(metrics) == 1)
  row <- metrics[1, ]

  stopifnot(
    "expected nFixations == 2 (idle-split) -- pre-fix bug bridged into nFixations==1" =
      row$nFixations == 2
  )
  stopifnot(abs(row$meanFixationMs - 2500.0) < 1e-6)
  stopifnot(abs(row$medianFixationMs - 2500.0) < 1e-6)
  stopifnot(abs(row$sdFixationMs - 2121.320343559643) < 1e-6)
  stopifnot(abs(row$idleMs - 66000.0) < 1e-6)
  stopifnot(abs(row$activeSpanMs - 5000.0) < 1e-6)
  stopifnot(abs(row$fixationsPerMin - 24.0) < 1e-6)

  out_files <- list.files(out_dir)
  fixation_files <- out_files[startsWith(out_files, "fixations_")]
  stopifnot(length(fixation_files) == 1)
  fixations <- utils::read.csv(file.path(out_dir, fixation_files[1]), stringsAsFactors = FALSE)
  stopifnot("expected 2 fixation rows (idle-split)" = nrow(fixations) == 2)

  f1 <- fixations[fixations$idx == 1, ]
  stopifnot(abs(f1$startMs - 0.0) < 1e-9)
  stopifnot(abs(f1$durationMs - 4000.0) < 1e-9)
  stopifnot(abs(f1$centerImageX - 100.0) < 1e-9)
  stopifnot(abs(f1$centerImageY - 100.0) < 1e-9)
  stopifnot(f1$nPoints == 2)

  f2 <- fixations[fixations$idx == 2, ]
  stopifnot(abs(f2$startMs - 70000.0) < 1e-9)
  stopifnot(abs(f2$durationMs - 1000.0) < 1e-9)
  stopifnot(abs(f2$centerImageX - 100.5) < 1e-9)
  stopifnot(abs(f2$centerImageY - 100.5) < 1e-9)
  stopifnot(f2$nPoints == 2)
}

# ---------------------------------------------------------------------------
# P1 selftest coverage gaps (docs/superpowers/specs/2026-07-25-enrichment-polish.md): logged Minors
# from the enrichment-cycle final review -- consensus_count_<slug>.csv only ever exercised at 2
# sessions, meanDiff's n>=2-per-group guard only ever exercised at n=0 (avgZoom) and zero-variance
# (dwellInAnnotationPct) -- never the n=1 boundary -- and the calibration summary
# (calibrationGap/brierScore/confidenceAccuracyR) never independently value-asserted at all. Mirrors
# the Python toolkit's selftest.py exactly (same fixtures, same hand-derived numbers).
# ---------------------------------------------------------------------------

CONSENSUS3_SLIDE_KEY <- "sha256:selftest-slide-consensus3-0001"
#' consensus_count_<slug>.csv 3-SESSION fixture (the Tier 3 C6 fixture above only covers 2). A
#' minimal 2x2 grid (gw=gh=2 for all 3 sessions -> `.target_grid_dims` picks (tw,th)=(2,2) too, so
#' resampled==native -- no resample-interaction to reason about). Every session's cell(0,0) is its
#' own per-session maximum (normalise_max -> 1.0 > HOTSPOT_THRESH_FRAC=0.5), so all 3 dwell on it;
#' each session ALSO has one other cell at norm value 0.6 (> 0.5, clear of the strict-> boundary),
#' unique to that session: d3a -> cell(0,1), d3b -> cell(1,0), d3c -> cell(1,1). Hand-derived
#' nReaders: (0,0) -> 3 (every session), (0,1) -> 1 (d3a only), (1,0) -> 1 (d3b only), (1,1) -> 1
#' (d3c only) -- exactly 4 rows.
build_consensus3_fixture <- function() {
  grid_a <- c(100.0, 60.0, 0.0, 0.0)
  grid_b <- c(100.0, 0.0, 60.0, 0.0)
  grid_c <- c(100.0, 0.0, 0.0, 60.0)
  list(
    .finalfix_fragment("d3a", 2, grid_a, 2, 2, 200, 200, 3000, 5, slide_key = CONSENSUS3_SLIDE_KEY),
    .finalfix_fragment("d3b", 2, grid_b, 2, 2, 200, 200, 3000, 5, slide_key = CONSENSUS3_SLIDE_KEY),
    .finalfix_fragment("d3c", 2, grid_c, 2, 2, 200, 200, 3000, 5, slide_key = CONSENSUS3_SLIDE_KEY)
  )
}

#' P1 selftest coverage gap: runs `build_consensus3_fixture` (3 sessions, not just the Tier 3 C6
#' fixture's 2) through the full `analyze()` pipeline and asserts `consensus_count_<slug>.csv`'s
#' per-cell reader counts reach `nReaders==3` for the one cell every session dwells on, plus the
#' exact 4-row set (see the fixture's own docstring for the hand derivation).
check_consensus3_fixture <- function(tmp) {
  fragments <- build_consensus3_fixture()
  in_dir <- file.path(tmp, "in_consensus3")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out_consensus3")
  analyze(list(in_dir), out_dir)

  out_files <- list.files(out_dir)
  cc_files <- out_files[startsWith(out_files, "consensus_count_")]
  stopifnot(length(cc_files) == 1)
  cc <- utils::read.csv(file.path(out_dir, cc_files[1]), stringsAsFactors = FALSE)
  stopifnot(identical(colnames(cc), c("cellRow", "cellCol", "nReaders")))
  stopifnot(nrow(cc) == 4)
  got <- setNames(as.numeric(cc$nReaders), paste(cc$cellRow, cc$cellCol))
  expected <- c("0 0" = 3, "0 1" = 1, "1 0" = 1, "1 1" = 1)
  stopifnot(setequal(names(got), names(expected)))
  stopifnot(all(abs(got[names(expected)] - expected) < 1e-9))
  stopifnot(
    "expected nReaders==3 for the cell all 3 sessions dwell on" =
      abs(got[["0 0"]] - 3) < 1e-9
  )
}

MEANDIFF_N1_SLIDE_KEY <- "sha256:selftest-slide-meandiff-n1-0001"
#' meanDiff n=1-per-group BOUNDARY fixture: 3 graded sessions on their own slide -- ONE graded
#' correct=1 (n=1 in that group), TWO graded correct=0 (n=2). `.nav_stat_row`'s meanDiff guard
#' (blinded_focus.R) requires `length(correct_vals) >= 2 AND length(incorrect_vals) >= 2` -- with
#' only 1 correct-group session this must render BLANK even though
#' meanCorrect/meanIncorrect/medianCorrect/medianIncorrect (which only need >= 1 value each) stay
#' populated. Uses `coveragePct` (grid-only, always populated -- same metric
#' `check_nav_accuracy`'s existing n=3-per-group case uses) with hand-derivable coverage fractions
#' on the module's GW=GH=8 (64-cell) grid: md1(correct=1)=56/64=87.5%, md2(correct=0)=6/64=9.375%,
#' md3(correct=0)=8/64=12.5% -> meanCorrect=87.5 (n=1), meanIncorrect=(9.375+12.5)/2=10.9375 (n=2),
#' medianCorrect=87.5, medianIncorrect=10.9375 (n=2 median == mean here).
build_meandiff_n1_fragments <- function() {
  .grid <- function(n_nonzero, value = 100.0) {
    g <- rep(0.0, GW * GH)
    if (n_nonzero > 0) {
      g[seq_len(n_nonzero)] <- value
    }
    g
  }

  spec <- list(
    list(sid = "md1", n_nonzero = 56, correct = 1),
    list(sid = "md2", n_nonzero = 6, correct = 0),
    list(sid = "md3", n_nonzero = 8, correct = 0)
  )
  fragments <- list()
  graded_rows <- list()
  for (i in seq_along(spec)) {
    s <- spec[[i]]
    f <- .fragment(
      s$sid, 2, .grid(s$n_nonzero), 3000, 10,
      slide_key = MEANDIFF_N1_SLIDE_KEY,
      decision = .decision("tumor", 3, 4000 + (i - 1) * 50)
    )
    fragments[[length(fragments) + 1]] <- f
    graded_rows[[length(graded_rows) + 1]] <- c(MEANDIFF_N1_SLIDE_KEY, s$sid, as.character(s$correct))
  }
  list(fragments = fragments, graded_rows = graded_rows)
}

#' P1 selftest coverage gap: the `meanDiff` n>=2-per-group guard (blinded_focus.R's
#' `.nav_stat_row`) at the n=1 boundary -- distinct from `check_nav_accuracy`'s existing n=0
#' (`avgZoom`) and zero-variance (`dwellInAnnotationPct`) blank-guard cases. With only 1
#' graded-correct session, `meanDiff` must render BLANK while
#' `meanCorrect`/`meanIncorrect`/`medianCorrect`/`medianIncorrect` (needing only n>=1 each) stay
#' populated -- and asserts their exact hand-derived values (see `build_meandiff_n1_fragments`'s
#' docstring).
check_meandiff_n1_boundary <- function(tmp) {
  fixture <- build_meandiff_n1_fragments()
  in_dir <- file.path(tmp, "in_meandiff_n1")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fixture$fragments, in_dir)
  graded_csv_path <- file.path(tmp, "meandiff_n1_graded.csv")
  .write_simple_csv(graded_csv_path, c("slideKey", "sessionId", "correct"), fixture$graded_rows)
  out_dir <- file.path(tmp, "out_meandiff_n1")
  analyze(list(in_dir), out_dir, graded_csv = graded_csv_path)

  nav_path <- file.path(out_dir, "nav_accuracy.csv")
  stopifnot("nav_accuracy.csv missing for the meanDiff n=1 fixture" = file.exists(nav_path))
  nav_rows <- utils::read.csv(nav_path, stringsAsFactors = FALSE, colClasses = "character")
  cov <- nav_rows[nav_rows$metric == "coveragePct", ][1, ]
  stopifnot(cov$n == "3")
  stopifnot(
    "expected BLANK meanDiff at the n=1-per-group boundary (1 correct, 2 incorrect)" =
      cov$meanDiff == ""
  )
  stopifnot("meanCorrect should stay populated with n=1 in that group" = cov$meanCorrect != "")
  stopifnot("meanIncorrect should stay populated with n=2 in that group" = cov$meanIncorrect != "")
  stopifnot("medianCorrect should stay populated with n=1 in that group" = cov$medianCorrect != "")
  stopifnot("medianIncorrect should stay populated with n=2 in that group" = cov$medianIncorrect != "")
  stopifnot(abs(as.numeric(cov$meanCorrect) - 87.5) < 1e-9)
  stopifnot(abs(as.numeric(cov$meanIncorrect) - 10.9375) < 1e-9)
  stopifnot(abs(as.numeric(cov$medianCorrect) - 87.5) < 1e-9)
  stopifnot(abs(as.numeric(cov$medianIncorrect) - 10.9375) < 1e-9)
}

#' P1 selftest coverage gap: independent hand-derived value asserts on `.calibration_stats`'s three
#' summary numbers (`calibrationGap`/`brierScore`/`confidenceAccuracyR`) -- previously only
#' reachable indirectly via `summary.md`'s rounded-to-3-decimals text line, never asserted against
#' a hand-derived value at all. 5 hand-built decision rows (`n == MIN_CORRELATION_N` exactly, the
#' r-guard's own boundary): `confidenceScaled = c(1.0, 0.75, 0.5, 0.25, 0.0)` paired with
#' `correct = c(1, 1, 1, 0, 0)` -> `calibrationGap = mean(conf) - mean(correct) = 0.5 - 0.6 = -0.1`;
#' `brierScore = mean((conf-correct)^2) = 0.375/5 = 0.075`; `confidenceAccuracyR` (Pearson r, both
#' sides non-degenerate variance) works out to exactly `sqrt(0.75) == sqrt(3)/2` (deviation
#' cross-product 0.75, deviation-sum-of-squares product 0.625*1.2=0.75, so
#' `r = 0.75/sqrt(0.75) = sqrt(0.75)` -- a clean closed form, hand-verified independently of the
#' implementation before this fixture was written). Mirrors the Python toolkit's
#' `check_calibration_direct_unit_asserts` exactly.
check_calibration_direct_unit_asserts <- function() {
  rows <- list(
    list(correct = 1, confidenceScaled = 1.0),
    list(correct = 1, confidenceScaled = 0.75),
    list(correct = 1, confidenceScaled = 0.5),
    list(correct = 0, confidenceScaled = 0.25),
    list(correct = 0, confidenceScaled = 0.0)
  )
  calib <- .calibration_stats(rows)
  stopifnot(calib$n == 5)
  stopifnot(abs(calib$gap - (-0.1)) < 1e-9)
  stopifnot(abs(calib$brier - 0.075) < 1e-9)
  stopifnot(abs(calib$conf_acc_r - sqrt(0.75)) < 1e-9)

  # Degenerate zero-eligible-rows case: all three stay NaN, n=0 (documented in the function's own
  # docstring but never previously asserted).
  calib0 <- .calibration_stats(list())
  stopifnot(calib0$n == 0)
  stopifnot(is.nan(calib0$gap) && is.nan(calib0$brier) && is.nan(calib0$conf_acc_r))
}

# ---------------------------------------------------------------------------
# Screening efficiency (Abe et al., *Cancer Cytopathology* 2026;e70132, doi:10.1002/cncy.70132):
# viewport-proxy analogues of the paper's "LPF main object" gaze-AOI biomarkers, computed against
# a --roi/--reference mask (screening_efficiency). Design rationale + construct-mapping table:
# docs/superpowers/2026-09-12-screening-efficiency-review.md. Exact port of the Python selftest's
# equivalent section -- same slide-key naming, same hand-derived fixture, same fixed literals.
# ---------------------------------------------------------------------------

SCREENING_SLIDE_KEY <- "sha256:selftest-slide-screening-efficiency-0001"
#: img 2000x1500, grid gw=gh=8 (cell = 250 x 187.5 image px) -- own dims per the C6-fixture
#: convention, chosen so BOTH sessions share one native grid resolution (no NN resampling to
#: reason about: the target-grid-dims pick (tw,th)=(8,8) trivially).
SCREENING_IMG_W <- 2000; SCREENING_IMG_H <- 1500
SCREENING_GW <- 8L; SCREENING_GH <- 8L
#: baseMagnification for both sessions (schema/4+ field) -- a round number so
#: true_magnification = 20.0 / (dsMilli/1000.0) works out to clean values below.
SCREENING_BASE_MAG <- 20.0

#' schema/5 fragment builder for the screening-efficiency fixtures below -- 8-element path points
#' `[t, cx, cy, w, h, dsMilli, mouseX, mouseY]`; mouse is the off-slide sentinel `(-1, -1)`
#' throughout (screening_efficiency never reads mouseX/mouseY -- only the shared schema is
#' exercised, per the task brief's "own two /5 sessions" instruction). `grid` is an all-zero 8x8
#' dwell array -- screening_efficiency never reads it either; only `path` + `baseMagnification`
#' matter for the metric under test. `path` is a matrix of 6-element rows (`[t,cx,cy,w,h,dsMilli]`,
#' same shape convention `build_magband_agreement_fixture` uses) -- the trailing `(-1,-1)` mouse
#' pair is appended here.
#' `grid` defaults to an all-zero 8x8 dwell array; callers that need a non-degenerate `--reference
#' SESSIONID` mask (Finding 1's regression fixture) pass an explicit `grid`.
.screening_fragment <- function(session_id, path, duration_ms, sample_count, grid = NULL) {
  path_list <- lapply(seq_len(nrow(path)), function(i) c(as.numeric(path[i, ]), -1, -1))
  list(
    schema = "atlas-focus-contribution/5",
    slideKey = SCREENING_SLIDE_KEY,
    sessionId = session_id,
    imageWidth = SCREENING_IMG_W, imageHeight = SCREENING_IMG_H,
    gridWidth = SCREENING_GW, gridHeight = SCREENING_GH,
    grid = if (!is.null(grid)) grid else rep(0.0, SCREENING_GW * SCREENING_GH),
    durationMs = duration_ms,
    sampleCount = sample_count,
    date = "2026-09-12",
    path = path_list,
    baseMagnification = SCREENING_BASE_MAG
  )
}

#' ROI: a rectangle covering EXACTLY cell(row=4, col=4)'s cell-center-containment test
#' (`rasterize_roi` tests `((col+0.5)/gw*img_w, (row+0.5)/gh*img_h)` per cell). Cell(4,4)'s center
#' is `((4.5/8)*2000, (4.5/8)*1500) = (1125, 843.75)`; neighbouring cell centers are >=125px away
#' in x (875, 1375) and >=93.75px away in y (656.25, 1031.25), so the rectangle x=[1000,1250],
#' y=[750,937.5] safely contains ONLY cell(4,4)'s center -> rasterizes to exactly flat index
#' `4*8+4 == 36` (0-based). Matches the Python selftest's `build_screening_roi_fc` exactly.
build_screening_roi_fc <- function() {
  list(
    type = "FeatureCollection",
    features = list(list(
      type = "Feature",
      geometry = list(
        type = "Polygon",
        coordinates = list(list(
          c(1000, 750), c(1250, 750), c(1250, 937.5), c(1000, 937.5), c(1000, 750)
        ))
      ),
      properties = list()
    ))
  )
}

#' Hand-derivation (verified independently against `fixations_idt`'s documented I-DT algorithm
#' before this fixture was run through the pipeline -- see scratchpad/check_screening.R's
#' standalone confirmation, and the Python selftest's `build_screening_fixture` docstring for the
#' full step-by-step derivation, IDENTICAL for this R port):
#'
#' "eff" (efficient reader): one fixation, startMs=1000.0, durationMs=1000.0, ON-reference,
#'   dsMilli=5000 -> downsample=5.0, true_magnification=20.0/(5000/1000)=4.0 < LPF_MAX_MAG=10.0 ->
#'   LPF. Expected reference_<slug>.csv row: timeToFirstRefFixMs=1000.0,
#'   timeToFirstRefFixLpfMs=1000.0, refFixTotalMs=1000.0, refFixTotalLpfMs=1000.0, refFixCount=1,
#'   refFixVisitCount=1, firstRefFixDownsample=5.0, firstRefFixMagnification=4.0.
#'
#' "ser" (serial reader): an OFF-reference fixation (startMs=0.0) then an ON-reference one
#'   "late" (startMs=5000.0, dsMilli=500 -> downsample=0.5, true_magnification=40.0 -- NOT < 10.0,
#'   but computable, so refFixTotalLpfMs is a well-defined 0.0, not blank; timeToFirstRefFixLpfMs
#'   stays blank -- no qualifying fixation at all). Expected row: timeToFirstRefFixMs=5000.0,
#'   timeToFirstRefFixLpfMs=<blank/NA>, refFixTotalMs=1000.0, refFixTotalLpfMs=0.0, refFixCount=1,
#'   refFixVisitCount=1, firstRefFixDownsample=0.5, firstRefFixMagnification=40.0.
build_screening_fixture <- function() {
  path_eff <- matrix(
    c(
      0, 100, 100, 400, 300, 20000,
      1000, 1125, 850, 400, 300, 5000,
      1250, 1125, 850, 400, 300, 5000,
      1500, 1125, 850, 400, 300, 5000,
      1750, 1125, 850, 400, 300, 5000,
      2000, 1125, 850, 400, 300, 5000,
      2250, 100, 100, 400, 300, 20000
    ),
    ncol = 6, byrow = TRUE
  )
  path_ser <- matrix(
    c(
      0, 100, 100, 400, 300, 5000,
      250, 100, 100, 400, 300, 5000,
      500, 100, 100, 400, 300, 5000,
      750, 100, 100, 400, 300, 5000,
      1000, 100, 100, 400, 300, 5000,
      5000, 1125, 850, 400, 300, 500,
      5250, 1125, 850, 400, 300, 500,
      5500, 1125, 850, 400, 300, 500,
      5750, 1125, 850, 400, 300, 500,
      6000, 1125, 850, 400, 300, 500
    ),
    ncol = 6, byrow = TRUE
  )
  f_eff <- .screening_fragment("eff", path_eff, duration_ms = 2250, sample_count = nrow(path_eff))
  f_ser <- .screening_fragment("ser", path_ser, duration_ms = 6000, sample_count = nrow(path_ser))
  list(f_eff, f_ser)
}

#' Direct, pipeline-independent unit checks for `screening_efficiency` -- TDD-style asserts on
#' hand-built `(path, base_mag, ref_mask, tw, th, img_w, img_h)` inputs, bypassing the full
#' `analyze()` pipeline entirely (mirrors the pipeline-level `check_screening_efficiency_fixture`
#' below, which exercises the SAME function through the reference/ROI comparison section instead).
#' Exact port of the Python selftest's equivalent function -- same fixtures, same literals.
check_screening_efficiency_direct_unit_asserts <- function() {
  tw <- SCREENING_GW; th <- SCREENING_GH
  img_w <- as.numeric(SCREENING_IMG_W); img_h <- as.numeric(SCREENING_IMG_H)
  mask <- rep(FALSE, tw * th)
  mask[4L * tw + 4L + 1L] <- TRUE  # ROI == cell(4,4), same as build_screening_roi_fc

  hold <- function(t0, cx, cy, ds, n = 5, step = 250.0) {
    lapply(0:(n - 1), function(i) c(t0 + i * step, cx, cy, 400.0, 300.0, ds))
  }

  all_none_keys <- c(
    "timeToFirstRefFixMs", "timeToFirstRefFixLpfMs", "refFixTotalMs", "refFixTotalLpfMs",
    "refFixCount", "refFixVisitCount", "firstRefFixDownsample", "firstRefFixMagnification"
  )

  # ---- fixations_idt(path) is NULL (path has < 2 points) -> all-NA list, whether path is a
  # single-point list or NULL outright (a grid-only /1-/2 fragment's `$path`). ----
  for (degenerate_path in list(list(c(0, 1125, 850, 400, 300, 5000)), NULL, list())) {
    res <- screening_efficiency(degenerate_path, SCREENING_BASE_MAG, mask, tw, th, img_w, img_h)
    stopifnot(all(sapply(all_none_keys, function(k) is.na(res[[k]]))))
  }

  # ---- empty ref_mask (no TRUE cell at all) -> all-NA, even with a perfectly good path. ----
  good_path <- hold(0.0, 1125.0, 850.0, 5000.0)
  res_empty_mask <- screening_efficiency(
    good_path, SCREENING_BASE_MAG, rep(FALSE, tw * th), tw, th, img_w, img_h
  )
  stopifnot(all(sapply(all_none_keys, function(k) is.na(res_empty_mask[[k]]))))

  # ---- on/off/on visit-count run logic: 4 fixations forced by idle gaps (dt=100000 >
  # IDLE_GAP_MS=60000) between 4 static holds -- on-ref flags [TRUE, TRUE, FALSE, TRUE] (the task
  # brief's own worked example) -> refFixVisitCount == 2, refFixCount == 3. Every on-ref hold uses
  # the SAME ds=5000 -> mag=4.0<10 -> LPF, so refFixTotalMs == refFixTotalLpfMs == 3000.0 and
  # timeToFirstRefFixMs == timeToFirstRefFixLpfMs == 0.0. ----
  path_visit <- c(
    hold(0.0, 1125.0, 850.0, 5000.0),
    hold(101000.0, 1125.0, 850.0, 5000.0),
    hold(202000.0, 100.0, 100.0, 5000.0),
    hold(303000.0, 1125.0, 850.0, 5000.0)
  )
  res_visit <- screening_efficiency(path_visit, SCREENING_BASE_MAG, mask, tw, th, img_w, img_h)
  stopifnot(res_visit$refFixCount == 3)
  stopifnot(res_visit$refFixVisitCount == 2)
  stopifnot(abs(res_visit$refFixTotalMs - 3000.0) < 1e-9)
  stopifnot(abs(res_visit$refFixTotalLpfMs - 3000.0) < 1e-9)
  stopifnot(abs(res_visit$timeToFirstRefFixMs - 0.0) < 1e-9)
  stopifnot(abs(res_visit$timeToFirstRefFixLpfMs - 0.0) < 1e-9)
  stopifnot(abs(res_visit$firstRefFixDownsample - 5.0) < 1e-9)
  stopifnot(abs(res_visit$firstRefFixMagnification - 4.0) < 1e-9)

  # ---- null base_mag: LPF fields (and firstRefFixMagnification) blank, but refFixTotalMs /
  # refFixCount / refFixVisitCount / timeToFirstRefFixMs / firstRefFixDownsample are all STILL
  # computed -- downsample never depends on base_mag, only magnification does. ----
  res_null_mag <- screening_efficiency(good_path, NULL, mask, tw, th, img_w, img_h)
  stopifnot(abs(res_null_mag$refFixTotalMs - 1000.0) < 1e-9)
  stopifnot(res_null_mag$refFixCount == 1)
  stopifnot(res_null_mag$refFixVisitCount == 1)
  stopifnot(abs(res_null_mag$timeToFirstRefFixMs - 0.0) < 1e-9)
  stopifnot(abs(res_null_mag$firstRefFixDownsample - 5.0) < 1e-9)
  stopifnot(is.na(res_null_mag$firstRefFixMagnification))
  stopifnot(is.na(res_null_mag$timeToFirstRefFixLpfMs))
  stopifnot(is.na(res_null_mag$refFixTotalLpfMs))

  # ---- schema/3 path (5-element points, no dsMilli at all): downsample AND magnification both
  # blank (true_magnification itself needs length(point)>=6), so both LPF fields blank too -- but
  # on-reference attribution needs only cx/cy, so those stay fully populated. A valid, non-null
  # base_mag confirms the blank is due to point shape, not base_mag. ----
  path_5elem <- lapply(0:4, function(i) c(i * 250.0, 1125.0, 850.0, 400.0, 300.0))
  res_5elem <- screening_efficiency(path_5elem, SCREENING_BASE_MAG, mask, tw, th, img_w, img_h)
  stopifnot(abs(res_5elem$refFixTotalMs - 1000.0) < 1e-9)
  stopifnot(res_5elem$refFixCount == 1)
  stopifnot(res_5elem$refFixVisitCount == 1)
  stopifnot(abs(res_5elem$timeToFirstRefFixMs - 0.0) < 1e-9)
  stopifnot(is.na(res_5elem$firstRefFixDownsample))
  stopifnot(is.na(res_5elem$firstRefFixMagnification))
  stopifnot(is.na(res_5elem$timeToFirstRefFixLpfMs))
  stopifnot(is.na(res_5elem$refFixTotalLpfMs))

  # ---- Finding 2 (.superpowers/sdd/task-screening-report.md): a NULL/zero-length dim
  # (tw/th/img_w/img_h) must degrade to the all-NA blank list, NOT error. Pre-fix, `as.integer(
  # NULL)` -> `integer(0)`, and OR-ing `is.na(integer(0))` (a zero-length logical) into the guard
  # chain silently collapsed to `NA`, so the enclosing `if (...)` raised "missing value where
  # TRUE/FALSE needed" instead of returning blank -- unlike the Python port, whose `int(tw)`/
  # `float(img_w)` `try/except` already handled a None dim correctly (see this same block in
  # selftest.py). ----
  for (bad_dims in list(
    list(NULL, th, img_w, img_h), list(tw, NULL, img_w, img_h),
    list(tw, th, NULL, img_h), list(tw, th, img_w, NULL)
  )) {
    res_none_dim <- screening_efficiency(
      good_path, SCREENING_BASE_MAG, mask, bad_dims[[1]], bad_dims[[2]], bad_dims[[3]], bad_dims[[4]]
    )
    stopifnot(all(sapply(all_none_keys, function(k) is.na(res_none_dim[[k]]))))
  }

  # ---- Finding 3 (.superpowers/sdd/task-screening-report.md): a ref_mask whose length
  # doesn't equal tw*th must degrade to the all-NA blank list, not silently index out-of-range
  # (returning NA into `on_ref`, which then poisons the `flag && !prev_on` visit-count logic with
  # an `if (NA)` error downstream). ----
  wrong_len_mask <- rep(TRUE, tw * th - 1L)  # deliberately mismatched vs. tw*th
  res_wrong_len <- screening_efficiency(
    good_path, SCREENING_BASE_MAG, wrong_len_mask, tw, th, img_w, img_h
  )
  stopifnot(all(sapply(all_none_keys, function(k) is.na(res_wrong_len[[k]]))))
}

#' Pipeline-level check: runs `build_screening_fixture` (2 schema/5 sessions, a `--roi`-driven
#' single-cell reference mask) through the full `analyze()` pipeline and asserts
#' `reference_<slug>.csv`'s EXACT new column order plus every hand-derived value documented in
#' that function's own docstring, at 1e-6 tolerance. Also extends the fixture with a tiny
#' `--graded` CSV (Selftests item 5): confirms at least one new screening column produces a
#' populated `nav_accuracy.csv` row (`timeToFirstRefFixMs`, n=2 -- both sessions have at least one
#' on-reference fixation) and that a column blank for one session (`timeToFirstRefFixLpfMs`,
#' blank for "ser") correctly drops that session from its row (n=1). Exact port of the Python
#' selftest's equivalent function.
check_screening_efficiency_fixture <- function(tmp) {
  fragments <- build_screening_fixture()
  in_dir <- file.path(tmp, "in_screening")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)
  roi_path <- file.path(tmp, "screening_roi.geojson")
  writeLines(jsonlite::toJSON(build_screening_roi_fc(), auto_unbox = TRUE), roi_path)

  graded_csv_path <- file.path(tmp, "screening_graded.csv")
  .write_simple_csv(
    graded_csv_path, c("slideKey", "sessionId", "correct"),
    list(c(SCREENING_SLIDE_KEY, "eff", "1"), c(SCREENING_SLIDE_KEY, "ser", "0"))
  )

  out_dir <- file.path(tmp, "out_screening")
  analyze(list(in_dir), out_dir, roi = roi_path, graded_csv = graded_csv_path)

  out_files <- list.files(out_dir)
  ref_files <- out_files[startsWith(out_files, "reference_")]
  stopifnot(length(ref_files) == 1)
  ref <- utils::read.csv(file.path(out_dir, ref_files[1]), stringsAsFactors = FALSE)

  expected_cols <- c(
    "session", "nss", "aucJudd", "cc", "iou", "refCoveragePct",
    "timeOnRefMs", "timeOffRefMs", "precisionAtTopK", "recall",
    "timeToFirstRefFixMs", "timeToFirstRefFixLpfMs", "refFixTotalMs", "refFixTotalLpfMs",
    "refFixCount", "refFixVisitCount", "firstRefFixDownsample", "firstRefFixMagnification"
  )
  stopifnot(identical(colnames(ref), expected_cols))
  stopifnot(setequal(ref$session, c("eff", "ser")))

  row_eff <- ref[ref$session == "eff", ]
  row_ser <- ref[ref$session == "ser", ]

  # --- "eff": on-reference, LPF (mag=4.0 < 10.0) ---
  stopifnot(abs(row_eff$timeToFirstRefFixMs - 1000.0) < 1e-6)
  stopifnot(abs(row_eff$timeToFirstRefFixLpfMs - 1000.0) < 1e-6)
  stopifnot(abs(row_eff$refFixTotalMs - 1000.0) < 1e-6)
  stopifnot(abs(row_eff$refFixTotalLpfMs - 1000.0) < 1e-6)
  stopifnot(row_eff$refFixCount == 1)
  stopifnot(row_eff$refFixVisitCount == 1)
  stopifnot(abs(row_eff$firstRefFixDownsample - 5.0) < 1e-6)
  stopifnot(abs(row_eff$firstRefFixMagnification - 4.0) < 1e-6)

  # --- "ser": on-reference "late", NOT LPF (mag=40.0) -- refFixTotalLpfMs is a well-defined 0.0
  # (magnification WAS computable, just not low-power), timeToFirstRefFixLpfMs is blank (no
  # qualifying fixation at all) -- the two distinct branches of the LPF-blank rule. ---
  stopifnot(abs(row_ser$timeToFirstRefFixMs - 5000.0) < 1e-6)
  stopifnot("ser has no qualifying LPF fixation -> should be blank" = is.na(row_ser$timeToFirstRefFixLpfMs))
  stopifnot(abs(row_ser$refFixTotalMs - 1000.0) < 1e-6)
  stopifnot(
    "ser's on-ref fixation has a COMPUTABLE (non-LPF) magnification -> refFixTotalLpfMs should be 0.0, not blank" =
      abs(row_ser$refFixTotalLpfMs - 0.0) < 1e-6
  )
  stopifnot(row_ser$refFixCount == 1)
  stopifnot(row_ser$refFixVisitCount == 1)
  stopifnot(abs(row_ser$firstRefFixDownsample - 0.5) < 1e-6)
  stopifnot(abs(row_ser$firstRefFixMagnification - 40.0) < 1e-6)

  # --- nav_accuracy.csv (Selftests item 5): the 6 non-zoom screening columns are stamped
  # in-memory onto metrics_rows and joined via NAV_ACCURACY_COLS, same as any other navigation
  # metric ---
  nav <- utils::read.csv(file.path(out_dir, "nav_accuracy.csv"), stringsAsFactors = FALSE)
  for (col in c(
    "timeToFirstRefFixMs", "timeToFirstRefFixLpfMs", "refFixTotalMs", "refFixTotalLpfMs",
    "refFixCount", "refFixVisitCount"
  )) {
    stopifnot(col %in% nav$metric)
  }

  # timeToFirstRefFixMs: BOTH sessions have an on-reference fixation -> n=2.
  ttfr <- nav[nav$metric == "timeToFirstRefFixMs", ]
  stopifnot(ttfr$n == 2)
  stopifnot(abs(ttfr$meanCorrect - 1000.0) < 1e-6)   # "eff", correct=1
  stopifnot(abs(ttfr$meanIncorrect - 5000.0) < 1e-6) # "ser", correct=0

  # timeToFirstRefFixLpfMs: "ser"'s value is blank -> excluded from the join entirely -> n=1
  # (only "eff" contributes) -- confirms a blank screening column degrades the SAME way any other
  # blank NAV_ACCURACY_COLS metric already does, not a new code path.
  ttfrl <- nav[nav$metric == "timeToFirstRefFixLpfMs", ]
  stopifnot(ttfrl$n == 1)
  stopifnot(abs(ttfrl$meanCorrect - 1000.0) < 1e-6)
}

#' Finding 1 (.superpowers/sdd/task-screening-report.md): the eff/ser paths are IDENTICAL to
#' `build_screening_fixture` -- only "eff"'s `grid` and the presence of a third, dims-less session
#' change. "eff" gets a single non-zero cell at flat index `4*8+4+1==37` (1-based; cell(4,4), the
#' EXACT cell `build_screening_roi_fc`'s rectangle covers) so a plain `--reference eff` run (NO
#' `--roi`) thresholds to the SAME single-cell mask the `--roi` fixture uses (`ref_grid >
#' IOU_THRESH * max(ref_grid)` keeps only the one nonzero cell) -- letting a `--reference`-only
#' run's "eff" screening columns be compared directly against `check_screening_efficiency_fixture`'s
#' hand-derived `--roi` values above ("ser" is untouched, still all-zero grid, irrelevant to the
#' reference mask since only `resampled[[reference]]` builds it).
#'
#' A THIRD fragment, sessionId `"zzz-nodim"` (named to sort LAST: `load_fragments` reads a
#' directory via `sort(list.files(p, pattern = "\\.json$", ...))`, and each fragment is written to
#' `<sessionId>.json`, so `"eff.json" < "ser.json" < "zzz-nodim.json"` alphabetically -- making
#' `"zzz-nodim"` the LAST entry of both the loaded fragment list and the insertion-ordered
#' `by_session`/`session_ids`), is schema/2 (grid-only, no `path` element at all) and carries
#' NEITHER `imageWidth` NOR `imageHeight`. On the PRE-FIX code, the hotspots loop's plain `if
#' (!is.null(f$imageWidth)) f$imageWidth else 1` for this LAST session leaves `img_w <- img_h <- 1`
#' sitting in the enclosing scope once that loop finishes, and the `--reference`-only branch below
#' (no `--roi`) never reassigns it (only the `--roi` branch does) -- so "eff"'s on-reference
#' cell-mapping would be computed against a degenerate 1x1 "image" instead of the true 2000x1500,
#' corrupting the clamp+floor cell lookup and producing screening values that do NOT match the
#' `--roi` run's "eff" row. With the fix (deriving `img_w`/`img_h` from `session_ids[1]` -- "eff"
#' itself -- right in this block, same as the `--roi` branch), the two runs agree exactly.
build_screening_reference_only_fixture <- function() {
  path_eff <- matrix(
    c(
      0, 100, 100, 400, 300, 20000,
      1000, 1125, 850, 400, 300, 5000,
      1250, 1125, 850, 400, 300, 5000,
      1500, 1125, 850, 400, 300, 5000,
      1750, 1125, 850, 400, 300, 5000,
      2000, 1125, 850, 400, 300, 5000,
      2250, 100, 100, 400, 300, 20000
    ),
    ncol = 6, byrow = TRUE
  )
  path_ser <- matrix(
    c(
      0, 100, 100, 400, 300, 5000,
      250, 100, 100, 400, 300, 5000,
      500, 100, 100, 400, 300, 5000,
      750, 100, 100, 400, 300, 5000,
      1000, 100, 100, 400, 300, 5000,
      5000, 1125, 850, 400, 300, 500,
      5250, 1125, 850, 400, 300, 500,
      5500, 1125, 850, 400, 300, 500,
      5750, 1125, 850, 400, 300, 500,
      6000, 1125, 850, 400, 300, 500
    ),
    ncol = 6, byrow = TRUE
  )
  eff_grid <- rep(0.0, SCREENING_GW * SCREENING_GH)
  eff_grid[4L * SCREENING_GW + 4L + 1L] <- 1.0  # cell(4,4) -- same cell the --roi fixture covers
  f_eff <- .screening_fragment(
    "eff", path_eff, duration_ms = 2250, sample_count = nrow(path_eff), grid = eff_grid
  )
  f_ser <- .screening_fragment("ser", path_ser, duration_ms = 6000, sample_count = nrow(path_ser))
  f_third <- list(
    schema = "atlas-focus-contribution/2",
    slideKey = SCREENING_SLIDE_KEY,
    sessionId = "zzz-nodim",
    gridWidth = SCREENING_GW, gridHeight = SCREENING_GH,
    grid = rep(0.0, SCREENING_GW * SCREENING_GH),
    durationMs = 1000.0,
    sampleCount = 4,
    date = "2026-09-12"
    # deliberately NO imageWidth/imageHeight, NO path -- grid-only schema/2 fragment.
  )
  list(f_eff, f_ser, f_third)
}

#' Finding 1 regression test: a `--reference eff` run with NO `--roi` (three sessions on the
#' slide, the third grid-only/dims-less and last-iterated -- see
#' `build_screening_reference_only_fixture`) must produce the EXACT SAME "eff" screening columns
#' as the `--roi`-driven `check_screening_efficiency_fixture` above, at `1e-6` tolerance -- both
#' runs compare "eff" against the identical single-cell reference mask (cell(4,4)) using "eff"'s
#' own true 2000x1500 image dimensions.
check_screening_efficiency_reference_only_regression <- function(tmp) {
  fragments <- build_screening_reference_only_fixture()
  in_dir <- file.path(tmp, "in_screening_refonly")
  dir.create(in_dir, showWarnings = FALSE, recursive = TRUE)
  write_fragments_to_dir(fragments, in_dir)

  out_dir <- file.path(tmp, "out_screening_refonly")
  analyze(list(in_dir), out_dir, reference = "eff")

  out_files <- list.files(out_dir)
  ref_files <- out_files[startsWith(out_files, "reference_")]
  stopifnot(length(ref_files) == 1)
  ref <- utils::read.csv(file.path(out_dir, ref_files[1]), stringsAsFactors = FALSE)
  row_eff <- ref[ref$session == "eff", ]

  # Same hand-derived "eff" values as check_screening_efficiency_fixture's --roi run.
  stopifnot(abs(row_eff$timeToFirstRefFixMs - 1000.0) < 1e-6)
  stopifnot(abs(row_eff$timeToFirstRefFixLpfMs - 1000.0) < 1e-6)
  stopifnot(abs(row_eff$refFixTotalMs - 1000.0) < 1e-6)
  stopifnot(abs(row_eff$refFixTotalLpfMs - 1000.0) < 1e-6)
  stopifnot(row_eff$refFixCount == 1)
  stopifnot(row_eff$refFixVisitCount == 1)
  stopifnot(abs(row_eff$firstRefFixDownsample - 5.0) < 1e-6)
  stopifnot(abs(row_eff$firstRefFixMagnification - 4.0) < 1e-6)
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
  # Older ZIPs may repeat the same named fragment. Keep one identical copy, but retain a
  # changed payload even when its archive entry name is the same.
  old_zip_a <- file.path(tmp, "old-a.zip")
  old_zip_b <- file.path(tmp, "old-b.zip")
  changed_zip <- file.path(tmp, "changed.zip")
  write_fragments_to_zip(list(fragments[[1]]), old_zip_a)
  write_fragments_to_zip(list(fragments[[1]]), old_zip_b)
  changed <- fragments[[1]]
  changed$sampleCount <- changed$sampleCount + 1
  write_fragments_to_zip(list(changed), changed_zip)
  stopifnot("duplicate archived fragment should count once" =
            length(load_fragments(list(old_zip_a, old_zip_b, changed_zip))) == 2)
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
    "mouseVelocityPxPerSec", "activeFractionPct",
    "idleMs", "activeSpanMs", "avgZoomLog2W", "drillingRateOctavesPerMin",
    "magnificationSource",
    "nFixations", "meanFixationMs", "medianFixationMs", "sdFixationMs", "fixationsPerMin",
    "mouseCoveragePct", "mouseEntropy", "meanSegmentLinearity",
    "annotatedAreaUnionPx", "visitCountJaccard",
    "meanSegmentLinearityROI"
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

  # --- Tier 3 C1: nFixations/fixationsPerMin populated (non-blank, >=0) for the path-carrying
  # sessions (s1, s2, s4), blank for s3 (no path at all) ---
  for (r in list(row_s1, row_s2, row_s4)) {
    stopifnot(!is.na(r$nFixations) && r$nFixations >= 0)
    stopifnot(!is.na(r$fixationsPerMin) && r$fixationsPerMin >= 0)
  }
  stopifnot(
    "nFixations should be blank for a pathless session" = is.na(row_s3$nFixations)
  )
  stopifnot(is.na(row_s3$fixationsPerMin))

  # --- fixations_<slug>.csv (Tier 3 C1): written for the path-carrying sessions that found at
  # least one fixation each (s1/s2/s4's jittered synthetic paths all do) ---
  out_files <- list.files(out_dir)
  fixation_files <- out_files[startsWith(out_files, "fixations_")]
  stopifnot("expected exactly one fixations_ file" = length(fixation_files) == 1)
  fixations_df <- utils::read.csv(file.path(out_dir, fixation_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "fixations_<slug>.csv columns mismatch" = identical(
      colnames(fixations_df),
      c("session", "idx", "startMs", "durationMs", "centerImageX", "centerImageY", "nPoints")
    )
  )
  stopifnot(all(unique(fixations_df$session) %in% c("s1", "s2", "s4")))
  stopifnot("every fixation must have a positive duration" = all(fixations_df$durationMs > 0))
  stopifnot(all(fixations_df$nPoints >= 1))
  for (sess in unique(fixations_df$session)) {
    grp <- fixations_df[fixations_df$session == sess, ]
    idxs <- sort(grp$idx)
    stopifnot(identical(idxs, seq_len(length(idxs))))
  }

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

  # --- magbands_<slug>.csv: written for the path-carrying sessions (s1, s2, s4). Tier 2 B3
  # (default --magband-scheme canonical): s1 has a known baseMagnification (40.0) AND every path
  # point carries dsMilli -> true magnification is computable -> CANONICAL scheme, 7 bands (an
  # intentional Tier-2 output change vs pre-T2, where s1 used tercile like every other session --
  # see t2-report.md). s2 (schema/3, no dsMilli at all) and s4 (schema/4, baseMagnification
  # deliberately NULL) cannot compute a true magnification -> auto-fallback to the pre-T2 TERCILE
  # scheme, 3 bands each -- unchanged from before. ---
  magband_files <- out_files[startsWith(out_files, "magbands_")]
  stopifnot("expected exactly one magbands_ file" = length(magband_files) == 1)
  magbands_df <- utils::read.csv(file.path(out_dir, magband_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "magbands.csv columns mismatch" =
      identical(colnames(magbands_df), c("session", "band", "bandTimeMs", "bandTimePct", "bandScheme"))
  )
  stopifnot(
    "magbands sessions mismatch" =
      setequal(unique(magbands_df$session), c("s1", "s2", "s4"))
  )
  s1_magbands <- magbands_df[magbands_df$session == "s1", ]
  stopifnot("s1 should use the canonical scheme by default" = all(s1_magbands$bandScheme == "canonical"))
  stopifnot("s1 canonical scheme should emit 7 fixed bands" = nrow(s1_magbands) == 7)
  for (sess in c("s2", "s4")) {
    sess_magbands <- magbands_df[magbands_df$session == sess, ]
    if (!all(sess_magbands$bandScheme == "tercile")) {
      stop(sprintf("%s should auto-fall back to tercile", sess), call. = FALSE)
    }
    if (nrow(sess_magbands) != 3) {
      stop(sprintf("%s tercile scheme should emit the default 3 bands", sess), call. = FALSE)
    }
  }
  # s2/s4's tercile bandTimeMs is unaffected by B1 (no idle gaps here) or B3 (they never used
  # canonical) -- byte-identical to the pre-T2 baseline captured before this task's edits.
  pre_t2_magbands <- list(
    s2_0 = 0.0, s2_1 = 0.0, s2_2 = 8500.0,
    s4_0 = 0.0, s4_1 = 4750.0, s4_2 = 5000.0
  )
  for (nm in names(pre_t2_magbands)) {
    parts <- strsplit(nm, "_")[[1]]
    sess <- parts[1]; band_i <- as.integer(parts[2])
    val <- magbands_df$bandTimeMs[magbands_df$session == sess & magbands_df$band == band_i]
    if (abs(val - pre_t2_magbands[[nm]]) >= 1e-6) {
      stop(sprintf("REGRESSION: %s bandTimeMs drifted (expected %s, got %s)", nm, pre_t2_magbands[[nm]], val), call. = FALSE)
    }
  }

  # --- PT4 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P4): no
  # magband_agreement_<slug>.csv on THIS slide -- only s1 is canonical-scheme (s2/s4 fall back to
  # tercile, per the magbands assert above), so n_canonical_sessions == 1 < 2 -> the file-level gate
  # fails and the file must not exist at all (not header-only). ---
  stopifnot(
    "magband_agreement_<slug>.csv should NOT be written when fewer than 2 sessions use the canonical scheme (this slide has only 1: s1)" =
      length(out_files[startsWith(out_files, "magband_agreement_")]) == 0
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

  # --- Tier 2 B1: idleMs/activeSpanMs, and a HARDCODED-baseline no-drift check -------------------
  # None of s1/s2/s4's synthetic ~250ms-step paths contain a >60s gap -> idleMs must be exactly
  # 0.0 for every one of them, and activeSpanMs must equal the path's total wall-clock span --
  # this IS the B1 invariant "a session with no idle gap is unaffected".
  fragment_by_session <- list(s1 = fragments[[1]], s2 = fragments[[2]], s4 = fragments[[4]])
  row_by_session <- list(s1 = row_s1, s2 = row_s2, s4 = row_s4)
  for (sess in c("s1", "s2", "s4")) {
    r <- row_by_session[[sess]]
    pm_full <- as_path_matrix(fragment_by_session[[sess]]$path)
    expected_span <- pm_full[nrow(pm_full), 1] - pm_full[1, 1]
    if (r$idleMs != 0.0) stop(sprintf("%s: expected idleMs == 0.0 (no >60s gap), got %s", sess, r$idleMs))
    if (abs(r$activeSpanMs - expected_span) >= 1e-9) {
      stop(sprintf(
        "%s: expected activeSpanMs == total span (%s) when idleMs==0, got %s",
        sess, expected_span, r$activeSpanMs
      ))
    }
  }
  stopifnot("schema/2 (no path) should have blank idleMs" = is.na(row_s3$idleMs))
  stopifnot("schema/2 (no path) should have blank activeSpanMs" = is.na(row_s3$activeSpanMs))

  # Pre-T2 (pre-B1) hardcoded reference values captured from the unmodified R pipeline, before any
  # of this task's edits -- proves the B1 refactor is a byte-identical no-op for these idle-free
  # fixtures (rather than merely "close"), which is the core B1 regression the spec requires
  # guarding: "sessions with NO >60s gap must produce IDENTICAL numbers to before".
  pre_t2_rates <- list(
    s1 = list(scanningRatePxPerMin = 24398.8811896941, drillingRatePerMin = 12.3076923076923,
              pathVelocityPxPerSec = 432.666153055679, searchFocusRatio = 0.794871794871795),
    s2 = list(scanningRatePxPerMin = 25938.0088737907, drillingRatePerMin = 0.0,
              pathVelocityPxPerSec = 437.109269310815, searchFocusRatio = 1.0),
    s4 = list(scanningRatePxPerMin = 49656.5707510415, drillingRatePerMin = 6.15384615384615,
              pathVelocityPxPerSec = 84.0951841665146, searchFocusRatio = 0.769230769230769)
  )
  for (sess in c("s1", "s2", "s4")) {
    r <- row_by_session[[sess]]
    for (col in names(pre_t2_rates[[sess]])) {
      expected <- pre_t2_rates[[sess]][[col]]
      if (abs(r[[col]] - expected) >= 1e-6) {
        stop(sprintf(
          "REGRESSION (B1): %s.%s drifted for an idle-free path -- expected %s (pre-T2 value), got %s",
          sess, col, expected, r[[col]]
        ))
      }
    }
  }

  # --- Tier 2 B2: avgZoomLog2W/drillingRateOctavesPerMin -- populated for path sessions, blank
  # for s3 (no path) ---
  for (sess in c("s1", "s2", "s4")) {
    r <- row_by_session[[sess]]
    if (is.na(r$avgZoomLog2W)) stop(sprintf("%s missing avgZoomLog2W", sess))
    if (is.na(r$drillingRateOctavesPerMin)) stop(sprintf("%s missing drillingRateOctavesPerMin", sess))
    stopifnot("drillingRateOctavesPerMin should be non-negative" = r$drillingRateOctavesPerMin >= 0)
  }
  stopifnot("schema/2 (no path) should have blank avgZoomLog2W" = is.na(row_s3$avgZoomLog2W))
  stopifnot(
    "schema/2 (no path) should have blank drillingRateOctavesPerMin" =
      is.na(row_s3$drillingRateOctavesPerMin)
  )

  # --- Tier 2 B4: magnificationSource -- "true" iff baseMagnification is present, else
  # "proxy-downsample"; blank without a path at all ---
  stopifnot("s1 magnificationSource mismatch" = row_s1$magnificationSource == "true")
  stopifnot("s2 magnificationSource mismatch" = row_s2$magnificationSource == "proxy-downsample")
  stopifnot("s4 magnificationSource mismatch" = row_s4$magnificationSource == "proxy-downsample")
  stopifnot(
    # A character column's blank cell round-trips through read.csv as "" (not NA) -- R's
    # read.csv only maps "" -> NA for columns it coerces to numeric (see the diagnosis == ""
    # convention in check_decisions above); mirrors that same convention here.
    "schema/2 (no path) should have blank magnificationSource" = row_s3$magnificationSource == ""
  )

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

  # --- Tier 3 C2 (mouse-dwell grid): populated only for s1 (schema/5, has mouse data with at
  # least one on-slide point), blank elsewhere ---
  stopifnot("s1 missing mouseCoveragePct" = !is.na(row_s1$mouseCoveragePct))
  stopifnot("mouseCoveragePct out of [0,100]" = row_s1$mouseCoveragePct >= 0.0 && row_s1$mouseCoveragePct <= 100.0)
  stopifnot("s1 missing mouseEntropy" = !is.na(row_s1$mouseEntropy))
  stopifnot("mouseEntropy should be >= 0" = row_s1$mouseEntropy >= 0.0)
  for (r in list(row_s2, row_s3, row_s4)) {
    stopifnot("expected blank mouseCoveragePct (no mouse data)" = is.na(r$mouseCoveragePct))
    stopifnot("expected blank mouseEntropy (no mouse data)" = is.na(r$mouseEntropy))
  }

  # --- Tier 3 C2: mouse_<slug>.csv written (s1 has mouse data), self-diagonal cc == 1.0 for the
  # one session with real (non-constant) mouse-dwell data, == 0.0 for the all-zero placeholder
  # grids of the mouse-data-less sessions (cc()'s documented "constant grid -> 0.0" convention) ---
  out_files <- list.files(out_dir)
  mouse_files <- out_files[startsWith(out_files, "mouse_")]
  stopifnot("expected exactly one mouse_ file" = length(mouse_files) == 1)
  mouse_df <- utils::read.csv(file.path(out_dir, mouse_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "mouse_<slug>.csv columns mismatch" =
      identical(colnames(mouse_df), c("sessionA", "sessionB", "cc", "iou", "coincidenceLevel", "mouseICC"))
  )
  stopifnot("expected 4x4=16 pairwise rows" = nrow(mouse_df) == 16)
  diag_s1 <- mouse_df[mouse_df$sessionA == "s1" & mouse_df$sessionB == "s1", ]
  stopifnot(
    "s1 (real mouse-dwell data) self-diagonal cc should be 1.0" = abs(diag_s1$cc - 1.0) < 1e-9
  )
  for (sid in c("s2", "s3", "s4")) {
    diag <- mouse_df[mouse_df$sessionA == sid & mouse_df$sessionB == sid, ]
    stopifnot(
      "all-zero placeholder mouse grid should self-cc == 0.0 (constant)" = abs(diag$cc - 0.0) < 1e-9
    )
  }
  # PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2): only s1 actually has mouse
  # data on this 4-session slide -- fewer than 2 mouse-carrying sessions -> mouseICC must be
  # blank (icc() is undefined for a single reader), even though the diagonal-reuse row (s1,
  # idx 1) is where coincidenceLevel IS populated.
  stopifnot(
    "mouseICC should be blank with only 1 mouse-data session on the slide" = is.na(diag_s1$mouseICC)
  )

  # --- Tier 3 C4 (segment-level linearity): populated for s1/s2 (whose synthetic path dwells
  # right at their own recorded grid's center -- and also drifts through a couple of neighboring
  # hotspot cells, so even post-C4-dedup-fix, consecutive same-cell hits collapsed to one boundary
  # each, there are still >=2 DISTINCT-hotspot boundaries); blank for s3 (no path) AND,
  # legitimately, for s4 (its recorded grid is centered elsewhere from its bouncing path by
  # deliberate fixture design -- see build_fragments' f4 comment -- so 0 boundary hotspot hits, a
  # real documented degenerate case, not a bug). Any populated value must still be a valid
  # linearity in [0, 1] -- NOT asserted exactly here (the dedup fix moved s1/s2 off their pre-fix
  # 1.0 to ~0.68/~0.80 respectively; build_seglin_dwell_fragment above pins the exact numbers). ---
  for (r in list(row_s1, row_s2)) {
    stopifnot("missing meanSegmentLinearity" = !is.na(r$meanSegmentLinearity))
    stopifnot(
      "meanSegmentLinearity out of [0,1]" =
        r$meanSegmentLinearity >= -1e-9 && r$meanSegmentLinearity <= 1.0 + 1e-9
    )
  }
  if (!is.na(row_s4$meanSegmentLinearity)) {
    stopifnot(
      "meanSegmentLinearity out of [0,1]" =
        row_s4$meanSegmentLinearity >= -1e-9 && row_s4$meanSegmentLinearity <= 1.0 + 1e-9
    )
  }
  stopifnot(
    "schema/2 (no path) should have blank meanSegmentLinearity" = is.na(row_s3$meanSegmentLinearity)
  )

  # --- PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): ROI-entry segment
  # linearity -- s2 (has a path, but NO annotations at all) and s3 (no path, no annotations) must
  # both be blank (the "no annotations -> blank" case), regardless of path. s1/s4 have
  # annotations + paths but aren't guaranteed to actually cross an ROI-entry boundary twice by
  # this fixture's design (untested here -- see the dedicated build_roi_seglin_fragment fixture
  # for an exact-value assert), so only bounds-check them if populated. ---
  stopifnot(
    "meanSegmentLinearityROI should be blank for a session with a path but no annotations" =
      is.na(row_s2$meanSegmentLinearityROI)
  )
  stopifnot(
    "meanSegmentLinearityROI should be blank for a session with no path and no annotations" =
      is.na(row_s3$meanSegmentLinearityROI)
  )
  for (r in list(row_s1, row_s4)) {
    if (!is.na(r$meanSegmentLinearityROI)) {
      stopifnot(
        "meanSegmentLinearityROI out of [0,1]" =
          r$meanSegmentLinearityROI >= -1e-9 && r$meanSegmentLinearityROI <= 1.0 + 1e-9
      )
    }
  }

  # --- Tier 3 C3 (DTW): scanpath_<slug>.csv gains dtwDistance, diagonal exactly 0.0
  # (self-comparison, by construction of the DP), off-diagonal non-negative and non-blank ---
  scan_files <- out_files[startsWith(out_files, "scanpath_")]
  stopifnot("expected exactly one scanpath_ file" = length(scan_files) == 1)
  scan_df <- utils::read.csv(file.path(out_dir, scan_files[1]), stringsAsFactors = FALSE)
  stopifnot(
    "scanpath_<slug>.csv columns mismatch" = identical(
      colnames(scan_df),
      c("sessionA", "sessionB", "levenshteinSim", "transitionEntropy", "dtwDistance")
    )
  )
  stopifnot("dtwDistance should never be blank here" = !any(is.na(scan_df$dtwDistance)))
  for (sid in c("s1", "s2", "s4")) {
    diag <- scan_df[scan_df$sessionA == sid & scan_df$sessionB == sid, ]
    stopifnot(
      "self-diagonal dtwDistance should be exactly 0.0" = abs(diag$dtwDistance - 0.0) < 1e-9
    )
  }
  stopifnot("dtwDistance should never be negative" = all(scan_df$dtwDistance >= 0.0))

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

  # --- A2 parity-critical assert: turn angles landing exactly on a turnAngleEntropy bin boundary
  # (45-degree multiples), from exact-integer diagonal/axis-aligned pixel deltas -- the realistic
  # case where the degree-conversion association order (`x*180/pi` vs `x*(180.0/pi)`) could
  # silently disagree with the Python toolkit, since floor((deg+180)/45) is a discontinuous step
  # function of `deg`. Path alternates heading 0deg (dx,dy=100,0) and heading 45deg
  # (dx,dy=100,100) -> turns alternate exactly -45deg/+45deg (two distinct bins, not one, so
  # entropy is a non-trivial ~1 bit rather than the degenerate 0 the L-path above exercises).
  bx <- 0; by <- 0; bt <- 0
  boundary_path <- list(c(0, 0, 0, 400, 300))
  for (i in 0:20) { # odd count -> 20 turns (even) -> exact 10/10 split, 2 clean bins
    if (i %% 2 == 0) {
      bdx <- 100; bdy <- 0
    } else {
      bdx <- 100; bdy <- 100
    }
    bt <- bt + 100
    bx <- bx + bdx
    by <- by + bdy
    boundary_path[[length(boundary_path) + 1]] <- c(bt, bx, by, 400, 300)
  }
  boundary_turns <- .turn_angles_deg(boundary_path)
  stopifnot(
    "expected every turn to be exactly +/-45deg" = all(abs(abs(boundary_turns) - 45.0) < 1e-9)
  )
  boundary_mean <- mean_abs_turn_angle_deg(boundary_path)
  stopifnot("boundary path mean turn should be 45deg" = abs(boundary_mean - 45.0) < 1e-6)
  boundary_ent <- turn_angle_entropy(boundary_path)
  # 2 equally-likely bins out of 8 -> 1 bit of entropy, normalized by log2(8)=3 -> 1/3.
  stopifnot(
    "expected turnAngleEntropy == 1/3 (2 equally-likely bins out of 8)" =
      abs(boundary_ent - (1.0 / 3.0)) < 1e-6
  )

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

  # --- Tier 2 (B1-B4): idle exclusion, Drew-fidelity zoom, canonical mag bands, mag-source flag ---
  check_tier2_direct_unit_asserts()
  check_tier2_idle_fixture(tmp)
  check_tier2_zoom_fidelity_fixture(tmp)
  check_tier2_magband_scheme_cli(tmp)

  # --- Tier 3 C1: I-DT fixation extraction ---
  check_tier3_direct_unit_asserts()
  check_tier3_fixation_fixture(tmp)

  # --- Tier 3 C2/C3/C4: mouse-dwell map + cross-reader mouse agreement, DTW trajectory
  # similarity, segment-level linearity ---
  check_tier4_direct_unit_asserts()
  check_tier4_mouse_fixture(tmp)
  check_tier4_seglin_fixture(tmp)
  check_tier4_seglin_dwell_fixture(tmp)

  # --- Tier 3 C6: JSD, precision@k/recall, visit-count Jaccard, union area, reader-count map ---
  check_tier6_direct_unit_asserts()
  check_tier6_c6_fixture(tmp)

  # --- Final review (2026-07-24): zero-size-grid guards (visit-count, mouse-raster),
  # precision@k strictly-positive, fixation idle-boundary ---
  check_finalfix_zerogrid_path_fixture(tmp)
  check_finalfix_zerogrid_mouse_fixture(tmp)
  check_finalfix_topk_sparse_fixture(tmp)
  check_finalfix_idle_fixation_fixture(tmp)

  # --- Polish final-review (docs/superpowers/sdd/polish-finalfix-report.md): root-cause
  # zero-dim-grid rejection at load (.is_valid_fragment), resample_nn/raster_from_path
  # defense-in-depth guards, and the non-numeric baseMagnification crash (point_zoom /
  # magnificationSource / true_magnification) ---
  check_polish_degenerate_plus_normal_fixture(tmp)
  check_polish_canonical_degenerate_fixture(tmp)
  check_polish_nonnumeric_basemag_fixture(tmp)
  check_polish_mouse_icc_allzero_fixture(tmp)

  # --- P1 selftest coverage gaps (docs/superpowers/specs/2026-07-25-enrichment-polish.md):
  # consensus_count_<slug>.csv at 3 sessions, meanDiff's n=1-per-group boundary, and independent
  # calibration-summary value asserts ---
  check_consensus3_fixture(tmp)
  check_meandiff_n1_boundary(tmp)
  check_calibration_direct_unit_asserts()

  # --- PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2): mouseICC ---
  check_mouse_icc_fixture(tmp)

  # --- PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): ROI-entry segment
  # linearity ---
  check_pt3_direct_unit_asserts()
  check_pt3_roi_seglin_fixture(tmp)

  # --- PT4 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P4): per-magnification-band
  # cross-reader agreement ---
  check_pt4_magband_agreement_fixture(tmp)
  check_pt4_magband_agreement_frac_fixture(tmp)
  check_pt4_magband_agreement_partial_fixture(tmp)
  check_pt4_tercile_scheme_no_file(tmp)

  # --- Screening efficiency (Abe et al. 2026, doi:10.1002/cncy.70132): viewport-proxy LPF
  # main-object biomarkers vs a --roi/--reference mask, in reference_<slug>.csv and
  # nav_accuracy.csv ---
  check_screening_efficiency_direct_unit_asserts()
  check_screening_efficiency_fixture(tmp)
  # Finding 1 (.superpowers/sdd/task-screening-report.md): --reference-only (no --roi) must
  # not leak img_w/img_h from the earlier hotspots loop.
  check_screening_efficiency_reference_only_regression(tmp)

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
