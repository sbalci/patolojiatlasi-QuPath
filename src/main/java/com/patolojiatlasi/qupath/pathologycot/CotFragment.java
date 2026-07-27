package com.patolojiatlasi.qupath.pathologycot;

import java.util.List;
import java.util.Map;

/** Parsed subset of a focus fragment (schema atlas-focus-contribution/3–5) needed to discretize. */
public record CotFragment(String slideKey, String sessionId, int imageWidth, int imageHeight,
                          Double baseMagnification, List<int[]> path, Map<String,Object> decision) {}
