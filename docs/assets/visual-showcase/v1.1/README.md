# EdgeFit AI — Visual Showcase Assets (Walkthrough v1.1)

Self-contained SVG masters for the public visual showcase (EF-DOC-VISUAL-SHOWCASE-12).
Each file is a byte-identical copy of the approved walkthrough graphics master —
no data, labels, or styling were changed.

Authoritative source: `EdgeFit_AI_Definitive_Walkthrough_v1.1_POLISHED.docx`
(SHA-256 `8F62A020F2E533443638C9F93CA1458BFFE14DBA2D26F66BA52EFAB67CA9C396`).
The embedded DOCX PNGs are pixel renders of these same SVG masters (hashes verified
against the graphics `SHA256SUMS.txt`).

## Figure map

Filenames keep their original master identities. v1.1 renumbered the last eight
diagrams by +2 (two new agent-session screenshots were inserted as Figs 34–35),
so some graphics carry an earlier baked-in `FIG NN` label — that label is part of
the approved artwork and is preserved as-is. Headings below use v1.1 numbers.

| v1.1 Fig | Master file | Subject | ViewBox | SHA-256 (first 16) |
|---|---|---|---|---|
| 02 | `edgefit-fig-02-system-architecture.svg` | System architecture | 1600×1000 | `1860B16990CC9848` |
| 15 | `edgefit-fig-15-device-intelligence.svg` | Device intelligence | 1600×1000 | `B03646D7992AF608` |
| 26 | `edgefit-fig-26-benchmark-pipeline.svg` | Native benchmark pipeline | 1600×1000 | `22B0B93083C31D14` |
| 43 | `edgefit-fig-41-agent-lifecycle.svg` | Muse Spark agent lifecycle | 1600×1000 | `7420A2C32CB94CAE` |
| 50 | `edgefit-fig-48-global-network.svg` | Global evidence network | 1600×1000 | `4FB424DB59678BB4` |
| 53 | `edgefit-fig-51-study-chart.svg` | S22 Ultra six-run study chart | 1600×1000 | `11CCF90407ECFA07` |
| 54 | `edgefit-fig-52-provenance-stack.svg` | Evidence provenance stack | 1600×1000 | `BE329DE43B691D40` |

Full SHA-256 list: [`SHA256SUMS.txt`](SHA256SUMS.txt).

## Approved captions (v1.1, abbreviated)

- **Fig 02:** System overview — on-device app, native benchmark, Muse Spark
  (external, optional), governed cloud, public evidence. Contribution
  IMPLEMENTED and PHYSICALLY VERIFIED; publication PROPOSED, gate B OFF.
- **Fig 15:** Device-telemetry source map — reported fields versus
  recorded-unavailable fields; guards fail closed.
- **Fig 26:** Benchmark native execution — preflight guards, worker thread
  (load, prefill, generate, assemble), watchdog, four terminal outcomes.
- **Fig 43:** Muse Spark bounded agent lifecycle — five-attempt budget;
  model proposes, host validates, owner approves each run.
- **Fig 50:** Global evidence pipeline — consent, ingestion, quorum,
  publication gate and revocation. Receipt ≠ qualified ≠ published.
- **Fig 53:** Measured A/B generation throughput — median bars with observed
  ranges (A 16.52, 15.28–16.66; B 10.49, 6.82–13.38; n=3 each). Single S22
  Ultra, exploratory; no population claims.
- **Fig 54:** Measurement provenance stack (L1–L6) and
  confidence/abstention taxonomy.

## Showcase destinations

- GitHub README “EdgeFit AI — How It Works”: Figs 02, 26, 43, 50, 53.
- Landing page “How EdgeFit AI Works” (`website/` line): Fig 02.
- Dashboard “How Evidence Becomes Public” (`website/` line): Fig 50.
- Walkthrough page (`ronbeesoftware.com/edgefit/walkthrough/`): Figs 53, 54
  extend the existing four-diagram preview.

## Integrity

- SVG masters are self-contained (no external images, scripts, or fonts).
- Displayed at 16:10 aspect; rendered text uses
  `'Segoe UI', Verdana, sans-serif` with platform fallback.
- The four reserved v1.1 screenshot slots (Figs 27, 32, 49, 52) have no
  artwork and are never depicted as completed evidence.
