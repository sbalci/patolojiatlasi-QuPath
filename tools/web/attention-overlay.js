/*
 * Atlas attention-map overlay — Phase 3 of docs/focus-aggregation-plan.md.
 *
 * Drop-in for the atlas OpenSeadragon viewer: shows the crowd-aggregated focus
 * heatmap produced by tools/aggregate-focus.py as a translucent, toggleable
 * overlay on the slide, with a "N readers" legend. It renders the aggregator's
 * own <hash>.png (so the colormap always matches the extension), sized from the
 * aggregate metadata in index.json. No build step, no dependencies beyond the
 * OpenSeadragon that the page already loads.
 *
 * Usage — after the viewer is created:
 *
 *   AtlasAttentionOverlay.init(viewer, {
 *     focusBase: 'focus',   // folder served with index.json + <hash>.png (from Phase 2)
 *     slideKey:  'https://images.patolojiatlasi.com/<case>/HE.dzi', // MUST equal the
 *                           // slideKey the QuPath extension recorded (DZI URL, no query)
 *     container: '#toolbar .btn-row', // optional: element/selector to place the toggle button
 *     opacity:   0.55       // optional overlay opacity
 *   });
 *
 * If this slide has no published aggregate (or fewer than the minimum readers),
 * the toggle button hides itself — nothing is shown.
 */
(function (global) {
  'use strict';

  function el(tag, props) {
    var e = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (k) { e[k] = props[k]; });
    }
    return e;
  }

  function init(viewer, opts) {
    opts = opts || {};
    var base = String(opts.focusBase || 'focus').replace(/\/+$/, '');
    var slideKey = opts.slideKey;
    var opacity = opts.opacity != null ? opts.opacity : 0.55;

    var btn = el('button', { type: 'button', textContent: 'Dikkat haritası', disabled: true });
    btn.title = 'Okuyucuların odaklandığı bölgeler (anonim topluluk verisi)';
    var legend = el('span', { className: 'attention-readers' });
    legend.style.cssText = 'margin-left:6px;font-size:11px;color:#9ab;';

    var img = null, added = false, visible = false, meta = null;

    if (opts.container) {
      var c = typeof opts.container === 'string' ? document.querySelector(opts.container) : opts.container;
      if (c) { c.appendChild(btn); c.appendChild(legend); }
    }

    function overlayRect() {
      var aspect = (meta.imageHeight && meta.imageWidth) ? meta.imageHeight / meta.imageWidth : 1;
      return new OpenSeadragon.Rect(0, 0, 1, aspect); // image spans viewport x[0..1], y[0..aspect]
    }

    function show() {
      if (!meta) return;
      if (!added) {
        img = el('img', { src: base + '/' + meta.hash + '.png', alt: 'attention map' });
        img.style.cssText = 'width:100%;height:100%;opacity:' + opacity + ';pointer-events:none;';
        viewer.addOverlay({ element: img, location: overlayRect() });
        added = true;
      } else {
        img.style.display = '';
      }
      visible = true;
      btn.classList.add('active');
    }

    function hide() {
      if (img) { img.style.display = 'none'; }
      visible = false;
      btn.classList.remove('active');
    }

    btn.addEventListener('click', function () { visible ? hide() : show(); });

    // Look up this slide's aggregate. Missing slide / no index → hide the button.
    fetch(base + '/index.json', { cache: 'no-store' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (idx) {
        if (!idx || !idx.slides || !slideKey || !idx.slides[slideKey]) {
          btn.style.display = 'none';
          return;
        }
        meta = idx.slides[slideKey];
        legend.textContent = meta.readers + ' okuyucu';
        btn.disabled = false;
      })
      .catch(function () { btn.style.display = 'none'; });

    return { button: btn, legend: legend, show: show, hide: hide };
  }

  global.AtlasAttentionOverlay = { init: init };
})(typeof window !== 'undefined' ? window : this);
