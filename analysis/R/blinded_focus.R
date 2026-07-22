#!/usr/bin/env Rscript
# blinded_focus.R — standalone R analysis toolkit for QuPath atlas blinded-focus fragments.
#
# Reads anonymised viewport-dwell fragments (schema `atlas-focus-contribution/{1,2,3,4}`) produced
# by the qupath-extension-atlas blinded recording feature and computes the standard saliency /
# eye-tracking evaluation set: spatial similarity (CC, SIM, KLD, NSS, AUC-Judd, IoU),
# inter-observer agreement (mean pairwise CC, ICC(2,1) via `irr::icc`), reference/ROI comparison,
# scanpath sequence metrics (visited-cell Levenshtein similarity, transition entropy), and (Phase 1
# navigation-research upgrade) a scanpath-rasterized fine dwell grid plus a zoom/navigation metric
# family (avg/variance/range zoom, magnification percentage, scanning/drilling rate, path
# velocity/linearity/search-focus, cross-session coincidence/region-coverage).
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

#: Accepted fragment schemas. /1 = fixed-weight sample counts (visible "Contribute" mode).
#: /2, /3, /4 = real dwell-ms (blinded recording, weightUnit="ms"). /3, /4 additionally have
#: "path" (/3 points are 5-element `[tRelMs,cx,cy,w,h]`; /4 points are 6-element
#: `[tRelMs,cx,cy,w,h,dsMilli]` and /4 fragments also carry `baseMagnification`/`pathTruncated`).
#: No branching on the schema string beyond this membership check anywhere in the toolkit — 5- vs
#: 6-element points are told apart by `length(point)`, and `baseMagnification`/`pathTruncated` are
#: read via `f$field` (NULL when absent), so /1-/3 fragments degrade to blank CSV cells
#: automatically (mirrors `blinded_focus.io`'s doc comment in the Python toolkit).
SCHEMAS <- c(
  "atlas-focus-contribution/1",
  "atlas-focus-contribution/2",
  "atlas-focus-contribution/3",
  "atlas-focus-contribution/4"
)

# ---------------------------------------------------------------------------
# io: fragment loading (dirs / .zip / single files), grouping, slugging, labels
# ---------------------------------------------------------------------------

#' Test whether a parsed JSON object is a valid blinded-focus fragment. Besides the required-field
#' checks, also validates that `length(grid) == gridWidth*gridHeight` — a mismatch would otherwise
#' make later `matrix(..., nrow=gh, ncol=gw)` / reshape calls throw (too few values) or silently
#' misalign rows/cols (too many, matrix() just recycles), so it must be caught here at load time
#' rather than downstream.
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
  if (length(gw) != 1 || length(gh) != 1 || is.na(gw) || is.na(gh)) {
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

#' Resolve a sessionId to its human-readable label if `labels` has one, else the sessionId itself.
label_for <- function(sid, labels) {
  lab <- labels[[sid]]
  if (is.null(lab)) sid else lab
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
resample_nn <- function(grid, gw, gh, tw, th) {
  gw <- as.integer(gw); gh <- as.integer(gh); tw <- as.integer(tw); th <- as.integer(th)
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

#' Top-`n` `(row, col, value)` cells by dwell value, descending. Not used by `run_analysis`'s
#' output pipeline directly (mirrors `blinded_focus.metrics.top_hotspots` in Python for parity /
#' potential ad-hoc use).
top_hotspots <- function(grid, gw, gh, n = 5) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  g <- as.numeric(grid)
  ord <- order(g, decreasing = TRUE)[seq_len(min(n, length(g)))]
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

#' Rebuild a `gh x gw` dwell-ms grid (flat, row-major) directly from the scanpath, independent of
#' the recorded `grid` resolution. For each step, `dt` (see `step_durations_ms`) is attributed to
#' the viewport rectangle of point i (`cx +/- w/2, cy +/- h/2`), clamped to `[0,img_w] x
#' [0,img_h]`, spread evenly across the covered cells (`floor` lower bound, `ceiling(...)-1` upper
#' bound — not `floor` — so a rect edge exactly on a cell boundary doesn't spuriously include the
#' next cell). If the clamped rect collapses (viewport entirely off-image), the whole `dt` lands on
#' the single cell containing the clamped center. Steps with `dt<=0` contribute nothing.
#' `step_mask`, if given, is a logical vector of length `nrow(pm)-1` (steps where it is FALSE are
#' skipped — used by the magnification-band split to reuse this same raster math per band).
#' Returns `NULL` for a 0/1-point path (a `dt` requires two points). Exact port of
#' `blinded_focus.metrics.raster_from_path`.
raster_from_path <- function(path, img_w, img_h, gw, gh, step_mask = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(NULL)
  }
  gw <- as.integer(gw); gh <- as.integer(gh)
  img_w <- if (!is.null(img_w) && length(img_w) && img_w != 0) as.numeric(img_w) else 1.0
  img_h <- if (!is.null(img_h) && length(img_h) && img_h != 0) as.numeric(img_h) else 1.0
  dts <- step_durations_ms(path)
  grid <- matrix(0.0, nrow = gh, ncol = gw)
  for (i in seq_len(n - 1)) {
    dt <- dts[i]
    if (dt <= 0) next
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
point_zoom <- function(point, base_mag = NULL, img_w = NULL) {
  has_ds <- length(point) >= 6
  if (has_ds) {
    ds_milli <- as.numeric(point[6])
    if (is.na(ds_milli) || ds_milli <= 0) ds_milli <- 1000.0
    if (!is.null(base_mag) && !is.na(base_mag) && as.numeric(base_mag) > 0) {
      return(as.numeric(base_mag) / (ds_milli / 1000.0))
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

#' Fraction of consecutive scanpath transitions that are zoom-IN or same-zoom (after Ghezloo):
#' `|{i : zoom[i+1] >= zoom[i]}| / (n-1)`. Exact `>=`, no tolerance -- `point_zoom` is deterministic
#' on integer-quantized inputs, so a held zoom level produces bit-identical doubles (see the Python
#' docstring for the full determinism argument). `0.0` if the path has fewer than 2 points.
magnification_percentage <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  zooms <- .zoom_series(path, base_mag, img_w)
  diffs <- zooms[2:n] - zooms[1:(n - 1)]
  sum(diffs >= 0) / length(diffs)
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
#' unchanged (see `.step_zoom_changed`), normalized by the path's **total duration**
#' (`(t[last]-t[first])/60000`, minutes -- the deliberate denominator choice documented in the
#' Python `scanning_rate_px_per_min` docstring: total session time, not time-spent-scanning, so the
#' rate is comparable across sessions with different scanning/drilling mixes). `0.0` if the path
#' has fewer than 2 points or non-positive total duration.
scanning_rate_px_per_min <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  duration_min <- (pm[n, 1] - pm[1, 1]) / 60000.0
  if (duration_min <= 0) {
    return(0.0)
  }
  changed <- .step_zoom_changed(path, base_mag, img_w)
  pan <- 0.0
  for (i in seq_len(n - 1)) {
    if (!changed[i]) {
      x0 <- pm[i, 2]; y0 <- pm[i, 3]
      x1 <- pm[i + 1, 2]; y1 <- pm[i + 1, 3]
      pan <- pan + sqrt((x1 - x0)^2 + (y1 - y0)^2)
    }
  }
  pan / duration_min
}

#' "Drilling" rate (events/min): count of zoom-change steps (see `.step_zoom_changed`) per minute
#' of the path's total duration (same denominator as `scanning_rate_px_per_min`). `0.0` if the path
#' has fewer than 2 points or non-positive total duration.
drilling_rate_per_min <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  duration_min <- (pm[n, 1] - pm[1, 1]) / 60000.0
  if (duration_min <= 0) {
    return(0.0)
  }
  changed <- .step_zoom_changed(path, base_mag, img_w)
  sum(changed) / duration_min
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

#' Median of per-step velocities (see `.step_velocities_px_per_sec`). `0.0` if the path has fewer
#' than 2 points.
path_velocity_px_per_sec <- function(path) {
  vels <- .step_velocities_px_per_sec(path)
  if (length(vels) == 0) {
    return(0.0)
  }
  stats::median(vels)
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
#' (session-relative, not a fixed absolute cutoff). `0.0` if the path has fewer than 2 points or
#' zero total dt.
search_focus_ratio <- function(path, base_mag = NULL, img_w = NULL) {
  pm <- as_path_matrix(path)
  n <- nrow(pm)
  if (is.null(n) || n < 2) {
    return(0.0)
  }
  zooms <- vapply(seq_len(n - 1), function(i) point_zoom(pm[i, ], base_mag, img_w), numeric(1))
  dts <- step_durations_ms(path)
  vels <- .step_velocities_px_per_sec(path)
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
#' **2 or more** of the given grids (already resampled to a common shape). `NaN` if fewer than 2
#' grids are given (undefined for a single reader); `0.0` for an empty (zero-cell) grid shape.
coincidence_level <- function(grids, thresh = 0.1) {
  if (length(grids) < 2) {
    return(NaN)
  }
  normed <- lapply(grids, normalise_max)
  counts <- rep(0, length(normed[[1]]))
  for (g in normed) {
    counts <- counts + as.numeric(g > thresh)
  }
  if (length(counts) == 0) {
    return(0.0)
  }
  sum(counts >= 2) / length(counts)
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

#' Flatten a GeoJSON Polygon/MultiPolygon geometry into a list of rings (each a 2-col matrix of
#' `(x, y)`). All rings (exterior + holes) are returned; even-odd counting handles holes.
.extract_rings <- function(geometry) {
  gtype <- geometry$type
  coords <- geometry$coordinates
  rings <- list()
  if (is.null(gtype) || is.null(coords)) {
    return(rings)
  }
  if (gtype == "Polygon") {
    for (ring in coords) {
      rings[[length(rings) + 1]] <- matrix(as.numeric(ring[, 1:2]), ncol = 2)
    }
  } else if (gtype == "MultiPolygon") {
    for (poly in coords) {
      for (ring in poly) {
        rings[[length(rings) + 1]] <- matrix(as.numeric(ring[, 1:2]), ncol = 2)
      }
    }
  }
  rings
}

#' Load a QuPath-exported GeoJSON (Feature, FeatureCollection, or bare geometry) polygon and
#' return its rings (image-px coordinates) for rasterization.
load_roi_rings <- function(roi_path) {
  d <- jsonlite::fromJSON(roi_path, simplifyVector = FALSE)
  if (!is.null(d$type) && d$type == "FeatureCollection") {
    rings <- list()
    for (feat in d$features) {
      rings <- c(rings, .extract_rings(feat$geometry))
    }
    return(rings)
  }
  if (!is.null(d$type) && d$type == "Feature") {
    return(.extract_rings(d$geometry))
  }
  .extract_rings(d)
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

# ---------------------------------------------------------------------------
# ggplot2 figure builders
# ---------------------------------------------------------------------------

#' Long-format (row, col, value) data frame for a row-major flat `grid`, used by all the raster
#' plots below. `row`/`col` are 0-based grid indices (as in the Python `_grid2d` reshape).
.grid_long_df <- function(grid, gw, gh) {
  gw <- as.integer(gw); gh <- as.integer(gh)
  data.frame(
    row = rep(0:(gh - 1), each = gw),
    col = rep(0:(gw - 1), times = gh),
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

#' Run the full blinded-focus pipeline over `inputs` (files/dirs/zips) into `out_dir`. Returns the
#' `metrics.csv` rows as a data frame (for programmatic/test use), and writes:
#'
#' - `metrics.csv` — one row per (slide, session); includes the Phase-1 zoom/navigation metric
#'   family (`avgZoom`, `zoomVariance`, `zoomRange`, `magnificationPercentage`,
#'   `scanningRatePxPerMin`, `drillingRatePerMin`, `pathVelocityPxPerSec`, `linearity`,
#'   `searchFocusRatio`) plus `baseMagnification`/`pathTruncated` passthrough — blank for sessions
#'   with no `path`.
#' - per slide: `compare_<slug>.csv` (pairwise cc/sim/iou, tidy long format), `consensus_<slug>.png`.
#'   Also carries a slide-level `coincidenceLevel` (one row) and a per-session
#'   `regionCoveragePct` (vs the slide consensus).
#' - per slide, when `reference`/`roi` given: `reference_<slug>.csv`.
#' - per slide, when any session has a schema/3 or /4 `path`: `scanpath_<slug>.csv`, and (Phase 1)
#'   `magbands_<slug>.csv` — per-session dwell time in each of `magbands` within-path zoom bands.
#' - `summary.md` — counts, per-slide agreement, reference ranking, headline zoom/scanning numbers.
#' - with `make_figures=TRUE`: per-(slide,session) heatmap/scanpath/coverage-over-time PNGs under
#'   `<out_dir>/<slug>/`, plus (Phase 1, when a path exists) a scanpath-rasterized fine heatmap at
#'   `res` resolution and one heatmap per magnification band.
#'
#' `res` sets the longest-side resolution of the scanpath-rasterized fine/magband heatmaps (see
#' `.res_grid_dims`); `magbands` sets the number of within-path zoom bands for the
#' magnification-split analysis (see `zoom_band_labels`).
analyze <- function(inputs, out_dir, reference = NULL, roi = NULL, labels_csv = NULL,
                     make_figures = FALSE, res = DEFAULT_RES, magbands = DEFAULT_MAGBANDS) {
  dir.create(out_dir, showWarnings = FALSE, recursive = TRUE)
  fragments <- load_fragments(inputs)
  if (length(fragments) == 0) {
    message("warning: no valid fragments found (schema atlas-focus-contribution/{1,2,3,4})")
  }
  labels <- load_labels(labels_csv)
  groups <- group_by_slide(fragments)
  roi_rings <- if (!is.null(roi)) load_roi_rings(roi) else NULL

  metrics_rows <- list()
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
    path_seq <- list() # sessionId -> visited-cell sequence (schema/3 only)

    for (sid in session_ids) {
      f <- by_session[[sid]]
      gw <- as.integer(f$gridWidth); gh <- as.integer(f$gridHeight)
      grid <- as.numeric(f$grid)
      native_grid[[sid]] <- list(grid = grid, gw = gw, gh = gh)
      resampled[[sid]] <- resample_nn(grid, gw, gh, tw, th)

      com <- center_of_mass(grid, gw, gh)
      base_mag <- f$baseMagnification
      img_w <- if (!is.null(f$imageWidth)) f$imageWidth else 1
      img_h <- if (!is.null(f$imageHeight)) f$imageHeight else 1
      row <- list(
        slide = slide_key,
        session = label_for(sid, labels),
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
        # Passthrough fragment-level fields (schema/4; NA -> blank for /1,/2,/3 which lack them).
        baseMagnification = if (!is.null(base_mag)) as.numeric(base_mag) else NA,
        pathTruncated = if (!is.null(f$pathTruncated)) as.logical(f$pathTruncated) else NA
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
      }
      metrics_rows[[length(metrics_rows) + 1]] <- row
    }

    # ------------------------------------------------------------------
    # cross-user compare (pairwise cc/sim/iou + consensus + agreement summary)
    # ------------------------------------------------------------------
    # This slide's just-appended metrics_rows entries (one per session, same order as
    # session_ids) -- reused below for the zoom/scanning summary aggregates.
    slide_metric_rows <- metrics_rows[
      seq(length(metrics_rows) - length(session_ids) + 1, length(metrics_rows))
    ]
    consensus_grid <- Reduce(`+`, lapply(session_ids, function(sid) normalise_max(resampled[[sid]]))) /
      length(session_ids)
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
          regionCoveragePct = NA
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
        "coincidenceLevel", "regionCoveragePct")
    )
    plot_heatmap(
      consensus_grid, tw, th, paste0("Consensus - ", slide_key),
      file.path(out_dir, paste0("consensus_", slide_slug, ".png"))
    )

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
      meanMagnificationPercentage = .mean_of_col(slide_metric_rows, "magnificationPercentage")
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
        ref_mask <- rasterize_roi(roi_rings, tw, th, img_w, img_h)
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
        ref_row_fn <- function(sid) {
          other <- resampled[[sid]]
          time_on <- sum(other[ref_mask])
          time_off <- sum(other[!ref_mask])
          denom <- max(sum(ref_mask), 1)
          ref_cov <- sum(other[ref_mask] > 0) / denom
          list(
            session = label_for(sid, labels),
            nss = nss(other, ref_mask),
            aucJudd = auc_judd(other, ref_mask),
            cc = cc(other, ref_map),
            iou = iou(other, ref_map, IOU_THRESH),
            refCoveragePct = ref_cov * 100.0,
            timeOnRefMs = time_on,
            timeOffRefMs = time_off
          )
        }
        other_sids <- session_ids[session_ids != reference]
        ref_rows <- lapply(other_sids, ref_row_fn)
        if (!is.null(reference) && reference %in% session_ids) {
          ref_rows <- c(list(ref_row_fn(reference)), ref_rows)
        }
        nss_vals <- sapply(ref_rows, function(r) if (is.nan(r$nss)) -1e9 else r$nss)
        ref_rows <- ref_rows[order(-nss_vals)]
        write_csv_tidy(
          ref_rows, file.path(out_dir, paste0("reference_", slide_slug, ".csv")),
          c("session", "nss", "aucJudd", "cc", "iou", "refCoveragePct", "timeOnRefMs", "timeOffRefMs")
        )
        reference_summaries[[length(reference_summaries) + 1]] <- list(
          slide = slide_key, slug = slide_slug, rows = ref_rows
        )
      }
    }

    # ------------------------------------------------------------------
    # scanpath (schema/3, /4 sessions only)
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
            transitionEntropy = NA
          )
          if (identical(a, b)) {
            r$transitionEntropy <- transition_entropy(path_seq[[a]])
          }
          scan_rows[[length(scan_rows) + 1]] <- r
        }
      }
      write_csv_tidy(
        scan_rows, file.path(out_dir, paste0("scanpath_", slide_slug, ".csv")),
        c("sessionA", "sessionB", "levenshteinSim", "transitionEntropy")
      )
    }

    # ------------------------------------------------------------------
    # magnification-split (Phase 1): per-session dwell time in each within-path zoom band
    # ------------------------------------------------------------------
    if (length(scan_sids) > 0) {
      magband_rows <- list()
      for (sid in scan_sids) {
        f <- by_session[[sid]]
        path <- f$path
        base_mag <- f$baseMagnification
        img_w <- if (!is.null(f$imageWidth)) f$imageWidth else 1
        bands <- zoom_band_labels(path, base_mag, img_w, magbands)
        if (length(bands) == 0) next
        dts <- step_durations_ms(path)
        total_dt <- sum(dts)
        label <- label_for(sid, labels)
        for (band in 0:(magbands - 1)) {
          band_dt <- sum(dts[bands == band])
          magband_rows[[length(magband_rows) + 1]] <- list(
            session = label,
            band = band,
            bandTimeMs = band_dt,
            bandTimePct = if (total_dt > 0) band_dt / total_dt * 100.0 else 0.0
          )
        }
      }
      if (length(magband_rows) > 0) {
        write_csv_tidy(
          magband_rows, file.path(out_dir, paste0("magbands_", slide_slug, ".csv")),
          c("session", "band", "bandTimeMs", "bandTimePct")
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

          # Phase 1: magnification-split heatmaps, one per within-path zoom band.
          base_mag <- f$baseMagnification
          bands <- zoom_band_labels(path, base_mag, img_w, magbands)
          if (length(bands) > 0) {
            for (band in 0:(magbands - 1)) {
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
        }
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
      "linearity", "searchFocusRatio", "baseMagnification", "pathTruncated"
    )
  )
  write_summary(out_dir, groups, slide_summaries, reference_summaries)
  utils::read.csv(file.path(out_dir, "metrics.csv"), stringsAsFactors = FALSE)
}

#' Write `summary.md`: slide/session counts, per-slide mean pairwise CC + ICC(2,1) + coverage/
#' duration spread, and the reference ranking when applicable. Matches the Python toolkit's
#' `_write_summary` structure/section headers.
write_summary <- function(out_dir, groups, slide_summaries, reference_summaries) {
  lines <- c("# Blinded-focus analysis summary", "", paste0("- Slides analyzed: ", length(groups)), "")
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
  writeLines(lines, file.path(out_dir, "summary.md"))
}
