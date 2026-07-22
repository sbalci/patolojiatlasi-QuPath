#!/usr/bin/env Rscript
# selftest.R — synthetic end-to-end selftest for the blinded_focus R analysis toolkit.
#
# Mirrors analysis/python/selftest.py: builds 3 sessions on one slide (2 similar grids + 1
# different grid; two of the three carry a schema/3 `path` while one is schema/2 with no path),
# designates one session as the `--reference`, runs `analyze()` into a temp dir, and asserts the
# same output contract:
#
# - metrics.csv has 3 rows and exactly the spec'd columns.
# - compare_<slug>.csv's cc matrix is symmetric with a 1.0 diagonal, and the similar pair's cc
#   exceeds the dissimilar pair's.
# - consensus_<slug>.png is a valid PNG.
# - reference_<slug>.csv's reference-vs-itself row has high NSS/CC.
# - scanpath_<slug>.csv's levenshtein-similarity diagonal is 1.0.
# - summary.md exists and is non-empty.
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

.fragment <- function(session_id, schema, grid, duration_ms, sample_count, path = NULL) {
  d <- list(
    schema = paste0("atlas-focus-contribution/", schema),
    slideKey = "sha256:selftest-slide-0001",
    sessionId = session_id,
    imageWidth = IMG_W, imageHeight = IMG_H,
    gridWidth = GW, gridHeight = GH,
    grid = grid,
    durationMs = duration_ms,
    sampleCount = sample_count,
    date = "2026-07-22"
  )
  if (!is.null(path)) {
    # unclass to a plain matrix-of-rows list so jsonlite serializes it as an array-of-arrays
    d$path <- lapply(seq_len(nrow(path)), function(i) as.numeric(path[i, ]))
  }
  d
}

build_fragments <- function() {
  grid_s1 <- .gaussian_grid(2, 2, seed = 1)
  grid_s2 <- .gaussian_grid(2, 2, seed = 2) # similar hotspot location to s1
  grid_s3 <- .gaussian_grid(6, 6, sigma = 1.0, seed = 3) # different hotspot location

  f1 <- .fragment("s1", 3, grid_s1, 40 * 250, 40, path = .make_path(2, 2, n = 40, seed = 101))
  f2 <- .fragment("s2", 3, grid_s2, 35 * 250, 35, path = .make_path(2, 2, n = 35, seed = 102))
  f3 <- .fragment("s3", 2, grid_s3, 8000, 32, path = NULL) # schema/2, no path
  list(f1, f2, f3)
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
  in_dir <- file.path(tmp, "in")
  dir.create(in_dir)
  write_fragments_to_dir(fragments, in_dir)
  out_dir <- file.path(tmp, "out")

  metrics <- analyze(list(in_dir), out_dir, reference = "s1", make_figures = TRUE)

  # --- metrics.csv: 3 rows + expected columns ---
  stopifnot("expected 3 metrics rows" = nrow(metrics) == 3)
  expected_cols <- c(
    "slide", "session", "durationMs", "sampleCount", "coveragePct", "entropy",
    "comX", "comY", "peakDwell", "nHotspots", "pathPoints", "pathLengthPx",
    "nRevisits", "transitionEntropy"
  )
  stopifnot("metrics.csv columns mismatch" = identical(colnames(metrics), expected_cols))

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

  # --- scanpath_<slug>.csv: levenshtein-sim diagonal 1.0 ---
  scan_files <- out_files[startsWith(out_files, "scanpath_")]
  stopifnot("expected exactly one scanpath_ file" = length(scan_files) == 1)
  scan <- utils::read.csv(file.path(out_dir, scan_files[1]), stringsAsFactors = FALSE)
  scan_diag <- scan$levenshteinSim[scan$sessionA == scan$sessionB]
  stopifnot("scanpath diagonal != 1.0" = all(abs(scan_diag - 1.0) < 1e-9))
  stopifnot("schema/2 session should be absent from scanpath output" = !("s3" %in% scan$sessionA))

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

  # --- summary.md written and non-trivial ---
  summary_path <- file.path(out_dir, "summary.md")
  stopifnot("summary.md missing" = file.exists(summary_path))
  stopifnot("summary.md is empty" = file.info(summary_path)$size > 0)

  # --- mixed schema/2 + schema/3 handled: s3 has blank scanpath metrics, s1 does not ---
  row_s3 <- metrics[metrics$session == "s3", ]
  stopifnot("schema/2 session should have no pathPoints" = is.na(row_s3$pathPoints))
  row_s1 <- metrics[metrics$session == "s1", ]
  stopifnot("schema/3 session pathPoints mismatch" = row_s1$pathPoints == 40)

  # --- .zip input also works ---
  zip_path <- file.path(tmp, "fragments.zip")
  write_fragments_to_zip(fragments, zip_path)
  zip_out <- file.path(tmp, "out_zip")
  zip_metrics <- analyze(list(zip_path), zip_out, reference = "s1")
  stopifnot("zip input: expected 3 metrics rows" = nrow(zip_metrics) == 3)

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
