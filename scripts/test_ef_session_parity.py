"""EdgeFit EF-SESSION-01 Python/Kotlin comparison parity.

Runs the REAL ef_loop_01 attach path over shared vectors and asserts the
error outputs equal the literals embedded in SessionStoreTest.kt. Both
sides pin the same literals; if either implementation drifts, its own
test fails. Vectors avoid exact rounding ties (CPython uses banker's
rounding, Kotlin Math.round is half-up).

Run: python scripts/test_ef_session_parity.py
"""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_loop_01 as loop
import test_ef_loop_01 as loop_test


# Shared parity vectors. MUST match SessionStoreTest.PARITY_VECTORS.
# (pred_gen, pred_pre, meas_gen, meas_pre,
#  gen_signed, gen_abs, gen_pct, pre_signed, pre_abs, pre_pct)
PARITY_VECTORS = [
    (15.0, 20.0, 9.2, 22.54901960784314,
     -5.8, 5.8, -38.67, 2.549, 2.549, 12.75),
    (0.4, 0.6, 14.382022471910112, 22.54901960784314,
     13.982, 13.982, 3495.51, 21.949, 21.949, 3658.17),
    (16.9, 24.52, 16.6, 26.65,
     -0.3, 0.3, -1.78, 2.13, 2.13, 8.69),
]


# (gen_ms, gen_tokens, pre_ms, pre_tokens) consistent with each vector's
# measured tps within the contract validator's 0.01 tolerance.
MS_TOKENS = [
    (6957, 64, 1020, 23),
    (4450, 64, 1020, 23),
    (3855, 64, 863, 23),
]


class ParityBase(unittest.TestCase):
    def manifest_dir(self):
        return Path(tempfile.mkdtemp())


class ComparisonParityTest(ParityBase):
    def test_loop_errors_match_kotlin_literals(self):
        """ef_loop_01's signed errors equal the shared parity literals.

        Note: ef_loop_01 names the SIGNED error "gen_abs_tps"; Kotlin
        exposes it as gen_signed plus a true gen_abs. Parity is on the
        signed value and the prediction-denominator percentages.
        """
        for (index, vector) in enumerate(PARITY_VECTORS):
            (pred_gen, pred_pre, meas_gen, meas_pre, gen_signed, _gen_abs,
             gen_pct, pre_signed, _pre_abs, pre_pct) = vector
            with self.subTest(vector=index):
                manifest_dir = self.manifest_dir()
                loop_id = "LOOP-PARITY-%04d" % index
                model = Path(tempfile.mkdtemp()) / "parity.gguf"
                model.write_bytes(b"GGUF-parity-%d" % index)
                model_sha = hashlib.sha256(model.read_bytes()).hexdigest()
                profile = loop_test.write_json(
                    self, loop_test.make_profile())
                mock_file = loop_test.write_json(
                    self, loop_test.mock_prediction(
                        predicted_gen_tps=pred_gen,
                        predicted_prefill_tps=pred_pre))
                loop.cmd_init({
                    "profile": str(profile),
                    "model": str(model),
                    "n_ctx": 512,
                    "n_threads": 4,
                    "n_predict": 64,
                    "seed": 42,
                    "mock_meta": str(mock_file),
                    "manifest_dir": str(manifest_dir),
                    "loop_id": loop_id,
                })
                gen_ms, gen_tokens, pre_ms, pre_tokens = MS_TOKENS[index]
                bench = loop_test.write_json(
                    self, loop_test.make_bench(
                        model_sha,
                        **{"measurements.tps_gen": meas_gen,
                           "measurements.tps_prefill": meas_pre,
                           "measurements.gen_ms": gen_ms,
                           "measurements.generated_tokens": gen_tokens,
                           "measurements.prefill_ms": pre_ms,
                           "measurements.prefill_tokens": pre_tokens}))
                loop.cmd_attach_bench({"manifest": loop_id,
                                       "bench": str(bench),
                                       "manifest_dir": str(manifest_dir)})
                manifest = json.loads(
                    (manifest_dir / (loop_id + ".json")).read_text(
                        encoding="utf-8"))
                errors = manifest["errors"]
                self.assertEqual(errors["gen_abs_tps"], gen_signed)
                self.assertEqual(errors["gen_pct"], gen_pct)
                self.assertEqual(errors["prefill_abs_tps"], pre_signed)
                self.assertEqual(errors["prefill_pct"], pre_pct)
                self.assertTrue(errors["from_persisted_prediction"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
