# Automatic sweep (autoview)

**Extensions → Otomatik tarama…** pans the slide for you in a serpentine — across, down a row, back,
down again — the way you screen a slide under a microscope. You watch instead of dragging.

Two things make it more than a constant-speed pan, and both exist because of how atlas slides are
served. Tiles arrive over HTTP from a Deep Zoom pyramid, so a fixed speed outruns the fetches and
glides across un-rendered grey; the sweep therefore **paces itself against the tile cache**. And a
full-slide serpentine at reading magnification is mostly blank glass, so a coarse tissue map built
from the slide thumbnail **drops empty fields from the route**.

Because the sweep moves the viewer programmatically, it also has to tell the focus-heatmap and
navigation recorders to stop counting — otherwise the extension's own research data records the
sweep as if a human had looked there. That contract is in
[Recording and research data](#recording-and-research-data), and it is the part to read before
writing anything else that drives the viewer.

---

## Using it

Open a slide, then **Extensions → Otomatik tarama…**. The window is small and stays open; the sweep
runs against whichever viewer was active when you pressed **Başlat**.

| Control | What it does |
|---|---|
| **Hız** | Speed, in fields of view per second, read back as seconds per field. Adjustable **while the sweep runs**. |
| **Yön** | *Satırlar (yatay)* sweeps in rows, *Sütunlar (dikey)* in columns. |
| **Başlangıç** | Which corner to start from. With **Yön**, this gives all eight serpentine variants. |
| **Boş alanları atla** | Skip blank glass. |
| **Doku duyarlılığı** | How faint a region still counts as tissue. Only active when the box above is ticked. |
| **Başlat / Duraklat / Durdur** | Start, hold, stop. **Duraklat** genuinely stops the animation rather than idling it. |

**Yön**, **Başlangıç** and **Boş alanları atla** are settled before you start — they grey out while
a sweep runs, because changing them mid-sweep would mean re-planning the whole route. **Hız** stays
live.

Underneath: a progress bar, an **alan i / N** field counter, and a status line reading *Tarıyor*,
*Karo bekleniyor* (waiting for tiles), *Duraklatıldı* or *Tamamlandı*.

### What gets swept

- **Select an annotation first** and the sweep covers its bounding box.
- **Select nothing** and it covers the whole slide.
- Either way it runs at **whatever magnification you are already at** — it never changes your zoom.
  Zoom in before you press Başlat and you get a slow, detailed sweep of many fields; zoom out and
  you get a quick overview of few.

Adjacent fields overlap by 10 %, so nothing falls through the gap between two fields.

### What stops it

The sweep gets out of your way as soon as you show any sign of taking over:

- **Clicking or scrolling in the viewer** — the ordinary "I'll take it from here" gesture.
- **Changing slides, or switching to another viewer.**
- **Rotating the image.** It also refuses to *start* on a rotated image.
- **Closing the window.**
- **Reaching the end of the route** — status goes to *Tamamlandı* and the progress bar stays full.

> **Keyboard panning does not stop it.** Only mouse press and scroll are watched. If you pan with
> the arrow keys during a sweep, you and the sweep will fight over the viewer. Press **Durdur**
> first. This is deliberate — a key filter on the viewer risks interfering with QuPath's own
> shortcut handling — but it is a rough edge.

> **In a synchronised compare grid, the other panels should follow — but they are not paced.**
> Case-Compare turns QuPath's viewer synchronisation on, and the sweep pans through the ordinary
> viewer API, so the pan should propagate to every panel (this has not been checked against
> `ViewerManager`'s sync implementation). Note that the readiness probe only looks at the **pinned
> viewer's** tiles, so the other panels can still glide across un-rendered grey even while the swept
> one is perfectly paced. For serial stains that is usually an acceptable trade; if it isn't,
> collapse back to a single viewer first.

---

## How the pacing works

Each frame, before moving, the sweep asks: *how much of the view I am about to be in has already
arrived?*

```java
List<RegionRequest> reqs = ImageRegionStoreHelpers.getTilesToRequest(
        server, viewRect, downsample, z, t, reusableList);
int ready = 0;
for (RegionRequest r : reqs)
    if (store.getCachedTile(server, r) != null) ready++;
```

`getTilesToRequest` is the **painting path's own request builder** — it runs the same
`ServerTools.getPreferredDownsampleFactor` → `TileRequestManager.getTileRequests` chain the painter
does. That matters: its `RegionRequest`s are exactly the keys `DefaultImageRegionStore` caches
under. A hand-built `RegionRequest` would not guarantee that parity and would read as "nothing
cached" forever.

That fraction goes through `AutoviewPacing`, which ramps rather than switches:

- at or above **`READY_FULL` = 0.90** → full speed
- at or below **`READY_STOP` = 0.60** → hold
- in between → linear
- and the factor only ever moves **`SLEW_PER_SEC` = 3.0** per second towards that target

The slew limit is the whole reason it reads as a glide. A binary glide/hold would stutter at every
tile boundary. A cache miss eases the sweep down over about a third of a second; tiles arriving ease
it back up.

A `NaN` ready fraction — which is what a failed probe produces — targets **full speed**. The probe
failing must never be able to wedge the sweep.

### Why the lookahead must stay sub-frame

`LOOKAHEAD_SEC = 0.15` — about 2–5 % of a field. **It must stay small, and this is not a tuning
preference.**

`getTilesToRequest` is pure computation: it works out which tiles a region needs, it does **not**
enqueue them. Only the viewer's own paint enqueues tiles, and only for what is actually on screen.
So a lookahead band reaching past the viewport edge is never requested, never cached, and its
readiness is pinned at zero — the sweep holds, the band never comes on screen, and the hold never
lifts. A large lookahead does not make the sweep smoother; it deadlocks it.

A sub-frame lookahead keeps the early warning while staying inside the region the viewer has already
asked for, so the worst case is bounded and self-recovering. **`LOOKAHEAD_SEC = 0.0`** degenerates to
"probe the current viewport" and is the always-safe fallback.

### The stall watchdog

If the factor sits below 0.02 for **`STALL_MS` = 5000**, the sweep assumes the probe is lying and
forces the factor to 1.0. This is a backstop against a permanently missing tile, not a normal path —
see [Diagnostics](#diagnostics) for what it looks like when it fires repeatedly.

### `setDoFasterRepaint(true)`

The sweep turns this on while gliding and restores it on stop. **Verified against the QuPath 0.6.0
bytecode**, `doFasterRepaint` is read in exactly two places:

1. `updateBufferedImage` — selects nearest-neighbour instead of bilinear interpolation.
2. `paintViewer` — clears the `paintCompletely` flag passed to `PathOverlay.paintOverlay`.

It takes **no part in choosing the downsample or the tile set**. That was worth checking rather than
assuming: if it had shifted the painted resolution level, the probe at the pinned downsample would
have read as uncached permanently, which is the deadlock above by another route.

`setMinimumRepaintSpacingMillis` is deliberately **not** touched — it is global state with a restore
obligation and no measured benefit here.

---

## Skipping empty glass

`TissueMask` builds a coarse grid (longest side ≤ 192 cells) from `server.getDefaultThumbnail(z, t)`
on a background thread, then every field whose tissue coverage falls below 1 % is dropped from the
route.

Per cell it takes mean RGB, then luminance `L = 0.299R + 0.587G + 0.114B` and saturation
`S = (max − min) / max`. A cell is tissue when it is enough darker than the background, **or**
saturated enough — the second test is what keeps pale eosin pink, which is bright but colourful.

**Background polarity is detected, not assumed.** The median luminance of the border cells decides
whether this is bright-field (dark tissue on white glass) or the inverse. A median rather than a mean
so tissue running off the slide edge cannot drag the estimate towards it.

**Doku duyarlılığı** maps linearly onto both tolerances: luminance 45 → 12 and saturation 0.22 →
0.06 as you go from 0 to 1. Higher keeps fainter tissue. The thresholds lean towards keeping things,
because a false positive costs a little sweep time while a false negative skips real tissue.

### The mask lands mid-sweep

The thumbnail is a real HTTP fetch and can take seconds, so the sweep **starts immediately on the
unfiltered route** and re-plans the part it has not reached yet when the mask arrives. There is no
"hazırlanıyor…" dead state, and if the fetch fails the unfiltered route simply stands — degradation,
not failure. The mask is cached per slide path (single entry), so re-sweeping the same slide is
instant.

Re-seating onto the rebuilt route is `AutoviewCursor.resumeOn`, and it **must never move backwards**.
Filtering only removes fields — it never reorders them, changes the line count, or changes a line's
travel direction — so the current `(line, travel coordinate)` pair still locates the cursor in the
rebuilt plan.

> **The trap this replaced.** Taking "the first leg whose line is at or after the current one"
> rewinds. Connector legs carry the line they lead *into*, so that leg is usually the connector from
> the previous line, which starts back at the previous line's **end** — and the sweep re-travels a
> line it was already partway through. `AutoviewCursorTest.resumeMidLineDoesNotRewindAlongTravel`
> pins this.

**If the mask lands while the sweep is paused it is held**, not applied, and takes effect on
**Devam et**. A reader who paused to look at something should not have the view teleport out from
under them.

---

## Recording and research data

> **The contract: anything that drives the viewer programmatically must hold a
> `FocusHeatmap.suppressTracking(...)` handle for its whole duration.**

`FocusHeatmap` polls `viewer.getDisplayedRegionShape()` four times a second and has **no way to tell
a synthetic pan from a human one**. Without suppression, a sweep deposits dwell into the `FocusMap`
and appends a scanpath point on every tick — corrupting the heatmap, the contribution fragment's
`path`, and everything downstream of it (`PathologyCotDiscretizer`, the behaviours file, the CoT
draft tour, `tools/aggregate-focus.py`).

`suppressTracking(String reason)` returns a counted, idempotently-closeable handle. It is counted
rather than a bare boolean because a sweep spans minutes and has abnormal exits — window closed,
slide changed, exception — so the same handle may legitimately be closed twice. The sweep acquires
it **last** among its side effects, after every refusal path, so no early return can leak it, and
`stop()` releases it unconditionally, so every abort route funnels through one release.

**What suppression covers, verified against the source:**

| Field | Covered? | Why |
|---|---|---|
| `grid` (dwell) | ✅ | the `deposit` call is gated |
| `path` (scanpath) | ✅ | lives inside the same `ms > 0` branch as the deposit |
| `durationMs` | ✅ | it is `FocusMap.getTotalWeight()` — summed deposits, not wall clock |

Slide switching, fragment writing and checkpointing are deliberately **not** suppressed, so a
suppressed sweep still leaves the departing slide's real data intact and still saves it. (The gate
sits *after* the slide-change dispatch in `tick()` for exactly this reason — gating earlier would
mean a slide change during a long sweep never wrote the departing slide's fragment.)

### The residual limitation — state it, don't paper over it

Suppression stops points being *written*, but `tRel` is wall-clock relative to slide open, so a
sweep leaves a **gap** between the last pre-sweep point and the first post-sweep one. What happens to
that gap depends entirely on whether it exceeds `IDLE_GAP_MS = 60_000` in `metrics.py` (and
`blinded_focus.R`, which carries the same literal and semantics).

**A sweep longer than 60 s — handled correctly.** `idle_step_mask` flags the step, and:

- `raster_from_path` *always* skips idle steps, regardless of any other mask, so the gap contributes
  **zero** dwell at any output resolution.
- `mouse_raster_from_path` skips it too.
- `fixations_idt` treats it as a **hard window boundary**: the path is split into maximal idle-free
  runs and the I-DT algorithm runs on each independently, so no fixation can bridge the sweep.

**A sweep of 60 s or less — a real artifact.** The gap falls under the threshold and is treated as
ordinary viewing time:

- `raster_from_path` attributes the whole Δt to **point *i*'s viewport rectangle** — the view the
  reader was in when they pressed Başlat — spread evenly across every grid cell that rectangle
  overlaps. So it is not one cell: it is **a whole field of view's worth of area**, which at low
  magnification is a large part of the slide.
- `mouse_raster_from_path` deposits it into the single cell holding that point's cursor position
  (or skips the step entirely if the cursor was off-slide, the `(-1,-1)` sentinel).
- `fixations_idt` is *not* broken by it, so the gap can sit **inside a fixation window** and inflate
  `meanFixationMs` / `medianFixationMs` / `sdFixationMs`.

**`decisionMs` and `promptShownMs`** are wall-clock relative to slide open either way, so a sweep of
any length inflates both by its own duration.

None of this is corruption of the sweep's own path — no synthetic points exist, and the dwell grid
and `durationMs` are clean. It is a timing artifact at the **seam**, and it is only bounded for
sweeps over a minute. **If you need clean temporal data, don't sweep during a recorded session** —
and in particular don't run short sweeps, which are the ones the idle rule does not catch.

---

## Preferences

Persisted through `PathPrefs.createPersistentPreference` — the extension's first use of QuPath's own
preference mechanism, chosen over another hand-rolled JSON sidecar because these are exactly the
set-once user-taste settings it exists for.

| Key | Default |
|---|---|
| `atlas.autoview.fovPerSec` | `0.25` (4 s per field) |
| `atlas.autoview.axis` | `ROWS` |
| `atlas.autoview.corner` | `TOP_LEFT` |
| `atlas.autoview.skipEmpty` | `true` |
| `atlas.autoview.tissueSensitivity` | `0.5` |

> **These fields must stay `static final`.** `createPersistentPreference` returns a **new** property
> on every call, so building them inside `build()` would create duplicate properties bound to one
> key, and they would silently stop tracking each other.

`AutoviewControl` is also constructed **lazily**, from the menu action, inside a `catch (Throwable)`.
Those static initialisers run on first class load, and a failure there arrives as an
`ExceptionInInitializerError` — an `Error`, which `installExtension`'s `catch (Exception e)` would
not stop from taking the entire extension down. Lazy construction costs one menu item instead.

---

## Diagnostics

With debug logging on, the sweep traces its probe about once a second:

```
Autoview readiness 7/9 (0.78), speedFactor 0.60
```

**A broken probe is nearly invisible from the outside — all of these look like a smooth glide.** The
log is the only thing that separates them:

| Signature | Meaning |
|---|---|
| `N/M` **varying** across the range | Healthy. This is what tile-awareness actually working looks like. |
| `-1/-1` | The probe threw and the fail-open catch is swallowing it. No tile-awareness at all. |
| `0/0` | No tiles were asked for — off-image, or a degenerate viewport. |
| constant `0/M` | The requests are not keying the way the paint path caches. |

That last one is worth spelling out, because it is easy to misread as a slow network: it does **not**
degrade to full speed. The watchdog lifts the factor to 1.0, the slew limit drags it back under the
0.02 threshold within about 20 frames (~0.33 s), and the watchdog re-arms. The sweep ends up
crawling at roughly **3 % speed, lurching every ~5.3 s**, with the status stuck on *Karo bekleniyor*.

---

## Code map

| File | Role | Tested |
|---|---|---|
| `autoview/AutoviewPath.java` | Serpentine geometry: extent + field size + axis + corner → ordered legs | ✅ 13 |
| `autoview/TissueMask.java` | Coarse tissue grid from a thumbnail; area-weighted coverage | ✅ 10 |
| `autoview/AutoviewPacing.java` | Readiness → speed factor, with slew limit | ✅ 10 |
| `autoview/AutoviewCursor.java` | Position in a plan: stepping, lookahead, re-seating | ✅ 14 |
| `autoview/AutoviewSweep.java` | The `AnimationTimer` runtime, pinning, probing, suppression | GUI |
| `autoview/AutoviewControl.java` | The floating window and its preferences | GUI |

The first four are **pure** — no QuPath, no JavaFX, no AWT — following the convention already set by
`focus/FocusMap`, `pathologycot/PathologyCotDiscretizer` and `quiz/QuizScoring`: a tested kernel with
a thin FX driver. `AutoviewCursor` was split out of `AutoviewSweep` specifically because the cursor
walk is the most bug-prone arithmetic in the feature and, living inside an FX class, had no coverage.

`AutoviewSweep` is the extension's **first `AnimationTimer`**. Everything else timed here samples on
a `Timeline`, but a sweep has to move on the render pulse to look smooth.

---

## Invariants nothing enforces

- **Anything that moves the viewer programmatically holds `suppressTracking`.** No check will catch
  you; the symptom is quietly corrupted research data.
- **`LOOKAHEAD_SEC` stays below one frame of travel.** Raising it deadlocks the pacing.
- **The `PREF_*` fields stay `static final`.**
- **`stop()` stays idempotent**, and stays the single release point for the suppression handle.
- **Every frame re-checks the pinned viewer and `ImageData` identity** before touching anything. The
  frame loop runs on the FX pulse; the world can change under it.
- **No `showAndWait()` from inside the frame loop.** Animation pulses keep firing inside a modal's
  nested event loop, so a sweep would keep moving behind one. Currently unreachable — the only modal
  near this path is the focus-heatmap leave prompt, which fires on a slide change, and a slide change
  aborts the sweep in the same frame — but it is one refactor away from being reachable.

## Traps

- **A zero-length leg is a real leg.** A single kept field has `x0 == x1`, and it is given one
  field's worth of *dwell* so it is actually seen instead of flashing past in a 16 ms frame.
- **Connector legs carry `fieldCount == 0`** and belong to the line they lead *into*. Summing
  `fieldCount` over a plan therefore counts each kept field exactly once — and treating a connector
  as a resume target is the rewind bug above.
- **`advance()` stops at every jump** even when handed a step large enough to cross the rest of the
  plan, so the caller sees each discontinuity and can reset pacing.
- **The initial jump is a pan, not a `setDownsampleFactor`.** Re-setting the zoom risks it being
  clamped to a different value, which would leave the probe querying a resolution level other than
  the one being painted.
- **The field counter re-bases when the mask lands.** `replanTail` replaces `fieldCount` with the
  filtered route's total and `fieldsVisited()` recomputes against the new leg prefix sums, so
  "alan 100 / 200" can legitimately become "alan 20 / 60" and the bar can drop from 50 % to 33 %.
  Monotonicity is only guaranteed — and only tested — *within* one plan. Carrying a visited offset
  across the re-plan would fix the display at the cost of a counter that no longer matches the route
  actually being swept.

## Limitations

- **Keyboard panning does not abort the sweep** (see above).
- **Rotated images are refused** outright rather than supported. Supporting them would mean
  expressing the route in view space instead of image space, for a case nobody has.
- **Dark-background polarity detection is only tested on synthetic RGB arrays.** The logic is
  polarity-agnostic by construction and `TissueMaskTest` covers an inverted array, but
  `BufferedImage.getRGB()` on a genuinely multichannel QuPath image is **untested** — atlas DZI
  slides are RGB, so this path has never run in anger. Treat fluorescence support as plausible, not
  verified.
- **No progress overlay on the slide.** The progress bar and field counter carry the same
  information without an `AbstractOverlay` subclass, its staleness guard, and its per-repaint cost.
- **`LOOKAHEAD_SEC`, `READY_FULL` and `READY_STOP` have not been tuned against the live CDN**, only
  reasoned about. They are the three constants most likely to need adjusting; the
  [Diagnostics](#diagnostics) trace is how you would tell.
- **The GUI layer has no automated coverage.** The pure kernels have 47 tests; `AutoviewSweep` and
  `AutoviewControl` are verified by hand.
