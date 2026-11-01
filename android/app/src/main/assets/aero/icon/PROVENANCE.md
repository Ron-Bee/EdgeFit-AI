# EdgeFit Aero icon — provenance note (EF-UX-AERO-01 Step 1)

## Originality

This icon is **original artwork** created 2026-10-31 for EdgeFit AI by the
EdgeFit AI coding session (Muse Code). It is an abstract "signal-E"
geometric composition — a vertical spine with three rounded (stadium) bars,
a violet mid-bar, and a mist dot in the lower counter — drawn from scratch
on a 108-unit adaptive grid. It is not traced, copied, or derived from any
existing logo, stock asset, screenshot, or reference mockup, and no
screenshot is bundled as a launcher foreground.

## Geometry (108 adaptive canvas, safe circle d=66 @ 54,54)

- Spine: x35–45, y33–76, r5 — sky→azure gradient (flat `@color/aero_sky` in vectors)
- Top bar: x35–73, y33–43, r5 — sky→azure gradient
- Mid bar: x45–65, y48–58, r5 — violet `#8054F8`
- Bottom bar: x35–73, y66–76, r5 — sky→azure gradient
- Counter dot: center (58,62), r3 — mist `#F3F7FE`
- Background: full bleed 108×108 — abyss `#071D3B` to deep `#081427`

Farthest art corner (35,76) sits r≈29 from center — inside the 33-unit
safe radius, so no launcher mask clips the mark. Bar weight is 10/72 of the
visible width, legible at 48dp.

## File inventory

- Master: `edgefit-aero-icon-master.svg` (this folder; gradients + halo)
- Wordmark lockup: `edgefit-aero-wordmark.svg` (ref only; live text)
- Android foreground: `res/drawable/ic_launcher_foreground.xml` (flat fills —
  VectorDrawable has no gradient support)
- Android background: `res/drawable/ic_launcher_background.xml`
- Themed layer: `res/drawable/ic_launcher_monochrome.xml`
- In-app mark: `res/drawable/ic_aero_mark.xml` (runtime-tinted)
- Adaptive: `res/mipmap-anydpi-v26/ic_launcher.xml`,
  `res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Legacy raster: `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
  and `ic_launcher_round.png` (rendered from this master)
- GitHub square: `export/github/edgefit-aero-1024.png`
- Presentation: `export/presentation/edgefit-aero-presentation-2048.png`
- Manifest: `android:icon` / `android:roundIcon` on `<application>`;
  package `ai.edgefit.diag` (+`.bench` variant) unchanged.

## Re-rendering

Rasters were rendered from the master geometry (background wash + halo +
gradient bars) at each density with 4× supersampling. To regenerate, reuse
the same path data and palette; keep art inside the safe circle and keep
the background full-bleed.
