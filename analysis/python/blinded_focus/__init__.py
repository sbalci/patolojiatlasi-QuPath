"""blinded_focus — standalone analysis toolkit for QuPath atlas blinded-focus fragments.

Reads anonymised viewport-dwell fragments (schema ``atlas-focus-contribution/{1,2,3,4}``) produced
by the qupath-extension-atlas blinded recording feature and computes the standard saliency /
eye-tracking evaluation set: spatial similarity (CC, SIM, KLD, NSS, AUC-Judd, IoU), inter-observer
agreement (mean pairwise CC, ICC(2,1)), cross-session consistency (coincidence level, region
coverage), reference/ROI comparison, scanpath sequence metrics (visited-cell Levenshtein
similarity, transition entropy), a scanpath-rasterized fine dwell heatmap independent of the
recorded grid, and (schema/4) a zoom/navigation metric family (avg/variance/range zoom,
magnification percentage, scanning/drilling rate, path velocity/linearity/search-focus).

This package is standalone: it does not import anything from the QuPath extension or from
``tools/aggregate-focus.py`` (only the fragment JSON shape and the heat colormap idea are shared
by convention, not by code).

See ``analyze.py`` for the CLI entry point (``python -m blinded_focus.analyze``) and
``../README.md`` for install + usage instructions.
"""

__version__ = "0.1.0"
