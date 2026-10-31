-- G2 migration 001: initial global evidence schema (H11).
-- Applied by `wrangler d1 migrations apply` on staging; NEVER
-- edited after first apply (later changes are new migrations).
-- Body is verbatim `cloud/schema.sql`, guarded by the G2 drift
-- test. No data, no seeds, no fixtures migrate anywhere.
-- EF-CLOUD-01A global evidence foundation schema.
-- Plain SQLite (Cloudflare D1 compatible): no extensions, no
-- stored procedures. Hashes are computed in JS so the same
-- statements run on node:sqlite, D1, and any SQLite host.
-- Local install identities are random per-install secrets;
-- they are NEVER hardware attestation and never leave the
-- backend except as salted aggregate counts.

CREATE TABLE IF NOT EXISTS installs (
  install_id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS submissions (
  submission_id TEXT PRIMARY KEY,
  install_id TEXT NOT NULL REFERENCES installs (install_id),
  content_hash TEXT NOT NULL UNIQUE,
  result_json TEXT NOT NULL,
  run_id TEXT NOT NULL,
  device_class TEXT NOT NULL,
  model_sha TEXT NOT NULL,
  model_size_bytes INTEGER NOT NULL,
  quantization_label TEXT NOT NULL,
  workload_id TEXT NOT NULL,
  prompt_sha TEXT NOT NULL,
  n_ctx INTEGER NOT NULL,
  n_threads INTEGER NOT NULL,
  n_predict INTEGER NOT NULL,
  sampler TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  runtime_impl TEXT NOT NULL,
  runtime_backend TEXT NOT NULL,
  runtime_version TEXT NOT NULL,
  effective_opt TEXT NOT NULL,
  effective_opt_status TEXT NOT NULL,
  build_key TEXT NOT NULL,
  status TEXT NOT NULL,
  tps_gen REAL,
  tps_prefill REAL,
  gen_tokens INTEGER,
  prompt_tokens INTEGER,
  collected_at TEXT NOT NULL,
  app_version TEXT NOT NULL,
  mem_avail_pct_start REAL,
  mem_below_advisory INTEGER,
  mem_low_memory INTEGER,
  consent_policy_version TEXT NOT NULL,
  consent_accepted_at TEXT NOT NULL,
  consent_scopes TEXT NOT NULL,
  eligibility TEXT NOT NULL,
  received_at TEXT NOT NULL,
  published_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_sub_eligibility ON submissions (eligibility);
CREATE INDEX IF NOT EXISTS idx_sub_cohort ON submissions (
  model_sha, device_class, build_key, workload_id, config_hash);
CREATE INDEX IF NOT EXISTS idx_sub_run ON submissions (run_id);
CREATE INDEX IF NOT EXISTS idx_sub_install ON submissions (install_id);

CREATE TABLE IF NOT EXISTS eligibility_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  submission_id TEXT NOT NULL REFERENCES submissions (submission_id),
  from_state TEXT,
  to_state TEXT NOT NULL,
  reason TEXT NOT NULL,
  at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_hist_sub ON eligibility_history (submission_id);

CREATE TABLE IF NOT EXISTS daily_counts (
  install_id TEXT NOT NULL,
  day TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (install_id, day)
);
