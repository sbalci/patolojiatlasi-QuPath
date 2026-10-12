# Patoloji Atlası visual assets

The files in this directory are ready to copy into a website's static asset folder. The square mark is also bundled in the QuPath extension and embedded as the standalone tour player's favicon. The mark combines a newly drawn QuPath-style blue magnifier and an open book; it does not embed QuPath's original icon artwork.

| File | Size | Use |
| --- | --- | --- |
| `atlas-mark.svg` | Vector | Website logo and scalable icon master |
| `atlas-mark-16.png`, `atlas-mark-32.png` | 16, 32 px | Favicons and compact UI |
| `favicon.ico` | 16, 32, 48 px | Browser fallback favicon |
| `atlas-mark-64.png`, `atlas-mark-128.png`, `atlas-mark-256.png` | 64–256 px | Desktop and promotional icons |
| `atlas-mark-180.png` | 180 px | Apple touch icon |
| `atlas-hero.webp` | 1672 × 941 px | Website hero background, with light space on the left for HTML text |
| `atlas-stain-comparison.webp` | 1672 × 941 px | Website feature image for stain comparison |
| `atlas-social-1200x630.jpg` | 1200 × 630 px | Social sharing / Open Graph image |

Use real HTML text over the hero image; the artwork contains no labels. For a site that serves these files at `/assets/brand/`:

```html
<link rel="icon" href="/assets/brand/atlas-mark.svg" type="image/svg+xml">
<link rel="alternate icon" href="/assets/brand/favicon.ico">
<link rel="apple-touch-icon" href="/assets/brand/atlas-mark-180.png">
<meta property="og:image" content="https://YOUR-SITE/assets/brand/atlas-social-1200x630.jpg">

<img src="/assets/brand/atlas-hero.webp" width="1672" height="941"
     alt="Illustrative histology fields at increasing magnification">
<img src="/assets/brand/atlas-stain-comparison.webp" width="1672" height="941"
     alt="Illustrative side-by-side H&amp;E and immunohistochemistry stain motifs">
```

The histology images are generated **illustrations**, not photographs of atlas cases or diagnostic specimens. Keep that distinction in captions and alt text; use the atlas's actual slide thumbnails when presenting a specific case.

The full-resolution image masters are in `source/`. Run `python tools/build-brand-assets.py` from the repository root after editing the SVG or masters to rebuild PNG icons, WebP images, the social card, the QuPath resource icon, and the web player's embedded favicon. The script requires Pillow. If `rsvg-convert` is installed, it also refreshes `atlas-mark-256.png` from the SVG; otherwise it uses the checked-in PNG.

The older promotional JPGs in `assets/` remain available for existing uses. This set has clean typography drawn separately from the image assets and a mark designed for small UI sizes.
