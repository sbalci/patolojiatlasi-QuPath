# Named teaching regions

Draw regions on a slide in QuPath, give them names, and the same named regions become
**click-to-zoom buttons** on the OpenSeadragon viewer page and **navigable targets for an AI
assistant** through the atlas MCP server. One small file does all three.

The reason this works without any coordinate maths: **QuPath ROI pixels, the DZI descriptor's
`Size`, and OpenSeadragon's `imageToViewportRectangle` are the same coordinate space** —
full-resolution pixels, top-left origin. Nothing is transformed anywhere along the chain. A local
copy of a slide and its published copy are also geometrically identical (both `79923 × 42180` for
`lymphocytic-gastritis/HE`), so regions drawn on one land on the same pixels in the other.

---

## Step-by-step guide

Three parts: draw the regions in QuPath, add one file to the slide's repository, and (only for a
case that doesn't have them yet) switch the buttons on. Everything you add is **small** — one JSON
file of a couple of kilobytes, plus two lines of HTML for a new case. You never touch the tiles.

### Part A — Draw and name the regions in QuPath

**A1. Create or open a project — before opening the slide.** `File → Project → Create project...`,
or open an existing one. **Annotations on a slide opened without a project exist only in memory and
are gone when the slide closes.**

**The order matters.** Both openers decide whether to add the slide to a project by checking for one
**at the moment the slide opens**. Open the slide first and it never enters the project — you have
to close it and open it again. The warning dialog says exactly that: *"önce bir proje açın veya
oluşturun, sonra slaytı yeniden ekleyin"*.

**A2. Open the slide.** With a project open, either route adds it automatically:

- *If you have the case repo cloned* — the easiest path, because the export dialog then opens in the
  right folder: `Extensions → Patoloji Atlası → Yerel DZI aç…`, and pick e.g.
  `lymphocytic-gastritis/HE.dzi`.
- *Otherwise*: `Slaytlara gözat…`, find the case, and open the slide from the atlas. Coordinates are
  identical either way, so you can annotate the streamed slide and add the one file to the
  repository afterwards without ever cloning tens of thousands of tiles.

Pixel size comes from the `vips-properties.xml` sidecar automatically, so the scale bar and µm
measurements work with no setup. The confirmation dialog reports what it found — check that it names
a µm/px value rather than warning that the image is uncalibrated.

**A3. Draw each region.** Use the toolbar drawing tools — Rectangle, Polygon ([P]), Brush ([B]) or
Wand. A rough outline is fine: **the bounding box is what the buttons use**, so what matters is that
the region covers the structure you want the reader to land on. Only **annotations** are exported,
never detections, so there's no need to run cell detection.

**A4. Name each region — this is the step that matters most.** The name becomes the button label, so
an unnamed region is useless to a reader. Select the annotation and open its properties (QuPath 0.6
offers this from the **Annotations** tab and from the viewer; the dialog is *Set annotation
properties*). Fill in:

- **Name** — the label. For both languages use the atlas `|` convention:

  ```
  Lenfoid agregat | Lymphoid aggregate
  ```

  Turkish first, English second. A plain name without `|` is treated as Turkish, and consumers fall
  back to it when no English label exists.
- **Description** *(optional)* — becomes the button's tooltip, split on `|` the same way.

If you leave the name empty the annotation's classification is used instead; if there is no
classification either, the region is labelled `Bolge` and nobody can tell what it is.

**A5. Export.** `Extensions → Patoloji Atlası → Bölgeler → Bölgeleri dışa aktar…`.

The dialog pre-fills the file name as `<stain>.regions.json` and, for a local slide, opens in the
folder holding the `.dzi`. **Keep both.** The confirmation tells you how many regions were written
and whether a pixel size was recorded.

> **Annotate each stain separately.** Every stain page loads its own `<stain>.regions.json`, and the
> stains have different dimensions (HE is 79923 × 42180, CD3 is 76638 × 51504). An HE file reused
> for CD3 is refused by the size check rather than silently misplaced — which is the point, but it
> does mean there is no shortcut.

### Part B — Add the file to the slide's repository

Each case is its own repository under the `pathologyatlas` organisation, published with GitHub Pages
and served at `images.patolojiatlasi.com/<case>/`. For `lymphocytic-gastritis` that is
`github.com/pathologyatlas/lymphocytic-gastritis`.

The regions file belongs **beside the `.dzi` and the `_files/` directory**, at the repository root:

```
lymphocytic-gastritis/
├── HE.dzi
├── HE.regions.json      ← the file you just exported
├── HE_files/            ← tiles (untouched)
├── HE.html
└── openseadragon/
```

```bash
cd lymphocytic-gastritis
git add HE.regions.json
git commit -m "Add teaching regions for HE"
git push
```

If you annotated a streamed slide and have no clone, GitHub's web interface ("Add file → Upload
files") does the same job — it is one small file.

> **Never add a regions file to a Case-of-the-Month (quiz) case.** The MCP server withholds regions
> from quiz slides, but **`osd-regions.js` has no quiz filter at all** — a region named after the
> diagnosis would be published as a button label on the public viewer page, which gives the answer
> away. Nothing enforces this.

### Part C — Turn the orientation buttons on

**If the case page already loads the plugin, there is nothing to do.** The panel appears by itself
once the regions file is live, and stays invisible when there is none. Check with:

```bash
grep -c osd-regions.js HE.html
```

For a case that doesn't have it yet, copy the plugin in and wire it up:

**C1. Copy the plugin.** Take `openseadragon/osd-regions.js` from `pathologyatlas-template` (the
source of truth) into the case's `openseadragon/` folder. The copies are kept **byte-identical**.

**C2. Add the script tag**, after the OpenSeadragon library is loaded and alongside the other
`osd-*.js` plugins:

```html
<script src="./openseadragon/osd-regions.js"></script>
```

**C3. Initialise it, after `viewer` exists** — at the end of the page's main `<script>` block. Use
the minimal form unless the page already has hash navigation:

```js
var regionsPanel = OSDRegions(viewer, { lang: 'tr' });
```

That is all a normal page needs. The plugin *reads* an `r=<id>` term from the URL hash on its own,
so a link like `HE.html#r=1a2b3c4d` jumps straight to that region — but the minimal form never
*writes* the hash, so clicking a region does not produce a shareable URL. Build such links by hand,
taking the id from the regions file. For pages that should update the URL as the reader clicks, use
C4 instead.

**C4. Only for pages that already own a `#stain=..&x=..&y=..&z=..` hash** (currently the four
`lymphocytic-gastritis` stain pages, via an `updateHashCoords()` function) use the integrated form
instead, so a clicked region is reflected in the shareable URL. It needs **all four** pieces:

```js
var currentRegionId = null;                      // 1. near the other page state

var regionsPanel = OSDRegions(viewer, {          // 2. after viewer exists
  lang: 'tr',
  onActivate: function (regionId) {
    currentRegionId = regionId;
    updateHashCoords();
  }
});

// 3. inside updateHashCoords(), after x/y/z are appended:
if (currentRegionId) hash += '&r=' + encodeURIComponent(currentRegionId);

// 4. inside the stain-switch function, so a region id never carries to another slide:
currentRegionId = null;
```

Do **not** paste this form into a page without `updateHashCoords` — it throws a `ReferenceError` and
no buttons appear at all.

### Part D — Optional: make the regions available to AI assistants

The MCP server bakes regions in **at build time, fetching them over HTTP from the published site**.
So push Part B first and wait for the Pages rebuild, then:

```powershell
cd atlas-mcp
$env:ATLAS_DIR = 'G:\GitHub\patolojiatlasi-github-io'   # default is ../patolojiatlasi.github.io
npm run catalog
npm test
npm run deploy
```

In Git Bash the first two lines become one:
`ATLAS_DIR=G:/GitHub/patolojiatlasi-github-io npm run catalog`. Use a Windows-style path either way
— a `/g/...` MSYS path happens to be rewritten correctly by Git Bash, but not by PowerShell.

`ATLAS_DIR` is required unless the atlas site happens to be checked out at
`../patolojiatlasi.github.io` next to `atlas-mcp`. Check the reported slide count before committing
the regenerated `data/catalog.json` — the script refuses to write if it drops by more than 5 %.

**Expect `npm test` to report one failure**, `viewer loads only self-hosted scripts whose SRI hashes
match public/vendor`. It is a Windows-checkout artefact: the vendored OpenSeadragon file arrives with
CRLF line endings, so its hash doesn't match the pin, which is correct for the LF file that actually
deploys. It has nothing to do with regions. **Anything else failing is real** — stop and look.

Two caveats: deploying also ships the regions feature itself (the `list_regions` tool and
`show_slide`'s `region` parameter), and because that changes **tool metadata**, connectors need
refreshing and a listed connector re-enters review. Catalog-data-only updates are free.

### Part E — Verify

```bash
curl -sI https://images.patolojiatlasi.com/<case>/<stain>.regions.json   # expect 200
```

Allow a minute or two for the Pages rebuild, then hard-refresh the viewer page: the panel should
list your regions, and clicking one should frame the structure you drew. Cross-check a region
against the same slide in QuPath if anything looks off by more than a little.

### Revising regions later

`Bölgeler → Bölgeleri içe aktar…` reads the file back into annotations so you can adjust them and
export again. It **asks** before touching what is already on the slide — the default is to cancel
rather than duplicate, and "replace" updates only the regions the file actually contains. Because
import restores each annotation's identity, **a re-export keeps the same `id` values**, so published
deep links keep working.

---

## Format: `atlas-regions/1`

File name: `<slide>.regions.json` (the `RegionsExport.SUFFIX` constant).

```json
{
  "format": "atlas-regions/1",
  "slide": "lymphocytic-gastritis/HE",
  "size": { "width": 79923, "height": 42180 },
  "mpp": 0.263018,
  "source": { "tool": "QuPath 0.6.0", "exported": "2026-09-18" },
  "regions": [
    {
      "id": "1a2b3c4d",
      "uuid": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
      "label_tr": "Lenfoid agregat",
      "label_en": "Lymphoid aggregate",
      "x": 12000, "y": 8000, "width": 3000, "height": 2400,
      "note_tr": "Lamina propriada yoğun lenfoid doku.",
      "note_en": "Dense lymphoid tissue in the lamina propria.",
      "geometry": {
        "type": "Polygon",
        "coordinates": [[[12000, 8000], [15000, 8000], [15000, 10400], [12000, 10400], [12000, 8000]]]
      }
    }
  ]
}
```

### Envelope

| Member | Required | Meaning |
|---|---|---|
| `format` | yes | `atlas-regions/1`. Bump only on an incompatible change. |
| `size` | yes | **The dimensions the regions were drawn against.** Every consumer compares this to the live image and refuses to place regions on a mismatch — see *Traps*. |
| `slide` | no | e.g. `lymphocytic-gastritis/HE`. |
| `mpp` | no | µm/px at export time. **Omitted entirely** when the slide was uncalibrated, rather than written as a misleading `1.0`. |
| `source` | no | `tool` (e.g. `QuPath 0.6.0`) and `exported` (ISO date). |
| `regions` | yes | Possibly empty. |

### Per region

| Member | Required | Meaning |
|---|---|---|
| `id` | yes | First **8 hex characters** of the annotation's UUID. Short enough for a deep link (`#r=1a2b3c4d`) and stable across re-exports. |
| `label_tr` | yes | Turkish label. Consumers fall back to this when `label_en` is absent. |
| `x`, `y`, `width`, `height` | yes | **Bounding box in full-resolution pixels**, always present and **rounded outwards** (minimum 1 px) so a region is never clipped. This alone is enough for "jump here". |
| `uuid` | no | The full UUID. Lets an import restore the object's identity so a **re-export keeps the same `id`** and published deep links keep resolving. **QuPath-only** — dropped on the way to the MCP server. |
| `label_en` | no | English label. |
| `note_tr`, `note_en` | no | From the annotation description; tooltip on the web. |
| `geometry` | no | The full outline as a GeoJSON geometry (QuPath's `ROI` adapter output). Used for the QuPath round trip; **nothing currently draws it**. |

### Not the same as the GeoJSON exchange

The `Bölgeler` menu also carries **`GeoJSON dışa aktar… / içe aktar…`** and **`Bölge dosyasını
GeoJSON'a çevir…`**. Those are a separate **interchange** format for other tools (R's wsiTools,
geometry checks, anything that speaks GeoJSON `FeatureCollection`), documented in the README.

The distinction matters:

| | `<slide>.regions.json` | GeoJSON exchange |
|---|---|---|
| Format id | `atlas-regions/1` | `atlas-regions-geojson/1` |
| Purpose | **publishing** | **interchange** |
| Read by the website / MCP | **yes** | no |
| Bounding box per region | yes, always | **no** — geometry only |
| Classification | only as a label fallback | `class` + `classification` |
| Full outline | optional `geometry` | always, as the `Feature` geometry |
| Short id for deep links | `id` | `properties.atlas_region_id` |

The practical consequence: **editing a GeoJSON copy does not change what visitors see.** Export
`.regions.json` again afterwards if the published teaching regions should change.

`Bölge dosyasını GeoJSON'a çevir…` is **one-way** — it reads an existing `.regions.json` and writes
`.regions.geojson` — but it does so **without opening the slide**, copying the stored geometry
directly and so avoiding QuPath's ROI coordinate rounding.

---

## Pixel size

`DziImageServer` resolves µm/px in this order, and never guesses:

1. An explicit `?mpp=0.25` on the `.dzi` URI.
2. **`xres`** from `<name>_files/vips-properties.xml` — pixels per **millimetre**, so µm/px is
   `1000 / xres`. Preferred because vips computes it *for the image it actually wrote*, which stays
   correct even if the slide was downsampled on export.
3. The scanner tags `openslide.mpp-x`, then `aperio.MPP`. These describe the **original** slide and
   go stale in exactly that downsampling case.
4. Nothing — the slide opens uncalibrated, as before.

Values outside **0.01–100 µm/px** are discarded as placeholders (a pyramid built from a source with
no resolution info can carry `xres=1`, i.e. 1000 µm/px). When `xres` and a scanner tag disagree by
more than **5 %**, the chosen value is used and the disagreement is **logged** — never hidden.

`Piksel boyutu ayarla…` remains for slides the sidecar can't help with; see
[pixel-size-mpp.md](pixel-size-mpp.md).

---

## The three consumers

### QuPath — this extension

- `regions/RegionsExport.java` — the format. Pure, no I/O; reuses `quiz/QuizGeometry` for geometry.
- `regions/RegionsCommands.java` — the `Bölgeler` menu commands.
- `LocalDziOpener.java` — `Yerel DZI aç…`.
- `dzi/DziImageServer.java` — reads local and HTTP pyramids, plus the sidecar calibration.

### Website — `osd-regions.js`

A dependency-free drop-in plugin, **byte-identical** in `pathologyatlas-template` and in each
instance repo, loaded like the other `openseadragon/osd-*.js` plugins:

```html
<script src="./openseadragon/osd-regions.js"></script>
```

```js
var regionsPanel = OSDRegions(viewer, { lang: 'tr' });
```

Options: `lang` (`'tr'` default), `padding` (0.10 — fractional inflate before framing),
`regionsUrl`, `container`, `title`, and the callbacks `onRegionsLoaded(data)`,
`onActivate(regionId)`, `onSizeMismatch(expected, actual)`.
Returns `{ getRegions, getActiveRegionId, activateById, refresh, destroy }`.

It derives `<stain>.regions.json` from the tile source, validates `size` against
`viewer.world.getItemAt(0).getContentSize()`, and **does nothing at all** when the file is missing
or unparsable — most slides have no regions.

**Deep links.** The plugin *reads* an `r=<id>` term from the hash on first open. It never writes to
the hash; a host page that owns a hash convention appends `&r=` itself from `onActivate`. That is
deliberate: in `HE.html`'s `#stain=..&x=..&y=..&z=..`, **`x`/`y` are viewport fractions** while the
plugin works in raw pixels, so the two never meet in one code path.

### AI assistants — `atlas-mcp`

- **`list_regions({ id })`** — the slide's named regions.
- **`show_slide({ id, region? })`** — `region` takes an id *or* a label and opens the viewer zoomed
  to it. Resolution order (`findRegion`): exact `id` → case-folded `id` → case-folded label in
  **either** language → no match (the slide still opens, and the response says so).

Regions ride **inline in `structuredContent`**, which is why this needed no CSP change.

**Quiz slides expose no regions.** 26 *Case of the Month* slides keep their diagnoses in the
search-only `terms` field, and **QuPath region labels are diagnoses**. `visibleRegions()` returns an
empty list for any `slide.quiz`, and `publicSlide()` — what `show_slide` actually returns — projects
through it rather than spreading the raw record.

---

## Invariants nothing enforces

Four places where a future change can break this silently:

1. **The µm/px precedence is duplicated** in `DziImageServer.mppFromVipsProperties` (Java) and
   `atlas-mcp/scripts/mpp.mjs` (JS). If they drift, the same slide reports a different pixel size in
   QuPath than in the catalog. Both are unit-tested; keep them identical.
2. **`osd-regions.js` is copied byte-for-byte** into the template and each instance repo. Edit one,
   copy to the others.
3. **`REGION_FIELDS` in `build-catalog.mjs`** is the whitelist deciding what reaches the Worker
   bundle. It deliberately drops `geometry` (bloat) and `uuid` (meaningless there). The catalog is
   statically imported into every isolate, so **named regions only** — a full detection export would
   not fit.
4. **Any `src/viewer.html` edit needs a `VIEWER_URI` bump** (currently `viewer-v3`). Hosts cache the
   resource by URI, so an un-bumped edit silently serves the old widget.

---

## Traps

- **Annotate the DZI, not the source SVS.** For `lymphocytic-gastritis/HE` the SVS is
  `88055 × 52569` and the DZI is `79923 × 42180`. Regions drawn on the SVS would not line up, and
  the ratios differ per axis so no single scale factor fixes it. The `size` member exists to catch
  this: a mismatch is refused rather than silently misplaced.
- **`fitBounds`, not `zoomTo`.** OpenSeadragon's zoom is `imageWidth / visibleWidth`, so `zoomTo(4)`
  means "the viewport spans a quarter of the image width" — *not* 4× magnification.
  `fitBounds(imageToViewportRectangle(...))` takes the bounding box and solves the zoom itself,
  including for tall regions.
- **OpenSeadragon divides both axes by image *width*.** Viewport-y runs `0 … H/W`, not `0 … 1`.
  `imageToViewportRectangle` handles this; hand-rolled maths usually doesn't.

---

## Limitations

- **Read-only on the web.** Regions are authored in QuPath; visitors can jump to them but not add
  or change any. There is no write path — the MCP server has no storage binding and is declared
  read-only.
- **No outlines are drawn.** Click-to-zoom only. `geometry` is kept in the format so outlines can be
  added later without a migration.
- **Named teaching regions only** — not detections (see invariant 4).
- **Annotorious is not used.** It is vendored but never initialised in all three web repos; a
  click-to-zoom list needs none of it.
