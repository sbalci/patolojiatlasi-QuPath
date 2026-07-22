#!/usr/bin/env Rscript
# Checks for (and installs, if missing) the packages the blinded_focus R toolkit needs.
#
# Usage:
#   Rscript requirements.R
#
# Mirrors analysis/python/requirements.txt's role for the parallel Python toolkit: a single,
# idempotent entry point that leaves the environment ready to run selftest.R / run_analysis.R.

required_packages <- c("jsonlite", "dplyr", "tidyr", "ggplot2", "irr", "proxy")

missing <- required_packages[!vapply(required_packages, requireNamespace, logical(1), quietly = TRUE)]

if (length(missing) > 0) {
  cat("Installing missing packages:", paste(missing, collapse = ", "), "\n")
  install.packages(missing, repos = "https://cloud.r-project.org")
} else {
  cat("All required packages already installed:", paste(required_packages, collapse = ", "), "\n")
}

# Re-check after install attempt so a failed install is reported clearly rather than silently
# surfacing later as a cryptic "could not find function" error in blinded_focus.R.
still_missing <- required_packages[!vapply(required_packages, requireNamespace, logical(1), quietly = TRUE)]
if (length(still_missing) > 0) {
  stop(
    "The following packages could not be installed: ",
    paste(still_missing, collapse = ", "),
    ". Install manually and re-run.",
    call. = FALSE
  )
}

cat("blinded_focus R toolkit dependencies OK: ", paste(required_packages, collapse = ", "), "\n", sep = "")
