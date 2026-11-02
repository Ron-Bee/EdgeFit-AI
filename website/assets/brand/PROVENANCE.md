# Website brand assets — provenance (EF-UNIFIED-STOREFRONT-01)

All files in this folder derive from the Android app's own artwork.
No stock logos, no invented marks.

## Source of truth

- Master: `android/app/src/main/assets/aero/icon/edgefit-aero-icon-master.svg`
  (original signal-E artwork, EF-UX-AERO-01; see its PROVENANCE.md)
- `edgefit-icon.svg` here is a byte copy of that master.
- Palette: abyss `#071D3B`, deep `#081427`, azure `#1265F5`,
  sky `#49BBFF`, violet `#8054F8`, mist `#F3F7FE`
  (`android/app/src/main/res/values/aero_colors.xml`).

## Generated files (all by `render-brand.ps1`, same geometry)

| File | Use |
|---|---|
| `favicon-16.png`, `favicon-32.png` | Browser tabs |
| `apple-touch-icon.png` (180) | iOS home screen |
| `icon-64.png` | Site header brand mark |
| `icon-192.png`, `icon-512.png` | `site.webmanifest` PWA icons |
| `edgefit-hero-1600.png` | GitHub README hero + download page art |
| `edgefit-og-1200.png` | OpenGraph/social card |

Regenerate: `powershell -File render-brand.ps1`.
The GitHub hero copy lives in the main repo under `assets/brand/`;
both copies render from identical geometry and must be refreshed
together if the master ever changes.
