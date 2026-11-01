# EF-FLEET-AUTO-02: Automated Multi-Device Fleet Qualification — Architecture Draft

Status: DRAFT (readiness report; no fleet code implemented yet).
Basis: verified `tools/auto01/` S22 harness (EfAuto.psm1 C837BD86…,
Run-StudyBenchmark.ps1 D35CA221…, DryRun-Ui.ps1 914D5AC0…).
Preservation: `C:\Users\Ron\EdgeFit-Evidence\backup-ef-comp-01b-20261031`
(50 files + MANIFEST.sha256). Study checkout untouched on
`implement/ef-comp-01b-gate-1a-2026-10-30` @ `7a30853`.
Fleet branch ref created: `fleet/auto-02-harness-2026-10-31` (no checkout).

## 1. Lessons from the S22 study (must fix for fleet)

1. Case-insensitive `-like` terminal match can false-positive on the
   main screen's stale `Benchmark: COMPLETED` text. Fleet monitor must
   require bench-overlay context (SAVE/WORKLOAD/BACK visible) AND a
   run-start handshake (tokens `0 / N` or stage `starting…` observed,
   or run_id newer than batch start).
2. Export linkage is newest-file heuristic. Fleet must parse the pulled
   JSON run_id and cross-check freshness (collected_at > run start) and,
   where available, the standalone session record's run_id.
3. `beforeSave` race (snapshot must precede the SAVE tap) — fixed in
   S22 script; keep the ordering invariant as a tested rule.
4. Hard-coded serial, package, paths, triples, decision timestamps.
   Fleet takes a per-device manifest (JSON) as the only input.
5. Fixed sleeps; re-dump immediately before the Run tap; verify return
   after Back.
6. PREREAD logs unfiltered dumpsys blobs; split lines before filtering.
7. No battery/thermal STOP gates; no run-count enforcement; ledger has
   no duplicate-run guard. All required for fleet.
8. One approved run must never start two benchmarks: single-tap +
   post-tap overlay verification + refusal on ambiguous state; never
   re-tap Run after any possibly-started run.

## 2. Fleet components (proposed, `tools/fleet/` on fleet branch)

- `FleetCore.psm1`: parameterized ADB (serial per call), UI dump/parse,
  tap with ambiguity refusal, byte-exact pull, PNG magic check.
- `Register-Device.ps1` (read-only): model, Android/API, ABI, CPUs,
  RAM, storage, thermal/charging, APK id, model SHA, runtime support →
  `devices/<serial>.json`.
- `New-DeviceManifest.ps1`: builds the owner-reviewable batch manifest:
  device, protocol/study id, model/workload/runtime ids, config
  sequence, authorized run count, readiness gates, evidence dir,
  stop rules. Owner approves the manifest file, not chat text.
- `Run-DeviceBatch.ps1 -Manifest <file>`: executes the bounded batch,
  enforcing run count, gates, per-run evidence linkage, append-only
  ledger; STOPS (no retry) on any failure/ambiguity.
- `Audit-FleetEvidence.ps1`: independent re-verification (hashes,
  sequence, identities, medians) mirroring Phase 6 of AUTO-01.
- `New-FleetMatrix.ps1`: human-readable compatibility matrix incl.
  unsupported/failed devices; never pools cohorts.

## 3. Cohort and consent rules (from master architecture)

- Cohort key: model SHA + workload SHA + runtime/build + config triple
  + device class. Different keys are never pooled.
- S22 model (770 MB) unsuitable for low-RAM devices: separate
  registered protocols with smaller models, separate cohorts.
- Quorum per exact cohort: >= 5 eligible runs AND >= 3 distinct
  install ids. Below quorum: insufficient-evidence, no aggregates.
- No global upload without separate per-batch consent; aggregate vs
  raw-run disclosure scopes stay independent; only PUBLISHED evidence
  served publicly or to Muse retrieval.

## 4. Approval checkpoints

- A (this report): S22 preservation + audit. DONE, owner review.
- B: fleet harness implemented + read-only qual on S22. Needs auth.
- C: per-device batch manifest approval (one bounded approval/device).
- D: fleet execution + evidence audit.
- E: separate consent + global integration once backend deployed.

## 5. Open owner decisions

1. Which 5+ additional physical devices (model/RAM/Android) join first?
2. Smaller-model protocol(s) for low-RAM devices: which GGUFs, who
   registers their SHA/license?
3. New study freeze per device cohort (required; S22 freeze not reused).
4. Backup retention/encryption for `EdgeFit-Evidence` (contains raw runs).
5. Whether fleet harness source may enter public Git (no evidence/keys).
