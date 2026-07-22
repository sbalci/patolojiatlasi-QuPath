#!/usr/bin/env Rscript
# run_analysis.R — CLI entry point for the blinded_focus R analysis toolkit.
#
# Usage:
#   Rscript run_analysis.R <input...> --out DIR [--reference SESSIONID] [--roi geojson]
#       [--labels csv] [--figures] [--res 512] [--magbands 3]
#
# <input...> may be fragment JSON files, directories (recursed for *.json), and/or .zip archives,
# in any mix. Writes the SAME output files as the Python toolkit's `python -m
# blinded_focus.analyze` (see ../python/README.md and ./README.md for the full contract):
# metrics.csv, compare_<slug>.csv, consensus_<slug>.png, reference_<slug>.csv, scanpath_<slug>.csv,
# magbands_<slug>.csv, summary.md, and (with --figures) per-(slide,session) PNGs under
# <out>/<slug>/ (incl. the Phase-1 scanpath-raster and magnification-band heatmaps).

# Resolve this script's own directory so `source()` works regardless of the caller's working
# directory (Rscript does not chdir to the script's location).
.args <- commandArgs(trailingOnly = FALSE)
.file_arg <- "--file="
.match <- grep(.file_arg, .args)
.script_dir <- if (length(.match) > 0) {
  dirname(normalizePath(sub(.file_arg, "", .args[.match[1]])))
} else {
  "."
}
source(file.path(.script_dir, "blinded_focus.R"))

.parse_args <- function(argv) {
  inputs <- c()
  out_dir <- NULL
  reference <- NULL
  roi <- NULL
  labels_csv <- NULL
  make_figures <- FALSE
  res <- DEFAULT_RES
  magbands <- DEFAULT_MAGBANDS
  i <- 1
  n <- length(argv)
  while (i <= n) {
    a <- argv[i]
    if (a == "--out") {
      out_dir <- argv[i + 1]; i <- i + 2
    } else if (a == "--reference") {
      reference <- argv[i + 1]; i <- i + 2
    } else if (a == "--roi") {
      roi <- argv[i + 1]; i <- i + 2
    } else if (a == "--labels") {
      labels_csv <- argv[i + 1]; i <- i + 2
    } else if (a == "--figures") {
      make_figures <- TRUE; i <- i + 1
    } else if (a == "--res") {
      res <- as.integer(argv[i + 1]); i <- i + 2
    } else if (a == "--magbands") {
      magbands <- as.integer(argv[i + 1]); i <- i + 2
    } else {
      inputs <- c(inputs, a); i <- i + 1
    }
  }
  if (length(inputs) == 0 || is.null(out_dir)) {
    stop(
      "usage: Rscript run_analysis.R <input...> --out DIR [--reference ID] [--roi geojson] ",
      "[--labels csv] [--figures] [--res 512] [--magbands 3]",
      call. = FALSE
    )
  }
  list(
    inputs = inputs, out_dir = out_dir, reference = reference, roi = roi,
    labels_csv = labels_csv, make_figures = make_figures, res = res, magbands = magbands
  )
}

.main <- function() {
  argv <- commandArgs(trailingOnly = TRUE)
  opts <- .parse_args(argv)
  analyze(
    opts$inputs, opts$out_dir,
    reference = opts$reference, roi = opts$roi, labels_csv = opts$labels_csv,
    make_figures = opts$make_figures, res = opts$res, magbands = opts$magbands
  )
  invisible(NULL)
}

.main()
