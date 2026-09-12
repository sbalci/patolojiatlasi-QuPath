#!/usr/bin/env Rscript
# blinded_focus.R — standalone R analysis toolkit for QuPath atlas blinded-focus fragments.
#
# Reads anonymised viewport-dwell fragments (schema `atlas-focus-contribution/{1,2,3,4,5}`)
# produced by the qupath-extension-atlas blinded recording feature and computes the standard
# saliency / eye-tracking evaluation set: spatial similarity (CC, SIM, KLD, NSS, AUC-Judd, IoU),
# inter-observer agreement (mean pairwise CC, ICC(2,1) via `irr::icc`), reference/ROI comparison,
# scanpath sequence metrics (visited-cell Levenshtein similarity, transition entropy), (Phase 1
# navigation-research upgrade) a scanpath-rasterized fine dwell grid plus a zoom/navigation metric
# family (avg/variance/range zoom, magnification percentage, scanning/drilling rate, path
# velocity/linearity/search-focus, cross-session coincidence/region-coverage), and (Phase 2)
# annotation metrics (schema/4+ `annotations` GeoJSON FeatureCollection: dwell-in-annotation %,
# enrichment ratio, re-entry count, cross-user annotation IoU/coincidence) plus cursor metrics
# (schema/5 8-element path points with `mouseX`/`mouseY`: % time on-slide, cursor/viewport
# coupling distance), and (Tier 1, docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md,
# purely additive) turn-angle directionality, mouse kinematics (schema/5 only), active fraction,
# hotspots_<slug>.csv / transitions_<slug>.csv exports, and a multi-reader scanpath overlay figure.
#
# This is the R sibling of `analysis/python/blinded_focus/` (io.py + metrics.py + figures.py +
# analyze.py combined into one sourced file, per this project's R convention). Metric formulas are
# pinned to be numerically identical to the Python implementation so the two toolkits are directly
# comparable on the same input. It does not import anything from the QuPath extension, from
# `tools/aggregate-focus.py`, or from the Python toolkit — only the fragment JSON shape, the
# output-file contract, and the metric formulas are shared, by convention/spec, not by code.
#
# Source this file (`source("blinded_focus.R")`) to get all functions below; see `run_analysis.R`
# for the CLI entry point and `README.md` for install + usage instructions.

suppressPackageStartupMessages({
  library(jsonlite)
  library(dplyr)
  library(tidyr)
  library(ggplot2)
  library(irr)
  library(proxy)
})

#: Small constant added to denominators/logs to avoid division-by-zero / log(0). Matches
#: `blinded_focus.metrics.EPS` in the Python toolkit exactly.
EPS <- 1e-12

#: Tier 2 B1 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): a scanpath step
#: whose dt exceeds this threshold (ms) is "idle" -- the user stepped away from the slide/mouse for
#: over a minute (Ghezloo's >60s-frozen-viewport idle-time-exclusion rule) -- and is excluded from
#: every rate/weight computation listed in `idle_step_mask`'s docs. Matches the Python toolkit's
#: `IDLE_GAP_MS` literal exactly.
IDLE_GAP_MS <- 60000

#: Tier 3 C6 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): the reader's
#: "top-K" attended cells for `precisionAtTopK`/`recall` are the cells whose dwell value falls in
#: the top 10% (by value, tie-inclusive -- see `top_k_frac_mask`). Matches the Python toolkit's
#: `PRECISION_K_FRAC` literal exactly.
PRECISION_K_FRAC <- 0.10

#: Accepted fragment schemas. /1 = fixed-weight sample counts (visible "Contribute" mode).
#: /2, /3, /4, /5 = real dwell-ms (blinded recording, weightUnit="ms"). /3+ additionally have
#: "path" (/3 points are 5-element `[tRelMs,cx,cy,w,h]`; /4 points are 6-element
#: `[tRelMs,cx,cy,w,h,dsMilli]` and /4 fragments also carry `baseMagnification`/`pathTruncated`/
#: `annotations`; /5 points are 8-element `[tRelMs,cx,cy,w,h,dsMilli,mouseX,mouseY]`).
#: No branching on the schema string beyond this membership check anywhere in the toolkit — 5- vs
#: 6- vs 8-element points are told apart by `ncol(as_path_matrix(path))`, and
#: `baseMagnification`/`pathTruncated`/`annotations` are read via `f$field` (NULL when absent), so
#: /1-/3 fragments degrade to blank CSV cells automatically (mirrors `blinded_focus.io`'s doc
#: comment in the Python toolkit).
SCHEMAS <- c(
  "atlas-focus-contribution/1",
  "atlas-focus-contribution/2",
  "atlas-focus-contribution/3",
  "atlas-focus-contribution/4",
  "atlas-focus-contribution/5"
)

# ---------------------------------------------------------------------------
# io: fragment loading (dirs / .zip / single files), grouping, slugging, labels
# ---------------------------------------------------------------------------

#' Test whether a parsed JSON object is a valid blinded-focus fragment. Besides the required-field
#' checks, also validates that `length(grid) == gridWidth*gridHeight` — a mismatch would otherwise
#' make later `matrix(..., nrow=gh, ncol=gw)` / reshape calls throw (too few values) or silently
#' misalign rows/cols (too many, matrix() just recycles), so it must be caught here at load time
#' rather than downstream.
#'
#' **Polish final-review root-cause fix (docs/superpowers/sdd/polish-finalfix-report.md):**
#' `gw > 0 && gh > 0` is now ALSO required. A fragment recording `gridWidth==0` or `gridHeight==0`
#' (`grid=[]`, or `grid=list()`) used to pass this check -- `length(list())==0==0*5` -- despite
#' carrying no spatial grid at all. That degenerate-but-schema-valid shape is exactly what kept
#' resurfacing as a recurring "schema-valid input crashes the whole analyze() batch" class deep in
#' this file's grid functions (see the final-review report's Findings 2/3 fixture history).
#' Rejecting it here, at load, is the single choke point that makes every one of those downstream
#' crashes unreachable in the first place -- a zero-dimension fragment is simply never handed to
#' `analyze()` at all, degrading exactly like any other malformed fragment (a skipped file, not a
#' crashed batch). The per-function guards added to `resample_nn`/`raster_from_path` below are kept
#' anyway, as defense-in-depth for any DIRECT caller of those functions that bypasses
#' `load_fragments` entirely.
.is_valid_fragment <- function(d) {
  if (!(is.list(d) &&
    !is.null(d$schema) && length(d$schema) == 1 && d$schema %in% SCHEMAS &&
    !is.null(d$slideKey) &&
    !is.null(d$grid) &&
    !is.null(d$gridWidth) &&
    !is.null(d$gridHeight))) {
    return(FALSE)
  }
  gw <- suppressWarnings(as.integer(d$gridWidth))
  gh <- suppressWarnings(as.integer(d$gridHeight))
  if (length(gw) != 1 || length(gh) != 1 || is.na(gw) || is.na(gh) || gw <= 0 || gh <= 0) {
    return(FALSE)
  }
  length(d$grid) == gw * gh
}

#' Parse one JSON document (character vector of lines, or a single string) into a fragment list
#' (tagged with "_source"), or NULL if it isn't valid JSON / isn't a recognised fragment.
.parse_fragment <- function(txt, source) {
  d <- tryCatch(jsonlite::fromJSON(txt, simplifyVector = TRUE), error = function(e) NULL)
  if (is.null(d) || !.is_valid_fragment(d)) {
    return(NULL)
  }
  d[["_source"]] <- source
  d
}

#' Coerce a fragment's `path` field (schema/3 or /4) to an (n x 5) or (n x 6) numeric matrix with
#' columns `[tRelMs, cx, cy, w, h]` (schema/3) or `[tRelMs, cx, cy, w, h, dsMilli]` (schema/4),
#' regardless of whether jsonlite simplified it to a matrix already (the common case for
#' well-formed, uniform-length rows) or left it as a list-of-vectors / empty list. Column count is
#' inferred from the data itself (`ncol(path)` if already a matrix, else the length of each
#' row-vector via `rbind`) — no schema branching needed here.
as_path_matrix <- function(path) {
  if (is.null(path) || length(path) == 0) {
    return(matrix(numeric(0), ncol = 5))
  }
  if (is.matrix(path)) {
    return(matrix(as.numeric(path), nrow = nrow(path), ncol = ncol(path)))
  }
  if (is.list(path)) {
    return(do.call(rbind, lapply(path, as.numeric)))
  }
  matrix(numeric(0), ncol = 5)
}

#' Load blinded-focus fragment JSONs from files, directories, or `.zip` archives.
#'
#' `paths` may mix any of: a single fragment `.json` file, a directory (recursively globbed for
#' `*.json`), or a `.zip` archive (its `*.json` entries are read directly via a `unz()` connection,
#' no extraction to disk). Only objects whose `schema` is in `SCHEMAS` and that carry
#' `slideKey`/`grid`/`gridWidth`/`gridHeight` are kept; anything else (unreadable JSON, unrelated
#' files, wrong schema) is silently skipped.
#'
#' Returns a flat list of fragment lists (each with an added `"_source"` string used only for
#' diagnostics/error messages).
load_fragments <- function(paths) {
  fragments <- list()
  for (p in paths) {
    p <- as.character(p)
    if (dir.exists(p)) {
      json_files <- sort(list.files(p, pattern = "\\.json$", recursive = TRUE, full.names = TRUE))
      for (jf in json_files) {
        frag <- .parse_fragment(jf, jf)
        if (!is.null(frag)) fragments[[length(fragments) + 1]] <- frag
      }
    } else if (file.exists(p) && grepl("\\.zip$", p, ignore.case = TRUE)) {
      entries <- utils::unzip(p, list = TRUE)$Name
      entries <- sort(entries[grepl("\\.json$", entries, ignore.case = TRUE)])
      for (name in entries) {
        con <- unz(p, name)
        txt <- tryCatch(readLines(con, warn = FALSE, encoding = "UTF-8"), error = function(e) NULL)
        close(con)
        if (is.null(txt)) next
        frag <- .parse_fragment(txt, paste0(p, "!", name))
        if (!is.null(frag)) fragments[[length(fragments) + 1]] <- frag
      }
    } else if (file.exists(p)) {
      frag <- .parse_fragment(p, p)
      if (!is.null(frag)) fragments[[length(fragments) + 1]] <- frag
    } else {
      stop(sprintf("input path not found: %s", p), call. = FALSE)
    }
  }
  fragments
}

#' Group fragment lists by `slideKey` -> named list `slideKey -> list(fragment, ...)` (first-seen
#' insertion order of the names, mirroring the Python `group_by_slide` dict-of-lists behaviour).
group_by_slide <- function(fragments) {
  groups <- list()
  for (f in fragments) {
    key <- as.character(f$slideKey)
    if (is.null(groups[[key]])) {
      groups[[key]] <- list(f)
    } else {
      groups[[key]] <- c(groups[[key]], list(f))
    }
  }
  groups
}

#' Filesystem-safe slug for a slideKey/sessionId: ascii alnum + '-', hash-suffixed for uniqueness
#' (so two different keys that collapse to the same readable prefix never collide).
#'
#' Uses a djb2-style rolling hash (not the Python toolkit's sha1) — this is an intentional, benign
#' divergence: the two toolkits are never expected to produce byte-identical *filenames* (only the
#' same file-naming *pattern* and CSV contents/columns), and a hand-rolled SHA1 in R risks silent
#' 32-bit-integer-overflow bugs (R's `bitwAnd`/`bitwShiftL` operate on signed 32-bit `integer`,
#' which overflows to `NA` well before the 0xFFFFFFFF masks a real SHA1 needs). The djb2 hash below
#' is computed entirely in `double` arithmetic (exact up to 2^53), so it can't overflow.
slug <- function(text, maxlen = 40) {
  text <- as.character(text)
  base <- tolower(gsub("^-+|-+$", "", gsub("[^A-Za-z0-9]+", "-", text)))
  base <- if (nchar(base) > 0) substr(base, 1, maxlen) else "x"
  h <- substr(.hash8(text), 1, 8)
  paste0(base, "-", h)
}

#' djb2 rolling hash of a UTF-8 string, rendered as an 8-hex-char string. Pure `double` arithmetic
#' (mod 2^32 after every step, so intermediate values never exceed ~1.4e11, far inside the 2^53
#' double-precision exact-integer range) — deliberately avoids R's 32-bit `integer` bitwise ops,
#' which silently overflow to `NA` for values >= 2^31 (see `slug()`'s docstring for why).
.hash8 <- function(text) {
  bytes <- as.integer(charToRaw(enc2utf8(text)))
  h <- 5381
  for (b in bytes) {
    h <- (h * 33 + b) %% 4294967296
  }
  hex <- ""
  x <- h
  for (i in 1:8) {
    nibble <- x %% 16
    hex <- paste0(sprintf("%x", nibble), hex)
    x <- floor(x / 16)
  }
  hex
}

#' Composite lookup key for a `(slideKey, sessionId)` pair, used by `load_graded`'s named-list
#' lookup and `.nav_accuracy_rows`'s `(slide, sessionId) -> correct` map.  (ASCII unit
#' separator, never expected in a slideKey/sessionId/session label) avoids the collision risk of
#' plain string concatenation (e.g. `"ab"+"c"` vs `"a"+"bc"`).
.decision_key <- function(a, b) {
  paste0(as.character(a), "", as.character(b))
}

#' Load a `sessionId,label` CSV (optional header row) into a named list `sessionId -> label`.
#' Returns an empty named list if `csv_path` is NULL/empty. Out-of-band identity mapping: the
#' fragment data itself stays anonymous (sessionId only); a coordinator supplies this CSV
#' separately to render human-readable output.
load_labels <- function(csv_path) {
  labels <- list()
  if (is.null(csv_path) || !nzchar(csv_path)) {
    return(labels)
  }
  rows <- utils::read.csv(csv_path, header = FALSE, colClasses = "character", stringsAsFactors = FALSE)
  start <- 1
  if (nrow(rows) > 0 && tolower(trimws(rows[1, 1])) %in% c("sessionid", "session_id", "session")) {
    start <- 2
  }
  if (nrow(rows) >= start) {
    for (i in start:nrow(rows)) {
      sid <- trimws(rows[i, 1])
      if (nzchar(sid) && ncol(rows) >= 2) {
        labels[[sid]] <- trimws(rows[i, 2])
      }
    }
  }
  labels
}

#' Load a `slideKey,correctDx` CSV (optional header) into a named list `slideKey -> correctDx`.
#'
#' This is a DISPLAY-ONLY reference answer -- populated beside the reader's own `diagnosis` in
#' `decisions.csv` purely for a human to read side by side. It is never compared against
#' `diagnosis` to derive `correct`; grading is hand-supplied only, via `load_graded`. Returns an
#' empty named list for a NULL/empty `csv_path` (mirrors `load_labels`'s convention exactly).
load_answer_key <- function(csv_path) {
  key <- list()
  if (is.null(csv_path) || !nzchar(csv_path)) {
    return(key)
  }
  rows <- utils::read.csv(csv_path, header = FALSE, colClasses = "character", stringsAsFactors = FALSE)
  start <- 1
  if (nrow(rows) > 0 && tolower(trimws(rows[1, 1])) %in% c("slidekey", "slide_key", "slide")) {
    start <- 2
  }
  if (nrow(rows) >= start) {
    for (i in start:nrow(rows)) {
      sk <- trimws(rows[i, 1])
      if (nzchar(sk) && ncol(rows) >= 2) {
        key[[sk]] <- trimws(rows[i, 2])
      }
    }
  }
  key
}

#' Load a `slideKey,sessionId,correct` CSV (optional header) into a named list keyed by
#' `.decision_key(slideKey, sessionId)` -> `correct` (`1L`/`0L`, parsed case-insensitively from
#' `1`/`0`/`true`/`false`/`yes`/`no`/`correct`/`incorrect`).
#'
#' The join key is the stable `sessionId`, never the human-readable display label -- so a
#' coordinator's grading sheet stays valid even if `--labels` is re-mapped later. Rows with an
#' unparseable `correct` value are skipped (never crash the load). Returns an empty named list for
#' a NULL/empty `csv_path`. This is the ONLY source of `correct` in `decisions.csv` -- it is never
#' auto-derived from comparing `diagnosis` to `load_answer_key`'s answers.
load_graded <- function(csv_path) {
  graded <- list()
  if (is.null(csv_path) || !nzchar(csv_path)) {
    return(graded)
  }
  rows <- utils::read.csv(csv_path, header = FALSE, colClasses = "character", stringsAsFactors = FALSE)
  start <- 1
  if (nrow(rows) > 0 && tolower(trimws(rows[1, 1])) %in% c("slidekey", "slide_key", "slide")) {
    start <- 2
  }
  if (nrow(rows) >= start && ncol(rows) >= 3) {
    for (i in start:nrow(rows)) {
      sk <- trimws(rows[i, 1])
      sid <- trimws(rows[i, 2])
      if (nzchar(sk) && nzchar(sid)) {
        v <- tolower(trimws(rows[i, 3]))
        if (v %in% c("1", "true", "yes", "correct")) {
          graded[[.decision_key(sk, sid)]] <- 1L
        } else if (v %in% c("0", "false", "no", "incorrect")) {
          graded[[.decision_key(sk, sid)]] <- 0L
        }
      }
    }
  }
  graded
}

#' Resolve a sessionId to its human-readable label if `labels` has one, else the sessionId itself.
label_for <- function(sid, labels) {
  lab <- labels[[sid]]
  if (is.null(lab)) sid else lab
}

#' Fallback FeatureCollection for a fragment with no (or a malformed) `annotations` field. Freshly
#' constructed on every call so no caller can mutate a "default" and corrupt it for a later
#' fragment (mirrors `blinded_focus.io._empty_feature_collection` in the Python toolkit).
.empty_feature_collection <- function() {
  list(type = "FeatureCollection", features = list())
}

#' Return a fragment's `annotations` GeoJSON `FeatureCollection` as a list, defaulting to an empty
#' one (see `.empty_feature_collection`) when the field is absent or malformed. Present
#' (additively) from schema/4 onward; schema/1-/3 fragments never carry the field.
#'
#' `fragment` was parsed by `.parse_fragment` with `simplifyVector = TRUE` (needed elsewhere for
#' `grid`/`path` to come back as plain numeric vectors/matrices), which means `fragment$annotations`
#' itself may arrive **partially simplified** in a shape that depends on feature count/consistency
#' (jsonlite may coerce a uniform `features` array into a `data.frame`, `coordinates` into an
#' array, etc. — verified empirically to vary with input shape). Rather than writing
#' shape-detection code for every possible partial-simplification outcome, this round-trips the
#' extracted `annotations` value through `toJSON()` -> `fromJSON(simplifyVector = FALSE)`, which
#' normalizes it to the same fully-nested list-of-lists shape `load_roi_rings` gets directly from a
#' `simplifyVector = FALSE` file parse, regardless of how the TRUE-mode parse happened to simplify
#' it. `digits = 15` is required on the `toJSON()` step — jsonlite's default (`digits = 4`) rounds
#' away precision on non-integer image-px coordinates (e.g. a grid-cell boundary at `y = 187.5`),
#' which would silently corrupt `annotatedAreaPx`/rasterization for exactly those coordinates.
get_annotations <- function(fragment) {
  ann <- fragment$annotations
  if (is.null(ann)) {
    return(.empty_feature_collection())
  }
  raw <- tryCatch(
    jsonlite::fromJSON(jsonlite::toJSON(ann, auto_unbox = TRUE, digits = 15), simplifyVector = FALSE),
    error = function(e) NULL
  )
  if (is.null(raw) || is.null(raw$type) || raw$type != "FeatureCollection" || is.null(raw$features)) {
    return(.empty_feature_collection())
  }
  raw
}

#' Fallback returned by `get_decision` for a fragment with no (or a malformed) `decision` field. A
#' fresh empty list is constructed on every call (not a shared reference), same rationale as
#' `.empty_feature_collection`.
.empty_decision <- function() {
  list()
}

#' Return a fragment's `decision` object (a list with at least a single-string `diagnosis`) or an
#' empty list when the field is absent or malformed -- so schema/1-5 fragments recorded without a
#' decision (or with a corrupt one) degrade to blank decision columns, never a crash. The returned
#' list is passed through as-is, so an optional `promptShownMs` (Tier 3 C5, added 2026-07-23) reads
#' normally via `dec$promptShownMs` when present and is simply absent (`NULL`) for a decision
#' recorded before the recorder gained that field -- the caller degrades that to a blank
#' `decisions.csv` cell, never a crash.
get_decision <- function(fragment) {
  dec <- fragment$decision
  if (!is.null(dec) && is.list(dec) && !is.null(dec$diagnosis) &&
    is.character(dec$diagnosis) && length(dec$diagnosis) == 1) {
    return(dec)
  }
  .empty_decision()
}

# ---------------------------------------------------------------------------
# Grid helpers
# ---------------------------------------------------------------------------

#' `grid / max(grid)`. An all-zero (or empty) grid stays all-zero.
normalise_max <- function(grid) {
  g <- as.numeric(grid)
  m <- if (length(g)) max(g) else 0
  if (m > 0) g / m else rep(0, length(g))
}

#' `grid / sum(grid)`. An all-zero (or empty) grid stays all-zero.
normalise_sum <- function(grid) {
  g <- as.numeric(grid)
  s <- sum(g)
  if (s > 0) g / s else rep(0, length(g))
}

#' Nearest-neighbour resample a row-major `(gh, gw)` grid to `(th, tw)`. Returns a flat
#' `(tw*th,)` row-major vector. Same algorithm as `blinded_focus.metrics.resample_nn` in the
#' Python toolkit.
#'
#' **Zero-size-grid guard (polish final-review Finding 2, defense-in-depth):** the root cause -- a
#' schema-valid fragment recording `gridWidth`/`gridHeight` of `0` -- is now rejected at load by
#' `.is_valid_fragment`, so `analyze()` never calls this function with a degenerate `(gw, gh)` or
#' `(tw, th)`. This guard exists purely for a DIRECT caller that bypasses `load_fragments` (e.g. a
#' script or test constructing a grid by hand): without it, `matrix(..., nrow=gh, ncol=gw)` at
#' `gw<=0`/`gh<=0` errors ("invalid 'nrow' value"), or (if `gw`/`gh` are valid but
#' `tw<=0`/`th<=0`) the `0:(th-1L)`/`0:(tw-1L)` index sequences count DOWNWARD from 0 (R's `:`
#' operator has no empty-range form), producing a wrongly-shaped/garbage result instead of the
#' well-defined "nothing to resample" callers expect. Returns an all-zero vector of the
#' (clamped-non-negative) target size instead. Matches the Python port's `resample_nn` guard
#' exactly.
resample_nn <- function(grid, gw, gh, tw, th) {
  gw <- as.integer(gw); gh <- as.integer(gh); tw <- as.integer(tw); th <- as.integer(th)
  if (gw <= 0 || gh <= 0 || tw <= 0 || th <= 0) {
    return(rep(0.0, max(tw, 0L) * max(th, 0L)))
  }
  g <- matrix(as.numeric(grid), nrow = gh, ncol = gw, byrow = TRUE)
  if (gw == tw && gh == th) {
    return(as.numeric(t(g)))
  }
  ys <- pmin(gh - 1L, (0:(th - 1L) * gh) %/% th) + 1L
  xs <- pmin(gw - 1L, (0:(tw - 1L) * gw) %/% tw) + 1L
  out <- g[ys, xs, drop = FALSE]
  as.numeric(t(out))
}

#' `count(g>0) / length(g)`.
coverage <- function(grid) {
  g <- as.numeric(grid)
  if (length(g) == 0) {
    return(0.0)
  }
  sum(g > 0) / length(g)
}

#' `-sum(p*log2(p+eps))`, `p = g/sum(g)`. 0.0 for an all-zero grid.
entropy <- function(grid) {
  g <- as.numeric(grid)
  s <- sum(g)
  p <- if (s > 0) g / s else rep(0, length(g))
  -sum(p * log2(p + EPS))
}

#' Intensity-weighted centroid; each coord normalised by gw/gh -> `c(x, y)` in `[0, 1]`. Returns
#' `c(0.5, 0.5)` (grid center) for an all-zero grid. Operates on the flat row-major vector directly
#' (`idx %/% gw` = row, `idx %% gw` = col), equivalent to the Python reshape-then-indices approach.
center_of_mass <- function(grid, gw, gh) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  g <- as.numeric(grid)
  total <- sum(g)
  if (total <= 0) {
    return(c(0.5, 0.5))
  }
  idx <- 0:(length(g) - 1)
  row <- idx %/% gw
  col <- idx %% gw
  cx <- sum(col * g) / total
  cy <- sum(row * g) / total
  c(cx / gw, cy / gh)
}

#' Top-`n` `(row, col, value)` cells by dwell value, descending. Wired into `hotspots_<slug>.csv`
#' (Tier 1 A5) -- mirrors `blinded_focus.metrics.top_hotspots` in Python exactly, including its
#' tie-break: value descending, then flat row-major index ascending
#' (`order(-g, seq_along(g))`, matching Python's `sorted(key=lambda idx: (-flat[idx], idx))`) --
#' plain `order(g, decreasing=TRUE)` does not guarantee this ascending-index tie-break, so two
#' cells sharing the same dwell value (common on sparse/all-zero grids) could otherwise come back
#' in a different order than the Python port, breaking the CSV's exact-match parity.
top_hotspots <- function(grid, gw, gh, n = 5) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  g <- as.numeric(grid)
  ord <- order(-g, seq_along(g))[seq_len(min(n, length(g)))]
  lapply(ord, function(idx0) {
    idx <- idx0 - 1L
    list(row = idx %/% gw, col = idx %% gw, value = g[idx0])
  })
}

#' Number of 4-connected regions with value `> thresh_frac * max(grid)`. 0 for an all-zero grid.
#' Used as `nHotspots` in `metrics.csv` (a simple, reproducible "distinct attended regions" count).
#' Implemented as a manual BFS flood-fill (4-connectivity, matching `scipy.ndimage.label`'s default
#' cross-shaped structuring element) since base R has no connected-components primitive.
count_hotspots <- function(grid, gw, gh, thresh_frac = 0.5) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  g <- matrix(as.numeric(grid), nrow = gh, ncol = gw, byrow = TRUE)
  if (length(g) == 0) {
    return(0L)
  }
  m <- max(g)
  if (m <= 0) {
    return(0L)
  }
  mask <- g > (thresh_frac * m)
  visited <- matrix(FALSE, nrow = gh, ncol = gw)
  n_components <- 0L
  for (r in seq_len(gh)) {
    for (col in seq_len(gw)) {
      if (mask[r, col] && !visited[r, col]) {
        n_components <- n_components + 1L
        stack <- list(c(r, col))
        visited[r, col] <- TRUE
        while (length(stack) > 0) {
          cur <- stack[[length(stack)]]
          stack[[length(stack)]] <- NULL
          cr <- cur[1]; cc <- cur[2]
          neighbours <- list(c(cr - 1L, cc), c(cr + 1L, cc), c(cr, cc - 1L), c(cr, cc + 1L))
          for (nb in neighbours) {
            nr <- nb[1]; nc <- nb[2]
            if (nr >= 1 && nr <= gh && nc >= 1 && nc <= gw && mask[nr, nc] && !visited[nr, nc]) {
              visited[nr, nc] <- TRUE
              stack[[length(stack) + 1]] <- c(nr, nc)
            }
          }
        }
      }
    }
  }
  n_components
}

# ---------------------------------------------------------------------------
# Spatial similarity (equal-shape vectors; resample_nn first for cross-fragment use)
# ---------------------------------------------------------------------------

#' Population standard deviation (`ddof=0`, i.e. divide by n not n-1) — matches numpy's
#' `array.std()` default, which several formulas below rely on for exact-value parity (not just a
#' zero-check) with the Python toolkit.
.pop_sd <- function(x) {
  n <- length(x)
  if (n <= 1) {
    return(0.0)
  }
  mu <- mean(x)
  sqrt(mean((x - mu)^2))
}

#' Pearson correlation coefficient of the two flattened grids. Returns 0.0 if either grid is
#' constant (correlation undefined). `cor()` is scale-invariant to the n vs n-1 std convention, so
#' it agrees with numpy's `corrcoef` exactly despite R's `sd()` using n-1.
cc <- function(a, b) {
  a <- as.numeric(a); b <- as.numeric(b)
  if (length(a) < 2 || .pop_sd(a) == 0 || .pop_sd(b) == 0) {
    return(0.0)
  }
  as.numeric(stats::cor(a, b))
}

#' Histogram intersection: `sum(min(a/sum(a), b/sum(b)))`.
sim <- function(a, b) {
  pa <- normalise_sum(a)
  pb <- normalise_sum(b)
  sum(pmin(pa, pb))
}

#' `sum(P*log((P+eps)/(Q+eps)))`, `P=ref/sum(ref)`, `Q=pred/sum(pred)` (KL divergence, `ref` as
#' the "true" distribution).
kld <- function(ref, pred) {
  p <- normalise_sum(ref)
  q <- normalise_sum(pred)
  sum(p * log((p + EPS) / (q + EPS)))
}

#' Elementwise zero-safe KL contribution `p_i * log2(p_i / q_i)`, with the standard
#' information-theory convention `0 * log(0/x) = 0` -- a cell where `p_i` is exactly 0 contributes
#' 0 regardless of `q_i` (never evaluates `log2(0/q_i)`). This is a DIFFERENT convention from
#' `kld`'s additive-EPS smoothing (which would make even a self-comparison a tiny nonzero number)
#' -- `js_divergence` needs the zero-safe form specifically so a cell where ONE grid is exactly 0
#' doesn't force a nonzero floor onto the divergence, and so the diagonal self-pair is exactly 0.0.
#' Base-2 (`log2`), pinned to match the Python toolkit's `_kl_terms` exactly.
#'
#' Contract (relied on by `js_divergence`, not re-checked here): `q_i` is never exactly 0 at a cell
#' where `p_i` is nonzero -- true when `q = (p + other) / 2`, since then `q_i >= p_i / 2 > 0`
#' whenever `p_i > 0`.
.kl_terms <- function(p, q) {
  p <- as.numeric(p)
  q <- as.numeric(q)
  out <- rep(0.0, length(p))
  nz <- p > 0
  out[nz] <- p[nz] * log2(p[nz] / q[nz])
  out
}

#' Jensen-Shannon divergence (base-2), symmetric and bounded in `[0, 1]`.
#'
#' `P = normalise_sum(a)`, `Q = normalise_sum(b)`, `M = (P + Q) / 2`;
#' `JSD = 0.5*sum(.kl_terms(P, M)) + 0.5*sum(.kl_terms(Q, M))`. Deliberately NOT built on `kld`
#' (whose additive-EPS convention is the wrong tool here -- see `.kl_terms`'s docs for why). `M`'s
#' zero-safety contract is satisfied by construction: wherever `P`/`Q` is nonzero, `M` is too, so
#' `.kl_terms` never divides by zero.
#'
#' Exactly `0.0` for two identical grids -- including the both-all-zero case (`P=Q=` all zero, so
#' both `.kl_terms` sums are 0 by the zero-safe convention, no special-casing needed -- this is the
#' pinned, deliberate convention for `compare_<slug>.csv`'s diagonal rows and for two grids that
#' are each entirely empty).
js_divergence <- function(a, b) {
  p <- normalise_sum(a)
  q <- normalise_sum(b)
  mgrid <- (p + q) / 2.0
  kl_pm <- sum(.kl_terms(p, mgrid))
  kl_qm <- sum(.kl_terms(q, mgrid))
  0.5 * kl_pm + 0.5 * kl_qm
}

#' Normalized Scanpath Saliency: mean, over `mask==1` cells, of the z-scored `salmap`
#' (`(salmap - mean(salmap)) / population_sd(salmap)`). `mask` is a binary attended-region vector
#' (same length as `salmap`). Returns 0.0 if `salmap` is constant or the mask has no positive
#' cells (undefined otherwise).
nss <- function(salmap, mask) {
  s <- as.numeric(salmap)
  msk <- as.logical(mask)
  sdv <- .pop_sd(s)
  if (sdv == 0 || !any(msk)) {
    return(0.0)
  }
  z <- (s - mean(s)) / sdv
  mean(z[msk])
}

#' Standard Judd ROC-AUC: `mask==1` cells are the positive (fixated) class, all other cells are
#' negatives, thresholds swept over `salmap` values. Computed via the Mann-Whitney U / rank-sum
#' identity (`stats::rank` default ties.method="average" matches `scipy.stats.rankdata`'s
#' default): `AUC = (sum(rank(pos)) - n1*(n1+1)/2) / (n1*n0)`. Returns `NaN` if the mask is all-0
#' or all-1 (AUC undefined without both classes).
auc_judd <- function(salmap, mask) {
  s <- as.numeric(salmap)
  msk <- as.logical(mask)
  pos <- s[msk]
  neg <- s[!msk]
  n1 <- length(pos); n0 <- length(neg)
  if (n1 == 0 || n0 == 0) {
    return(NaN)
  }
  ranks <- rank(c(pos, neg), ties.method = "average")
  rank_pos_sum <- sum(ranks[seq_len(n1)])
  (rank_pos_sum - n1 * (n1 + 1) / 2.0) / (n1 * n0)
}

#' `|{a>thresh*max(a)} INTERSECT {b>thresh*max(b)}| / |union|`. 0.0 if the union is empty.
iou <- function(a, b, thresh = 0.1) {
  a <- as.numeric(a); b <- as.numeric(b)
  ma <- if (length(a)) max(a) else 0; mb <- if (length(b)) max(b) else 0
  am <- if (ma > 0) a > (thresh * ma) else rep(FALSE, length(a))
  bm <- if (mb > 0) b > (thresh * mb) else rep(FALSE, length(b))
  union_n <- sum(am | bm)
  if (union_n == 0) {
    return(0.0)
  }
  inter_n <- sum(am & bm)
  inter_n / union_n
}

#' Tier 3 C6: boolean mask of the top `frac` (fraction, e.g. `0.10` = top 10%) highest-dwell cells
#' in `grid`: `k = ceiling(frac * n)` (at least 1 for a non-empty grid), `cutoff` = the value of
#' the `k`-th largest cell (descending order), mask = `grid >= cutoff`.
#'
#' Tie-inclusive **by construction**: this returns the full `>= cutoff` set, not a fixed-size
#' top-`k` slice, so every cell sharing the exact cutoff value is included even when that pushes
#' the mask's True-count above `k` -- pinned, deterministic, and identical across languages
#' regardless of a sort's tie-break, since the final mask only depends on the cutoff VALUE, not on
#' which index a sort happened to rank `k`-th among ties.
#'
#' **Strictly-positive guard (final-review fix):** for a sparse/focused grid where fewer than `k`
#' cells have nonzero dwell (the ORDINARY case for a focused reader on a fine grid), `cutoff` is
#' `0.0` -- the plain `grid >= cutoff` mask would then select EVERY cell (including every
#' untouched one), silently degrading "top-K" to "the whole grid" and making downstream recall
#' report `1.0` for a reader who never touched the reference region at all. The extra `& (grid >
#' 0)` restricts the tie-inclusive `>=cutoff` set to cells the reader actually dwelled in -- a
#' no-op whenever `cutoff > 0` (`grid >= cutoff` already implies `grid > 0`), so every
#' non-degenerate/dense case is numerically unchanged.
#'
#' Returns an all-`FALSE` (length-0) mask for an empty (0-length) grid (no cutoff to compute).
top_k_frac_mask <- function(grid, frac = PRECISION_K_FRAC) {
  g <- as.numeric(grid)
  n <- length(g)
  if (n == 0) {
    return(logical(0))
  }
  k <- max(1L, as.integer(ceiling(frac * n)))
  cutoff <- sort(g, decreasing = TRUE)[k]
  (g >= cutoff) & (g > 0)
}

#' Tier 3 C6: `precisionAtTopK`/`recall` of a reader's top-`frac` highest-dwell cells
#' (`top_k_frac_mask`) against a reference ROI boolean mask (`roi_mask`, same length as `dwell`) --
#' separates "missed target" (low recall) from "wasted attention" (low precision).
#'
#' `precisionAtTopK = |topK INTERSECT roi_mask| / |topK|` -- fraction of the reader's own
#' most-attended cells that fall inside the reference region.
#' `recall = |topK INTERSECT roi_mask| / |roi_mask|` -- fraction of the reference region the
#' reader's top-attended cells cover.
#'
#' Returns `list(precision = NaN, recall = NaN)` (blank in the CSV) if the ROI mask is empty (no
#' reference region defined at all) OR the top-K set is empty (degenerate zero-size grid only --
#' `top_k_frac_mask` otherwise always returns >=1 cell for a non-empty grid).
precision_recall_at_topk <- function(dwell, roi_mask, frac = PRECISION_K_FRAC) {
  topk <- top_k_frac_mask(dwell, frac)
  roi <- as.logical(roi_mask)
  topk_n <- sum(topk)
  roi_n <- sum(roi)
  if (topk_n == 0 || roi_n == 0) {
    return(list(precision = NaN, recall = NaN))
  }
  inter <- sum(topk & roi)
  list(precision = inter / topk_n, recall = inter / roi_n)
}

# ---------------------------------------------------------------------------
# Scanpath (schema/3 "path" only)
# ---------------------------------------------------------------------------

#' Map each path point `[t, cx, cy, w, h]` (image px) to a grid-cell index `row*gw + col`
#' (`col = floor(cx/img_w*gw)`, `row = floor(cy/img_h*gh)`, clamped to valid range, 0-based), then
#' run-length-dedup consecutive repeats (so dwelling in one cell across many samples collapses to
#' a single visit in the sequence). Returns an integer vector (possibly empty).
visited_sequence <- function(path, gw, gh, img_w, img_h) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  img_w <- if (!is.null(img_w) && length(img_w) && img_w != 0) as.numeric(img_w) else 1.0
  img_h <- if (!is.null(img_h) && length(img_h) && img_h != 0) as.numeric(img_h) else 1.0
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n == 0) {
    return(integer(0))
  }
  seq_idx <- integer(n)
  for (i in seq_len(n)) {
    cx <- as.numeric(pm[i, 2]); cy <- as.numeric(pm[i, 3])
    col <- as.integer(floor(cx / img_w * gw))
    row <- as.integer(floor(cy / img_h * gh))
    col <- min(max(col, 0L), gw - 1L)
    row <- min(max(row, 0L), gh - 1L)
    seq_idx[i] <- row * gw + col
  }
  deduped <- integer(0)
  for (idx in seq_idx) {
    if (length(deduped) == 0 || deduped[length(deduped)] != idx) {
      deduped <- c(deduped, idx)
    }
  }
  deduped
}

#' Standard Levenshtein DP edit distance over arbitrary-token integer sequences (not chars) — a
#' manual dynamic-program (rather than `utils::adist`, which operates character-wise on strings
#' and would not reproduce token-level edit distance for multi-digit cell indices) guarantees
#' exact numeric parity with the Python `_edit_distance` implementation.
.edit_distance <- function(a, b) {
  n <- length(a); m <- length(b)
  if (n == 0) {
    return(m)
  }
  if (m == 0) {
    return(n)
  }
  prev <- 0:m
  for (i in seq_len(n)) {
    cur <- integer(m + 1)
    cur[1] <- i
    ai <- a[i]
    for (j in seq_len(m)) {
      cost <- if (ai == b[j]) 0L else 1L
      cur[j + 1] <- min(prev[j + 1] + 1L, cur[j] + 1L, prev[j] + cost)
    }
    prev <- cur
  }
  prev[m + 1]
}

#' `1 - edit_distance(seqA,seqB) / max(len(seqA), len(seqB), 1)`. Tokens are grid-cell indices
#' (from `visited_sequence`), compared for exact equality (not string chars).
levenshtein_sim <- function(seq_a, seq_b) {
  seq_a <- as.integer(seq_a); seq_b <- as.integer(seq_b)
  d <- .edit_distance(seq_a, seq_b)
  1.0 - d / max(length(seq_a), length(seq_b), 1)
}

#' Shannon entropy (base-2) of the normalized consecutive-transition distribution. 0.0 if the
#' sequence has fewer than 2 elements (no transitions).
transition_entropy <- function(seq) {
  seq <- as.integer(seq)
  n <- length(seq)
  if (n < 2) {
    return(0.0)
  }
  trans <- paste(seq[1:(n - 1)], seq[2:n], sep = "->")
  counts <- table(trans)
  total <- sum(counts)
  p <- as.numeric(counts) / total
  -sum(p * log2(p + EPS))
}

#' Top-`top_n` `(fromCell, toCell, count)` directed transitions -- consecutive-transition pairs
#' `(seq[i], seq[i+1])` counted and ranked by count descending, ties broken by `(fromCell,
#' toCell)` ascending -- **deterministic** across languages. Mirrors
#' `blinded_focus.metrics.top_transitions` in the Python toolkit exactly: `table()`'s and
#' `Counter.items()`'s iteration order over equal-count entries are not guaranteed to agree
#' between R and Python, so an unordered tie-break would let the two toolkits'
#' `transitions_<slug>.csv` (Tier 1 A5) disagree on which transitions make the top-`top_n` cut
#' whenever counts tie -- common on short scanpaths. Inlines the count directly (via `table()`,
#' the same idiom `transition_entropy` already uses above) rather than exposing a separate
#' `transition_matrix` helper, since nothing else in this file needs the raw unordered counts.
#' `list()` for a sequence with fewer than 2 elements (no transitions).
top_transitions <- function(seq, top_n = 15) {
  seq <- as.integer(seq)
  n <- length(seq)
  if (n < 2) {
    return(list())
  }
  from_seq <- seq[1:(n - 1)]
  to_seq <- seq[2:n]
  key <- paste(from_seq, to_seq, sep = "->")
  tab <- table(key)
  counts <- as.integer(tab)
  parts <- strsplit(names(tab), "->", fixed = TRUE)
  from_i <- as.integer(vapply(parts, `[`, character(1), 1))
  to_i <- as.integer(vapply(parts, `[`, character(1), 2))
  ord <- order(-counts, from_i, to_i)
  ord <- ord[seq_len(min(top_n, length(ord)))]
  lapply(ord, function(i) list(fromCell = from_i[i], toCell = to_i[i], count = counts[i]))
}

#' Sum of consecutive-center Euclidean distances, in image px (`cx`, `cy` of each point).
scanpath_length_px <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  total <- 0.0
  for (i in 2:n) {
    x0 <- pm[i - 1, 2]; y0 <- pm[i - 1, 3]
    x1 <- pm[i, 2]; y1 <- pm[i, 3]
    total <- total + sqrt((x1 - x0)^2 + (y1 - y0)^2)
  }
  total
}

#' Count of steps entering a cell already seen earlier in the (run-length-deduped) sequence.
n_revisits <- function(seq) {
  seen <- character(0)
  revisits <- 0L
  for (idx in as.character(seq)) {
    if (idx %in% seen) {
      revisits <- revisits + 1L
    }
    seen <- c(seen, idx)
  }
  revisits
}

#' Tier 3 C6: +1 per cell ENTRY in a run-length-deduped visited-cell sequence (see
#' `visited_sequence`) -- NOT per raw path tick, so a long dwell in one cell counts as a single
#' entry, not one count per sample. `seq` is 0-based (as returned by `visited_sequence`; offset by
#' +1 for R's 1-based vector indexing here). Returns a flat `(gw*gh,)` numeric vector (every value
#' is a whole number of dwell-run entries, matching the Python toolkit's float-dtype convention).
#'
#' **Zero-size-grid guard (final-review fix):** `gw<=0` or `gh<=0` (a schema-valid fragment with
#' `gridWidth`/`gridHeight` of `0`, `grid=list()`) returns an EMPTY `numeric(0)` without touching
#' `seq` at all -- mirrors `visit_count_jaccard`'s own documented "blank for a degenerate zero-size
#' grid" convention. (Unlike the Python port, R does not actually crash on this input without the
#' guard -- `seq`'s entries all clamp to index `-1` via `visited_sequence`, so `counts[idx + 1L]`
#' becomes `counts[0]`, and assigning to R's index `0` is a documented silent no-op, not an error
#' -- but the guard is added anyway for explicitness and Python<->R parity.)
visit_count_grid <- function(seq, gw, gh) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  if (gw <= 0 || gh <= 0) {
    return(numeric(0))
  }
  counts <- rep(0.0, gw * gh)
  for (idx in seq) {
    counts[idx + 1L] <- counts[idx + 1L] + 1.0
  }
  counts
}

#' Tier 3 C6: Jaccard similarity between the top-`top_n` DWELL-TIME hotspot cells and the
#' top-`top_n` VISIT-COUNT hotspot cells (`visit_count_grid`) at the same `(gw, gh)` grid
#' resolution -- disagreement between "where the reader lingered longest" and "which cells the
#' reader entered most often" is a navigation-style signal (e.g. one long dwell vs many brief
#' revisits). Both hotspot sets reuse `top_hotspots` (deterministic tie-break) so flat cell indices
#' align 1:1 between the two grids.
#'
#' `NaN` (blank) if either hotspot set is empty (only possible for a degenerate zero-size grid --
#' `top_hotspots` otherwise always returns >=1 cell for any grid with >=1 cell).
visit_count_jaccard <- function(dwell_grid, gw, gh, seq, top_n = 5) {
  gw_i <- as.integer(gw); gh_i <- as.integer(gh)
  visit_grid <- visit_count_grid(seq, gw_i, gh_i)
  dwell_top <- top_hotspots(dwell_grid, gw_i, gh_i, top_n)
  visit_top <- top_hotspots(visit_grid, gw_i, gh_i, top_n)
  set_a <- vapply(dwell_top, function(h) h$row * gw_i + h$col, numeric(1))
  set_b <- vapply(visit_top, function(h) h$row * gw_i + h$col, numeric(1))
  if (length(set_a) == 0 || length(set_b) == 0) {
    return(NaN)
  }
  inter <- length(intersect(set_a, set_b))
  uni <- length(union(set_a, set_b))
  if (uni == 0) {
    return(NaN)
  }
  inter / uni
}

# ---------------------------------------------------------------------------
# Scanpath -> fine dwell raster (schema/3, /4; independent of the recorded grid resolution)
# Phase 1 (navigation-research upgrade). Mirrors `blinded_focus.metrics`'s equivalent section in
# the Python toolkit function-for-function; see each Python docstring for the exact edge-case
# behavior (blank-vs-0.0, ddof, quantile method) this R port reproduces.
# ---------------------------------------------------------------------------

#' Per-step (`path[i] -> path[i+1]`) time delta in ms, length `nrow(as_path_matrix(path))-1`. A
#' step with non-positive `dt` (out-of-order/duplicate timestamps) is clamped to `0.0` rather than
#' going negative. `numeric(0)` if `path` has fewer than 2 points. R equivalent of the Python
#' docstring's own note: `pmax(0, diff(t))`.
step_durations_ms <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(numeric(0))
  }
  pmax(diff(pm[, 1]), 0)
}

#' Tier 2 B1: logical vector (length `nrow(as_path_matrix(path))-1`), TRUE iff step i's dt (see
#' `step_durations_ms`) exceeds `IDLE_GAP_MS` -- a "step-away" gap (Ghezloo's >60s-frozen-viewport
#' idle-time-exclusion rule) the user was not actively looking at the slide during. Every rate/
#' weight metric documented as "idle-excluded" (`scanning_rate_px_per_min`,
#' `drilling_rate_per_min`, `path_velocity_px_per_sec`, `search_focus_ratio`,
#' `raster_from_path`'s step weight, `avg_zoom_log2_w`, `drilling_rate_octaves_per_min`, and the
#' `magbands_<slug>.csv` `bandTimeMs` aggregation in `analyze()`) drops steps where this is TRUE
#' entirely -- not just caps their contribution. `logical(0)` if the path has fewer than 2 points.
idle_step_mask <- function(path) {
  dts <- step_durations_ms(path)
  dts > IDLE_GAP_MS
}

#' Tier 2 B1: sum of step dt (see `step_durations_ms`) over steps flagged idle by
#' `idle_step_mask` -- the `idleMs` `metrics.csv` transparency column. `0.0` if the path has fewer
#' than 2 points (no steps) or no step is idle -- a session with no >60s gap therefore always
#' reports `idleMs == 0.0`, which is what keeps every idle-excluded rate metric below numerically
#' identical to its pre-B1 value for such sessions (the exclusion set is empty).
idle_ms <- function(path) {
  dts <- step_durations_ms(path)
  idle <- idle_step_mask(path)
  if (length(dts) == 0) {
    return(0.0)
  }
  sum(dts[idle])
}

#' Tier 2 B1: wall-clock span (`tRel_last - tRel_first`) minus `idle_ms` -- the `activeSpanMs`
#' `metrics.csv` transparency column, and the denominator every idle-excluded rate metric below
#' (`scanning_rate_px_per_min`, `drilling_rate_per_min`, `drilling_rate_octaves_per_min`) divides by
#' (in minutes) in place of the old total-duration denominator. `0.0` if the path has fewer than 2
#' points (no span at all) -- matches the file's existing "0.0 for an insufficient path" convention
#' for administrative/passthrough-style fields (as opposed to the NaN-blank convention used for
#' genuinely undefined statistics like `avg_zoom_log2_w`).
active_span_ms <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  span <- pm[n, 1] - pm[1, 1]
  span - idle_ms(path)
}

#' Rebuild a `gh x gw` dwell-ms grid (flat, row-major) directly from the scanpath, independent of
#' the recorded `grid` resolution. For each step, `dt` (see `step_durations_ms`) is attributed to
#' the viewport rectangle of point i (`cx +/- w/2, cy +/- h/2`), clamped to `[0,img_w] x
#' [0,img_h]`, spread evenly across the covered cells (`floor` lower bound, `ceiling(...)-1` upper
#' bound — not `floor` — so a rect edge exactly on a cell boundary doesn't spuriously include the
#' next cell). If the clamped rect collapses (viewport entirely off-image), the whole `dt` lands on
#' the single cell containing the clamped center. Steps with `dt<=0` contribute nothing.
#' `step_mask`, if given, is a logical vector of length `nrow(pm)-1` (steps where it is FALSE are
#' skipped — used by the magnification-band split to reuse this same raster math per band).
#'
#' **Tier 2 B1:** a step flagged idle by `idle_step_mask` (dt > `IDLE_GAP_MS`) is *always* skipped
#' as well, regardless of `step_mask` -- the user was not looking at that viewport, so it should
#' contribute no dwell-weight to the raster at any resolution. For a path with no idle step
#' (`idle_step_mask` all FALSE) this is a no-op, so the raster is numerically identical to the
#' pre-B1 behavior.
#'
#' Returns `NULL` for a 0/1-point path (a `dt` requires two points). Exact port of
#' `blinded_focus.metrics.raster_from_path`.
#'
#' **Zero-size-grid guard (polish final-review Finding 3, defense-in-depth):** also returns `NULL`
#' for `gw<=0`/`gh<=0`. The root cause -- a schema-valid fragment recording
#' `gridWidth`/`gridHeight` of `0` -- is now rejected at load by `.is_valid_fragment`, so
#' `analyze()` never reaches this function with a degenerate grid; this guard is defense-in-depth
#' for a direct caller. Without it, `matrix(0.0, nrow=gh, ncol=gw)` at `gw==0`/`gh==0` is a
#' genuinely empty matrix, and every cell-index clamp below (`min(max(...), gw-1L)`, `gw-1L==-1L`)
#' resolves to `-1L`; R's `grid[row+1L, col+1L]` with `col+1L==0L` is a documented silent no-op
#' rather than an error (unlike the Python port's `IndexError` on the same input), so this guard is
#' about Python<->R PARITY of the "nothing measurable" contract, not preventing an R crash -- same
#' rationale as `mouse_raster_from_path`'s existing zero-size-grid guard above. Matches the Python
#' port's `raster_from_path` exactly (callers already null-check this function's return, e.g. the
#' magband-split export's `if (is.null(raster_b)) next`).
raster_from_path <- function(path, img_w, img_h, gw, gh, step_mask = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(NULL)
  }
  gw <- as.integer(gw); gh <- as.integer(gh)
  if (gw <= 0 || gh <= 0) {
    return(NULL)
  }
  img_w <- if (!is.null(img_w) && length(img_w) && img_w != 0) as.numeric(img_w) else 1.0
  img_h <- if (!is.null(img_h) && length(img_h) && img_h != 0) as.numeric(img_h) else 1.0
  dts <- step_durations_ms(path)
  idle <- idle_step_mask(path)
  grid <- matrix(0.0, nrow = gh, ncol = gw)
  for (i in seq_len(n - 1)) {
    dt <- dts[i]
    if (dt <= 0) next
    if (idle[i]) next
    if (!is.null(step_mask) && !step_mask[i]) next
    cx <- pm[i, 2]; cy <- pm[i, 3]
    w <- if (pm[i, 4] > 0) pm[i, 4] else 1.0
    h <- if (pm[i, 5] > 0) pm[i, 5] else 1.0
    x0 <- max(0.0, cx - w / 2.0); x1 <- min(img_w, cx + w / 2.0)
    y0 <- max(0.0, cy - h / 2.0); y1 <- min(img_h, cy + h / 2.0)
    if (x1 <= x0 || y1 <= y0) {
      # Viewport rect entirely outside the image after clamping -- fall back to the single cell
      # containing the (clamped) center point rather than dropping the dt.
      ccx <- min(max(cx, 0.0), img_w - EPS)
      ccy <- min(max(cy, 0.0), img_h - EPS)
      col <- min(max(as.integer(floor(ccx / img_w * gw)), 0L), gw - 1L)
      row <- min(max(as.integer(floor(ccy / img_h * gh)), 0L), gh - 1L)
      grid[row + 1L, col + 1L] <- grid[row + 1L, col + 1L] + dt
      next
    }
    col0 <- min(max(as.integer(floor(x0 / img_w * gw)), 0L), gw - 1L)
    col1 <- min(max(as.integer(ceiling(x1 / img_w * gw)) - 1L, 0L), gw - 1L)
    row0 <- min(max(as.integer(floor(y0 / img_h * gh)), 0L), gh - 1L)
    row1 <- min(max(as.integer(ceiling(y1 / img_h * gh)) - 1L, 0L), gh - 1L)
    if (col1 < col0) col1 <- col0
    if (row1 < row0) row1 <- row0
    n_cells <- (col1 - col0 + 1L) * (row1 - row0 + 1L)
    grid[(row0 + 1L):(row1 + 1L), (col0 + 1L):(col1 + 1L)] <-
      grid[(row0 + 1L):(row1 + 1L), (col0 + 1L):(col1 + 1L)] + dt / n_cells
  }
  as.numeric(t(grid))
}

# ---------------------------------------------------------------------------
# Zoom / magnification (schema/4 dsMilli+baseMagnification; schema/3 w-proxy fallback)
# ---------------------------------------------------------------------------

#' Magnification (or a zoom proxy when unavailable) for one scanpath point vector (`point`, one row
#' of `as_path_matrix`'s output: length 5 = schema/3, length 6 = schema/4). Fallback order
#' (higher = more zoomed in, always) -- see `blinded_focus.metrics.point_zoom`'s docstring for the
#' full rationale:
#'
#' 1. `base_mag` known AND point has a 6th element (`dsMilli`): `base_mag / (dsMilli/1000)`.
#' 2. Point has `dsMilli` but `base_mag` is NULL/NA: `1000/dsMilli` (== `1/downsample`).
#' 3. Point has no `dsMilli` (5-element): width-proxy `img_w/w`.
#'
#' `dsMilli<=0`/`w<=0` are treated defensively as full-resolution/1px respectively.
#'
#' **Non-numeric `base_mag` guard (polish final-review Finding 1, completing the fix):** a
#' fragment-level `baseMagnification` that is present but not numeric (e.g. the string
#' `"unknown"` -- schema-valid, since the field is typed loosely) degrades to branch 2 (the
#' `base_mag`-unknown zoom level), identical to `base_mag=NULL`, rather than erroring. Before this
#' guard, `is.na(base_mag)` ran on the PRE-conversion value: `is.na("unknown")` is `FALSE` (it's a
#' valid non-NA string), so the check fell through to `as.numeric(base_mag) > 0`, which coerces to
#' `NA` (with a warning) -- and `if (NA)` is a fatal R error ("missing value where TRUE/FALSE
#' needed"). Since every one of `avg_zoom`/`zoom_variance`/`zoom_range`/
#' `magnification_percentage`/`scanning_rate_px_per_min`/`drilling_rate_per_min`/
#' `avg_zoom_log2_w`/`drilling_rate_octaves_per_min` calls this per scanpath point, this was
#' reachable on the very first `dsMilli`-carrying path point (`row$avgZoom <- avg_zoom(path,
#' base_mag, img_w)`, well before the `magnificationSource` assignment separately guarded) --
#' aborting the whole batch the instant any real schema/4+ session recorded a non-numeric
#' `baseMagnification`. Fixed by coercing FIRST (`suppressWarnings(as.numeric(base_mag))`), then
#' checking `is.na()` on the coerced value -- mirrors `true_magnification`'s fix below exactly.
point_zoom <- function(point, base_mag = NULL, img_w = NULL) {
  has_ds <- length(point) >= 6
  if (has_ds) {
    ds_milli <- as.numeric(point[6])
    if (is.na(ds_milli) || ds_milli <= 0) ds_milli <- 1000.0
    bm <- if (!is.null(base_mag)) suppressWarnings(as.numeric(base_mag)) else NA_real_
    if (!is.na(bm) && bm > 0) {
      return(bm / (ds_milli / 1000.0))
    }
    return(1000.0 / ds_milli)
  }
  w <- as.numeric(point[4])
  if (is.na(w) || w <= 0) w <- 1.0
  iw <- if (!is.null(img_w) && length(img_w) && !is.na(img_w) && img_w != 0) as.numeric(img_w) else 1.0
  iw / w
}

#' `point_zoom` for every point in `path`, as a numeric vector (`numeric(0)` if `path` is
#' empty/NULL).
.zoom_series <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n == 0) {
    return(numeric(0))
  }
  vapply(seq_len(n), function(i) point_zoom(pm[i, ], base_mag, img_w), numeric(1))
}

#' Mean of `point_zoom` over every point in the path. `0.0` for an empty path.
avg_zoom <- function(path, base_mag = NULL, img_w = NULL) {
  z <- .zoom_series(path, base_mag, img_w)
  if (length(z) == 0) {
    return(0.0)
  }
  mean(z)
}

#' Sample variance (`var()`'s default divide-by-`n-1`, matching numpy's `ddof=1`) of `point_zoom`
#' over every point in the path. `0.0` (never NA) if the path has fewer than 2 points -- R's
#' `var()` would otherwise return `NA` on a length-1 input; special-cased here to match the Python
#' toolkit's documented "0.0 not NaN/NA for n<2" CSV contract.
zoom_variance <- function(path, base_mag = NULL, img_w = NULL) {
  z <- .zoom_series(path, base_mag, img_w)
  if (length(z) < 2) {
    return(0.0)
  }
  stats::var(z)
}

#' `max(point_zoom) - min(point_zoom)` over the path. `0.0` for an empty path.
zoom_range <- function(path, base_mag = NULL, img_w = NULL) {
  z <- .zoom_series(path, base_mag, img_w)
  if (length(z) == 0) {
    return(0.0)
  }
  max(z) - min(z)
}

#' Fraction of consecutive scanpath transitions that are strictly zoom-IN (a "consecutive zooming"
#' measure after Ghezloo): `|{i : zoom[i+1] > zoom[i]}| / (n-1)`. Exact `>`, no tolerance --
#' `point_zoom` is deterministic on integer-quantized inputs, so a held zoom level produces
#' bit-identical doubles (see the Python docstring for the full determinism argument). A held zoom
#' level (an exact tie) does **not** count -- see the bug-fix note below. `0.0` if the path has
#' fewer than 2 points.
#'
#' **Bug fix (2026-07, see `docs/superpowers/navtrack-lit-review-improvements.md` §0.B):** this
#' used to count zoom-*unchanged* transitions too (`diffs >= 0`). Ghezloo's definition is that the
#' zoom level must *strictly* increase; a held zoom level (an exact tie) must not count as
#' "consecutive zooming". Changed `sum(diffs >= 0)` to `sum(diffs > 0)` below.
magnification_percentage <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  zooms <- .zoom_series(path, base_mag, img_w)
  diffs <- zooms[2:n] - zooms[1:(n - 1)]
  sum(diffs > 0) / length(diffs)
}

#' Per-step logical (length `nrow(pm)-1`): TRUE iff `point_zoom` differs (exact `!=`) between the
#' step's two endpoints. Caveat for schema/3 (w-proxy) paths: a mid-session viewer-window resize
#' changes `w` without an actual zoom and would misread as a zoom-change step; schema/4
#' (`dsMilli`-based) zoom is unaffected by window resizes.
.step_zoom_changed <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(logical(0))
  }
  zooms <- .zoom_series(path, base_mag, img_w)
  zooms[2:n] != zooms[1:(n - 1)]
}

#' "Scanning" rate (px/min): total center pan-distance accumulated over steps where zoom is
#' unchanged (see `.step_zoom_changed`), normalized by the path's **ACTIVE duration** (Tier 2 B1:
#' `active_span_ms`, minutes, replacing the pre-B1 total-duration denominator
#' `(t[last]-t[first])/60000` -- the deliberate denominator choice documented in the Python
#' `scanning_rate_px_per_min` docstring: active session time, not time-spent-scanning, so the rate
#' is comparable across sessions with different scanning/drilling mixes -- idle "stepped away" time
#' is neither "scanning" nor "drilling" time and would otherwise dilute the rate). `0.0` if the path
#' has fewer than 2 points or non-positive active duration.
#'
#' **Tier 2 B1:** a step flagged idle by `idle_step_mask` also contributes nothing to the
#' pan-distance numerator, even if its zoom is unchanged -- an idle "parked at this zoom level for
#' 5 minutes" step is not scanning. For a path with no idle step this is numerically identical to
#' the pre-B1 formula (idle set empty, and `active_span_ms` reduces to the total duration).
scanning_rate_px_per_min <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  duration_min <- active_span_ms(path) / 60000.0
  if (duration_min <= 0) {
    return(0.0)
  }
  changed <- .step_zoom_changed(path, base_mag, img_w)
  idle <- idle_step_mask(path)
  pan <- 0.0
  for (i in seq_len(n - 1)) {
    if (idle[i]) next
    if (!changed[i]) {
      x0 <- pm[i, 2]; y0 <- pm[i, 3]
      x1 <- pm[i + 1, 2]; y1 <- pm[i + 1, 3]
      pan <- pan + sqrt((x1 - x0)^2 + (y1 - y0)^2)
    }
  }
  pan / duration_min
}

#' "Drilling" rate (events/min): count of zoom-change steps (see `.step_zoom_changed`) per minute
#' of the path's ACTIVE duration (Tier 2 B1: `active_span_ms`, same denominator as
#' `scanning_rate_px_per_min`). `0.0` if the path has fewer than 2 points or non-positive active
#' duration.
#'
#' **Tier 2 B1:** a zoom-change step that is *also* idle (see `idle_step_mask` -- e.g. the user
#' zoomed in right before stepping away for 5+ minutes, so the change is only discovered on
#' return) does not count as a "drilling event" -- it was not an active navigation action. For a
#' path with no idle step this is numerically identical to the pre-B1 formula.
drilling_rate_per_min <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  duration_min <- active_span_ms(path) / 60000.0
  if (duration_min <= 0) {
    return(0.0)
  }
  changed <- .step_zoom_changed(path, base_mag, img_w)
  idle <- idle_step_mask(path)
  sum(changed & !idle) / duration_min
}

#' Tier 2 B2 (Drew-fidelity zoom, ADD-alongside `avg_zoom` -- does not replace it): dt-weighted
#' mean of `log2(magnification)` over ACTIVE (non-idle, see `idle_step_mask`) steps:
#'
#' `avgZoomLog2W = sum_active(dt_i * log2(zoom_i)) / sum_active(dt_i)`
#'
#' where `zoom_i = point_zoom(path[i], base_mag, img_w)` (the step-i-owns-point-i convention used
#' throughout this file, e.g. `raster_from_path`). `log2()` for Python<->R parity.
#'
#' `NaN` (blank in `metrics.csv`) if the path has fewer than 2 points, every step is idle, or the
#' total active dt weight is otherwise 0 -- a 0/0 weighted mean has no defensible value (unlike
#' `avg_zoom`'s unweighted `0.0`-for-empty-path convention, `0.0` here would misleadingly read as
#' "1x magnification", a real, specific claim -- so this uses the same NaN-for-genuinely-undefined
#' convention as e.g. `enrichment_ratio` instead).
avg_zoom_log2_w <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(NaN)
  }
  dts <- step_durations_ms(path)
  idle <- idle_step_mask(path)
  total_w <- 0.0
  total_wl <- 0.0
  for (i in seq_len(n - 1)) {
    dt <- dts[i]
    if (idle[i] || dt <= 0) next
    z <- point_zoom(pm[i, ], base_mag, img_w)
    total_w <- total_w + dt
    total_wl <- total_wl + dt * log2(z)
  }
  if (total_w > 0) total_wl / total_w else NaN
}

#' Tier 2 B2 (Drew-fidelity zoom, ADD-alongside `drilling_rate_per_min` -- does not replace it):
#' sum of the absolute per-step `log2(zoom)` change over ACTIVE (non-idle, see `idle_step_mask`)
#' steps, per active minute (`active_span_ms` / 60000, same denominator as
#' `scanning_rate_px_per_min`/`drilling_rate_per_min`) -- a continuous zoom-change-magnitude
#' complement to `drilling_rate_per_min`'s discrete event count:
#'
#' `drillingRateOctavesPerMin = sum_active(|log2(zoom_{i+1}) - log2(zoom_i)|) / activeDurationMin`
#'
#' `log2()` for Python<->R parity. `NaN` (blank in `metrics.csv`) if the path has fewer than 2
#' points or the active duration is non-positive (all steps idle, or a degenerate zero-span path)
#' -- same NaN-for-undefined convention as `avg_zoom_log2_w` (as opposed to
#' `drilling_rate_per_min`'s `0.0`-for-zero-duration convention: a 0/0 rate of continuous change is
#' undefined, not "no change happened over a real duration").
drilling_rate_octaves_per_min <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(NaN)
  }
  duration_min <- active_span_ms(path) / 60000.0
  if (duration_min <= 0) {
    return(NaN)
  }
  idle <- idle_step_mask(path)
  zooms <- .zoom_series(path, base_mag, img_w)
  total <- 0.0
  for (i in seq_len(n - 1)) {
    if (idle[i]) next
    total <- total + abs(log2(zooms[i + 1]) - log2(zooms[i]))
  }
  total / duration_min
}

#' Assign each *step* (`path[i] -> path[i+1]`) to one of `n_bands` zoom bands (band `0` = lowest
#' zoom, `n_bands-1` = highest) by within-path quantile cut points: cuts at the
#' `1/n_bands, 2/n_bands, ...` quantiles of the per-step `point_zoom` values, using `type=7`
#' (R's default -- numerically identical to numpy's default linear-interpolation method), then
#' `findInterval(zooms, cuts)` (equivalent to numpy's `searchsorted(cuts, zooms, side="right")`:
#' both return, for each value, the count of cut points `<=` that value). Returns a vector of
#' length `nrow(pm)-1`, or `integer(0)` if the path has fewer than 2 points.
zoom_band_labels <- function(path, base_mag = NULL, img_w = NULL, n_bands = 3) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(integer(0))
  }
  zooms <- vapply(seq_len(n - 1), function(i) point_zoom(pm[i, ], base_mag, img_w), numeric(1))
  if (n_bands < 2) {
    return(rep(0L, length(zooms)))
  }
  qs <- (1:(n_bands - 1)) / n_bands
  cuts <- stats::quantile(zooms, probs = qs, names = FALSE, type = 7)
  findInterval(zooms, cuts)
}

#: Tier 2 B3: canonical magnification-band cut points (objective power, x). 6 cuts -> 7 labeled
#: bands. Matches the Python toolkit's `MAG_BAND_CUTS` literal exactly.
MAG_BAND_CUTS <- c(1.0, 2.0, 4.0, 10.0, 20.0, 40.0)
#: Tier 2 B3: human-readable labels for the 7 canonical bands (index-aligned, 0-based, with the
#: band index `canonical_mag_band_labels` returns -- not itself written to `magbands_<slug>.csv`).
MAG_BAND_LABELS <- c("<1x", "1-2x", "2-4x", "4-10x", "10-20x", "20-40x", ">=40x")

#' Tier 2 B3: true objective magnification for one scanpath point vector (`point`, one row of
#' `as_path_matrix`'s output) -- `base_mag / (dsMilli / 1000.0)` -- requiring BOTH a known, positive
#' `base_mag` (fragment-level `baseMagnification`, schema/4+) and the point's own `dsMilli` (6th
#' element, schema/4+).
#'
#' Returns `NULL` (a plain sentinel for "not computable" -- checked via `is.null()` at every call
#' site, never participating in arithmetic) when either is absent/non-positive or the point has no
#' `dsMilli` at all (schema/3, 5-element points) -- callers fall back to the existing tercile
#' scheme (`zoom_band_labels`) in that case, per B3's auto-fallback rule. `dsMilli <= 0` is treated
#' defensively as full-resolution (matches `point_zoom`'s own guard).
#' **Non-numeric `base_mag` guard (polish final-review Finding 1):** fixed the same pre-conversion
#' `is.na()` bug `point_zoom` had -- `is.na(base_mag)` on a non-numeric string like `"unknown"` is
#' `FALSE`, so it used to fall through to `bm <- as.numeric(base_mag)` (`NA` with a warning) and
#' then `if (bm <= 0)`, i.e. `if (NA)` -- a fatal R error. Since this function is called once per
#' STEP from `canonical_mag_band_labels` (used by `magband_labels_for_scheme`, the default
#' `--magband-scheme canonical` path), a non-numeric `baseMagnification` used to abort the
#' `magbands_<slug>.csv`/per-band-figure export for the whole slide. Fixed by coercing FIRST
#' (`suppressWarnings(as.numeric(base_mag))`), then checking `is.na()` on the coerced value -- `NA`
#' correctly degrades to the documented "not computable" `NULL` sentinel, so callers auto-fall-back
#' to the tercile scheme exactly as they already do for a `NULL`/absent `baseMagnification`.
true_magnification <- function(point, base_mag) {
  if (is.null(base_mag) || length(base_mag) == 0) {
    return(NULL)
  }
  bm <- suppressWarnings(as.numeric(base_mag))
  if (is.na(bm) || bm <= 0) {
    return(NULL)
  }
  if (length(point) < 6) {
    return(NULL)
  }
  ds_milli <- as.numeric(point[6])
  if (is.na(ds_milli) || ds_milli <= 0) ds_milli <- 1000.0
  bm / (ds_milli / 1000.0)
}

#' Tier 2 B3: assign each *step* (`path[i] -> path[i+1]`, the same point-i-owns-the-step convention
#' as `zoom_band_labels`/`raster_from_path`) a canonical magnification-band index in `[0, 6]` via
#' `MAG_BAND_CUTS` (`findInterval(tm, cuts)`, same convention as `zoom_band_labels`), using
#' `true_magnification(path[i], base_mag)`.
#'
#' Returns `NULL` (not `integer(0)`) the moment any step's true magnification is not computable
#' (missing/non-positive `base_mag`, or a point with no `dsMilli` at all) -- signalling "the
#' canonical scheme does not apply to this session at all; use the tercile fallback instead"
#' (checked via `is.null()`, never truthiness, so a genuinely empty/too-short path -- which returns
#' `integer(0)` -- is distinguished from "not computable"). `integer(0)` for a <2-point path (no
#' steps; canonical is trivially inapplicable but not a fallback signal).
canonical_mag_band_labels <- function(path, base_mag) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(integer(0))
  }
  bands <- integer(n - 1)
  for (i in seq_len(n - 1)) {
    tm <- true_magnification(pm[i, ], base_mag)
    if (is.null(tm)) {
      return(NULL)
    }
    idx <- as.integer(findInterval(tm, MAG_BAND_CUTS))
    bands[i] <- min(idx, length(MAG_BAND_LABELS) - 1L)
  }
  bands
}

#' Tier 2 B3: returns `list(bands=, scheme=)` for one session's path, honoring the
#' `--magband-scheme` CLI flag:
#'
#' - `scheme == "tercile"`: always use the existing within-path quantile bands
#'   (`zoom_band_labels`); `scheme == "tercile"` in the result.
#' - `scheme == "canonical"` (default, and the fallback for any other value): try
#'   `canonical_mag_band_labels` first; if it returns `NULL` (this session's
#'   `base_mag`/`dsMilli` are not computable -- e.g. an Atlas DZI slide with a null
#'   `baseMagnification`, or a schema/3 5-element w-proxy path), fall back to
#'   `zoom_band_labels`, result `scheme == "tercile"`. Otherwise result `scheme == "canonical"`.
#'
#' Returns `list(bands=integer(0), scheme="tercile")` for a <2-point path (matches
#' `zoom_band_labels`'s own `integer(0)`-for-<2-points convention; the scheme label is unused in
#' that case since no rows are ever emitted for an empty band list).
magband_labels_for_scheme <- function(path, base_mag, img_w, n_bands, scheme = "canonical") {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(list(bands = integer(0), scheme = "tercile"))
  }
  if (identical(scheme, "tercile")) {
    return(list(bands = zoom_band_labels(path, base_mag, img_w, n_bands), scheme = "tercile"))
  }
  bands <- canonical_mag_band_labels(path, base_mag)
  if (is.null(bands)) {
    return(list(bands = zoom_band_labels(path, base_mag, img_w, n_bands), scheme = "tercile"))
  }
  list(bands = bands, scheme = "canonical")
}

# ---------------------------------------------------------------------------
# Path descriptors (Roa-Pena)
# ---------------------------------------------------------------------------

#' Per-step instantaneous speed (image px/sec): `distance(i, i+1) / (dt/1000)`. A step with
#' non-positive `dt` (see `step_durations_ms`) gets velocity `0.0` (treated as "no motion" rather
#' than undefined/dropped). Length `nrow(pm)-1`; `numeric(0)` if the path has fewer than 2 points.
.step_velocities_px_per_sec <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(numeric(0))
  }
  dts <- step_durations_ms(path)
  out <- numeric(n - 1)
  for (i in seq_len(n - 1)) {
    dt <- dts[i]
    if (dt <= 0) {
      out[i] <- 0.0
    } else {
      x0 <- pm[i, 2]; y0 <- pm[i, 3]
      x1 <- pm[i + 1, 2]; y1 <- pm[i + 1, 3]
      out[i] <- sqrt((x1 - x0)^2 + (y1 - y0)^2) / (dt / 1000.0)
    }
  }
  out
}

#' Median of per-step velocities (see `.step_velocities_px_per_sec`), **Tier 2 B1: excluding steps
#' flagged idle** by `idle_step_mask` (a step "traversed" during a >60s away-gap is not a real
#' navigation velocity -- it is dropped from the median entirely, not counted as a near-zero
#' speed). `0.0` if the path has fewer than 2 points or every step is idle. For a path with no idle
#' step this is numerically identical to the pre-B1 formula (idle set empty).
path_velocity_px_per_sec <- function(path) {
  vels <- .step_velocities_px_per_sec(path)
  idle <- idle_step_mask(path)
  active_vels <- vels[!idle]
  if (length(active_vels) == 0) {
    return(0.0)
  }
  stats::median(active_vels)
}

#' Net displacement (first->last point, straight-line) divided by the total scanpath length
#' (`scanpath_length_px`, sum of consecutive-step distances). `0.0` if the total length is 0
#' (degenerate/empty/stationary path).
linearity <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  x0 <- pm[1, 2]; y0 <- pm[1, 3]
  x1 <- pm[n, 2]; y1 <- pm[n, 3]
  net <- sqrt((x1 - x0)^2 + (y1 - y0)^2)
  total <- scanpath_length_px(path)
  if (total > 0) net / total else 0.0
}

#' Fraction of dt-weighted steps that are "focused": zoom(i) >= median(per-step zooms) OR
#' velocity(i) <= median(per-step velocities) -- both thresholds are the path's own median
#' (session-relative, not a fixed absolute cutoff), **Tier 2 B1: computed entirely over ACTIVE
#' (non-idle, see `idle_step_mask`) steps** -- idle steps are dropped before either the median
#' thresholds or the dt-weighted sum are computed, exactly as if they never existed, rather than
#' being included in `total_dt` (which would otherwise let a single long away-gap dominate the
#' denominator and dilute the ratio toward whatever its own zoom/velocity happened to be) or in the
#' threshold computation (which would otherwise let a stale zoom/near-zero velocity from an
#' away-gap skew the "focused" cutoff for every other step). `0.0` if the path has fewer than 2
#' points, every step is idle, or zero total active dt. For a path with no idle step this is
#' numerically identical to the pre-B1 formula (idle set empty, active steps == all steps).
search_focus_ratio <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  zooms_all <- vapply(seq_len(n - 1), function(i) point_zoom(pm[i, ], base_mag, img_w), numeric(1))
  dts_all <- step_durations_ms(path)
  vels_all <- .step_velocities_px_per_sec(path)
  idle <- idle_step_mask(path)
  active <- !idle
  if (!any(active)) {
    return(0.0)
  }
  zooms <- zooms_all[active]
  dts <- dts_all[active]
  vels <- vels_all[active]
  zoom_thresh <- stats::median(zooms)
  vel_thresh <- stats::median(vels)
  focused <- (zooms >= zoom_thresh) | (vels <= vel_thresh)
  total_dt <- sum(dts)
  if (total_dt <= 0) {
    return(0.0)
  }
  sum(dts[focused]) / total_dt
}

# ---------------------------------------------------------------------------
# Cross-session consistency (dwell grids; Xu/Nan, Roa-Pena)
# ---------------------------------------------------------------------------

#' Fraction of cells above-threshold (`> thresh` of each grid's own max, via `normalise_max`) in
#' **2 or more** of the given grids (already resampled to a common shape), normalized to the
#' **visited footprint** — cells above-threshold in **at least 1** grid — not the whole grid
#' shape. Roa-Peña reports ~70.5% with this style of rule on real multi-reader data.
#'
#' `coincidence% = |cells visited by >=2 readers| / |cells visited by >=1 reader|`
#'
#' **Bug fix (2026-07, see `docs/superpowers/navtrack-lit-review-improvements.md` §0.A):** this
#' used to normalize by the *whole grid* (`length(counts)`, every cell including never-visited
#' ones), which silently under-reports coincidence for any partially-explored slide and isn't
#' comparable to the literature's ~70.5% benchmark — Roa-Peña's own sanity check is a
#' 48%-visited slide still showing 97% coincidence, which is impossible under a whole-grid
#' denominator.
#'
#' `NaN` if fewer than 2 grids are given (undefined for a single reader). `0.0` if the visited
#' footprint (`counts >= 1`) is empty — nothing was visited by anyone, so there is nothing to
#' compute a coincidence fraction over (guarded to avoid division by zero).
coincidence_level <- function(grids, thresh = 0.1) {
  if (length(grids) < 2) {
    return(NaN)
  }
  normed <- lapply(grids, normalise_max)
  counts <- rep(0, length(normed[[1]]))
  for (g in normed) {
    counts <- counts + as.numeric(g > thresh)
  }
  visited <- sum(counts >= 1)
  if (visited == 0) {
    return(0.0)
  }
  sum(counts >= 2) / visited
}

#' Percentage of the consensus's above-threshold cells (`> thresh` of its own max) that this
#' session's grid *also* has above its own threshold. `0.0` if the consensus grid has no
#' above-threshold cells.
region_coverage_pct <- function(session_grid, consensus_grid, thresh = 0.1) {
  cons <- normalise_max(consensus_grid)
  sess <- normalise_max(session_grid)
  cons_mask <- cons > thresh
  n <- sum(cons_mask)
  if (n == 0) {
    return(0.0)
  }
  covered <- sum(cons_mask & (sess > thresh))
  covered / n * 100.0
}

# ---------------------------------------------------------------------------
# Annotation metrics (Phase 2; schema/4+ "annotations" GeoJSON FeatureCollection).
#
# These functions take an already-rasterized boolean cell mask (see `rasterize_roi` in the "ROI"
# section below, the same GeoJSON-polygon-to-grid rasterizer already used for the `--roi` CLI
# flag) rather than GeoJSON themselves -- mirrors `blinded_focus.metrics`'s equivalent section in
# the Python toolkit function-for-function.
# ---------------------------------------------------------------------------

#' Percentage of total dwell that falls inside `mask` -- Ghezloo's "ROI time percentage",
#' generalized from an expert reference ROI to a reader's own annotated region:
#'
#' `dwellInAnnotationPct = 100 * sum(grid[mask]) / sum(grid)`
#'
#' `grid` and `mask` (logical, same length) are both coerced to plain vectors before comparison.
#' `0.0` if `sum(grid)` is 0 (no dwell recorded at all, or an empty grid) -- there is genuinely 0%
#' dwell anywhere, not an undefined ratio; also `0.0` (not undefined) when `mask` has no `TRUE`
#' cells (no annotation on this slide) since `sum(grid[mask])` is then simply 0.
dwell_in_mask_pct <- function(grid, mask) {
  g <- as.numeric(grid)
  msk <- as.logical(mask)
  total <- sum(g)
  if (total <= 0) {
    return(0.0)
  }
  sum(g[msk]) / total * 100.0
}

#' Mean dwell-ms per annotated cell divided by mean dwell-ms per non-annotated cell (Nan 2025 Nat
#' Commun's enrichment ratio, a companion to the area-based `region_coverage_pct`):
#'
#' `enrichmentRatio = mean(grid[mask]) / mean(grid[!mask])`
#'
#' `NaN` (blank in `metrics.csv` via `write.csv(..., na="")`) in three distinct undefined cases,
#' all mapped to the same NaN/blank rather than 0 or Inf:
#'
#' - `mask` has no `TRUE` cells (no annotation on this slide -- nothing to enrich);
#' - `mask` has no `FALSE` cells (the whole grid is annotated -- no "outside" to compare against);
#' - the non-annotated mean is exactly 0 (division by zero).
enrichment_ratio <- function(grid, mask) {
  g <- as.numeric(grid)
  msk <- as.logical(mask)
  if (!any(msk) || all(msk)) {
    return(NaN)
  }
  mean_out <- mean(g[!msk])
  if (mean_out == 0) {
    return(NaN)
  }
  mean_in <- mean(g[msk])
  mean_in / mean_out
}

#' Tier 3 C6: area (image px^2) of the UNIONED rasterized annotation mask
#' (`rasterize_feature_collection`) -- the overlap-correct companion to the existing sum-based
#' `annotatedAreaPx` (`annotations_area_px`, which double-counts overlapping/nested Features -- see
#' its docs).
#'
#' `count(mask) * (img_w/gw) * (img_h/gh)` -- each `TRUE` cell contributes its rasterized footprint
#' area (approximating each grid cell as an `img_w/gw` x `img_h/gh` rectangle), NOT the exact
#' vector polygon-union area (no polygon-clipping library is used here, consistent with the rest of
#' this file's dependency-free approach -- the raster resolution is the grid's own `(gw, gh)`, same
#' as every other per-session native-grid metric).
#'
#' `0.0` for an all-`FALSE` (no-annotations, or a degenerate zero-size grid) mask.
annotated_area_union_px <- function(mask, gw, gh, img_w, img_h) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  if (gw <= 0 || gh <= 0) {
    return(0.0)
  }
  cell_area <- (as.numeric(img_w) / gw) * (as.numeric(img_h) / gh)
  sum(as.logical(mask)) * cell_area
}

#' Count of scanpath re-entries into the annotated region (Brunyé 2017's re-entry rate).
#'
#' Maps every path point to a grid cell via `visited_sequence` (the same floor-division convention
#' used throughout this file, 0-based cell indices), which also run-length-dedups consecutive
#' repeats so dwelling in one cell across many samples doesn't inflate the count. Looks up `mask`
#' (logical, `gh x gw` flattened, 1-based R indexing -- hence `mask[idx + 1]` below for a 0-based
#' `idx`) at each deduped visited cell to get a per-visit inside/outside logical sequence, then
#' counts the number of maximal `TRUE` runs ("visits") in that sequence:
#'
#' `annotationReentryCount = max(0, n_visits - 1)`
#'
#' The first visit is an *entry*, not a *re*-entry -- this holds regardless of whether the very
#' first visited cell happens to already be inside the region, so the `- 1` is unconditional once
#' `n_visits >= 1`.
#'
#' `0` if the path is empty/NULL, `mask` has no `TRUE` cells (no annotation on this slide), or the
#' deduped visited sequence never enters the region at all (`n_visits == 0`).
annotation_reentry_count <- function(path, mask, gw, gh, img_w, img_h) {
  mask <- as.logical(mask)
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n == 0 || !any(mask)) {
    return(0L)
  }
  seq_idx <- visited_sequence(path, gw, gh, img_w, img_h)
  if (length(seq_idx) == 0) {
    return(0L)
  }
  n_visits <- 0L
  prev_inside <- FALSE
  for (idx in seq_idx) {
    inside <- mask[idx + 1L]
    if (inside && !prev_inside) {
      n_visits <- n_visits + 1L
    }
    prev_inside <- inside
  }
  max(0L, n_visits - 1L)
}

# ---------------------------------------------------------------------------
# Cursor / mouse metrics (Phase 2; schema/5 8-element path points only:
# [tRelMs, cx, cy, w, h, dsMilli, mouseX, mouseY] -- a partial-attention proxy after Raghunath).
# ---------------------------------------------------------------------------

#' `TRUE` iff `path` is non-empty and its points carry cursor data (8-element schema/5 points)
#' rather than the shorter schema/3-/4 point shapes. A single fragment's path is uniformly one
#' shape (the recorder never mixes point lengths within one session), so checking `ncol` of the
#' coerced matrix is equivalent to (and simpler than) checking the first point's length.
has_mouse_data <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  !is.null(n) && n > 0 && ncol(pm) >= 8
}

#' Percentage of path points where the cursor was over the slide viewer.
#'
#' The recorder's off-viewer sentinel is exactly `(mouseX, mouseY) == (-1, -1)`, so a point counts
#' as "on-slide" iff `mouseX != -1 | mouseY != -1` (checking either coordinate also tolerates a
#' malformed single `-1` defensively, though the recorder always writes both together):
#' `100 * count(on-slide) / nrow(path)`. Points without mouse data at all (`ncol(pm) < 8`) are
#' treated as off-slide entirely (0 on-slide) -- in practice this never happens within one
#' fragment since point shape is uniform per `has_mouse_data`.
#'
#' `0.0` for an empty path. Callers should gate on `has_mouse_data` before calling this at all --
#' `metrics.csv` leaves the column blank (not `0.0`) for schema </5 fragments, which have no mouse
#' data whatsoever, rather than reporting a spurious 0%.
cursor_over_slide_pct <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n == 0) {
    return(0.0)
  }
  if (ncol(pm) < 8) {
    return(0.0)
  }
  mouse_x <- pm[, 7]; mouse_y <- pm[, 8]
  on_slide <- sum(mouse_x != -1 | mouse_y != -1)
  on_slide / n * 100.0
}

#' Median Euclidean distance (image px) between the cursor position (`mouseX`, `mouseY`) and the
#' viewport center (`cx`, `cy`) of the *same* path point, over on-slide points only (see
#' `cursor_over_slide_pct`'s on-slide test) -- smaller means the cursor tracks the visible view
#' more tightly (a partial-attention proxy: a cursor glued to the viewport center suggests active
#' visual engagement with the current view, versus one that wanders or leaves the window while the
#' viewport itself stays put).
#'
#' `NaN` (blank in `metrics.csv`) if the path is empty, has no mouse data at all, or has zero
#' on-slide points -- undefined, not 0, since an all-off-slide path says nothing about
#' cursor/viewport coupling.
mouse_viewport_coupling_px <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n == 0 || ncol(pm) < 8) {
    return(NaN)
  }
  mouse_x <- pm[, 7]; mouse_y <- pm[, 8]
  cx <- pm[, 2]; cy <- pm[, 3]
  on_slide <- mouse_x != -1 | mouse_y != -1
  if (!any(on_slide)) {
    return(NaN)
  }
  dists <- sqrt((mouse_x[on_slide] - cx[on_slide])^2 + (mouse_y[on_slide] - cy[on_slide])^2)
  stats::median(dists)
}

# ---------------------------------------------------------------------------
# Tier 1 additive metrics (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md):
# A2 turn-angle directionality, A3 mouse kinematics (schema/5 only), A4 active fraction. Mirrors
# `blinded_focus.metrics`'s equivalent section in the Python toolkit function-for-function. Every
# function here degrades to `NaN` (blank in `metrics.csv` via `write_csv_tidy`'s `na=""`) on its
# documented degenerate input -- never a raised error -- matching the file's existing blank-vs-0.0
# convention for path-only metrics that are genuinely undefined (not "zero") absent enough data.
# ---------------------------------------------------------------------------

#' `atan2(dy, dx)` heading (radians) for each consecutive segment `path[i] -> path[i+1]`, length
#' `nrow(pm)-1`. `numeric(0)` if the path has fewer than 2 points.
.headings_rad <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(numeric(0))
  }
  vapply(seq_len(n - 1), function(i) atan2(pm[i + 1, 3] - pm[i, 3], pm[i + 1, 2] - pm[i, 2]), numeric(1))
}

#' Turn angle (degrees, wrapped to `(-180, 180]`) at each interior point: `phi_i =
#' wrapToPi(theta_{i+1} - theta_i)` over the per-segment headings from `.headings_rad`, where
#' `wrapToPi` is computed as `atan2(sin(delta), cos(delta))` (not a hand-rolled modulo -- R's `%%`
#' and Python's `%` disagree on the sign of a negative dividend, which would silently diverge the
#' two toolkits' wrap-around behavior right at the `+/-180 deg` boundary).
#'
#' `numeric(0)` if the path has fewer than 3 points -- a turn needs two consecutive segments, i.e.
#' an "interior" point with both a preceding and a following segment.
.turn_angles_deg <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 3) {
    return(numeric(0))
  }
  heads <- .headings_rad(path)
  m <- length(heads)
  if (m < 2) {
    return(numeric(0))
  }
  d <- heads[2:m] - heads[1:(m - 1)]
  wrapped <- atan2(sin(d), cos(d))
  # Parity note: multiply by the precomputed constant `(180.0 / pi)` rather than
  # `wrapped * 180.0 / pi` (left-to-right: multiply then divide) -- floating-point
  # multiplication/division is not associative, so the two evaluation orders can round to
  # different doubles. This matches CPython's `math.degrees(x)`, which is implemented as
  # `x * (180.0 / Py_MATH_PI)` (a single multiply by a precomputed constant), so the pre-`floor()`
  # value going into turn_angle_entropy's bin assignment stays bit-identical with the Python
  # toolkit given the same (bit-identical, shared-libm) `wrapped` radians -- turn_angle_entropy's
  # `floor((deg+180)/45)` bin assignment is a discontinuous step function of this value, so even a
  # 1-ULP difference right at a 45-degree-multiple boundary (the common case for
  # exact-integer-pixel diagonal/axis-aligned pans) could otherwise flip a turn into a different
  # bin and shift the whole entropy histogram by far more than the 1e-6 parity tolerance --
  # unlike mean_abs_turn_angle_deg, which stays a continuous (1e-6-tolerant) function of the same
  # value.
  wrapped * (180.0 / pi)
}

#' A2: mean of `|turn angle|` (degrees) over every interior point of the ordered viewport centers
#' (see `.turn_angles_deg`). `NaN` (blank) if the path has fewer than 3 points -- movement-
#' ecology/visual-search directionality measure, not part of the pinned Bylinskii saliency set.
mean_abs_turn_angle_deg <- function(path) {
  turns <- .turn_angles_deg(path)
  if (length(turns) == 0) {
    return(NaN)
  }
  mean(abs(turns))
}

#' A2: Shannon entropy (bits) of the turn-angle distribution (see `.turn_angles_deg`), binned into
#' 8 equal bins over `(-180 deg, 180 deg]` (bin `i` = `[-180+45i, -180+45(i+1))`, with the `+180
#' deg` edge case folded into the last bin via `min(7, ...)`), normalized by `log2(8)` to `[0, 1]`
#' (0 = all turns in one bin/perfectly directional, 1 = turns spread evenly across all 8 bins).
#' `NaN` (blank) if the path has fewer than 3 points.
turn_angle_entropy <- function(path) {
  turns <- .turn_angles_deg(path)
  if (length(turns) == 0) {
    return(NaN)
  }
  bins <- integer(8)
  for (d in turns) {
    idx <- min(7L, as.integer(floor((d + 180.0) / 45.0)))
    idx <- max(0L, idx)
    bins[idx + 1L] <- bins[idx + 1L] + 1L
  }
  total <- length(turns)
  p <- bins / total
  ent <- -sum(p * log2(p + EPS))
  ent / log2(8)
}

#' A3 (schema/5 only): sum of Euclidean distance (image px) between consecutive **on-slide**
#' cursor points (`mouseX`, `mouseY`), sentinel-aware -- a segment is skipped entirely (not
#' bridged) if *either* endpoint is the off-viewer sentinel `(-1, -1)` (see
#' `cursor_over_slide_pct`'s on-slide test), so a single off-slide sample does not inflate the
#' path length with a spurious long jump to/from the sentinel coordinate.
#'
#' `NaN` (blank) if the path doesn't carry schema/5 mouse data at all (see `has_mouse_data`), or
#' carries mouse data but has zero valid on-slide consecutive pairs (e.g. every sample is
#' off-slide) -- distinct from `0.0`, which would misleadingly read as "a measured, stationary
#' cursor" rather than "nothing measurable".
mouse_path_length_px <- function(path) {
  if (!has_mouse_data(path)) {
    return(NaN)
  }
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  total <- 0.0
  n_segments <- 0L
  if (n >= 2) {
    for (i in seq_len(n - 1)) {
      mx0 <- pm[i, 7]; my0 <- pm[i, 8]
      mx1 <- pm[i + 1, 7]; my1 <- pm[i + 1, 8]
      if (!((mx0 != -1 || my0 != -1) && (mx1 != -1 || my1 != -1))) next
      total <- total + sqrt((mx1 - mx0)^2 + (my1 - my0)^2)
      n_segments <- n_segments + 1L
    }
  }
  if (n_segments == 0L) {
    return(NaN)
  }
  total
}

#' A3 (schema/5 only): median of (distance / dt_sec) over consecutive **on-slide** cursor point
#' pairs (same sentinel-aware segment rule as `mouse_path_length_px` -- a segment touching `(-1,
#' -1)` at either endpoint is skipped, never bridged). A segment with non-positive `dt` is also
#' skipped (not clamped to 0 velocity like `.step_velocities_px_per_sec` -- an undefined-duration
#' on-slide segment has no rate to report, so it is dropped from the median rather than counted as
#' "no motion").
#'
#' `NaN` (blank) if the path doesn't carry schema/5 mouse data at all, or carries mouse data but
#' has zero valid (on-slide, `dt > 0`) segments.
mouse_velocity_px_per_sec <- function(path) {
  if (!has_mouse_data(path)) {
    return(NaN)
  }
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  vels <- numeric(0)
  if (n >= 2) {
    for (i in seq_len(n - 1)) {
      mx0 <- pm[i, 7]; my0 <- pm[i, 8]
      mx1 <- pm[i + 1, 7]; my1 <- pm[i + 1, 8]
      if (!((mx0 != -1 || my0 != -1) && (mx1 != -1 || my1 != -1))) next
      dt <- pm[i + 1, 1] - pm[i, 1]
      if (dt <= 0) next
      dist <- sqrt((mx1 - mx0)^2 + (my1 - my0)^2)
      vels <- c(vels, dist / (dt / 1000.0))
    }
  }
  if (length(vels) == 0) {
    return(NaN)
  }
  stats::median(vels)
}

#' A4: `activeFractionPct = 100 * durationMs / (tRel_last - tRel_first)` -- the fragment's
#' recorded total dwell duration as a percentage of the scanpath's wall-clock span. **Not
#' clamped** at 100% -- a value above 100% is a real signal (the recorder's dwell-weight
#' accounting can exceed the raw tick-to-tick span for reasons upstream of this toolkit, e.g.
#' overlapping dwell attribution), not a data error to be hidden (Mello-Thoms engagement confound).
#'
#' `NaN` (blank) if the path has fewer than 2 points, `duration_ms` is missing/not a real number,
#' or the wall-clock span is zero or negative (degenerate/stationary-timestamp path).
active_fraction_pct <- function(path, duration_ms) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(NaN)
  }
  if (is.null(duration_ms) || length(duration_ms) == 0) {
    return(NaN)
  }
  d <- suppressWarnings(as.numeric(duration_ms))
  if (length(d) == 0 || is.na(d)) {
    return(NaN)
  }
  span <- pm[n, 1] - pm[1, 1]
  if (span <= 0) {
    return(NaN)
  }
  100.0 * d / span
}

# ---------------------------------------------------------------------------
# Tier 3 C1 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): I-DT (dispersion-
# threshold, Salvucci & Goldberg 2000) fixation extraction over the scanpath's viewport centers.
# Deterministic and index-based -- deliberately NOT DBSCAN (or any other library clustering
# routine), whose cluster assignment is not guaranteed identical across languages/library versions
# and would break the toolkit's 1e-6 Python<->R parity contract. Mirrors
# `blinded_focus.metrics.fixations_idt` (Python) function-for-function; directional metrics
# (turn-angle, transitions, etc., above) stay tick-based and are untouched by this section --
# fixations are an added lens on the scanpath, not a rebase of any existing metric.
# ---------------------------------------------------------------------------

#: Minimum time span (ms) a candidate fixation window must cover before its dispersion is even
#: tested -- Salvucci & Goldberg's duration threshold. Matches the Python toolkit's
#: `MIN_FIXATION_MS` literal exactly.
MIN_FIXATION_MS <- 250.0
#: Fraction of the window's starting viewport width used as its dispersion threshold (image px):
#: `threshold = DISPERSION_FRAC * w_at_window_start`, where `w_at_window_start` is FIXED to the `w`
#: of the window's first point at the moment step 1 (see `fixations_idt`) finds it, and is never
#: recomputed as the window later expands. Matches the Python toolkit's `DISPERSION_FRAC` literal
#: exactly.
DISPERSION_FRAC <- 0.25

#' `(max(cx)-min(cx)) + (max(cy)-min(cy))` over `pm[start:end, ]` inclusive (1-based row indices,
#' image px) -- the I-DT dispersion of one candidate fixation window. `pm` is an
#' `as_path_matrix()`-shaped matrix.
.window_dispersion <- function(pm, start, end) {
  cxs <- pm[start:end, 2]
  cys <- pm[start:end, 3]
  (max(cxs) - min(cxs)) + (max(cys) - min(cys))
}

#' Tier 3 C1: the exact original I-DT window-growing loop (Salvucci & Goldberg 2000), factored out
#' of `fixations_idt` so it can be run independently over each of a path's IDLE-FREE "runs" (see
#' that function's idle-boundary-splitting docs for why). `pm` is an `as_path_matrix()`-shaped
#' matrix for THIS run only -- row 1 is this run's own first point, not the original, un-split
#' path's -- otherwise byte-for-byte the same algorithm as before the final-review idle fix.
#'
#' Returns `list()` (never `NULL`) if the run has fewer than 2 points -- a run boundary is not the
#' same "insufficient path" case `fixations_idt` itself guards with `NULL`; that `NULL`-vs-`list()`
#' sentinel distinction stays owned entirely by that caller.
.fixations_idt_run <- function(pm) {
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(list())
  }
  out <- list()
  start <- 1L
  while (start <= n) {
    t_start <- pm[start, 1]
    end <- start
    while (end <= n && (pm[end, 1] - t_start) < MIN_FIXATION_MS) {
      end <- end + 1L
    }
    if (end > n) {
      break
    }
    w_start <- pm[start, 4]
    threshold <- DISPERSION_FRAC * w_start
    disp <- .window_dispersion(pm, start, end)
    if (disp <= threshold) {
      cur_end <- end
      while (cur_end + 1L <= n) {
        if (.window_dispersion(pm, start, cur_end + 1L) <= threshold) {
          cur_end <- cur_end + 1L
        } else {
          break
        }
      }
      cxs <- pm[start:cur_end, 2]
      cys <- pm[start:cur_end, 3]
      out[[length(out) + 1]] <- list(
        startMs = t_start,
        durationMs = pm[cur_end, 1] - t_start,
        centerImageX = mean(cxs),
        centerImageY = mean(cys),
        nPoints = cur_end - start + 1L
      )
      start <- cur_end + 1L
    } else {
      start <- start + 1L
    }
  }
  out
}

#' Tier 3 C1: I-DT (dispersion-threshold, Salvucci & Goldberg 2000) fixation detector -- exact port
#' of `blinded_focus.metrics.fixations_idt` (Python); see its docstring for the full algorithm
#' description (deterministic, index-based, NOT a clustering library, via `.fixations_idt_run`).
#' Per-point data `(t=pm[i,1], cx=pm[i,2], cy=pm[i,3], w=pm[i,4])`, 1-based R row indices
#' throughout.
#'
#' **Idle-gap hard boundary (final-review fix):** a step flagged idle by `idle_step_mask` (`dt >
#' IDLE_GAP_MS` -- Tier 2 B1's "reader stepped away, viewport didn't move" gap) is a HARD window
#' boundary a fixation may never bridge -- before this fix, `.fixations_idt_run`'s loop ran over
#' the WHOLE path with no idle awareness, so a >60s away-gap (with an otherwise near-stationary
#' viewport either side of it) got silently bridged into ONE giant fixation spanning the entire
#' gap. The fix splits `path` at every idle step into maximal idle-free "runs" and calls the
#' unmodified `.fixations_idt_run` independently on each run in order, concatenating the results --
#' a window can then never grow across a run boundary, and each run's own "path runs out of
#' points" termination (already in `.fixations_idt_run`, unchanged) does double duty as "close the
#' window at the idle boundary: emit it if it already qualified, else discard the never-yet-
#' qualified partial window" -- exactly the required idle-boundary semantics, with no new
#' termination logic needed. For a path with NO idle step at all (the common case), this is a
#' no-op -- the single "run" is the whole path, identical to the pre-fix behavior.
#'
#' Returns `NULL` if `path` has fewer than 2 points -- fixation extraction is not computable at
#' all, distinct from an empty list (see below); every metrics.csv column below (`n_fixations`
#' etc.) maps this to a blank cell. Returns `list()` (a real, well-defined "zero fixations found" --
#' NOT blank) if the path has >=2 points but no window ever qualifies. Otherwise a list of
#' `list(startMs=, durationMs=, centerImageX=, centerImageY=, nPoints=)`, one per fixation, in
#' start-index/time order.
fixations_idt <- function(path) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(NULL)
  }
  idle <- idle_step_mask(path)
  out <- list()
  run_start <- 1L
  for (i in seq_len(n - 1L)) {
    if (idle[i]) {
      out <- c(out, .fixations_idt_run(pm[run_start:i, , drop = FALSE]))
      run_start <- i + 1L
    }
  }
  out <- c(out, .fixations_idt_run(pm[run_start:n, , drop = FALSE]))
  out
}

#' `length(fixations)` (an integer, including `0` for a genuinely empty-but-computed list), or
#' `NaN` (blank in `metrics.csv`) if `fixations` is `NULL` (path had fewer than 2 points). Mirrors
#' `blinded_focus.metrics.n_fixations`.
n_fixations <- function(fixations) {
  if (is.null(fixations)) {
    return(NaN)
  }
  length(fixations)
}

#' Mean `durationMs` over `fixations`. `NaN` (blank) if `fixations` is `NULL` or empty -- the mean
#' of zero fixations is undefined, not `0.0`. Mirrors `blinded_focus.metrics.mean_fixation_ms`.
mean_fixation_ms <- function(fixations) {
  if (is.null(fixations) || length(fixations) == 0) {
    return(NaN)
  }
  mean(vapply(fixations, function(f) f$durationMs, numeric(1)))
}

#' Median `durationMs` over `fixations`. `NaN` (blank) if `fixations` is `NULL` or empty. Mirrors
#' `blinded_focus.metrics.median_fixation_ms`.
median_fixation_ms <- function(fixations) {
  if (is.null(fixations) || length(fixations) == 0) {
    return(NaN)
  }
  stats::median(vapply(fixations, function(f) f$durationMs, numeric(1)))
}

#' Sample standard deviation (`sd()`'s default divide-by-`n-1`, matching numpy's `ddof=1`) of
#' `durationMs` over `fixations`. `NaN` (blank) if `fixations` is `NULL` or has fewer than 2
#' fixations -- a single fixation (or none) has no defensible sample spread. Mirrors
#' `blinded_focus.metrics.sd_fixation_ms`.
sd_fixation_ms <- function(fixations) {
  if (is.null(fixations) || length(fixations) < 2) {
    return(NaN)
  }
  stats::sd(vapply(fixations, function(f) f$durationMs, numeric(1)))
}

#' `length(fixations) / active_minutes`, where `active_minutes = active_span_ms(path) / 60000.0`
#' (Tier 2 B1's idle-excluded active span -- same denominator convention as
#' `scanning_rate_px_per_min`/`drilling_rate_per_min`). `NaN` (blank) if `fixations` is `NULL`
#' (path had fewer than 2 points) or the active span is non-positive. `0.0` (not blank) when
#' `fixations` is a genuinely empty list (zero fixations found) but the active span is positive.
#' Mirrors `blinded_focus.metrics.fixations_per_min`.
fixations_per_min <- function(fixations, path) {
  if (is.null(fixations)) {
    return(NaN)
  }
  active_min <- active_span_ms(path) / 60000.0
  if (active_min <= 0) {
    return(NaN)
  }
  length(fixations) / active_min
}

# ---------------------------------------------------------------------------
# Tier 3 C2/C3/C4 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md):
# C2 mouse-dwell map (analogous to raster_from_path, but point-based -- deposits a step's dt into
# the single cell containing the cursor, not the viewport rectangle), C3 DTW trajectory similarity
# (deterministic DP over z-normalized viewport centers -- NOT Frechet), C4 segment-level linearity
# (mean linearity() over sub-paths split at the session's own top-hotspot cells). Mirrors
# `blinded_focus.metrics`'s equivalent section in the Python toolkit function-for-function (R's
# vectorized on-slide test below is equivalent to, not a literal port of, Python's per-point
# `mouse_cursor_over_slide` helper -- same rule, idiomatic R shape). All additive; no existing
# metric's formula or value changes.
# ---------------------------------------------------------------------------

#' Rebuild a `gh x gw` dwell-ms grid (flat, row-major) from the scanpath's schema/5 cursor
#' positions (`mouseX`, `mouseY`), analogous to `raster_from_path` but **point-based rather than
#' rectangle-based** -- the whole step's `dt` (see `step_durations_ms`) is deposited into the
#' single grid cell containing the cursor position of **point i** (the step's start point; same
#' step-i-owns-the-interval convention `raster_from_path` uses for the viewport rectangle), via
#' the SAME floor/clamp cell-mapping `raster_from_path` itself falls back to when a viewport
#' rectangle collapses entirely off-image.
#'
#' A step is skipped entirely (contributes no weight) if: it is flagged idle by
#' `idle_step_mask` (Tier 2 B1) or its `dt <= 0`; OR *either* endpoint's cursor is off-slide (the
#' `(-1, -1)` sentinel -- same sentinel-aware rule `mouse_path_length_px`/
#' `mouse_velocity_px_per_sec` use: a segment touching the sentinel at either point is dropped
#' whole, never bridged across).
#'
#' Returns `NULL` (blank `mouseCoveragePct`/`mouseEntropy` in `metrics.csv`) in three cases: `path`
#' doesn't carry schema/5 mouse data at all (`has_mouse_data`); `path` carries mouse data but has
#' **zero on-slide points** anywhere (every sample is the off-viewer sentinel); or `gw<=0`/`gh<=0`
#' (a schema-valid fragment recording `gridWidth`/`gridHeight` of `0` -- **final-review fix,
#' Python<->R parity**: the Python port crashes on this input -- its zero-size grid's column clamp
#' always resolves to `-1`, and `grid[row, -1] += dt` on a genuinely size-`(gh, 0)` numpy array
#' raises `IndexError`, aborting the whole batch run. R's own `grid[row+1, col+1]` (`col+1L == 0L`
#' after the same clamp) does NOT crash on this input -- assigning to R's matrix index `0` is a
#' documented silent no-op -- but the guard is added here anyway so both languages share the same
#' explicit "nothing measurable" contract rather than relying on an R indexing accident). Otherwise
#' returns a flat `(gw*gh,)` numeric vector -- all-zero is a legitimate result (not blank) whenever
#' there is at least one on-slide point but zero valid on-slide *consecutive pairs* to deposit a
#' step's dt into.
mouse_raster_from_path <- function(path, img_w, img_h, gw, gh) {
  if (!has_mouse_data(path)) {
    return(NULL)
  }
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n == 0) {
    return(NULL)
  }
  on_slide <- (pm[, 7] != -1) | (pm[, 8] != -1)
  if (!any(on_slide)) {
    return(NULL)
  }
  gw <- as.integer(gw); gh <- as.integer(gh)
  if (gw <= 0 || gh <= 0) {
    return(NULL)
  }
  img_w <- if (!is.null(img_w) && length(img_w) && img_w != 0) as.numeric(img_w) else 1.0
  img_h <- if (!is.null(img_h) && length(img_h) && img_h != 0) as.numeric(img_h) else 1.0
  grid <- matrix(0.0, nrow = gh, ncol = gw)
  if (n < 2) {
    return(as.numeric(t(grid)))
  }
  dts <- step_durations_ms(path)
  idle <- idle_step_mask(path)
  for (i in seq_len(n - 1)) {
    dt <- dts[i]
    if (dt <= 0) next
    if (idle[i]) next
    if (!(on_slide[i] && on_slide[i + 1])) next
    mx <- pm[i, 7]; my <- pm[i, 8]
    ccx <- min(max(mx, 0.0), img_w - EPS)
    ccy <- min(max(my, 0.0), img_h - EPS)
    col <- min(max(as.integer(floor(ccx / img_w * gw)), 0L), gw - 1L)
    row <- min(max(as.integer(floor(ccy / img_h * gh)), 0L), gh - 1L)
    grid[row + 1L, col + 1L] <- grid[row + 1L, col + 1L] + dt
  }
  as.numeric(t(grid))
}

#' Tier 3 C3: sample-sd (`ddof=1`, i.e. R's default `sd()`) z-normalization of a 1-D sequence:
#' `z = (v - mean(v)) / sd(v)`. `sd` is treated as `0.0` (rather than `NA`/erroring) whenever the
#' sequence has fewer than 2 elements OR is genuinely constant -- mirrors
#' `blinded_focus.metrics._zscore` exactly (R's `sd()` returns `NA` for a length-1 input, which
#' this explicitly guards against, and `0/0` for a constant sequence, also guarded).
.zscore <- function(values) {
  arr <- as.numeric(values)
  n <- length(arr)
  if (n == 0) {
    return(arr)
  }
  mu <- mean(arr)
  sdv <- if (n >= 2) stats::sd(arr) else 0.0
  if (is.na(sdv) || sdv == 0.0) {
    return(rep(0.0, n))
  }
  (arr - mu) / sdv
}

#' Tier 3 C3: Dynamic Time Warping distance between two scanpaths' viewport-center `(cx, cy)`
#' sequences -- exact port of `blinded_focus.metrics.dtw_distance` (Python); see its docstring for
#' the full pinned algorithm (z-normalize each axis/sequence independently via `.zscore`; local
#' cost `sqrt(dx^2+dy^2)`; standard DTW DP; `dtwDistance = D[nA,nB]`, the RAW accumulated cost, NOT
#' path-length-normalized). A self-comparison is always exactly `0.0` without a special-cased
#' branch (see the Python docstring for why). `NaN` (blank in `scanpath_<slug>.csv`) if either path
#' is empty/`NULL`.
dtw_distance <- function(path_a, path_b) {
  if (is.null(path_a) || length(path_a) == 0 || is.null(path_b) || length(path_b) == 0) {
    return(NaN)
  }
  pma <- as_path_matrix(path_a)
  pmb <- as_path_matrix(path_b)
  ax <- .zscore(pma[, 2]); ay <- .zscore(pma[, 3])
  bx <- .zscore(pmb[, 2]); by <- .zscore(pmb[, 3])
  n_a <- length(ax); n_b <- length(bx)
  cost <- function(i, j) {
    dx <- ax[i] - bx[j]
    dy <- ay[i] - by[j]
    sqrt(dx * dx + dy * dy)
  }
  d <- matrix(0.0, nrow = n_a, ncol = n_b)
  d[1, 1] <- cost(1, 1)
  if (n_b > 1) {
    for (j in 2:n_b) {
      d[1, j] <- d[1, j - 1] + cost(1, j)
    }
  }
  if (n_a > 1) {
    for (i in 2:n_a) {
      d[i, 1] <- d[i - 1, 1] + cost(i, 1)
    }
  }
  if (n_a > 1 && n_b > 1) {
    for (i in 2:n_a) {
      for (j in 2:n_b) {
        d[i, j] <- cost(i, j) + min(d[i - 1, j], d[i, j - 1], d[i - 1, j - 1])
      }
    }
  }
  d[n_a, n_b]
}

#' Tier 3 C4: mean `linearity` over the sub-paths a scanpath splits into at the session's own
#' top-`top_n` dwell hotspot cells -- exact port of `blinded_focus.metrics.mean_segment_linearity`
#' (Python); see its docstring for the full pinned segmentation algorithm (deterministic,
#' hotspot-based; the ROI-entry variant is a separate function, `mean_segment_linearity_roi`
#' (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3) -- segmented at annotation
#' boundaries instead of hotspot cells).
#'
#' **C4 dedup fix (2026-07-23, docs/superpowers/sdd/t4-report.md "C4 dedup fix" section):**
#' consecutive samples landing in the SAME hotspot cell are collapsed to a single boundary (its
#' first path index) -- a "boundary" marks a distinct hotspot VISIT, not every raw sample. Before
#' this fix every hotspot-cell hit was its own boundary, so a dwell run (several consecutive
#' samples in one hotspot cell -- the normal shape of real viewing) produced a chain of trivial
#' 2-point segments with `linearity == 1.0` unconditionally, skewing `meanSegmentLinearity` toward
#' 1.0 and defeating its purpose (Roa-Pena: measure TRANSIT segments between attended regions, not
#' within-dwell noise). A hit whose cell matches the immediately-preceding boundary's cell is
#' skipped (still inside the same dwell run); a hit whose cell differs (a different hotspot, or
#' the same hotspot re-entered after visiting a different one) becomes a new boundary, keeping its
#' real path index. Two DIFFERENT hotspot cells adjacent in the path are still two separate
#' boundaries (a short/zero-length transit segment between them) -- unaffected by this fix.
#'
#' `NaN` (blank) if: fewer than 2 hotspot cells are found at all (grid has fewer than 2 cells);
#' `path` has fewer than 2 points; fewer than 2 DISTINCT-hotspot (deduped) boundary points are
#' found in the path (zero segments); or every segment found has fewer than 2 points.
mean_segment_linearity <- function(path, grid, gw, gh, img_w, img_h, top_n = 5) {
  hotspots <- top_hotspots(grid, gw, gh, top_n)
  if (length(hotspots) < 2) {
    return(NaN)
  }
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(NaN)
  }
  gw_i <- as.integer(gw); gh_i <- as.integer(gh)
  img_w_f <- if (!is.null(img_w) && length(img_w) && img_w != 0) as.numeric(img_w) else 1.0
  img_h_f <- if (!is.null(img_h) && length(img_h) && img_h != 0) as.numeric(img_h) else 1.0
  hotspot_cells <- vapply(hotspots, function(h) h$row * gw_i + h$col, numeric(1))
  boundary_idx <- integer(0)
  last_boundary_cell <- NA_real_
  for (i in seq_len(n)) {
    cx <- pm[i, 2]; cy <- pm[i, 3]
    col <- min(max(as.integer(floor(cx / img_w_f * gw_i)), 0L), gw_i - 1L)
    row <- min(max(as.integer(floor(cy / img_h_f * gh_i)), 0L), gh_i - 1L)
    cell <- row * gw_i + col
    if (!(cell %in% hotspot_cells)) {
      next
    }
    if (is.na(last_boundary_cell) || cell != last_boundary_cell) {
      boundary_idx <- c(boundary_idx, i)
    }
    last_boundary_cell <- cell
  }
  if (length(boundary_idx) < 2) {
    return(NaN)
  }
  linearities <- c()
  for (j in seq_len(length(boundary_idx) - 1)) {
    seg <- pm[boundary_idx[j]:boundary_idx[j + 1], , drop = FALSE]
    if (nrow(seg) >= 2) {
      linearities <- c(linearities, linearity(seg))
    }
  }
  if (length(linearities) == 0) {
    return(NaN)
  }
  mean(linearities)
}

#' PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): mean `linearity` over the
#' sub-paths a scanpath splits into at **annotation-ROI-entry** boundaries -- exact port of
#' `blinded_focus.metrics.mean_segment_linearity_roi` (Python). Reuses the reader's own union
#' annotation mask (`rasterize_feature_collection` -- the SAME mask/native `(gw, gh)`
#' `dwell_in_mask_pct`/`annotation_reentry_count` already use). A complement to the hotspot-based
#' `mean_segment_linearity` (Roa-Pena whole-path vs region-transit): this variant segments at
#' entries into the reader's own annotated region instead of at dwell-hotspot visits.
#'
#' **Boundary definition** (deliberately simpler than `mean_segment_linearity`'s cell-identity
#' dedup -- here the state is a single inside/outside boolean, not a specific cell identity, so an
#' outside-excursion is REQUIRED between any two boundaries and the dedup falls out of the state
#' machine for free, needing no explicit "same cell as last boundary" check):
#'
#' 1. Walk every RAW path point (no run-length dedup of the walk itself, same rationale as
#'    `mean_segment_linearity` -- the 1:1 correspondence between a path INDEX and its point must
#'    be preserved) and map it to a grid cell via the same floor/clamp convention used throughout
#'    this file (`visited_sequence`'s mapping, 0-based cell indices -- hence `mask[cell + 1L]`
#'    below for R's 1-based indexing). A point is INSIDE iff its cell is `TRUE` in `mask`.
#' 2. A path point at index `i` is an ROI-entry boundary iff it is inside AND EITHER `i == 1`
#'    (R's first index, already inside at the very start) OR the immediately-preceding point's
#'    cell was OUTSIDE -- an outside-to-inside transition. Checked purely via the inside/outside
#'    boolean, not cell identity, so a maximal run of consecutive inside points (one cell or
#'    several, as long as the region is never left) naturally collapses to exactly ONE boundary,
#'    at the run's first index -- no separate dedup step needed: reaching a second boundary
#'    structurally requires >=1 intervening OUTSIDE point, so consecutive boundary indices are
#'    never adjacent.
#' 3. Segments are the sub-paths between consecutive boundary points (inclusive of both endpoints,
#'    same shared-endpoint convention as `mean_segment_linearity`). Fewer than 2 boundaries at all
#'    -> zero segments.
#' 4. `linearity` (unchanged, reused as-is) is computed on every segment with `>= 2` points;
#'    `meanSegmentLinearityROI` is the mean of those per-segment linearities.
#'
#' `NaN` (blank) if: `mask` has no `TRUE` cells (no annotation on this slide); `path` has fewer
#' than 2 points; fewer than 2 ROI-entry boundaries are found (zero segments); or every segment
#' found has fewer than 2 points (unreachable by the structural argument in step 2 -- kept as an
#' explicit guard for defensiveness, mirroring `mean_segment_linearity`'s own analogous guard).
mean_segment_linearity_roi <- function(path, mask, gw, gh, img_w, img_h) {
  mask <- as.logical(mask)
  if (!any(mask)) {
    return(NaN)
  }
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(NaN)
  }
  gw_i <- as.integer(gw); gh_i <- as.integer(gh)
  img_w_f <- if (!is.null(img_w) && length(img_w) && img_w != 0) as.numeric(img_w) else 1.0
  img_h_f <- if (!is.null(img_h) && length(img_h) && img_h != 0) as.numeric(img_h) else 1.0
  boundary_idx <- integer(0)
  prev_inside <- FALSE
  for (i in seq_len(n)) {
    cx <- pm[i, 2]; cy <- pm[i, 3]
    col <- min(max(as.integer(floor(cx / img_w_f * gw_i)), 0L), gw_i - 1L)
    row <- min(max(as.integer(floor(cy / img_h_f * gh_i)), 0L), gh_i - 1L)
    cell <- row * gw_i + col
    inside <- mask[cell + 1L]
    if (inside && !prev_inside) {
      boundary_idx <- c(boundary_idx, i)
    }
    prev_inside <- inside
  }
  if (length(boundary_idx) < 2) {
    return(NaN)
  }
  linearities <- c()
  for (j in seq_len(length(boundary_idx) - 1)) {
    seg <- pm[boundary_idx[j]:boundary_idx[j + 1], , drop = FALSE]
    if (nrow(seg) >= 2) {
      linearities <- c(linearities, linearity(seg))
    }
  }
  if (length(linearities) == 0) {
    return(NaN)
  }
  mean(linearities)
}

# ---------------------------------------------------------------------------
# Screening efficiency (Abe et al., *Cancer Cytopathology* 2026;e70132, doi:10.1002/cncy.70132) --
# viewport-proxy analogues of the paper's gaze-AOI "LPF main object" biomarkers, computed against
# a reference mask (whichever of --roi/--reference built it -- see the reference/ROI comparison
# section of analyze(), the SAME mask reference_<slug>.csv's nss/aucJudd/precisionAtTopK columns
# already compare against). Exact port of blinded_focus.metrics.screening_efficiency (Python);
# design rationale + construct-mapping table:
# docs/superpowers/2026-09-12-screening-efficiency-review.md.
# ---------------------------------------------------------------------------

#: "Low-power field" cutoff (true objective magnification, x): a fixation's zoom strictly BELOW
#: this is "low power". Matches the Python toolkit's `LPF_MAX_MAG` literal exactly -- see that
#: docstring for the full rationale (anchors to the toolkit's own canonical `MAG_BAND_CUTS` 10.0
#: boundary rather than a new literature-specific threshold; `mag == 10.0` itself is NOT
#: low-power, matching `canonical_mag_band_labels`'s `findInterval` convention).
LPF_MAX_MAG <- 10.0

#: The always-blank template every early-return below builds from -- `NA_real_` (never `NULL`, so
#: the returned list always subsets cleanly by name for `write_csv_tidy`'s `r[fieldnames]`) for
#: every one of the 8 fields.
.screening_efficiency_blank <- function() {
  list(
    timeToFirstRefFixMs = NA_real_, timeToFirstRefFixLpfMs = NA_real_,
    refFixTotalMs = NA_real_, refFixTotalLpfMs = NA_real_,
    refFixCount = NA_real_, refFixVisitCount = NA_real_,
    firstRefFixDownsample = NA_real_, firstRefFixMagnification = NA_real_
  )
}

#' Abe-2026-style screening-efficiency metrics of one session's scanpath against a reference mask
#' -- exact port of `blinded_focus.metrics.screening_efficiency` (Python); see that function's
#' docstring for the full algorithm description (never-crash guards, on-reference attribution via
#' the same clamp+floor cell mapping `visited_sequence`/`mean_segment_linearity_roi` use, the
#' zoom-at-fixation-start lookup, the LPF-blank rule). Returns a named list with the same 8 keys,
#' `NA_real_` (never `NULL`) for every blank field.
#'
#' **First-index-with-time>=startMs (bisect_left equivalent):** the Python port uses
#' `bisect.bisect_left` on the path's own timestamp list. This R port uses `sum(times < start_ms)
#' + 1L` (1-based) instead of `findInterval` -- the COUNT of timestamps strictly LESS than
#' `start_ms`, plus one, is exactly the 1-based position of the first timestamp `>= start_ms` in a
#' sorted-ascending vector (by `bisect_left`'s own definition: "the leftmost insertion point that
#' preserves sort order" is exactly the count of strictly-smaller elements) -- verified equivalent
#' to `bisect_left`, NOT to plain `findInterval(start_ms, times)` (which would instead return the
#' count of elements `<= start_ms`, i.e. the LAST matching index on a tie, the opposite of
#' `bisect_left`'s leftmost-match semantics). This point always exists (`start_ms` is itself one
#' of the path's own timestamps, by construction of `fixations_idt`).
screening_efficiency <- function(path, base_mag, ref_mask, tw, th, img_w, img_h) {
  fx <- fixations_idt(path)
  if (is.null(fx)) {
    return(.screening_efficiency_blank())
  }
  mask <- if (!is.null(ref_mask)) as.logical(ref_mask) else logical(0)
  if (length(mask) == 0 || !any(mask)) {
    return(.screening_efficiency_blank())
  }
  tw_i <- suppressWarnings(as.integer(tw))
  th_i <- suppressWarnings(as.integer(th))
  img_w_f <- suppressWarnings(as.numeric(img_w))
  img_h_f <- suppressWarnings(as.numeric(img_h))
  if (is.na(tw_i) || is.na(th_i) || is.na(img_w_f) || is.na(img_h_f) ||
      tw_i <= 0 || th_i <= 0 || img_w_f <= 0 || img_h_f <= 0) {
    return(.screening_efficiency_blank())
  }

  if (length(fx) == 0) {
    out <- .screening_efficiency_blank()
    out$refFixTotalMs <- 0.0
    out$refFixTotalLpfMs <- 0.0
    out$refFixCount <- 0L
    out$refFixVisitCount <- 0L
    return(out)
  }

  on_ref <- logical(length(fx))
  for (i in seq_along(fx)) {
    f <- fx[[i]]
    col <- as.integer(floor(f$centerImageX / img_w_f * tw_i))
    row <- as.integer(floor(f$centerImageY / img_h_f * th_i))
    col <- min(max(col, 0L), tw_i - 1L)
    row <- min(max(row, 0L), th_i - 1L)
    on_ref[i] <- mask[row * tw_i + col + 1L]
  }

  ref_fix_count <- sum(on_ref)
  visit_count <- 0L
  prev_on <- FALSE
  for (flag in on_ref) {
    if (flag && !prev_on) {
      visit_count <- visit_count + 1L
    }
    prev_on <- flag
  }

  pm <- as_path_matrix(path)
  times <- pm[, 1]

  .zoom_at_start <- function(start_ms) {
    idx <- sum(times < start_ms) + 1L
    if (idx > nrow(pm)) idx <- nrow(pm)
    pt <- pm[idx, ]
    ds <- if (length(pt) >= 6 && !is.na(pt[6]) && pt[6] > 0) pt[6] / 1000.0 else NA_real_
    list(ds = ds, mag = true_magnification(pt, base_mag))
  }

  ref_fix_total_ms <- 0.0
  time_to_first <- NA_real_
  first_ds <- NA_real_
  first_mag <- NA_real_
  found_first <- FALSE
  lpf_computable <- TRUE
  lpf_total <- 0.0
  time_to_first_lpf <- NA_real_

  for (i in seq_along(fx)) {
    if (!on_ref[i]) next
    f <- fx[[i]]
    ref_fix_total_ms <- ref_fix_total_ms + f$durationMs
    z <- .zoom_at_start(f$startMs)
    if (!found_first) {
      time_to_first <- f$startMs
      first_ds <- z$ds
      first_mag <- if (is.null(z$mag)) NA_real_ else z$mag
      found_first <- TRUE
    }
    if (is.null(z$mag)) {
      lpf_computable <- FALSE
    } else if (z$mag < LPF_MAX_MAG) {
      lpf_total <- lpf_total + f$durationMs
      if (is.na(time_to_first_lpf)) {
        time_to_first_lpf <- f$startMs
      }
    }
  }

  if (!lpf_computable) {
    lpf_total <- NA_real_
    time_to_first_lpf <- NA_real_
  }

  list(
    timeToFirstRefFixMs = time_to_first,
    timeToFirstRefFixLpfMs = time_to_first_lpf,
    refFixTotalMs = ref_fix_total_ms,
    refFixTotalLpfMs = lpf_total,
    refFixCount = ref_fix_count,
    refFixVisitCount = visit_count,
    firstRefFixDownsample = first_ds,
    firstRefFixMagnification = first_mag
  )
}

# ---------------------------------------------------------------------------
# Inter-observer agreement
# ---------------------------------------------------------------------------

#' Mean Pearson CC over all unique `(i<j)` pairs of equal-shape grids. `NaN` if fewer than 2 grids
#' are given.
mean_pairwise_cc <- function(grids) {
  n <- length(grids)
  if (n < 2) {
    return(NaN)
  }
  vals <- c()
  for (i in 1:(n - 1)) {
    for (j in (i + 1):n) {
      vals <- c(vals, cc(grids[[i]], grids[[j]]))
    }
  }
  mean(vals)
}

#' ICC(2,1): two-way random-effects, absolute-agreement, single-rater/measurement intraclass
#' correlation (Shrout & Fleiss 1979; McGraw & Wong 1996, Case 2), via `irr::icc(model="twoway",
#' type="agreement", unit="single")` — rows = cells (subjects), cols = sessions (raters). This is
#' the standard ANOVA-based ICC(2,1) estimator and is numerically identical to the Python
#' toolkit's manual two-way-ANOVA formula (verified against it directly). `NaN` if fewer than 2
#' sessions or fewer than 2 cells.
icc <- function(grids) {
  k <- length(grids)
  if (k < 2) {
    return(NaN)
  }
  data <- do.call(cbind, lapply(grids, as.numeric))
  n <- nrow(data)
  if (is.null(n) || n < 2) {
    return(NaN)
  }
  res <- tryCatch(irr::icc(data, model = "twoway", type = "agreement", unit = "single"),
    error = function(e) NULL
  )
  if (is.null(res) || is.na(res$value)) {
    return(NaN)
  }
  as.numeric(res$value)
}

# ---------------------------------------------------------------------------
# ROI (GeoJSON polygon, image-px coordinates) rasterization — same rule as the Python toolkit
# ---------------------------------------------------------------------------

#' Even-odd ray-casting point-in-polygon test across all rings (handles holes naturally: a point
#' inside an odd number of rings is inside the polygon).
.point_in_poly <- function(x, y, rings) {
  inside <- FALSE
  for (ring in rings) {
    n <- nrow(ring)
    if (is.null(n) || n < 3) next
    j <- n
    for (i in seq_len(n)) {
      x1 <- ring[i, 1]; y1 <- ring[i, 2]
      x2 <- ring[j, 1]; y2 <- ring[j, 2]
      if ((y1 > y) != (y2 > y)) {
        x_int <- x1 + (y - y1) * (x2 - x1) / (y2 - y1)
        if (x < x_int) inside <- !inside
      }
      j <- i
    }
  }
  inside
}

#' Coerce one GeoJSON ring (a list of `[x, y, ...]` points, in whatever shape jsonlite produced it
#' -- an already-simplified numeric matrix, a data.frame, or a plain list of 2+-element
#' points/vectors, e.g. from a `simplifyVector = FALSE` parse) into a plain 2-col numeric matrix
#' `(x, y)`. Handles all three shapes uniformly so callers never need to branch on how a given
#' GeoJSON document happened to get simplified.
.ring_to_matrix <- function(ring) {
  if (is.matrix(ring)) {
    return(matrix(as.numeric(ring[, 1:2]), ncol = 2))
  }
  if (is.data.frame(ring)) {
    return(matrix(as.numeric(as.matrix(ring[, 1:2])), ncol = 2))
  }
  pts <- lapply(ring, function(pt) {
    v <- as.numeric(unlist(pt))
    v[1:2]
  })
  matrix(unlist(pts), ncol = 2, byrow = TRUE)
}

#' Flatten a GeoJSON Polygon/MultiPolygon geometry into a list of rings (each a 2-col matrix of
#' `(x, y)`, via `.ring_to_matrix`). All rings (exterior + holes) are returned; even-odd counting
#' (`.point_in_poly`) handles holes naturally.
.extract_rings <- function(geometry) {
  gtype <- geometry$type
  coords <- geometry$coordinates
  rings <- list()
  if (is.null(gtype) || is.null(coords)) {
    return(rings)
  }
  if (gtype == "Polygon") {
    for (ring in coords) {
      rings[[length(rings) + 1]] <- .ring_to_matrix(ring)
    }
  } else if (gtype == "MultiPolygon") {
    for (poly in coords) {
      for (ring in poly) {
        rings[[length(rings) + 1]] <- .ring_to_matrix(ring)
      }
    }
  }
  rings
}

#' Same ring-extraction as `load_roi_rings`, but from an already-parsed GeoJSON list (a `Feature`,
#' `FeatureCollection`, or bare geometry) rather than a file path -- used for a fragment's embedded
#' `annotations` field (Phase 2), which arrives via `get_annotations` (already normalized to a
#' `simplifyVector = FALSE`-shaped nested list), not a file on disk. Returns a flat list of rings
#' (image-px coordinates) suitable for `rasterize_roi`/point-in-polygon (holes handled via the
#' even-odd rule). `list()` for a falsy/malformed input (e.g. an empty
#' `list(type="FeatureCollection", features=list())`, the default from `get_annotations`).
rings_from_feature_collection <- function(fc) {
  if (is.null(fc) || !is.list(fc)) {
    return(list())
  }
  if (!is.null(fc$type) && fc$type == "FeatureCollection") {
    rings <- list()
    for (feat in fc$features) {
      if (!is.null(feat$geometry)) {
        rings <- c(rings, .extract_rings(feat$geometry))
      }
    }
    return(rings)
  }
  if (!is.null(fc$type) && fc$type == "Feature") {
    return(.extract_rings(fc$geometry))
  }
  .extract_rings(fc)
}

#' Load a QuPath-exported GeoJSON (Feature, FeatureCollection, or bare geometry) polygon and
#' return its rings (image-px coordinates) for rasterization. Thin wrapper around
#' `rings_from_feature_collection` over a file's parsed content.
load_roi_rings <- function(roi_path) {
  d <- jsonlite::fromJSON(roi_path, simplifyVector = FALSE)
  rings_from_feature_collection(d)
}

#' Load a QuPath-exported GeoJSON (Feature, FeatureCollection, or bare geometry) polygon file and
#' return the parsed list (not its flattened rings), for per-Feature union rasterization via
#' `rasterize_feature_collection` -- a multi-polygon/overlapping reference ROI must be rasterized
#' feature-by-feature and unioned, not with a single pooled-rings even-odd test (see that
#' function's docs); this is the `--roi` counterpart to the annotation-mask fix.
load_roi_fc <- function(roi_path) {
  jsonlite::fromJSON(roi_path, simplifyVector = FALSE)
}

#' Rasterize polygon rings (image-px coords) to a flat `(gw*gh,)` logical mask (row-major) by
#' testing each grid cell's center point for containment. Same convention as
#' `blinded_focus.analyze.rasterize_roi` in the Python toolkit.
rasterize_roi <- function(rings, gw, gh, img_w, img_h) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  mask <- logical(gw * gh)
  idx <- 1
  for (row in 0:(gh - 1)) {
    cy <- (row + 0.5) / gh * img_h
    for (col in 0:(gw - 1)) {
      cx <- (col + 0.5) / gw * img_w
      mask[idx] <- .point_in_poly(cx, cy, rings)
      idx <- idx + 1
    }
  }
  mask
}

#' Rasterize a GeoJSON list (`FeatureCollection`, `Feature`, or bare geometry -- the same shapes
#' `rings_from_feature_collection` accepts) to a flat `(gw*gh,)` logical mask by rasterizing
#' **each Feature's own rings separately** (that Feature's exterior + its own holes -- even-odd
#' within the Feature, via `rasterize_roi`) and taking the **union (logical OR)** across Features.
#' Same convention as `blinded_focus.analyze.rasterize_feature_collection` in the Python toolkit.
#'
#' This is deliberately NOT the same as pooling every Feature's rings into one flat list and
#' running a single even-odd test across all of them (`rings_from_feature_collection` +
#' `rasterize_roi`): even-odd correctly handles holes *within one polygon*, but pooling rings from
#' separate Features breaks down when two distinct Features geometrically overlap (e.g. a coarse
#' "tumor" annotation with a smaller "high-grade focus" annotation nested inside it) -- a point
#' inside both gets even parity under the pooled test and is wrongly reported outside. Rasterizing
#' feature-by-feature and OR-ing the results sidesteps this: single-feature and
#' hole-within-one-feature behaviour is unchanged (this is a strict superset of the pooled-rings
#' result -- only cross-feature overlap changes, from wrongly-excluded to correctly-included).
#'
#' A `NULL`/malformed `fc`, or one with no features/rings at all, returns an all-`FALSE` mask
#' without walking any grid cell (same short-circuit the pooled-rings call sites relied on).
rasterize_feature_collection <- function(fc, gw, gh, img_w, img_h) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  if (is.null(fc) || !is.list(fc)) {
    return(rep(FALSE, gw * gh))
  }
  if (!is.null(fc$type) && fc$type == "FeatureCollection") {
    features <- fc$features
  } else if (!is.null(fc$type) && fc$type == "Feature") {
    features <- list(fc)
  } else {
    features <- list(list(geometry = fc)) # bare geometry: treat as a single implicit feature
  }
  mask <- rep(FALSE, gw * gh)
  for (feat in features) {
    if (is.null(feat$geometry)) next
    rings <- .extract_rings(feat$geometry)
    if (length(rings) == 0) next
    mask <- mask | rasterize_roi(rings, gw, gh, img_w, img_h)
  }
  mask
}

#' Absolute area (px^2) of a simple polygon ring (a 2-col `(x, y)` matrix, e.g. from
#' `.ring_to_matrix`) via the shoelace formula: `abs(sum(x_i*y_{i+1} - x_{i+1}*y_i)) / 2`. `0.0`
#' for a degenerate ring (fewer than 3 points).
.shoelace_area <- function(ring) {
  n <- nrow(ring)
  if (is.null(n) || n < 3) {
    return(0.0)
  }
  x <- ring[, 1]; y <- ring[, 2]
  x2 <- c(x[-1], x[1]); y2 <- c(y[-1], y[1])
  abs(sum(x * y2 - x2 * y)) / 2.0
}

#' Area (px^2) of one GeoJSON `Polygon` geometry's `coordinates` array: the exterior ring's area
#' (first ring) minus each hole ring's area (subsequent rings), each computed via `.shoelace_area`
#' -- using the *absolute* area of every ring sidesteps any ambiguity in ring winding order (CW vs
#' CCW), which GeoJSON does not strictly mandate a producer follow. Clamped at `0.0` (a malformed
#' polygon whose holes exceed its exterior should never report a negative area). `0.0` for an
#' empty `coordinates` array.
.polygon_coords_area <- function(poly_coords) {
  if (is.null(poly_coords) || length(poly_coords) == 0) {
    return(0.0)
  }
  rings <- lapply(poly_coords, .ring_to_matrix)
  area <- .shoelace_area(rings[[1]])
  if (length(rings) > 1) {
    for (i in 2:length(rings)) {
      area <- area - .shoelace_area(rings[[i]])
    }
  }
  max(area, 0.0)
}

#' Area (px^2) of one GeoJSON `Polygon`/`MultiPolygon` geometry: `.polygon_coords_area` of each
#' constituent polygon, summed for `MultiPolygon`. `0.0` for any other geometry type (e.g.
#' `Point`/`LineString` annotations, which QuPath also allows but which have no area).
.geometry_area <- function(geometry) {
  gtype <- geometry$type
  coords <- geometry$coordinates
  if (is.null(gtype) || is.null(coords)) {
    return(0.0)
  }
  if (gtype == "Polygon") {
    return(.polygon_coords_area(coords))
  }
  if (gtype == "MultiPolygon") {
    total <- 0.0
    for (poly in coords) {
      total <- total + .polygon_coords_area(poly)
    }
    return(total)
  }
  0.0
}

#' Total area (image px^2) of every `Polygon`/`MultiPolygon` feature in a GeoJSON
#' `FeatureCollection` list (a fragment's `annotations` field, via `get_annotations`), via
#' `.geometry_area` summed across features -- the `annotatedAreaPx` column. `0.0` for an
#' empty/missing/malformed FeatureCollection (including non-area annotation geometries only, e.g.
#' a lone point annotation).
#'
#' Caveat: this is the **sum of per-feature areas, not de-duplicated for overlap** -- two
#' overlapping/nested annotation Features (unlike the mask-based metrics, which correctly union
#' them via `rasterize_feature_collection`) report a combined `annotatedAreaPx` larger than their
#' true union area. True polygon-union area would need geometric clipping, which neither toolkit
#' implements.
annotations_area_px <- function(fc) {
  if (is.null(fc) || !is.list(fc) || is.null(fc$type) || fc$type != "FeatureCollection") {
    return(0.0)
  }
  total <- 0.0
  for (feat in fc$features) {
    geom <- feat$geometry
    if (!is.null(geom)) {
      total <- total + .geometry_area(geom)
    }
  }
  total
}

# ---------------------------------------------------------------------------
# ggplot2 figure builders
# ---------------------------------------------------------------------------

#' Long-format (row, col, value) data frame for a row-major flat `grid`, used by all the raster
#' plots below. `row`/`col` are 0-based grid indices (as in the Python `_grid2d` reshape).
#'
#' **Zero-size-grid fix (incidental, surfaced by the final-review F1 fixture):** built with
#' `seq_len(...) - 1L` rather than the `0:(n - 1)` idiom -- for `gw` or `gh` equal to `0` (a
#' schema-valid degenerate grid, the same fragment shape Finding 1/2's zero-grid guards handle),
#' `0:(0 - 1)` is R's colon operator counting DOWN (`0:-1` == `c(0, -1)`, length 2), producing a
#' bogus 2-row axis instead of the intended empty one -- `data.frame()` then errors on the
#' mismatched row count against `value`'s genuinely-empty `numeric(0)`. `seq_len(n) - 1L` is
#' `integer(0)` for `n <= 0` (never counts down), and `rep(x, each = 0)` / `rep(x, times = 0)` are
#' always empty regardless of `x` -- so `row`/`col` both come out length-0 whenever either
#' dimension is `<= 0`, matching `value`'s length and producing a valid, empty (0-row) data frame
#' instead of an error. A crash-prevention fix, not a plotted-value change, for any non-degenerate
#' `(gw, gh)`.
.grid_long_df <- function(grid, gw, gh) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  data.frame(
    row = rep(seq_len(gh) - 1L, each = gw),
    col = rep(seq_len(gw) - 1L, times = gh),
    value = as.numeric(grid)
  )
}

#' Save a heatmap PNG of the grid (magma colormap + colorbar), matching
#' `blinded_focus.figures.heatmap` in the Python toolkit.
plot_heatmap <- function(grid, gw, gh, title, out) {
  df <- .grid_long_df(grid, gw, gh)
  p <- ggplot(df, aes(x = col, y = row, fill = value)) +
    geom_raster() +
    scale_fill_viridis_c(option = "magma", name = "dwell") +
    scale_y_reverse() +
    labs(title = title, x = "grid col", y = "grid row") +
    theme_minimal()
  ggsave(out, plot = p, width = 6, height = 5, dpi = 110)
}

#' Heatmap background + time-graded scanpath line (start = green circle, end = red x), matching
#' `blinded_focus.figures.scanpath_overlay` in the Python toolkit. `path` is the raw
#' `[t, cx, cy, w, h]` matrix (image px); mapped to grid-cell coordinates the same way
#' `visited_sequence` does, for overlay onto the heatmap's grid axes.
plot_scanpath <- function(grid, gw, gh, path, img_w, img_h, title, out) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  df <- .grid_long_df(grid, gw, gh)
  p <- ggplot(df, aes(x = col, y = row, fill = value)) +
    geom_raster() +
    scale_fill_viridis_c(option = "magma", name = "dwell") +
    labs(title = title, x = "grid col", y = "grid row") +
    theme_minimal()

  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (!is.null(n) && n > 0) {
    xs <- as.numeric(pm[, 2]) / img_w * gw - 0.5
    ys <- as.numeric(pm[, 3]) / img_h * gh - 0.5
    path_df <- data.frame(x = xs, y = ys, t = seq_len(n))
    p <- p +
      geom_path(data = path_df, aes(x = x, y = y, color = t), inherit.aes = FALSE, linewidth = 0.6) +
      scale_color_gradient(low = "magenta", high = "cyan", guide = "none") +
      geom_point(
        data = path_df[1, , drop = FALSE], aes(x = x, y = y),
        inherit.aes = FALSE, color = "green", size = 3, shape = 16
      ) +
      geom_point(
        data = path_df[n, , drop = FALSE], aes(x = x, y = y),
        inherit.aes = FALSE, color = "red", size = 3, shape = 4, stroke = 1.4
      )
  }
  p <- p + scale_y_reverse()
  ggsave(out, plot = p, width = 6, height = 5, dpi = 110)
}

#' Heatmap of the mean of normalised (max) grids (already resampled to a common `(gw,gh)`),
#' matching `blinded_focus.figures.consensus` in the Python toolkit.
plot_consensus <- function(grids, gw, gh, title, out) {
  mean_grid <- Reduce(`+`, lapply(grids, normalise_max)) / length(grids)
  plot_heatmap(mean_grid, gw, gh, title, out)
}

#' Diverging heatmap of `grid - consensus_grid` (both already normalised + resampled), matching
#' `blinded_focus.figures.difference` in the Python toolkit.
plot_difference <- function(grid, consensus_grid, gw, gh, title, out) {
  d <- as.numeric(grid) - as.numeric(consensus_grid)
  lim <- if (length(d)) max(abs(d)) else 1.0
  lim <- if (lim > 0) lim else 1.0
  df <- .grid_long_df(d, gw, gh)
  p <- ggplot(df, aes(x = col, y = row, fill = value)) +
    geom_raster() +
    scale_fill_gradient2(
      low = "#2166AC", mid = "white", high = "#B2182B",
      midpoint = 0, limits = c(-lim, lim), name = "session - consensus"
    ) +
    scale_y_reverse() +
    labs(title = title, x = "grid col", y = "grid row") +
    theme_minimal()
  ggsave(out, plot = p, width = 6, height = 5, dpi = 110)
}

#' Tier 1 A6: every session's viewport-center path (image px) on one shared axis -- distinct color
#' per session, alpha 0.5, start=o/end=x markers, legend. `labels_vec`/`paths` are parallel
#' vectors/lists (one entry per session); a session with an empty/NULL path contributes no line.
#' Matches `blinded_focus.figures.scanpath_multi_overlay` in the Python toolkit -- not part of the
#' pinned numeric-parity contract (a PNG, not a CSV); only existence + valid PNG magic are
#' asserted by the selftests, so this need not reproduce the Python figure pixel-for-pixel.
plot_scanpath_multi_overlay <- function(labels_vec, paths, title, out) {
  rows <- list(); start_rows <- list(); end_rows <- list()
  for (i in seq_along(paths)) {
    pm <- as_path_matrix(paths[[i]])
    n <- nrow(pm)
    if (is.null(n) || n == 0) next
    lbl <- labels_vec[i]
    rows[[length(rows) + 1]] <- data.frame(x = pm[, 2], y = pm[, 3], session = lbl, row.names = NULL)
    start_rows[[length(start_rows) + 1]] <- data.frame(
      x = pm[1, 2], y = pm[1, 3], session = lbl, row.names = NULL
    )
    end_rows[[length(end_rows) + 1]] <- data.frame(
      x = pm[n, 2], y = pm[n, 3], session = lbl, row.names = NULL
    )
  }
  if (length(rows) == 0) {
    p <- ggplot() + labs(title = paste0(title, " (no path data)")) + theme_minimal()
    ggsave(out, plot = p, width = 7, height = 6, dpi = 110)
    return(invisible(NULL))
  }
  df <- do.call(rbind, rows)
  start_df <- do.call(rbind, start_rows)
  end_df <- do.call(rbind, end_rows)
  p <- ggplot(df, aes(x = x, y = y, color = session)) +
    geom_path(alpha = 0.5, linewidth = 0.8) +
    geom_point(data = start_df, aes(x = x, y = y, color = session), shape = 16, size = 3) +
    geom_point(data = end_df, aes(x = x, y = y, color = session), shape = 4, size = 3, stroke = 1.4) +
    scale_y_reverse() +
    labs(title = title, x = "image x (px)", y = "image y (px)", color = "session") +
    theme_minimal()
  ggsave(out, plot = p, width = 7, height = 6, dpi = 110)
}

#' Cumulative fraction of grid cells visited so far, plotted against relative time (ms), matching
#' `blinded_focus.figures.coverage_over_time` in the Python toolkit.
plot_coverage_over_time <- function(path, gw, gh, img_w, img_h, title, out) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n == 0) {
    p <- ggplot() +
      labs(title = paste0(title, " (no path data)")) +
      theme_minimal()
    ggsave(out, plot = p, width = 6, height = 4, dpi = 110)
    return(invisible(NULL))
  }
  total_cells <- gw * gh
  seen <- logical(total_cells)
  ts <- numeric(n); cov <- numeric(n)
  for (i in seq_len(n)) {
    t <- pm[i, 1]; cx <- pm[i, 2]; cy <- pm[i, 3]
    col <- min(max(as.integer(cx / img_w * gw), 0L), gw - 1L)
    row <- min(max(as.integer(cy / img_h * gh), 0L), gh - 1L)
    seen[row * gw + col + 1] <- TRUE
    ts[i] <- t
    cov[i] <- sum(seen) / total_cells * 100.0
  }
  df <- data.frame(t = ts, coverage = cov)
  p <- ggplot(df, aes(x = t, y = coverage)) +
    geom_line() +
    labs(title = title, x = "time (ms)", y = "coverage (%)") +
    theme_minimal()
  ggsave(out, plot = p, width = 6, height = 4, dpi = 110)
}

# ---------------------------------------------------------------------------
# CSV writing helper (tidy long-format, blank cells for NA/NaN — matches the Python CLI's
# `_write_csv` output convention: comma-separated, blank not the literal "NA". Quoting differs
# slightly but is benign: Python's `csv.DictWriter` uses `QUOTE_MINIMAL` (only fields containing a
# comma/quote/newline get quoted); R's `write.csv` default quotes *every* character field
# regardless of content. Both are read back correctly by their respective CSV readers — the point
# of alignment is "no unescaped comma ever corrupts column alignment", not byte-identical quoting.)
# ---------------------------------------------------------------------------

#' Write a list of row-lists (`rows`, each a named list covering `fieldnames`) to a tidy CSV at
#' `path`, in `fieldnames` column order. `NA`/`NaN` cells are written as blank fields (matching the
#' Python toolkit's diagonal-only-column convention, e.g. `diffFromConsensus`/`transitionEntropy`).
#'
#' Uses `write.csv`'s default quoting (i.e. does NOT pass `quote = FALSE`) so that a comma/quote/
#' newline inside a character field (most realistically a `--labels sessionId,label` value like
#' `"Dr. Smith, Pathology"` flowing through into the `session`/`sessionA`/`sessionB` columns) is
#' properly escaped rather than corrupting column alignment on write and crashing `read.csv` on
#' read (previously: `quote = FALSE` wrote the raw comma unescaped, and a later `utils::read.csv()`
#' of that same file — see `analyze()`'s return value below, or a re-read for `--labels` output
#' inspection — would then misparse the extra field and fail with "duplicate 'row.names' are not
#' allowed" or worse, silently shift columns).
write_csv_tidy <- function(rows, path, fieldnames) {
  if (length(rows) == 0) {
    df <- as.data.frame(matrix(nrow = 0, ncol = length(fieldnames)))
    names(df) <- fieldnames
  } else {
    df <- do.call(rbind, lapply(rows, function(r) as.data.frame(r[fieldnames], stringsAsFactors = FALSE)))
    names(df) <- fieldnames
  }
  utils::write.csv(df, path, row.names = FALSE, na = "")
  invisible(df)
}

#' Mean of a named field across a list of row-lists, ignoring `NA` entries (matches the Python
#' toolkit's `_mean_of` helper: sessions without a path leave the zoom/scanning columns blank/NA,
#' and the slide-level mean is taken only over sessions that have one). `NaN` if no non-NA values.
.mean_of_col <- function(rows, col) {
  vals <- vapply(rows, function(r) {
    v <- r[[col]]
    if (is.null(v)) NA_real_ else as.numeric(v)
  }, numeric(1))
  vals <- vals[!is.na(vals)]
  if (length(vals) == 0) {
    return(NaN)
  }
  mean(vals)
}

#' `sprintf`-based fixed-decimal formatter for `summary.md`; `NA`/`NaN` -> `"n/a"` (matches the
#' Python toolkit's `_fmt` helper).
.fmt <- function(x, nd = 3) {
  xf <- suppressWarnings(as.numeric(x))
  if (length(xf) == 0 || is.na(xf) || is.nan(xf)) {
    return("n/a")
  }
  sprintf(paste0("%.", nd, "f"), xf)
}

# ---------------------------------------------------------------------------
# Core pipeline — mirrors blinded_focus.analyze.analyze() in the Python toolkit exactly, so both
# toolkits emit the same output files / CSV columns / tidy long-format on the same input.
# ---------------------------------------------------------------------------

#: Threshold fraction (of a grid's own max) used for the connected-region hotspot count.
HOTSPOT_THRESH_FRAC <- 0.5
#: Threshold fraction used for all IoU / above-threshold-region computations (matches the spec's
#: iou(a,b,thresh=0.1) default and the reference attended-map mask definition), also reused for
#: coincidence_level / region_coverage_pct's "above-threshold" cell masks.
IOU_THRESH <- 0.1
#: Default resolution (longest grid side, aspect-preserved) for the scanpath-rasterized fine
#: heatmap and the magnification-band heatmaps, overridable via `res=`.
DEFAULT_RES <- 512
#: Default number of within-path zoom bands (terciles) for the magnification-split analysis,
#: overridable via `magbands=`.
DEFAULT_MAGBANDS <- 3
#: Tier 1 A5: number of top-dwell cells exported per session to `hotspots_<slug>.csv`.
HOTSPOT_TOP_N <- 5L
#: Tier 1 A5: number of top directed cell-transitions exported per session to
#: `transitions_<slug>.csv`.
TRANSITIONS_TOP_N <- 15L
#: Tier 2 B3: default `--magband-scheme` -- canonical (true-magnification) bands, auto-falling
#: back to the tercile scheme per-session when a session's baseMagnification/dsMilli aren't
#: computable (see `magband_labels_for_scheme`).
DEFAULT_MAGBAND_SCHEME <- "canonical"
#: Tier 2 B3: number of canonical magnification bands (fixed by `MAG_BAND_CUTS`'s 6 cut points --
#: NOT overridable via `magbands=`, which only sizes the tercile fallback scheme).
CANONICAL_MAGBAND_COUNT <- length(MAG_BAND_LABELS)

#' Aspect-preserving grid dims with the longest side capped at `res` (mirrors the QuPath
#' extension's own `GRID_MAX`-style longest-side cap, and `blinded_focus.analyze._res_grid_dims`
#' in the Python toolkit): `max(gw, gh) == res` (rounded), the other side scaled by the image's
#' aspect ratio, floored at 1. Returns `c(gw, gh)`.
.res_grid_dims <- function(img_w, img_h, res) {
  img_w <- if (!is.null(img_w) && length(img_w) && img_w != 0) as.numeric(img_w) else 1.0
  img_h <- if (!is.null(img_h) && length(img_h) && img_h != 0) as.numeric(img_h) else 1.0
  longest <- max(img_w, img_h)
  gw <- max(1L, as.integer(round(res * img_w / longest)))
  gh <- max(1L, as.integer(round(res * img_h / longest)))
  c(gw, gh)
}

# ---------------------------------------------------------------------------
# Phase 3: navigation <-> diagnostic accuracy correlation (schema/decision, "--key"/"--graded").
# Mirrors `blinded_focus.analyze`'s equivalent section in the Python toolkit function-for-function.
# ---------------------------------------------------------------------------

#: Navigation-metric columns (from `metrics.csv`) correlated against graded diagnostic accuracy in
#: `.nav_accuracy_rows`. All are grid/path-level per-session metrics already present as `row` keys
#: in `analyze()`'s main loop.
#:
#: Tier 1 A1 extension: `durationMs`, `cursorOverSlidePct`, `mouseViewportCouplingPx` close the
#: "recorded-but-uncorrelated" gap identified by the data-dimension audit -- they were already
#: written to `metrics.csv` but never joined against graded accuracy. (`decisionLatencyMs`, the
#: fourth recorded-but-uncorrelated dimension, is sourced directly from `decision_rows` rather than
#: this metrics.csv-backed vector -- see `.nav_accuracy_rows`'s dedicated block below.)
#: Screening efficiency (Abe et al., *Cancer Cytopathology* 2026;e70132, doi:10.1002/cncy.70132):
#: 6 of `screening_efficiency`'s 8 fields (excluding `firstRefFixDownsample`/
#: `firstRefFixMagnification`, which describe a zoom LEVEL rather than a navigation-efficiency
#: quantity) -- stamped in-memory-only onto `metrics_rows` in the reference/ROI comparison section
#: below (only present on a session with a computable reference mask AND at least one
#: on-reference fixation's magnification, per that function's blank rules). Matches the Python
#: toolkit's `NAV_ACCURACY_COLS` extension exactly (identical strings, same order).
NAV_ACCURACY_COLS <- c(
  "avgZoom", "zoomVariance", "magnificationPercentage", "scanningRatePxPerMin",
  "drillingRatePerMin", "coveragePct", "dwellInAnnotationPct", "enrichmentRatio",
  "searchFocusRatio", "linearity", "pathVelocityPxPerSec", "entropy", "transitionEntropy",
  "durationMs", "cursorOverSlidePct", "mouseViewportCouplingPx",
  "timeToFirstRefFixMs", "timeToFirstRefFixLpfMs", "refFixTotalMs", "refFixTotalLpfMs",
  "refFixCount", "refFixVisitCount"
)
#: Minimum sample size for a defensible point-biserial r at this pilot scale -- below this (or with
#: zero variance on either side) `.pearson_guarded` returns `NaN` (blank), never a numerically
#: unstable/undefined statistic.
MIN_CORRELATION_N <- 5

#' Plain Pearson r (point-biserial when one side is 0/1) via `stats::cor(..., method="pearson")`,
#' or `NaN` if `length(xs) < min_n` or either side has zero variance -- guard checked BEFORE
#' calling `cor()`, never after. `cor()` is scale-invariant to the n vs n-1 std convention (see
#' `cc()`'s docstring above), so this agrees with `numpy.corrcoef` exactly despite the population-
#' vs-sample variance difference; no p-value, no confidence interval (pilot-scale n doesn't support
#' them). Parity-safe port of `blinded_focus.analyze._pearson_guarded`.
.pearson_guarded <- function(xs, ys, min_n = MIN_CORRELATION_N) {
  xs <- as.numeric(xs); ys <- as.numeric(ys)
  if (length(xs) < min_n || stats::var(xs) == 0.0 || stats::var(ys) == 0.0) {
    return(NaN)
  }
  as.numeric(stats::cor(xs, ys, method = "pearson"))
}

#' Shared point-biserial-r + group mean/median/meanDiff computation for one navigation metric,
#' given already-filtered/zipped `xs`/`ys` vectors (`xs` = the metric's value, `ys` = the matching
#' 0/1 graded `correct`). Factored out of `.nav_accuracy_rows`'s per-column loop so the Tier 1 A1
#' `decisionLatencyMs` row (sourced directly from `decision_rows`, not via the `metrics_rows`
#' column loop) computes its stats identically, not via a parallel reimplementation that could
#' silently drift from the original. Mirrors `blinded_focus.analyze._nav_stat_row` exactly.
.nav_stat_row <- function(metric_name, xs, ys) {
  r_val <- .pearson_guarded(ys, xs)
  correct_vals <- xs[ys == 1]
  incorrect_vals <- xs[ys == 0]
  mean_correct <- if (length(correct_vals) > 0) mean(correct_vals) else NaN
  mean_incorrect <- if (length(incorrect_vals) > 0) mean(incorrect_vals) else NaN
  median_correct <- if (length(correct_vals) > 0) stats::median(correct_vals) else NaN
  median_incorrect <- if (length(incorrect_vals) > 0) stats::median(incorrect_vals) else NaN
  mean_diff <- if (length(correct_vals) >= 2 && length(incorrect_vals) >= 2) {
    mean_correct - mean_incorrect
  } else {
    NaN
  }
  list(
    metric = metric_name,
    n = length(xs),
    pointBiserialR = r_val,
    meanCorrect = mean_correct,
    meanIncorrect = mean_incorrect,
    medianCorrect = median_correct,
    medianIncorrect = median_incorrect,
    meanDiff = mean_diff
  )
}

#' Join decisions' hand-graded `correct` (0/1) onto `metrics_rows` by the stable `(slide,
#' sessionId)` key (never the display label), then compute a guarded point-biserial r plus group
#' means/medians per navigation column in `NAV_ACCURACY_COLS` (via `.nav_stat_row`), plus (Tier 1
#' A1) a `decisionLatencyMs` row sourced directly from `decision_rows`.
#'
#' Direct sessionId join: `metrics_rows` carries the stable `sessionId` (stamped in `analyze()`'s
#' per-session loop, right next to the human display `label_for` label) -- so no label-based
#' bridge is needed here at all. (Prior versions recovered the sessionId via a `(slide,
#' display-label)` lookup into `decision_rows`, which silently collapsed two sessions sharing a
#' display label -- e.g. via `--labels` mapping distinct sessionIds to the same name -- onto
#' whichever session's decision row was built last, misattributing/erasing the other's grade.
#' Stamping sessionId directly on `metrics_rows` removes the label from this join entirely.)
#' Mirrors `blinded_focus.analyze._nav_accuracy_rows` exactly.
#'
#' Returns `list(rows=..., had_any_graded=...)` -- `had_any_graded` gates whether
#' `nav_accuracy.csv` and the summary section get written at all (only when at least one
#' `--graded` row was supplied).
.nav_accuracy_rows <- function(metrics_rows, decision_rows) {
  correct_by <- list()
  for (r in decision_rows) {
    if (!is.na(r$correct)) {
      correct_by[[.decision_key(r$slide, r$sessionId)]] <- r$correct
    }
  }
  had_any_graded <- length(correct_by) > 0
  rows <- list()
  for (col in NAV_ACCURACY_COLS) {
    xs <- numeric(0); ys <- numeric(0)
    for (mr in metrics_rows) {
      key <- .decision_key(mr$slide, mr$sessionId)
      cval <- correct_by[[key]]
      if (is.null(cval)) next
      # Some metrics (e.g. enrichmentRatio) store a literal NaN in the in-memory row for a
      # well-defined "undefined" case (only rendered as a blank cell later, at CSV-write time, via
      # write_csv_tidy's na="") -- `is.na()` in R is TRUE for both NA and NaN, so this single check
      # already treats both identically, unlike the Python port which needs a separate
      # `_sanitize_nan` step (Python's blank placeholder is the literal string `""`, not NaN).
      v <- mr[[col]]
      if (is.null(v) || is.na(v)) next
      xs <- c(xs, as.numeric(v))
      ys <- c(ys, cval)
    }
    rows[[length(rows) + 1]] <- .nav_stat_row(col, xs, ys)
  }

  # Tier 1 A1: decisionLatencyMs is sourced directly from decision_rows -- it and `correct` already
  # live on the SAME (slide, sessionId) decision entry, so there is no cross-table join to perform
  # here at all (unlike the metrics_rows columns above); iterating decision_rows directly is
  # itself the (slide, sessionId)-keyed join, with no label bridge in sight.
  lat_xs <- numeric(0); lat_ys <- numeric(0)
  for (r in decision_rows) {
    if (is.na(r$correct)) next
    lat <- r$decisionLatencyMs
    if (is.null(lat) || is.na(lat)) next
    lat_xs <- c(lat_xs, as.numeric(lat))
    lat_ys <- c(lat_ys, r$correct)
  }
  rows[[length(rows) + 1]] <- .nav_stat_row("decisionLatencyMs", lat_xs, lat_ys)

  list(rows = rows, had_any_graded = had_any_graded)
}

#' Soft-guarded calibration summary over graded rows with a non-blank `confidenceScaled`:
#' `calibrationGap = mean(confidenceScaled) - mean(correct)` (positive = overconfident),
#' `brierScore = mean((confidenceScaled - correct)^2)` (lower = better calibrated), and
#' `confidenceAccuracyR` (hard-guarded via `.pearson_guarded`). All three are `NaN` (blank) when
#' there are zero eligible rows; `confidenceAccuracyR` additionally needs `n >= MIN_CORRELATION_N`
#' and non-zero variance on both sides (same guard as `.nav_accuracy_rows`). Returns
#' `list(gap=, brier=, conf_acc_r=, n=)`. Mirrors `blinded_focus.analyze._calibration_stats`.
.calibration_stats <- function(decision_rows) {
  conf_vals <- numeric(0); correct_vals <- numeric(0)
  for (r in decision_rows) {
    if (is.na(r$correct)) next
    cs <- r$confidenceScaled
    if (is.null(cs) || is.na(cs) || !is.numeric(cs)) next
    conf_vals <- c(conf_vals, as.numeric(cs))
    correct_vals <- c(correct_vals, r$correct)
  }
  if (length(conf_vals) == 0) {
    return(list(gap = NaN, brier = NaN, conf_acc_r = NaN, n = 0L))
  }
  gap <- mean(conf_vals) - mean(correct_vals)
  brier <- mean((conf_vals - correct_vals)^2)
  conf_acc_r <- .pearson_guarded(conf_vals, correct_vals)
  list(gap = gap, brier = brier, conf_acc_r = conf_acc_r, n = length(conf_vals))
}

#' Run the full blinded-focus pipeline over `inputs` (files/dirs/zips) into `out_dir`. Returns the
#' `metrics.csv` rows as a data frame (for programmatic/test use), and writes:
#'
#' - `metrics.csv` — one row per (slide, session); includes the Phase-1 zoom/navigation metric
#'   family (`avgZoom`, `zoomVariance`, `zoomRange`, `magnificationPercentage`,
#'   `scanningRatePxPerMin`, `drillingRatePerMin`, `pathVelocityPxPerSec`, `linearity`,
#'   `searchFocusRatio`) plus `baseMagnification`/`pathTruncated` passthrough — blank for sessions
#'   with no `path` — and (Phase 2) annotation metrics `nAnnotations`, `annotatedAreaPx`,
#'   `dwellInAnnotationPct`, `annotationReentryCount`, `enrichmentRatio` (the first three
#'   populated for every session — 0/0.0 when a session has no `annotations`;
#'   `annotationReentryCount` blank without a `path`; `enrichmentRatio` blank when its mask has no
#'   in/out split to compare) plus cursor metrics `cursorOverSlidePct`, `mouseViewportCouplingPx`
#'   (blank unless the session's `path` carries schema/5 8-element points with `mouseX`/`mouseY`)
#'   plus (Tier 3 C1, docs/superpowers/specs/2026-07-23-...) a deterministic I-DT fixation-
#'   extraction summary: `nFixations`, `meanFixationMs`, `medianFixationMs`, `sdFixationMs`,
#'   `fixationsPerMin` (path-only, blank without a path) -- see `fixations_idt`.
#' - per slide: `compare_<slug>.csv` (pairwise cc/sim/iou, tidy long format), `consensus_<slug>.png`.
#'   Also carries a slide-level `coincidenceLevel` (one row) and a per-session
#'   `regionCoveragePct` (vs the slide consensus).
#' - per slide, when `reference`/`roi` given: `reference_<slug>.csv`.
#' - per slide, when any session has a schema/3+ `path`: `scanpath_<slug>.csv`, and (Phase 1)
#'   `magbands_<slug>.csv` — per-session dwell time in each of `magbands` within-path zoom bands.
#' - per slide, when any session has a schema/3+ `path` AND at least one fixation was found on
#'   this slide (Tier 3 C1): `fixations_<slug>.csv` — one row per I-DT fixation, per session:
#'   `session`, `idx` (1-based, in scanpath order), `startMs`, `durationMs`, `centerImageX`,
#'   `centerImageY`, `nPoints` — see `fixations_idt`.
#' - per slide, when any session has at least one annotation: (Phase 2) `annotations_<slug>.csv` —
#'   pairwise IoU of each session's own rasterized annotated region (tidy long format, same
#'   diagonal-reuse convention as `compare_<slug>.csv`) plus a slide-level `coincidenceLevel` over
#'   those same regions.
#' - `summary.md` — counts, per-slide agreement, reference ranking, headline zoom/scanning numbers,
#'   and (Phase 2) headline annotation-coverage + cursor-coupling numbers.
#' - with `make_figures=TRUE`: per-(slide,session) heatmap/scanpath/coverage-over-time PNGs under
#'   `<out_dir>/<slug>/`, plus (Phase 1, when a path exists) a scanpath-rasterized fine heatmap at
#'   `res` resolution and one heatmap per magnification band.
#'
#' `res` sets the longest-side resolution of the scanpath-rasterized fine/magband heatmaps (see
#' `.res_grid_dims`); `magbands` sets the number of within-path zoom bands for the
#' tercile-fallback magnification-split analysis (see `zoom_band_labels`).
#'
#' `magband_scheme` (Tier 2 B3, `--magband-scheme`) is `"canonical"` (default) or `"tercile"`.
#' `"canonical"` uses true-objective-magnification bands (`MAG_BAND_CUTS`, 7 bands) for every
#' session whose `baseMagnification`/`dsMilli` are computable, auto-falling back to the tercile
#' scheme per-session otherwise (see `magband_labels_for_scheme`). `"tercile"` forces the pre-B3
#' within-path quantile scheme for every session regardless of `baseMagnification` availability.
#' `magbands_<slug>.csv` gains a `bandScheme` column recording which scheme was actually used.
analyze <- function(inputs, out_dir, reference = NULL, roi = NULL, labels_csv = NULL,
                     make_figures = FALSE, res = DEFAULT_RES, magbands = DEFAULT_MAGBANDS,
                     key_csv = NULL, graded_csv = NULL,
                     magband_scheme = DEFAULT_MAGBAND_SCHEME) {
  dir.create(out_dir, showWarnings = FALSE, recursive = TRUE)
  fragments <- load_fragments(inputs)
  if (length(fragments) == 0) {
    message("warning: no valid fragments found (schema atlas-focus-contribution/{1,2,3,4,5})")
  }
  labels <- load_labels(labels_csv)
  # `key_csv` (`--key`) is a DISPLAY-ONLY `slideKey,correctDx` answer key -- it populates
  # `decisions.csv`'s `correctDx` column beside the reader's own `diagnosis` for a human to
  # compare, but is never string-matched against `diagnosis` to derive `correct`. `correct` comes
  # only from `graded_csv` (`--graded`, a `slideKey,sessionId,correct` hand-graded sheet); without
  # it every `correct` cell is blank and no navigation-accuracy correlation is computed (see
  # `.nav_accuracy_rows`).
  answer_key <- load_answer_key(key_csv)
  graded <- load_graded(graded_csv)
  groups <- group_by_slide(fragments)
  roi_fc <- if (!is.null(roi)) load_roi_fc(roi) else NULL
  # Kept only for the reference/ROI gate below (truthy iff the ROI file has >=1 ring) -- the
  # actual reference mask is built per-Feature + unioned via
  # rasterize_feature_collection(roi_fc, ...) below, so a multi-polygon/overlapping reference ROI
  # composes correctly (same fix as the annotation mask; see its docs).
  roi_rings <- if (!is.null(roi_fc)) rings_from_feature_collection(roi_fc) else NULL

  metrics_rows <- list()
  decision_rows <- list()
  slide_summaries <- list()
  reference_summaries <- list()

  for (slide_key in sort(names(groups))) {
    frags <- groups[[slide_key]]
    slide_slug <- slug(slide_key)

    # One fragment per session: keep the richest (max sampleCount) if a session contributed more
    # than once (mirrors tools/aggregate-focus.py's dedup rule, same as the Python toolkit).
    by_session <- list()
    sample_counts <- list()
    for (f in frags) {
      sid <- f$sessionId
      if (is.null(sid) || !nzchar(as.character(sid))) sid <- paste0("anon-", length(by_session))
      sid <- as.character(sid)
      sc <- if (!is.null(f$sampleCount)) as.numeric(f$sampleCount) else 0
      prev_sc <- sample_counts[[sid]]
      if (is.null(prev_sc) || sc > prev_sc) {
        by_session[[sid]] <- f
        sample_counts[[sid]] <- sc
      }
    }
    session_ids <- names(by_session)
    if (length(session_ids) == 0) next

    # Target grid dims: largest-resolution (gridWidth, gridHeight) among the slide's sessions.
    areas <- sapply(session_ids, function(sid) {
      as.integer(by_session[[sid]]$gridWidth) * as.integer(by_session[[sid]]$gridHeight)
    })
    best_sid <- session_ids[which.max(areas)]
    tw <- as.integer(by_session[[best_sid]]$gridWidth)
    th <- as.integer(by_session[[best_sid]]$gridHeight)

    resampled <- list() # sessionId -> resampled (tw*th,) vector, raw units
    native_grid <- list() # sessionId -> list(grid=, gw=, gh=)
    path_seq <- list() # sessionId -> visited-cell sequence (schema/3+ only)
    common_ann_masks <- list() # sessionId -> this session's own annotated region, resampled to
                                # (tw, th) logical -- used only by the cross-user annotations_<slug>.csv
    mouse_native <- list() # sessionId -> this session's own NATIVE (gw, gh) point-based
                            # mouse-dwell grid (Tier 3 C2) -- all-zero (never NULL) for a session
                            # with no schema/5 mouse data or zero on-slide points, mirroring
                            # common_ann_masks' all-FALSE convention for annotation-less sessions.

    for (sid in session_ids) {
      f <- by_session[[sid]]
      gw <- as.integer(f$gridWidth); gh <- as.integer(f$gridHeight)
      grid <- as.numeric(f$grid)
      native_grid[[sid]] <- list(grid = grid, gw = gw, gh = gh)
      resampled[[sid]] <- resample_nn(grid, gw, gh, tw, th)
      mouse_native[[sid]] <- rep(0.0, gw * gh)

      com <- center_of_mass(grid, gw, gh)
      base_mag <- f$baseMagnification
      img_w <- if (!is.null(f$imageWidth)) f$imageWidth else 1
      img_h <- if (!is.null(f$imageHeight)) f$imageHeight else 1

      # ---- Phase 2: this session's own annotations, rasterized to its native (gw, gh) grid
      # (matching the resolution `grid`/`dwell` are already in) -- reuses the same
      # GeoJSON-polygon-to-grid rasterizer as the `--roi` CLI flag. Each annotation Feature is
      # rasterized on its own and the per-feature masks are unioned (OR'd), NOT pooled into one
      # flat ring list and tested with a single even-odd pass -- pooling misclassifies a point
      # inside two overlapping/nested Features (e.g. a "high-grade focus" annotation drawn inside
      # a coarser "tumor" annotation) as outside the annotated region. See
      # `rasterize_feature_collection`'s docs. It also keeps the short-circuit for the
      # no-annotations case (all-FALSE, no per-cell work).
      ann_fc <- get_annotations(f)
      n_ann <- length(ann_fc$features)
      ann_area <- annotations_area_px(ann_fc)
      native_ann_mask <- rasterize_feature_collection(ann_fc, gw, gh, img_w, img_h)
      # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): overlap-correct companion to ann_area
      # above -- reuses the SAME native_ann_mask (no re-rasterization), so this is always in sync
      # with dwellInAnnotationPct/enrichmentRatio's mask.
      ann_area_union <- annotated_area_union_px(native_ann_mask, gw, gh, img_w, img_h)
      # Cross-user (annotations_<slug>.csv) comparisons need every session's mask on the slide's
      # common (tw, th) grid -- resample the already-rasterized native mask (as 0.0/1.0 doubles)
      # via the same nearest-neighbour resampler used for dwell grids, rather than re-rasterizing
      # from scratch at (tw, th).
      common_ann_masks[[sid]] <- resample_nn(as.numeric(native_ann_mask), gw, gh, tw, th) > 0.5

      row <- list(
        slide = slide_key,
        session = label_for(sid, labels),
        # Stable join key for .nav_accuracy_rows -- NOT written to metrics.csv (its fieldnames
        # vector below deliberately omits "sessionId"; write_csv_tidy's `r[fieldnames]` subset
        # drops it silently at write time). Kept in-memory only so the nav-accuracy join below
        # never has to bridge back through the (possibly colliding) human display label -- see
        # .nav_accuracy_rows's docs.
        sessionId = sid,
        durationMs = if (!is.null(f$durationMs)) as.numeric(f$durationMs) else NA,
        sampleCount = if (!is.null(f$sampleCount)) as.numeric(f$sampleCount) else NA,
        coveragePct = coverage(grid) * 100.0,
        entropy = entropy(grid),
        comX = com[1],
        comY = com[2],
        peakDwell = if (length(grid)) max(grid) else 0.0,
        nHotspots = count_hotspots(grid, gw, gh, HOTSPOT_THRESH_FRAC),
        pathPoints = NA,
        pathLengthPx = NA,
        nRevisits = NA,
        transitionEntropy = NA,
        avgZoom = NA,
        zoomVariance = NA,
        zoomRange = NA,
        magnificationPercentage = NA,
        scanningRatePxPerMin = NA,
        drillingRatePerMin = NA,
        pathVelocityPxPerSec = NA,
        linearity = NA,
        searchFocusRatio = NA,
        # Passthrough fragment-level fields (schema/4+; NA -> blank for /1,/2,/3 which lack them).
        baseMagnification = if (!is.null(base_mag)) suppressWarnings(as.numeric(base_mag)) else NA,
        pathTruncated = if (!is.null(f$pathTruncated)) as.logical(f$pathTruncated) else NA,
        # Phase 2 annotation metrics: nAnnotations/annotatedAreaPx/dwellInAnnotationPct only need
        # the grid + this session's own annotation mask (no path required), so they're always
        # populated (0/0.0 when there are no annotations at all). enrichmentRatio is likewise
        # grid-only, but blank (NaN) whenever its mask has no meaningful in/out split to compare.
        nAnnotations = n_ann,
        annotatedAreaPx = ann_area,
        dwellInAnnotationPct = dwell_in_mask_pct(grid, native_ann_mask),
        enrichmentRatio = enrichment_ratio(grid, native_ann_mask),
        # Path-dependent Phase 2 metrics: blank without a path at all (schema/1, /2).
        annotationReentryCount = NA,
        cursorOverSlidePct = NA,
        mouseViewportCouplingPx = NA,
        # Tier 1 additive metrics (docs/superpowers/specs/2026-07-23-...): A2 turn-angle
        # directionality + A4 active fraction are path-only (blank without a path at all, like the
        # block above); A3 mouse kinematics is additionally gated on schema/5 mouse data
        # (populated in the `has_mouse_data` branch below, alongside the existing Phase 2 cursor
        # metrics).
        meanAbsTurnAngleDeg = NA,
        turnAngleEntropy = NA,
        mousePathLengthPx = NA,
        mouseVelocityPxPerSec = NA,
        activeFractionPct = NA,
        # Tier 2 (docs/superpowers/specs/2026-07-23-...) additive columns: B1 transparency
        # (idleMs/activeSpanMs), B2 Drew-fidelity zoom (avgZoomLog2W/drillingRateOctavesPerMin),
        # B4 magnification-source flag. All path-only (NA without a path at all, like the Tier 1
        # block above).
        idleMs = NA,
        activeSpanMs = NA,
        avgZoomLog2W = NA,
        drillingRateOctavesPerMin = NA,
        magnificationSource = NA_character_,
        # Tier 3 C1 (docs/superpowers/specs/2026-07-23-...): I-DT fixation extraction -- path-only
        # (NA without a path at all, like the Tier 1/2 blocks above).
        nFixations = NA,
        meanFixationMs = NA,
        medianFixationMs = NA,
        sdFixationMs = NA,
        fixationsPerMin = NA,
        # Tier 3 C2/C4 (docs/superpowers/specs/2026-07-23-...): mouse-dwell coverage/entropy
        # (schema/5 only, populated in the `has_mouse_data` branch below) and segment-level
        # linearity (path-only, populated in the path block below).
        mouseCoveragePct = NA,
        mouseEntropy = NA,
        meanSegmentLinearity = NA,
        # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): appended at the END of metrics.csv's
        # fieldnames (additive/append-only column order), NOT interleaved next to
        # annotatedAreaPx/meanSegmentLinearity above despite the conceptual relation.
        # annotatedAreaUnionPx is grid+annotation-mask-only (no path required), so it's always
        # populated (0.0 with no annotations), like annotatedAreaPx above.
        annotatedAreaUnionPx = ann_area_union,
        # visitCountJaccard is path-only (NA without a path), populated in the path block below.
        visitCountJaccard = NA,
        # PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): appended at the END of
        # metrics.csv's fieldnames (additive/append-only column order, same convention as the
        # Tier 3 C6 pair above), not interleaved next to meanSegmentLinearity despite the
        # conceptual relation. Path + annotations only (NA without a path, populated in the path
        # block below).
        meanSegmentLinearityROI = NA
      )

      path <- f$path
      if (!is.null(path) && length(path) > 0) {
        # Use the slide's common (tw, th) so metrics.csv values match the diagonal reuse in
        # scanpath_<slug>.csv (same visited-cell sequence definition either place).
        seq <- visited_sequence(path, tw, th, img_w, img_h)
        path_seq[[sid]] <- seq
        pm <- as_path_matrix(path)
        row$pathPoints <- nrow(pm)
        row$pathLengthPx <- scanpath_length_px(path)
        row$nRevisits <- n_revisits(seq)
        row$transitionEntropy <- transition_entropy(seq)
        row$avgZoom <- avg_zoom(path, base_mag, img_w)
        row$zoomVariance <- zoom_variance(path, base_mag, img_w)
        row$zoomRange <- zoom_range(path, base_mag, img_w)
        row$magnificationPercentage <- magnification_percentage(path, base_mag, img_w)
        row$scanningRatePxPerMin <- scanning_rate_px_per_min(path, base_mag, img_w)
        row$drillingRatePerMin <- drilling_rate_per_min(path, base_mag, img_w)
        row$pathVelocityPxPerSec <- path_velocity_px_per_sec(path)
        row$linearity <- linearity(path)
        row$searchFocusRatio <- search_focus_ratio(path, base_mag, img_w)
        # Phase 2: reentry uses the session's own NATIVE (gw, gh) mask/grid resolution (not the
        # slide's common (tw, th)) -- a per-session metric, not a cross-session one, so it should
        # stay at the resolution the fragment actually recorded.
        row$annotationReentryCount <- annotation_reentry_count(path, native_ann_mask, gw, gh, img_w, img_h)
        # Tier 1 A2 (turn-angle directionality) + A4 (active fraction): path-only, no mouse data
        # or annotation needed -- populated whenever a path exists at all (NA internally on their
        # own documented degenerate cases, e.g. <3 points).
        row$meanAbsTurnAngleDeg <- mean_abs_turn_angle_deg(path)
        row$turnAngleEntropy <- turn_angle_entropy(path)
        row$activeFractionPct <- active_fraction_pct(path, if (!is.null(f$durationMs)) f$durationMs else NA)
        if (has_mouse_data(path)) {
          row$cursorOverSlidePct <- cursor_over_slide_pct(path)
          row$mouseViewportCouplingPx <- mouse_viewport_coupling_px(path)
          # Tier 1 A3: mouse kinematics, schema/5 only (same gate as the two cursor metrics above).
          row$mousePathLengthPx <- mouse_path_length_px(path)
          row$mouseVelocityPxPerSec <- mouse_velocity_px_per_sec(path)
          # Tier 3 C2 (docs/superpowers/specs/2026-07-23-...): point-based mouse-dwell grid at
          # this session's own NATIVE (gw, gh) resolution (same resolution convention
          # coveragePct/entropy use for the recorded grid above). NULL (blank
          # mouseCoveragePct/mouseEntropy) iff zero on-slide points anywhere in the path;
          # `mouse_native[[sid]]` stays the all-zero default in that case, which is exactly right
          # for the cross-session mouse_<slug>.csv comparison below.
          mouse_grid <- mouse_raster_from_path(path, img_w, img_h, gw, gh)
          if (!is.null(mouse_grid)) {
            row$mouseCoveragePct <- coverage(mouse_grid) * 100.0
            row$mouseEntropy <- entropy(mouse_grid)
            mouse_native[[sid]] <- mouse_grid
          }
        }
        # Tier 2 B1 transparency columns + B2 Drew-fidelity zoom + B4 magnification-source flag:
        # path-only (like the Tier 1 block above), populated regardless of mouse data.
        row$idleMs <- idle_ms(path)
        row$activeSpanMs <- active_span_ms(path)
        row$avgZoomLog2W <- avg_zoom_log2_w(path, base_mag, img_w)
        row$drillingRateOctavesPerMin <- drilling_rate_octaves_per_min(path, base_mag, img_w)
        # Polish final-review Finding 1 (docs/superpowers/sdd/polish-finalfix-report.md): coerce
        # with suppressWarnings() FIRST, then test is.na()/> 0 on the already-coerced value -- the
        # bare `as.numeric(base_mag)` calls below previously ran twice (once inside is.na(), once
        # for the comparison) and each emitted an "NAs introduced by coercion" warning for a
        # non-numeric baseMagnification; this was never a crash here (`&&` short-circuits on
        # `is.na(...)==TRUE` before the second call), but is fixed for cleanliness/consistency with
        # the point_zoom/true_magnification guards above, which fix the same pattern where it DOES
        # crash.
        bm_src <- if (!is.null(base_mag)) suppressWarnings(as.numeric(base_mag)) else NA_real_
        row$magnificationSource <- if (!is.na(bm_src) && bm_src > 0) {
          "true"
        } else {
          "proxy-downsample"
        }
        # Tier 3 C1: I-DT fixation extraction (docs/superpowers/specs/2026-07-23-...) --
        # deterministic dispersion-threshold detector over the viewport centers. Computed once here
        # for metrics.csv's summary columns; the per-fixation fixations_<slug>.csv rows are built
        # later (per slide) by recomputing this same call directly off each path session's own
        # fragment (mirrors the magband-split export's recompute-don't-cache convention), so no
        # extra per-slide cache list is needed here.
        fx <- fixations_idt(path)
        row$nFixations <- n_fixations(fx)
        row$meanFixationMs <- mean_fixation_ms(fx)
        row$medianFixationMs <- median_fixation_ms(fx)
        row$sdFixationMs <- sd_fixation_ms(fx)
        row$fixationsPerMin <- fixations_per_min(fx, path)
        # Tier 3 C4 (docs/superpowers/specs/2026-07-23-...): segment-level linearity, split at
        # this session's own top-hotspot cells -- reuses the session's own NATIVE (grid, gw, gh)
        # recorded dwell grid (same resolution hotspots_<slug>.csv's top_hotspots call uses), not
        # the slide's common (tw, th) or a scanpath raster.
        row$meanSegmentLinearity <- mean_segment_linearity(path, grid, gw, gh, img_w, img_h, HOTSPOT_TOP_N)
        # PT3 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): ROI-entry segment
        # linearity -- reuses this session's own NATIVE (gw, gh) union annotation mask
        # (native_ann_mask, the SAME mask annotationReentryCount/dwellInAnnotationPct already use
        # above), not the slide's common (tw, th).
        row$meanSegmentLinearityROI <- mean_segment_linearity_roi(path, native_ann_mask, gw, gh, img_w, img_h)
        # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): visit-count Jaccard -- the visit-count
        # grid needs the session's own NATIVE (gw, gh) visited-cell sequence, recomputed here (NOT
        # `path_seq[[sid]]`, which is built at the slide's common (tw, th) resolution for
        # cross-session scanpath_<slug>.csv comparisons) -- same native-resolution convention
        # meanSegmentLinearity/hotspots_<slug>.csv use.
        native_seq <- visited_sequence(path, gw, gh, img_w, img_h)
        row$visitCountJaccard <- visit_count_jaccard(grid, gw, gh, native_seq, HOTSPOT_TOP_N)
      }
      metrics_rows[[length(metrics_rows) + 1]] <- row

      # ---- decisions.csv: this (slide, session)'s hand-entered diagnosis/confidence, if any --
      # HAND-GRADE ONLY. `correctDx` (from `--key`) is display-only; `correct` (from `--graded`) is
      # the only source of grading and is never derived from comparing `diagnosis` to `correctDx`.
      # Join key for both the `--graded` lookup here and the navigation<->accuracy correlation
      # later is the stable `sessionId` (`sid_stable`), never the human display label.
      dec <- get_decision(f)
      diagnosis <- if (!is.null(dec$diagnosis)) as.character(dec$diagnosis) else NA_character_
      confidence <- dec$confidence
      conf_scaled <- if (!is.null(confidence) && is.numeric(confidence)) {
        (as.numeric(confidence) - 1.0) / 4.0
      } else {
        NA_real_
      }
      decision_ms <- if (!is.null(dec$decisionMs)) as.numeric(dec$decisionMs) else NA_real_
      # Tier 3 C5 (2026-07-23): promptShownMs is a passthrough, mirroring decisionMs's own handling
      # immediately above -- absent (older fragment, recorded before the recorder gained this
      # field) degrades to NA/blank, never a crash. responseLatencyMs = decisionMs - promptShownMs
      # is computed only when BOTH are real numbers (is.numeric already excludes logical values,
      # same rationale as confidence's guard below).
      prompt_shown_ms <- if (!is.null(dec$promptShownMs) && is.numeric(dec$promptShownMs)) {
        as.numeric(dec$promptShownMs)
      } else {
        NA_real_
      }
      response_latency_ms <- if (!is.na(decision_ms) && !is.na(prompt_shown_ms)) {
        decision_ms - prompt_shown_ms
      } else {
        NA_real_
      }
      # Blank iff absent/NULL or its string form is empty; otherwise the string form -- so a
      # numeric 0 sessionId stably maps to "0" (this already matched that rule before the Python
      # parity fix: `nzchar(as.character(0))` is TRUE since "0" has 1 char, so this line needs no
      # change here -- see the Python sibling's `_sid = f.get("sessionId"); sid_stable = "" if
      # _sid is None or str(_sid) == "" else str(_sid)`, added to align it with this rule).
      sid_stable <- if (!is.null(f$sessionId) && nzchar(as.character(f$sessionId))) as.character(f$sessionId) else ""
      correct_dx <- if (!is.null(answer_key[[slide_key]])) answer_key[[slide_key]] else NA_character_
      graded_val <- graded[[.decision_key(slide_key, sid_stable)]]
      if (is.null(graded_val)) graded_val <- NA_integer_
      decision_rows[[length(decision_rows) + 1]] <- list(
        slide = slide_key,
        sessionId = sid_stable,
        session = label_for(sid, labels),
        diagnosis = diagnosis,
        # Blank unless confidence is a real number -- `is.numeric()` already excludes logical
        # values (R's `is.numeric(TRUE)` is FALSE), so a malformed boolean confidence renders NA
        # here (mirrors the Python sibling's isinstance(x, (int,float)) and not isinstance(x, bool)
        # guard on this same raw column, added for parity: pre-fix, `as.numeric(TRUE)` coerced a
        # boolean confidence to `1`, diverging from Python's `True` literal in the same cell).
        confidence = if (!is.null(confidence) && is.numeric(confidence)) as.numeric(confidence) else NA_real_,
        confidenceScaled = conf_scaled,
        decisionMs = decision_ms,
        # == decisionMs (both relative to the slide's recording start): "time from slide open to
        # submit". Tier 3 C5 (2026-07-23) added the recorder's promptShownMs, which made the
        # previously-anticipated "time from leave-prompt to submit" column real -- that's
        # responseLatencyMs below, appended as its own column rather than replacing this one.
        # decisionLatencyMs itself stays permanently == decisionMs.
        decisionLatencyMs = decision_ms,
        correctDx = correct_dx,
        # blank unless --graded supplied a (slideKey, sessionId) row -- NEVER auto-derived from
        # diagnosis == correctDx string comparison.
        correct = graded_val,
        # Tier 3 C5 (appended, additive): passthrough of the recorder's dialog-shown timestamp and
        # the derived once-prompted response latency. See the guard comments above.
        promptShownMs = prompt_shown_ms,
        responseLatencyMs = response_latency_ms
      )
    }

    # ------------------------------------------------------------------
    # cross-user compare (pairwise cc/sim/iou + consensus + agreement summary)
    # ------------------------------------------------------------------
    # This slide's just-appended metrics_rows entries (one per session, same order as
    # session_ids) -- reused below for the zoom/scanning summary aggregates.
    slide_metric_rows <- metrics_rows[
      seq(length(metrics_rows) - length(session_ids) + 1, length(metrics_rows))
    ]
    # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): factored out of the consensus_grid
    # one-liner below so consensus_count_<slug>.csv can reuse the SAME per-session normalized
    # grids (identical Reduce(`+`, ...) input as before -- byte-identical consensus_grid, no drift).
    norm_grids <- lapply(session_ids, function(sid) normalise_max(resampled[[sid]]))
    consensus_grid <- Reduce(`+`, norm_grids) / length(session_ids)
    # Slide-level (not per-session) statistic -- placed on exactly one row below (see
    # blinded_focus.analyze's module docstring "coincidenceLevel" convention note; the two
    # toolkits must place it identically for their compare_<slug>.csv files to diff-match).
    coincidence_val <- coincidence_level(lapply(session_ids, function(sid) resampled[[sid]]), IOU_THRESH)

    compare_rows <- list()
    for (idx_a in seq_along(session_ids)) {
      a <- session_ids[idx_a]
      for (b in session_ids) {
        r <- list(
          sessionA = label_for(a, labels),
          sessionB = label_for(b, labels),
          cc = cc(resampled[[a]], resampled[[b]]),
          sim = sim(resampled[[a]], resampled[[b]]),
          iou = iou(resampled[[a]], resampled[[b]], IOU_THRESH),
          diffFromConsensus = NA,
          coincidenceLevel = NA,
          regionCoveragePct = NA,
          # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): a genuine PAIRWISE quantity (unlike
          # diffFromConsensus/coincidenceLevel), so it is computed on EVERY row, not
          # diagonal-only -- exactly 0.0 for a==b (self-comparison).
          jsDivergence = js_divergence(resampled[[a]], resampled[[b]])
        )
        if (identical(a, b)) {
          r$diffFromConsensus <- 1.0 - cc(resampled[[a]], consensus_grid)
          r$regionCoveragePct <- region_coverage_pct(resampled[[a]], consensus_grid, IOU_THRESH)
          if (idx_a == 1) {
            r$coincidenceLevel <- coincidence_val
          }
        }
        compare_rows[[length(compare_rows) + 1]] <- r
      }
    }
    write_csv_tidy(
      compare_rows, file.path(out_dir, paste0("compare_", slide_slug, ".csv")),
      c("sessionA", "sessionB", "cc", "sim", "iou", "diffFromConsensus",
        "coincidenceLevel", "regionCoveragePct",
        # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): appended, existing column order above
        # is unchanged.
        "jsDivergence")
    )
    plot_heatmap(
      consensus_grid, tw, th, paste0("Consensus - ", slide_key),
      file.path(out_dir, paste0("consensus_", slide_slug, ".png"))
    )

    # ------------------------------------------------------------------
    # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): per-cell reader-count "weak annotation"
    # map -- the spatial structure coincidenceLevel collapses to one scalar. Reuses norm_grids
    # (same per-session normalise_max vectors as consensus_grid above, at the slide's common
    # (tw, th) grid) and HOTSPOT_THRESH_FRAC (the SAME threshold count_hotspots/nHotspots already
    # use -- a coarser per-session-max-relative threshold, distinct from coincidence_level's own
    # IOU_THRESH). Written whenever the slide has >=2 sessions (a single session's "reader count"
    # is a degenerate/uninformative 0-or-1 map), regardless of whether any cell actually clears the
    # threshold (possibly a header-only, zero-row file -- blank-not-crash, not a missing file).
    # ------------------------------------------------------------------
    if (length(session_ids) >= 2) {
      reader_counts <- Reduce(`+`, lapply(norm_grids, function(g) as.numeric(g > HOTSPOT_THRESH_FRAC)))
      consensus_count_rows <- list()
      for (idx0 in seq_len(tw * th) - 1L) {
        n_readers <- as.integer(reader_counts[idx0 + 1])
        if (n_readers >= 1) {
          consensus_count_rows[[length(consensus_count_rows) + 1]] <- list(
            cellRow = idx0 %/% tw, cellCol = idx0 %% tw, nReaders = n_readers
          )
        }
      }
      write_csv_tidy(
        consensus_count_rows, file.path(out_dir, paste0("consensus_count_", slide_slug, ".csv")),
        c("cellRow", "cellCol", "nReaders")
      )
    }

    # ------------------------------------------------------------------
    # Tier 1 A5: top-hotspots export -- surfaces the already-implemented top_hotspots at each
    # session's own NATIVE (gw, gh) grid resolution (per-session metric, not cross-session -- same
    # resolution convention as annotationReentryCount above). Written for every session
    # unconditionally ("when any session has a grid, i.e. always" -- every fragment always carries
    # a grid).
    # ------------------------------------------------------------------
    hotspot_rows <- list()
    for (sid in session_ids) {
      ng <- native_grid[[sid]]
      f <- by_session[[sid]]
      img_w <- if (!is.null(f$imageWidth)) f$imageWidth else 1
      img_h <- if (!is.null(f$imageHeight)) f$imageHeight else 1
      total <- sum(ng$grid)
      label <- label_for(sid, labels)
      hs <- top_hotspots(ng$grid, ng$gw, ng$gh, HOTSPOT_TOP_N)
      for (i in seq_along(hs)) {
        h <- hs[[i]]
        hotspot_rows[[length(hotspot_rows) + 1]] <- list(
          session = label,
          rank = i,
          cellRow = h$row,
          cellCol = h$col,
          centerImageX = (h$col + 0.5) / ng$gw * img_w,
          centerImageY = (h$row + 0.5) / ng$gh * img_h,
          dwellMs = h$value,
          dwellFrac = if (total > 0) h$value / total else NA
        )
      }
    }
    write_csv_tidy(
      hotspot_rows, file.path(out_dir, paste0("hotspots_", slide_slug, ".csv")),
      c("session", "rank", "cellRow", "cellCol", "centerImageX", "centerImageY",
        "dwellMs", "dwellFrac")
    )

    # ------------------------------------------------------------------
    # Phase 2: cross-user annotation agreement -- pairwise IoU of each session's own rasterized
    # annotated region + a coincidence level, mirroring compare_<slug>.csv's tidy-long +
    # diagonal-reuse convention exactly. Gated on at least one session having drawn at least one
    # annotation (mirrors the scanpath_<slug>.csv path-presence gate), so a slide with zero
    # annotations anywhere doesn't emit a trivially-all-zero file.
    # ------------------------------------------------------------------
    annotation_coincidence_val <- NaN
    any_annotated <- any(sapply(slide_metric_rows, function(r) r$nAnnotations > 0))
    if (any_annotated) {
      annotation_coincidence_val <- coincidence_level(
        lapply(session_ids, function(sid) as.numeric(common_ann_masks[[sid]])), IOU_THRESH
      )
      ann_rows <- list()
      for (idx_a in seq_along(session_ids)) {
        a <- session_ids[idx_a]
        for (b in session_ids) {
          ann_row <- list(
            sessionA = label_for(a, labels),
            sessionB = label_for(b, labels),
            iou = iou(as.numeric(common_ann_masks[[a]]), as.numeric(common_ann_masks[[b]]), IOU_THRESH),
            coincidenceLevel = NA
          )
          if (identical(a, b) && idx_a == 1) {
            ann_row$coincidenceLevel <- annotation_coincidence_val
          }
          ann_rows[[length(ann_rows) + 1]] <- ann_row
        }
      }
      write_csv_tidy(
        ann_rows, file.path(out_dir, paste0("annotations_", slide_slug, ".csv")),
        c("sessionA", "sessionB", "iou", "coincidenceLevel")
      )
    }

    # ------------------------------------------------------------------
    # Tier 3 C2 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): cross-reader
    # mouse agreement -- pairwise cc/iou of each session's own point-based mouse-dwell grid
    # (mouse_native, resampled to the slide's common (tw, th) grid) + a coincidence level,
    # mirroring annotations_<slug>.csv's tidy-long + diagonal-reuse convention exactly. Gated on at
    # least one session carrying schema/5 mouse data at all (NOT on whether that session's mouse
    # grid ended up non-empty -- a session whose mouse data has zero on-slide points still "has
    # mouse data" in the schema sense and should still trigger the file, same rationale as the
    # annotations gate above being on nAnnotations > 0, not on the mask being non-empty). A session
    # without mouse data contributes its all-zero mouse_native placeholder (never NULL), so every
    # session_ids entry participates in the pairwise matrix.
    #
    # PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2): mouseICC, a SLIDE-LEVEL
    # ICC(2,1) of the mouse-dwell grids (reusing the same `icc()` compare_<slug>.csv's
    # meanPairwiseCC/icc already use), placed on the same diagonal-reuse row as coincidenceLevel.
    # Unlike the cc/iou/coincidenceLevel columns above (deliberately computed across EVERY
    # session_ids entry, including mouse-data-less placeholder grids, for CSV matrix
    # completeness), mouseICC is computed only over the sessions that actually carry mouse data --
    # an all-zero placeholder grid isn't a second "reader" to agree with, and icc() itself would
    # silently mix a real dwell grid with a meaningless constant-zero one otherwise. NaN (->
    # blank) when fewer than 2 sessions have mouse data, mirroring icc()'s own "<2 grids" guard.
    # ------------------------------------------------------------------
    mouse_mean_cc <- NaN
    mouse_icc_val <- NaN
    any_mouse <- any(sapply(session_ids, function(sid) has_mouse_data(by_session[[sid]]$path)))
    if (any_mouse) {
      mouse_resampled <- list()
      for (sid in session_ids) {
        mouse_resampled[[sid]] <- resample_nn(
          mouse_native[[sid]], native_grid[[sid]]$gw, native_grid[[sid]]$gh, tw, th
        )
      }
      mouse_coincidence_val <- coincidence_level(
        lapply(session_ids, function(sid) mouse_resampled[[sid]]), IOU_THRESH
      )
      mouse_data_sids <- session_ids[sapply(session_ids, function(sid) has_mouse_data(by_session[[sid]]$path))]
      mouse_mean_cc <- mean_pairwise_cc(lapply(mouse_data_sids, function(sid) mouse_resampled[[sid]]))
      mouse_icc_val <- icc(lapply(mouse_data_sids, function(sid) mouse_resampled[[sid]]))
      mouse_rows <- list()
      for (idx_a in seq_along(session_ids)) {
        a <- session_ids[idx_a]
        for (b in session_ids) {
          mouse_row <- list(
            sessionA = label_for(a, labels),
            sessionB = label_for(b, labels),
            cc = cc(mouse_resampled[[a]], mouse_resampled[[b]]),
            iou = iou(mouse_resampled[[a]], mouse_resampled[[b]], IOU_THRESH),
            coincidenceLevel = NA,
            mouseICC = NA
          )
          if (identical(a, b) && idx_a == 1) {
            mouse_row$coincidenceLevel <- mouse_coincidence_val
            mouse_row$mouseICC <- mouse_icc_val
          }
          mouse_rows[[length(mouse_rows) + 1]] <- mouse_row
        }
      }
      write_csv_tidy(
        mouse_rows, file.path(out_dir, paste0("mouse_", slide_slug, ".csv")),
        c("sessionA", "sessionB", "cc", "iou", "coincidenceLevel", "mouseICC")
      )
    }

    mean_cc <- mean_pairwise_cc(lapply(session_ids, function(sid) resampled[[sid]]))
    icc_val <- icc(lapply(session_ids, function(sid) resampled[[sid]]))
    coverages <- sapply(session_ids, function(sid) coverage(native_grid[[sid]]$grid) * 100.0)
    durations <- sapply(session_ids, function(sid) {
      d <- by_session[[sid]]$durationMs
      if (is.null(d)) 0 else as.numeric(d)
    })
    slide_summaries[[length(slide_summaries) + 1]] <- list(
      slide = slide_key, slug = slide_slug,
      sessions = sapply(session_ids, function(sid) label_for(sid, labels)),
      meanPairwiseCC = mean_cc, icc = icc_val,
      coverageMin = min(coverages), coverageMedian = stats::median(coverages), coverageMax = max(coverages),
      durationMin = min(durations), durationMedian = stats::median(durations), durationMax = max(durations),
      coincidenceLevel = coincidence_val,
      meanAvgZoom = .mean_of_col(slide_metric_rows, "avgZoom"),
      meanScanningRatePxPerMin = .mean_of_col(slide_metric_rows, "scanningRatePxPerMin"),
      meanDrillingRatePerMin = .mean_of_col(slide_metric_rows, "drillingRatePerMin"),
      meanMagnificationPercentage = .mean_of_col(slide_metric_rows, "magnificationPercentage"),
      # Phase 2 headline numbers.
      meanDwellInAnnotationPct = .mean_of_col(slide_metric_rows, "dwellInAnnotationPct"),
      annotationCoincidenceLevel = annotation_coincidence_val,
      meanCursorOverSlidePct = .mean_of_col(slide_metric_rows, "cursorOverSlidePct"),
      # PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2): mouse-dwell cross-reader
      # agreement, for the new summary.md "cursor agreement" line. NaN (-> "n/a" via .fmt) when
      # the slide has no mouse data at all, or fewer than 2 sessions carry it.
      meanPairwiseMouseCC = mouse_mean_cc,
      mouseICC = mouse_icc_val
    )

    # ------------------------------------------------------------------
    # reference / ROI comparison
    # ------------------------------------------------------------------
    if (!is.null(reference) && !(reference %in% session_ids)) {
      message(sprintf(
        "warning: --reference '%s' matches no session on slide '%s' (sessions present: %s)",
        reference, slide_key, paste(session_ids, collapse = ", ")
      ))
    }

    if (!is.null(reference) || !is.null(roi_rings)) {
      ref_map <- NULL; ref_mask <- NULL
      if (!is.null(roi_rings)) {
        img_w <- by_session[[session_ids[1]]]$imageWidth; if (is.null(img_w)) img_w <- 1
        img_h <- by_session[[session_ids[1]]]$imageHeight; if (is.null(img_h)) img_h <- 1
        ref_mask <- rasterize_feature_collection(roi_fc, tw, th, img_w, img_h)
        ref_map <- as.numeric(ref_mask)
      }
      if (!is.null(reference) && reference %in% session_ids) {
        ref_grid <- resampled[[reference]]
        if (is.null(ref_map)) ref_map <- normalise_max(ref_grid)
        if (is.null(ref_mask)) {
          mx <- max(ref_grid)
          ref_mask <- if (mx > 0) ref_grid > (IOU_THRESH * mx) else rep(FALSE, length(ref_grid))
        }
      }

      if (!is.null(ref_map) && !is.null(ref_mask)) {
        # Screening efficiency (Abe et al., *Cancer Cytopathology* 2026;e70132,
        # doi:10.1002/cncy.70132): computed ONCE per session here (not inside ref_row_fn below) so
        # the metrics_rows stamp can use plain `<-` on the ALREADY-APPENDED entry by index --
        # this file avoids `<<-` (superassignment) entirely, unlike the Python toolkit's closures,
        # which mutate the same dict object by reference with no special scoping needed. Mirrors
        # the Python port's in-memory-only stamp: metrics.csv's own fieldnames vector is unchanged
        # (write_csv_tidy's `r[fieldnames]` subset drops unlisted keys silently at write time);
        # .nav_accuracy_rows picks the 6 non-zoom fields up via NAV_ACCURACY_COLS instead. See
        # docs/superpowers/2026-09-12-screening-efficiency-review.md.
        se_by_sid <- list()
        metrics_row_base_idx <- length(metrics_rows) - length(session_ids) + 1L
        for (sid in session_ids) {
          f <- by_session[[sid]]
          se <- screening_efficiency(f$path, f$baseMagnification, ref_mask, tw, th, img_w, img_h)
          se_by_sid[[sid]] <- se
          mrow_idx <- metrics_row_base_idx + match(sid, session_ids) - 1L
          metrics_rows[[mrow_idx]]$timeToFirstRefFixMs <- se$timeToFirstRefFixMs
          metrics_rows[[mrow_idx]]$timeToFirstRefFixLpfMs <- se$timeToFirstRefFixLpfMs
          metrics_rows[[mrow_idx]]$refFixTotalMs <- se$refFixTotalMs
          metrics_rows[[mrow_idx]]$refFixTotalLpfMs <- se$refFixTotalLpfMs
          metrics_rows[[mrow_idx]]$refFixCount <- se$refFixCount
          metrics_rows[[mrow_idx]]$refFixVisitCount <- se$refFixVisitCount
        }

        ref_row_fn <- function(sid) {
          other <- resampled[[sid]]
          time_on <- sum(other[ref_mask])
          time_off <- sum(other[!ref_mask])
          denom <- max(sum(ref_mask), 1)
          ref_cov <- sum(other[ref_mask] > 0) / denom
          # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): precisionAtTopK/recall vs the SAME
          # ref_mask this row already compares against (whichever of --roi/--reference built it
          # above) -- see precision_recall_at_topk's docs.
          pr <- precision_recall_at_topk(other, ref_mask)
          se <- se_by_sid[[sid]]
          list(
            session = label_for(sid, labels),
            nss = nss(other, ref_mask),
            aucJudd = auc_judd(other, ref_mask),
            cc = cc(other, ref_map),
            iou = iou(other, ref_map, IOU_THRESH),
            refCoveragePct = ref_cov * 100.0,
            timeOnRefMs = time_on,
            timeOffRefMs = time_off,
            precisionAtTopK = pr$precision,
            recall = pr$recall,
            timeToFirstRefFixMs = se$timeToFirstRefFixMs,
            timeToFirstRefFixLpfMs = se$timeToFirstRefFixLpfMs,
            refFixTotalMs = se$refFixTotalMs,
            refFixTotalLpfMs = se$refFixTotalLpfMs,
            refFixCount = se$refFixCount,
            refFixVisitCount = se$refFixVisitCount,
            firstRefFixDownsample = se$firstRefFixDownsample,
            firstRefFixMagnification = se$firstRefFixMagnification
          )
        }
        # Pre-existing latent bug fix (found by Tier 3 C6's roi-only/no-reference fixture, the
        # first R test to exercise this combination): `session_ids != reference` when
        # `reference` is NULL evaluates to `logical(0)` (R's `!=` against NULL is length-0, not
        # broadcast), so `session_ids[logical(0)]` silently returns an EMPTY vector rather than
        # every session -- diverging from the Python toolkit's `sid != reference` (always TRUE
        # for a string `sid` when `reference is None`), which correctly keeps every session. Guard
        # explicitly so a `--roi`-only call (no `--reference`) includes every session, matching
        # Python exactly; unaffected when `reference` is a real string (identical to before).
        other_sids <- if (is.null(reference)) session_ids else session_ids[session_ids != reference]
        ref_rows <- lapply(other_sids, ref_row_fn)
        if (!is.null(reference) && reference %in% session_ids) {
          ref_rows <- c(list(ref_row_fn(reference)), ref_rows)
        }
        nss_vals <- sapply(ref_rows, function(r) if (is.nan(r$nss)) -1e9 else r$nss)
        ref_rows <- ref_rows[order(-nss_vals)]
        write_csv_tidy(
          ref_rows, file.path(out_dir, paste0("reference_", slide_slug, ".csv")),
          c("session", "nss", "aucJudd", "cc", "iou", "refCoveragePct", "timeOnRefMs", "timeOffRefMs",
            # Tier 3 C6 (docs/superpowers/specs/2026-07-23-...): appended, existing column order
            # above is unchanged.
            "precisionAtTopK", "recall",
            # Screening efficiency (Abe 2026, doi:10.1002/cncy.70132): appended, existing column
            # order above is unchanged. See
            # docs/superpowers/2026-09-12-screening-efficiency-review.md.
            "timeToFirstRefFixMs", "timeToFirstRefFixLpfMs", "refFixTotalMs",
            "refFixTotalLpfMs", "refFixCount", "refFixVisitCount",
            "firstRefFixDownsample", "firstRefFixMagnification")
        )
        reference_summaries[[length(reference_summaries) + 1]] <- list(
          slide = slide_key, slug = slide_slug, rows = ref_rows
        )
      }
    }

    # ------------------------------------------------------------------
    # scanpath (schema/3+ sessions only)
    # ------------------------------------------------------------------
    scan_sids <- session_ids[session_ids %in% names(path_seq)]
    if (length(scan_sids) > 0) {
      scan_rows <- list()
      for (a in scan_sids) {
        for (b in scan_sids) {
          r <- list(
            sessionA = label_for(a, labels),
            sessionB = label_for(b, labels),
            levenshteinSim = levenshtein_sim(path_seq[[a]], path_seq[[b]]),
            transitionEntropy = NA,
            # Tier 3 C3 (docs/superpowers/specs/2026-07-23-...): DTW distance between the two
            # sessions' raw viewport-center paths (NOT the grid-cell path_seq/levenshtein
            # sequence) -- a resolution-independent complement. Every scan_sids session has a
            # non-empty path by construction, so this is never blank here; always exactly 0.0 on
            # the diagonal (a == b), by construction of the DP itself.
            dtwDistance = dtw_distance(by_session[[a]]$path, by_session[[b]]$path)
          )
          if (identical(a, b)) {
            r$transitionEntropy <- transition_entropy(path_seq[[a]])
          }
          scan_rows[[length(scan_rows) + 1]] <- r
        }
      }
      write_csv_tidy(
        scan_rows, file.path(out_dir, paste0("scanpath_", slide_slug, ".csv")),
        c("sessionA", "sessionB", "levenshteinSim", "transitionEntropy", "dtwDistance")
      )
    }

    # ------------------------------------------------------------------
    # Tier 1 A5: top-15 per-session directed cell-transitions (path sessions only) -- surfaces
    # the already-implemented top_transitions ranking on each session's own visited-cell sequence
    # (path_seq, already computed above at the slide's common (tw, th) grid, same sequence
    # n_revisits/transition_entropy use).
    # ------------------------------------------------------------------
    if (length(scan_sids) > 0) {
      transition_rows <- list()
      for (sid in scan_sids) {
        label <- label_for(sid, labels)
        tt <- top_transitions(path_seq[[sid]], TRANSITIONS_TOP_N)
        for (t in tt) {
          transition_rows[[length(transition_rows) + 1]] <- list(
            session = label, fromCell = t$fromCell, toCell = t$toCell, count = t$count
          )
        }
      }
      if (length(transition_rows) > 0) {
        write_csv_tidy(
          transition_rows, file.path(out_dir, paste0("transitions_", slide_slug, ".csv")),
          c("session", "fromCell", "toCell", "count")
        )
      }
    }

    # ------------------------------------------------------------------
    # Tier 3 C1 (docs/superpowers/specs/2026-07-23-phase3-analysis-enrichment.md): per-session I-DT
    # fixations (path sessions only), same tidy long-format / gate-on-nonempty-rows convention as
    # transitions_<slug>.csv above -- the file is only written if at least one fixation was found
    # across every path-carrying session on this slide. Recomputes fixations_idt directly off each
    # session's own path (same recompute-don't-cache convention the magnification-split section
    # below uses for its own per-session band assignment).
    # ------------------------------------------------------------------
    if (length(scan_sids) > 0) {
      fixation_rows <- list()
      for (sid in scan_sids) {
        label <- label_for(sid, labels)
        f <- by_session[[sid]]
        fx <- fixations_idt(f$path)
        if (is.null(fx)) fx <- list()
        for (i in seq_along(fx)) {
          fxn <- fx[[i]]
          fixation_rows[[length(fixation_rows) + 1]] <- list(
            session = label, idx = i, startMs = fxn$startMs, durationMs = fxn$durationMs,
            centerImageX = fxn$centerImageX, centerImageY = fxn$centerImageY, nPoints = fxn$nPoints
          )
        }
      }
      if (length(fixation_rows) > 0) {
        write_csv_tidy(
          fixation_rows, file.path(out_dir, paste0("fixations_", slide_slug, ".csv")),
          c("session", "idx", "startMs", "durationMs", "centerImageX", "centerImageY", "nPoints")
        )
      }
    }

    # ------------------------------------------------------------------
    # magnification-split (Phase 1; Tier 2 B1 idle-exclusion + B3 canonical-band scheme):
    # per-session dwell time in each zoom band -- band ASSIGNMENT is unaffected by idle exclusion
    # (matches the tercile scheme's pre-existing behavior: quantile cuts, or the canonical
    # MAG_BAND_CUTS, are computed/applied over every step regardless of idle), only the per-band
    # bandTimeMs/bandTimePct SUM excludes idle steps' dt (B1).
    # ------------------------------------------------------------------
    if (length(scan_sids) > 0) {
      magband_rows <- list()
      for (sid in scan_sids) {
        f <- by_session[[sid]]
        path <- f$path
        base_mag <- f$baseMagnification
        img_w <- if (!is.null(f$imageWidth)) f$imageWidth else 1
        result <- magband_labels_for_scheme(path, base_mag, img_w, magbands, magband_scheme)
        bands <- result$bands
        scheme_used <- result$scheme
        if (length(bands) == 0) next
        dts <- step_durations_ms(path)
        idle <- idle_step_mask(path)
        active_dts <- dts[!idle]
        total_dt <- sum(active_dts)
        n_bands_used <- if (identical(scheme_used, "canonical")) CANONICAL_MAGBAND_COUNT else magbands
        label <- label_for(sid, labels)
        for (band in 0:(n_bands_used - 1)) {
          band_dt <- sum(dts[bands == band & !idle])
          magband_rows[[length(magband_rows) + 1]] <- list(
            session = label,
            band = band,
            bandTimeMs = band_dt,
            bandTimePct = if (total_dt > 0) band_dt / total_dt * 100.0 else 0.0,
            bandScheme = scheme_used
          )
        }
      }
      if (length(magband_rows) > 0) {
        write_csv_tidy(
          magband_rows, file.path(out_dir, paste0("magbands_", slide_slug, ".csv")),
          c("session", "band", "bandTimeMs", "bandTimePct", "bandScheme")
        )
      }
    }

    # ------------------------------------------------------------------
    # PT4 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P4): per-magnification-band
    # cross-reader agreement -- "do readers agree more at overview vs cell power" (Chakraborty).
    # For each of the 7 canonical magnification bands (MAG_BAND_LABELS), build every
    # CANONICAL-scheme session's own dwell-time-restricted raster for that band (reusing
    # raster_from_path's step_mask mechanism -- idle-excluded automatically, same mechanism the
    # magband_rows/figures blocks above already use), resample it to the slide's common (tw, th)
    # grid (same resampler compare_<slug>.csv/mouse_<slug>.csv use), then
    # mean_pairwise_cc/coincidence_level across the sessions that actually dwelled in that band.
    #
    # "Canonical-scheme session" is determined by RE-DERIVING (bands, scheme) via
    # magband_labels_for_scheme -- the SAME per-step band assignment the magband_rows loop above
    # already computes (recomputed here, not cached -- mirrors the recompute-don't-cache convention
    # elsewhere in this module) -- rather than calling canonical_mag_band_labels directly, so that a
    # slide run under `--magband-scheme tercile` transitively yields 0 canonical sessions (every
    # session's scheme comes back "tercile") and the file is skipped, exactly like a session whose
    # own baseMagnification/dsMilli are individually not computable (per-session tercile fallback)
    # is excluded from this file's population -- the filtered-population approach PT2's mouseICC
    # established for schema-gated sessions, not an all-or-nothing block.
    #
    # File-level gate: written iff >=2 sessions are canonical-scheme -- this single condition
    # subsumes both "--magband-scheme tercile" (0 canonical sessions) and "null baseMagnification"
    # (<2 canonical-capable sessions on this slide), both of which the spec says should skip the
    # file entirely. "Has any dwell in this band" is checked on the POST-resample grid (consistent
    # with what mean_pairwise_cc/coincidence_level actually consume) -- a session whose dwell in
    # this band falls entirely into a grid cell dropped by nearest-neighbour resampling is
    # (correctly) excluded from that band's row. Written (possibly with zero data rows, mirroring
    # consensus_count_<slug>.csv's own "header-only is fine" convention) whenever the file-level
    # gate passes, regardless of whether any individual band clears its own >=2-dwelling-sessions
    # per-row threshold.
    # ------------------------------------------------------------------
    if (length(scan_sids) > 0) {
      canonical_band_grids <- vector("list", CANONICAL_MAGBAND_COUNT)
      for (band in 0:(CANONICAL_MAGBAND_COUNT - 1)) canonical_band_grids[[band + 1]] <- list()
      n_canonical_sessions <- 0L
      for (sid in scan_sids) {
        f <- by_session[[sid]]
        path <- f$path
        base_mag <- f$baseMagnification
        img_w <- if (!is.null(f$imageWidth)) f$imageWidth else 1
        img_h <- if (!is.null(f$imageHeight)) f$imageHeight else 1
        result_pt4 <- magband_labels_for_scheme(path, base_mag, img_w, magbands, magband_scheme)
        bands_pt4 <- result_pt4$bands
        scheme_used_pt4 <- result_pt4$scheme
        if (!identical(scheme_used_pt4, "canonical") || length(bands_pt4) == 0) next
        n_canonical_sessions <- n_canonical_sessions + 1L
        ng <- native_grid[[sid]]
        gw <- ng$gw; gh <- ng$gh
        for (band in 0:(CANONICAL_MAGBAND_COUNT - 1)) {
          band_step_mask <- (bands_pt4 == band)
          if (!any(band_step_mask)) next
          raster_b <- raster_from_path(path, img_w, img_h, gw, gh, step_mask = band_step_mask)
          if (is.null(raster_b)) next
          resampled_b <- resample_nn(raster_b, gw, gh, tw, th)
          if (!any(resampled_b > 0)) next
          canonical_band_grids[[band + 1]][[length(canonical_band_grids[[band + 1]]) + 1]] <- resampled_b
        }
      }
      if (n_canonical_sessions >= 2) {
        magband_agreement_rows <- list()
        for (band in 0:(CANONICAL_MAGBAND_COUNT - 1)) {
          grids_b <- canonical_band_grids[[band + 1]]
          if (length(grids_b) < 2) next
          magband_agreement_rows[[length(magband_agreement_rows) + 1]] <- list(
            band = MAG_BAND_LABELS[band + 1],
            nSessions = length(grids_b),
            meanPairwiseCC = mean_pairwise_cc(grids_b),
            coincidenceLevel = coincidence_level(grids_b, IOU_THRESH)
          )
        }
        write_csv_tidy(
          magband_agreement_rows, file.path(out_dir, paste0("magband_agreement_", slide_slug, ".csv")),
          c("band", "nSessions", "meanPairwiseCC", "coincidenceLevel")
        )
      }
    }

    # ------------------------------------------------------------------
    # figures
    # ------------------------------------------------------------------
    if (make_figures) {
      slide_out <- file.path(out_dir, slide_slug)
      dir.create(slide_out, showWarnings = FALSE, recursive = TRUE)
      for (sid in session_ids) {
        f <- by_session[[sid]]
        ng <- native_grid[[sid]]
        label <- label_for(sid, labels)
        sess_slug <- slug(sid)
        plot_heatmap(
          ng$grid, ng$gw, ng$gh, paste0(slide_key, " - ", label),
          file.path(slide_out, paste0(sess_slug, "_heatmap.png"))
        )
        path <- f$path
        if (!is.null(path) && length(path) > 0) {
          img_w <- if (!is.null(f$imageWidth)) f$imageWidth else 1
          img_h <- if (!is.null(f$imageHeight)) f$imageHeight else 1
          plot_scanpath(
            ng$grid, ng$gw, ng$gh, path, img_w, img_h,
            paste0(label, " scanpath"), file.path(slide_out, paste0(sess_slug, "_scanpath.png"))
          )
          plot_coverage_over_time(
            path, ng$gw, ng$gh, img_w, img_h,
            paste0(label, " coverage over time"), file.path(slide_out, paste0(sess_slug, "_coverage.png"))
          )

          # Phase 1: scanpath-rasterized fine heatmap at `res`, independent of the recorded grid
          # -- the trustworthy high-magnification map.
          res_dims <- .res_grid_dims(img_w, img_h, res)
          res_gw <- res_dims[1]; res_gh <- res_dims[2]
          raster <- raster_from_path(path, img_w, img_h, res_gw, res_gh)
          if (!is.null(raster)) {
            plot_heatmap(
              raster, res_gw, res_gh, paste0(label, " scanpath raster (", res, "px)"),
              file.path(slide_out, paste0(sess_slug, "_scanpath_raster.png"))
            )
          }

          # Phase 1 (Tier 2 B3: canonical-scheme-aware): magnification-split heatmaps, one per zoom
          # band. raster_from_path itself excludes idle steps (Tier 2 B1), so an idle step
          # contributes no heat to any band's figure either.
          base_mag <- f$baseMagnification
          band_result <- magband_labels_for_scheme(path, base_mag, img_w, magbands, magband_scheme)
          bands <- band_result$bands
          scheme_used_fig <- band_result$scheme
          if (length(bands) > 0) {
            n_bands_used_fig <- if (identical(scheme_used_fig, "canonical")) CANONICAL_MAGBAND_COUNT else magbands
            for (band in 0:(n_bands_used_fig - 1)) {
              step_mask <- (bands == band)
              if (!any(step_mask)) next
              raster_b <- raster_from_path(path, img_w, img_h, res_gw, res_gh, step_mask = step_mask)
              if (!is.null(raster_b)) {
                plot_heatmap(
                  raster_b, res_gw, res_gh, paste0(label, " - zoom band ", band),
                  file.path(slide_out, paste0(sess_slug, "_magband", band, ".png"))
                )
              }
            }
          }

          # Tier 3 C2 (docs/superpowers/specs/2026-07-23-...): mouse-dwell map figure, schema/5
          # only -- reuses the same heatmap plotting helper + `res` resolution as the
          # scanpath-raster figure above. Not part of the numeric-parity contract.
          if (has_mouse_data(path)) {
            mouse_raster_fig <- mouse_raster_from_path(path, img_w, img_h, res_gw, res_gh)
            if (!is.null(mouse_raster_fig)) {
              plot_heatmap(
                mouse_raster_fig, res_gw, res_gh, paste0(label, " mouse dwell"),
                file.path(slide_out, paste0(sess_slug, "_mousemap.png"))
              )
            }
          }
        }
      }

      # Tier 1 A6: multi-reader scanpath overlay -- one PNG per slide, every path-carrying
      # session's viewport-center path on a shared axis. Gated on scan_sids (same path-presence
      # gate as scanpath_<slug>.csv/magbands_<slug>.csv) so a slide with no paths at all doesn't
      # emit a trivially-empty overlay.
      if (length(scan_sids) > 0) {
        overlay_labels <- sapply(scan_sids, function(sid) label_for(sid, labels))
        overlay_paths <- lapply(scan_sids, function(sid) by_session[[sid]]$path)
        plot_scanpath_multi_overlay(
          overlay_labels, overlay_paths, paste0(slide_key, " - all scanpaths"),
          file.path(out_dir, paste0("overlay_", slide_slug, "_scanpaths.png"))
        )
      }
    }
  }

  write_csv_tidy(
    metrics_rows, file.path(out_dir, "metrics.csv"),
    c(
      "slide", "session", "durationMs", "sampleCount", "coveragePct", "entropy",
      "comX", "comY", "peakDwell", "nHotspots", "pathPoints", "pathLengthPx",
      "nRevisits", "transitionEntropy",
      "avgZoom", "zoomVariance", "zoomRange", "magnificationPercentage",
      "scanningRatePxPerMin", "drillingRatePerMin", "pathVelocityPxPerSec",
      "linearity", "searchFocusRatio", "baseMagnification", "pathTruncated",
      "nAnnotations", "annotatedAreaPx", "dwellInAnnotationPct", "annotationReentryCount",
      "enrichmentRatio", "cursorOverSlidePct", "mouseViewportCouplingPx",
      # Tier 1 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing column
      # order above is unchanged.
      "meanAbsTurnAngleDeg", "turnAngleEntropy", "mousePathLengthPx", "mouseVelocityPxPerSec",
      "activeFractionPct",
      # Tier 2 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing column
      # order above (incl. Tier 1) is unchanged. B1: idleMs/activeSpanMs. B2:
      # avgZoomLog2W/drillingRateOctavesPerMin. B4: magnificationSource.
      "idleMs", "activeSpanMs", "avgZoomLog2W", "drillingRateOctavesPerMin",
      "magnificationSource",
      # Tier 3 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing column
      # order above (incl. Tier 1/2) is unchanged. C1: I-DT fixation extraction.
      "nFixations", "meanFixationMs", "medianFixationMs", "sdFixationMs", "fixationsPerMin",
      # Tier 3 C2/C4 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing
      # column order above (incl. Tier 1/2/C1) is unchanged. C2: mouse-dwell coverage/entropy.
      # C4: segment-level linearity.
      "mouseCoveragePct", "mouseEntropy", "meanSegmentLinearity",
      # Tier 3 C6 additive columns (docs/superpowers/specs/2026-07-23-...): appended, existing
      # column order above (incl. Tier 1/2/C1/C2/C4) is unchanged.
      "annotatedAreaUnionPx", "visitCountJaccard",
      # PT3 additive column (docs/superpowers/specs/2026-07-25-enrichment-polish.md P3): appended
      # at the very END, existing column order above (incl. Tier 1/2/3/C6) is unchanged -- see the
      # PT3 note where meanSegmentLinearity is documented for why this isn't interleaved next to
      # it despite the conceptual relation.
      "meanSegmentLinearityROI"
    )
  )

  if (any(vapply(decision_rows, function(r) !is.na(r$diagnosis) && nzchar(r$diagnosis), logical(1)))) {
    write_csv_tidy(
      decision_rows, file.path(out_dir, "decisions.csv"),
      c("slide", "sessionId", "session", "diagnosis", "confidence", "confidenceScaled",
        "decisionMs", "decisionLatencyMs", "correctDx", "correct",
        # Tier 3 C5 (2026-07-23): appended, existing column order above is unchanged.
        "promptShownMs", "responseLatencyMs")
    )
  } else {
    message("warning: no decisions found in any fragment; decisions.csv not written")
  }

  nav_result <- .nav_accuracy_rows(metrics_rows, decision_rows)
  nav_rows <- nav_result$rows
  had_graded <- nav_result$had_any_graded
  if (had_graded) {
    write_csv_tidy(
      nav_rows, file.path(out_dir, "nav_accuracy.csv"),
      c("metric", "n", "pointBiserialR", "meanCorrect", "meanIncorrect",
        "medianCorrect", "medianIncorrect", "meanDiff")
    )
  }

  write_summary(
    out_dir, groups, slide_summaries, reference_summaries, decision_rows, nav_rows, had_graded,
    metrics_rows
  )
  utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
}

#' Write `summary.md`: slide/session counts, (Tier 2 B4, when any path-carrying session exists) a
#' magnification-source caveat line, per-slide mean pairwise CC + ICC(2,1) + coverage/duration
#' spread, the reference ranking when applicable, and (Phase 3, gated on `had_graded`) a
#' "Navigation <-> diagnostic accuracy" section. Matches the Python toolkit's `_write_summary`
#' structure/section headers exactly.
write_summary <- function(out_dir, groups, slide_summaries, reference_summaries,
                           decision_rows = NULL, nav_rows = NULL, had_graded = FALSE,
                           metrics_rows = NULL) {
  lines <- c("# Blinded-focus analysis summary", "", paste0("- Slides analyzed: ", length(groups)), "")
  # Tier 2 B4: magnification-source caveat -- how many path-carrying sessions used a proxy
  # (downsample-relative or w-proxy) magnification rather than the true objective power, so a
  # reader knows pooled avgZoom-family numbers may mix the two. Only emitted when at least one
  # path-carrying session exists at all (nothing to caveat otherwise).
  magsrc <- Filter(
    function(v) !is.null(v) && !is.na(v) && nzchar(v),
    lapply(if (is.null(metrics_rows)) list() else metrics_rows, function(r) r$magnificationSource)
  )
  if (length(magsrc) > 0) {
    magsrc_vec <- vapply(magsrc, as.character, character(1))
    n_true <- sum(magsrc_vec == "true")
    n_proxy <- sum(magsrc_vec == "proxy-downsample")
    lines <- c(lines, paste0(
      "> Magnification source: ", n_true, "/", length(magsrc_vec), " path-carrying sessions use ",
      "true objective magnification (baseMagnification present); ", n_proxy, "/", length(magsrc_vec),
      " use a proxy (downsample- or window-width-relative) value -- see `magnificationSource` in ",
      "metrics.csv. Proxy-magnification rows are not directly comparable to true-magnification ",
      "rows for avgZoom/zoomVariance/zoomRange/avgZoomLog2W."
    ))
    lines <- c(lines, "")
  }
  lines <- c(lines, "## Per-slide agreement", "")
  for (s in slide_summaries) {
    lines <- c(lines, paste0("### ", s$slide))
    lines <- c(lines, paste0("- sessions (", length(s$sessions), "): ", paste(s$sessions, collapse = ", ")))
    lines <- c(lines, paste0("- mean pairwise CC: ", .fmt(s$meanPairwiseCC)))
    lines <- c(lines, paste0("- ICC(2,1): ", .fmt(s$icc)))
    lines <- c(lines, paste0(
      "- coverage % (min/median/max): ", .fmt(s$coverageMin, 1), " / ",
      .fmt(s$coverageMedian, 1), " / ", .fmt(s$coverageMax, 1)
    ))
    lines <- c(lines, paste0(
      "- durationMs (min/median/max): ", .fmt(s$durationMin, 0), " / ",
      .fmt(s$durationMedian, 0), " / ", .fmt(s$durationMax, 0)
    ))
    lines <- c(lines, paste0(
      "- coincidence level (>=2 readers, thresh=", IOU_THRESH, "): ", .fmt(s$coincidenceLevel, 3)
    ))
    lines <- c(lines, paste0(
      "- mean avg zoom / scanning rate (px/min) / drilling rate (per min) / magnification %: ",
      .fmt(s$meanAvgZoom, 2), " / ", .fmt(s$meanScanningRatePxPerMin, 1), " / ",
      .fmt(s$meanDrillingRatePerMin, 2), " / ", .fmt(s$meanMagnificationPercentage, 3)
    ))
    lines <- c(lines, paste0(
      "- annotation coverage: mean dwell-in-annotation % = ",
      .fmt(s$meanDwellInAnnotationPct, 1), ", cross-user annotation coincidence (>=2 readers, thresh=",
      IOU_THRESH, ") = ", .fmt(s$annotationCoincidenceLevel, 3)
    ))
    lines <- c(lines, paste0(
      "- cursor coupling: mean % of path time cursor was over the slide = ",
      .fmt(s$meanCursorOverSlidePct, 1)
    ))
    # PT2 (docs/superpowers/specs/2026-07-25-enrichment-polish.md P2).
    lines <- c(lines, paste0(
      "- cursor agreement: mean pairwise mouse CC = ", .fmt(s$meanPairwiseMouseCC),
      ", mouse ICC(2,1) = ", .fmt(s$mouseICC)
    ))
    lines <- c(lines, "")
  }
  if (length(reference_summaries) > 0) {
    lines <- c(lines, "## Reference ranking (higher NSS/CC = closer to the reference/ROI)", "")
    for (r in reference_summaries) {
      lines <- c(lines, paste0("### ", r$slide))
      for (row in r$rows) {
        lines <- c(lines, paste0(
          "- ", row$session, ": NSS=", .fmt(row$nss), ", AUC-Judd=", .fmt(row$aucJudd),
          ", CC=", .fmt(row$cc), ", IoU=", .fmt(row$iou)
        ))
      }
      lines <- c(lines, "")
    }
  }

  # ------------------------------------------------------------------
  # Navigation <-> diagnostic accuracy (Phase 3): only when hand-graded decisions exist at all
  # (--graded). Never rendered otherwise -- no section, no nav_accuracy.csv, nothing implying a
  # correlation was computed from ungraded data.
  # ------------------------------------------------------------------
  if (isTRUE(had_graded)) {
    lines <- c(lines, "## Navigation ↔ diagnostic accuracy", "")
    lines <- c(lines, paste0(
      "> Pilot-scale caveats: viewport-only navigation has a null-result precedent; at ",
      "n=5–20 only r, its n, and group means/medians are defensible — no p-values/CIs. ",
      "Coincidence/accuracy numbers are cohort-composition-dependent."
    ))
    lines <- c(lines, "")
    graded_dec <- Filter(function(r) !is.na(r$correct), decision_rows)
    n_all <- length(graded_dec)
    if (n_all > 0) {
      total_correct <- sum(vapply(graded_dec, function(r) r$correct, numeric(1)))
      acc <- total_correct / n_all
      suffix <- if (n_all < 5) " (n<5 → descriptive only)" else ""
      lines <- c(lines, paste0(
        "- overall accuracy: ", total_correct, "/", n_all, " = ", .fmt(acc * 100, 1), "%", suffix
      ))
    }
    for (nr in nav_rows) {
      lines <- c(lines, paste0(
        "- ", nr$metric, ": r=", .fmt(nr$pointBiserialR), " (n=", nr$n, "), ",
        "meanCorrect=", .fmt(nr$meanCorrect), ", meanIncorrect=", .fmt(nr$meanIncorrect), ", ",
        "meanDiff=", .fmt(nr$meanDiff)
      ))
    }
    calib <- .calibration_stats(if (is.null(decision_rows)) list() else decision_rows)
    lines <- c(lines, paste0(
      "- calibration (n=", calib$n, "): calibrationGap=", .fmt(calib$gap), ", ",
      "brierScore=", .fmt(calib$brier), ", confidenceAccuracyR=", .fmt(calib$conf_acc_r)
    ))
    lines <- c(lines, "")
  }

  writeLines(lines, file.path(out_dir, "summary.md"))
}
