// EF-COMP-01A manifest validator tests (offline, temp fixtures).
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { validateManifest, sha256Hex } from "./ef-comp-01a-manifest.mjs";

function benchDoc(runId, nThreads = 4) {
  return {
    contract: "edgefit-bench-result",
    contract_version: 1,
    run_id: runId,
    status: "COMPLETED",
    config: { n_ctx: 512, n_threads: nThreads, n_predict: 64 },
    workload: { id: "edgefit-prompt-v1", prompt_sha256: "a".repeat(64) },
    model: { sha256: "b".repeat(64) },
    measurements: { tps_gen: 16.0 },
    collected_at_utc: "2026-10-28T01:00:00Z",
  };
}

function setup() {
  // EF_COMP_TMP overrides the temp base (sandboxed runners);
  // defaults to the OS temp directory.
  const base = process.env.EF_COMP_TMP || tmpdir();
  const dir = mkdtempSync(join(base, "comp01a-"));
  const env = { EDGEFIT_EVIDENCE: dir };
  const write = (name, obj) => {
    const bytes = Buffer.from(JSON.stringify(obj), "utf8");
    writeFileSync(join(dir, name), bytes);
    return sha256Hex(bytes).toUpperCase();
  };
  return { dir, env, write };
}

test("valid manifest verifies files and reports pairs", () => {
  const { env, write } = setup();
  const sha = write("bench.json", benchDoc("BENCH-1"));
  const manifest = {
    manifest_version: 1,
    entries: [
      {
        id: "m1", role: "measurement",
        path: "${EDGEFIT_EVIDENCE}/bench.json", sha256: sha,
      },
      {
        id: "o1", role: "documented-observation",
        session_ref: "SES-test", note: "fixture",
      },
    ],
    pairs: [
      {
        id: "p1", prediction: "o1",
        predicted_at_utc: "2026-10-28T00:00:00Z",
        collected_at_utc: "2026-10-28T01:00:00Z",
      },
    ],
  };
  const { ok, problems, report } = validateManifest(manifest, env);
  assert.equal(ok, true);
  assert.deepEqual(problems, []);
  assert.equal(report.measurements_verified, 1);
  assert.equal(report.evaluable_pairs, 1);
  assert.equal(report.config_variation, false);
});

test("tampered hash and missing file fail", () => {
  const { env, write } = setup();
  write("bench.json", benchDoc("BENCH-1"));
  const bad = validateManifest({
    manifest_version: 1,
    entries: [
      {
        id: "m1", role: "measurement",
        path: "${EDGEFIT_EVIDENCE}/bench.json",
        sha256: "0".repeat(64),
      },
    ],
    pairs: [],
  }, env);
  assert.equal(bad.ok, false);
  assert.ok(bad.problems.some((p) => p.includes("mismatch")));
  const missing = validateManifest({
    manifest_version: 1,
    entries: [
      {
        id: "m2", role: "measurement",
        path: "${EDGEFIT_EVIDENCE}/nope.json",
        sha256: "0".repeat(64),
      },
    ],
    pairs: [],
  }, env);
  assert.equal(missing.ok, false);
  assert.ok(missing.problems.some((p) => p.includes("missing")));
});

test("unrecorded prediction timestamps are NOT_EVALUABLE, not failures", () => {
  const { env } = setup();
  const { ok, report } = validateManifest({
    manifest_version: 1,
    entries: [
      {
        id: "o1", role: "documented-observation",
        session_ref: "SES-test", note: "fixture",
      },
    ],
    pairs: [
      {
        id: "p1", prediction: "o1",
        predicted_at_utc: null,
        collected_at_utc: "2026-10-28T01:00:00Z",
      },
    ],
  }, env);
  assert.equal(ok, true);
  assert.equal(report.evaluable_pairs, 0);
  assert.equal(report.pair_details[0].status, "NOT_EVALUABLE");
});

test("synthetic entries must stay inline and labeled", () => {
  const { env } = setup();
  const bad = validateManifest({
    manifest_version: 1,
    entries: [
      { id: "s1", role: "synthetic-adversarial", path: "x.json" },
    ],
    pairs: [],
  }, env);
  assert.equal(bad.ok, false);
  const good = validateManifest({
    manifest_version: 1,
    entries: [
      {
        id: "s1", role: "synthetic-adversarial",
        payload: { config: [512, 8, 64] },
      },
    ],
    pairs: [],
  }, env);
  assert.equal(good.ok, true);
  assert.deepEqual(good.report.synthetic_entries, ["s1"]);
});

test("config variation is detected across verified runs", () => {
  const { env, write } = setup();
  const a = write("a.json", benchDoc("BENCH-A", 4));
  const b = write("b.json", benchDoc("BENCH-B", 8));
  const { ok, report } = validateManifest({
    manifest_version: 1,
    entries: [
      {
        id: "a", role: "measurement",
        path: "${EDGEFIT_EVIDENCE}/a.json", sha256: a,
      },
      {
        id: "b", role: "measurement",
        path: "${EDGEFIT_EVIDENCE}/b.json", sha256: b,
      },
    ],
    pairs: [],
  }, env);
  assert.equal(ok, true);
  assert.equal(report.config_variation, true);
  assert.equal(report.distinct_configs.length, 2);
});
