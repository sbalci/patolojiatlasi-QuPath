package com.patolojiatlasi.qupath.pathologycot;

/** One discretized viewing action in slide-pixel coordinates. Clean-room implementation of the
 *  inspect/peek behaviour primitives from Wang et al., Pathology-CoT, Nat. Biomed. Eng. 2026. */
public record Behavior(Type type, String magBin, int x, int y, int w, int h,
                       long startMs, long endMs, long dwellMs) {
    public enum Type { INSPECT, PEEK }
    public int centerX() { return x + w / 2; }
    public int centerY() { return y + h / 2; }
}
