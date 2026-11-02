# Presentation derivatives — provenance (EF-GITHUB-VISUAL-POLISH-04)

Date: 2026-11-02. Renderer: `assets/brand/render-panels.ps1` (deterministic,
same template for all eleven; re-running reproduces these bytes).

## Evidence rule

Originals under `assets/screenshots/2026-11-01/` are byte-identical to
their recorded SHA-256 in `docs/screenshot-gallery-2026-11-01.md`
(verified 11/11 during this task). They are never modified. The panels
below are PRESENTATION versions: cropped system UI, decorative card.
App pixels are scaled, never edited, inpainted, or reworded.

## Uniform transform (all eleven)

- Source: 1080x2316 capture.
- Crop: top 96 px (Android status bar: clock, notification icons,
  battery) and bottom 160 px (system gesture nav bar). No app pixel
  removed or altered; no notification covered app content in any shot.
- Scale: aspect-preserving to 780 px tall inside a 1600x900 Aero card
  (abyss/deep gradient, azure glow, rounded phone frame, title,
  description, "PRESENTATION CROP" tag).

## Per-shot record

| # | Original (SHA-256, first 16) | Panel (SHA-256, first 16) | Notifications | System UI cropped | Notes |
|---|---|---|---|---|---|
| 01 | `E965A654E709C2FD…` | `2B120EAB2D34E65A…` | status icons only, removed by crop | status + nav bars | — |
| 02 | `F8F06D86FA7500CB…` | `D18A89B9593ECD52…` | status icons only, removed by crop | status + nav bars | Model card mid-scroll in original, shown as-is |
| 04 | `C8458CDFA3B5A8DB…` | `BDFB69446D2AF7C0…` | status icons only, removed by crop | status + nav bars | — |
| 06 | `7A82D260A7B0E76A…` | `209B64CEA3F4B793…` | status icons only, removed by crop | status + nav bars | — |
| 09 | `ECC80ADFFAAE29E6…` | `36140571B4A20420…` | status icons only, removed by crop | status + nav bars | Large empty region is authentic (run in progress) |
| 10 | `2A78369E2F06E07A…` | `FAB55D9585BF5922…` | status icons only, removed by crop | status + nav bars | — |
| 12 | `EF8C64C92615CE51…` | `2AF20D518457AA2D…` | status icons only, removed by crop | status + nav bars | — |
| 13 | `F7B3A58864452FCB…` | `19D6B14560E3E5C8…` | status icons only, removed by crop | status + nav bars | — |
| 14 | `1A3416254D3FEB24…` | `1EA7F1AB3547102B…` | status icons only, removed by crop | status + nav bars | — |
| 22 | `6820B3764D682D26…` | `2746FA39B600B48C…` | status icons only, removed by crop | status + nav bars | — |
| 25 | `EB937061E65100F2…` | `668CB5403164D9F1…` | status icons only, removed by crop | status + nav bars | Bottom row mid-scroll in original, shown as-is |

Full hashes: `Get-FileHash assets/screenshots/2026-11-01/*.png`
and `assets/screenshots/presentation/*.png -Algorithm SHA256`.

No shot required a replacement capture. No private content found in
any original (status icons unidentifiable, no personal text).
