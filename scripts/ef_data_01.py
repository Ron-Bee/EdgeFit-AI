"""EdgeFit EF-DATA-01: local SQLite schema + synthetic fixture and persistence checks.
No external dependencies, network requests, cloud data or Meta API key required.
"""
import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB = ROOT / "data" / "edgefit.db"
DB.parent.mkdir(parents=True, exist_ok=True)
connection = sqlite3.connect(DB)
connection.execute("PRAGMA foreign_keys=ON")
connection.executescript("""
CREATE TABLE IF NOT EXISTS device_profiles (
  id TEXT PRIMARY KEY, source TEXT NOT NULL, device_class TEXT NOT NULL,
  total_ram_mb INTEGER NOT NULL CHECK(total_ram_mb>0),
  available_ram_mb INTEGER NOT NULL CHECK(available_ram_mb>=0),
  api_level INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS experiments (
  id TEXT PRIMARY KEY, profile_id TEXT NOT NULL REFERENCES device_profiles(id),
  model_id TEXT NOT NULL, config_json TEXT NOT NULL, source TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS predictions (
  id TEXT PRIMARY KEY, experiment_id TEXT NOT NULL REFERENCES experiments(id),
  predicted_tps REAL NOT NULL CHECK(predicted_tps>=0),
  is_verified INTEGER NOT NULL CHECK(is_verified=0),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS benchmark_runs (
  id TEXT PRIMARY KEY, experiment_id TEXT NOT NULL REFERENCES experiments(id),
  measured_tps REAL NOT NULL CHECK(measured_tps>=0),
  source TEXT NOT NULL CHECK(source IN ('synthetic_fixture','physical_measurement')),
  physically_verified INTEGER NOT NULL CHECK(physically_verified IN (0,1)),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK(source='physical_measurement' OR physically_verified=0)
);
CREATE INDEX IF NOT EXISTS idx_experiments_profile_model ON experiments(profile_id,model_id);
""")
with connection:
    connection.execute("INSERT OR IGNORE INTO device_profiles(id,source,device_class,total_ram_mb,available_ram_mb,api_level) VALUES (?,?,?,?,?,?)",("SYNTH-DEVICE-001","synthetic_fixture","android-8gb-test",8192,4200,36))
    connection.execute("INSERT OR IGNORE INTO experiments(id,profile_id,model_id,config_json,source) VALUES (?,?,?,?,?)",("SYNTH-EXPERIMENT-001","SYNTH-DEVICE-001","llama-3.2-1b",json.dumps({"quantization":"Q4_K_M","threads":4,"context_tokens":2048}),"synthetic_fixture"))
    connection.execute("INSERT OR IGNORE INTO predictions(id,experiment_id,predicted_tps,is_verified) VALUES (?,?,?,0)",( "SYNTH-PREDICTION-001","SYNTH-EXPERIMENT-001",15.0))
    connection.execute("INSERT OR IGNORE INTO benchmark_runs(id,experiment_id,measured_tps,source,physically_verified) VALUES (?,?,?,?,?)",("SYNTH-BENCH-001","SYNTH-EXPERIMENT-001",9.2,"synthetic_fixture",0))
row=connection.execute("""SELECT p.id, e.model_id, pred.predicted_tps, b.measured_tps, b.source,b.physically_verified FROM device_profiles p JOIN experiments e ON e.profile_id=p.id JOIN predictions pred ON pred.experiment_id=e.id JOIN benchmark_runs b ON b.experiment_id=e.id WHERE p.id=?""",("SYNTH-DEVICE-001",)).fetchone()
assert row is not None
assert row[2]==15 and row[3]==9.2 and row[4]=='synthetic_fixture' and row[5]==0
n=connection.execute("SELECT count(*) FROM benchmark_runs WHERE id='SYNTH-BENCH-001'").fetchone()[0]
assert n==1, "Fixture must remain idempotent after reruns"
print("EF-DATA-01: PASS — persistent SQLite database and join retrieval")
print(json.dumps({"database":str(DB),"device_id":row[0],"model_id":row[1],"predicted_tps":row[2],"synthetic_observed_tps":row[3],"difference_tps":round(row[2]-row[3],3),"record_source":row[4],"physically_verified":bool(row[5]),"duplicate_count":n},indent=2))
connection.close()
