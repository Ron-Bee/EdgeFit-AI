"""EdgeFit EF-ANDROID-UI-01 static conformance tests.

Parses the Kotlin and C++ sources to prove the JNI progress interface
matches on both sides: mailbox layout, stage/error codes, nativeRun
signature, and the no-logging rule. Runnable on any host with Python.

This complements (never replaces) JVM unit tests, native compilation,
and physical validation.

Run: python scripts/test_ef_android_ui_01.py
"""

from __future__ import annotations

import hashlib
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "android/app/src/main/cpp/bench_jni.cpp"
KT_DIR = ROOT / "android/app/src/main/java/ai/edgefit/diag"


def read(name):
    return (KT_DIR / name).read_text(encoding="utf-8")


class MailboxLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cpp = CPP.read_text(encoding="utf-8")
        cls.kt = read("BenchProgress.kt")

    def test_offsets_match(self):
        # Kotlin byte offsets must equal C++ cell index * 4.
        cells = {
            "MAGIC": 0, "VERSION": 1, "STAGE": 2, "TARGET": 3,
            "GENERATED": 4, "PREFILL_TOKENS": 5, "LOAD_MS": 6,
            "PREFILL_MS": 7, "GEN_MS": 8, "ERROR": 9,
        }
        for name, cell in cells.items():
            with self.subTest(cell=name):
                cpp = re.search(r"PROG_%s = (\d+)" % name, self.cpp)
                kt = re.search(r"OFF_%s = (\d+)" % name, self.kt)
                self.assertIsNotNone(cpp)
                self.assertIsNotNone(kt)
                self.assertEqual(int(cpp.group(1)), cell)
                self.assertEqual(int(kt.group(1)), cell * 4)

    def test_magic_version_size_match(self):
        self.assertIn("0x45444231", self.cpp)
        self.assertIn("0x45444231", self.kt)
        self.assertRegex(self.cpp, r"PROG_CELLS = 16")
        self.assertRegex(self.kt, r"SIZE_BYTES = 64")

    def test_stage_codes_match(self):
        stages = ["LOADING", "PREFILL", "GENERATING", "NATIVE_DONE",
                  "NATIVE_ERROR"]
        for index, name in enumerate(stages):
            with self.subTest(stage=name):
                self.assertRegex(self.cpp, r"PSTAGE_%s = %d" % (name, index))
                self.assertRegex(self.kt, r"STAGE_%s = %d" % (name, index))

    def test_error_codes_match(self):
        errors = ["NONE", "MODEL_LOAD", "CONTEXT_INIT", "TOKENIZE",
                  "CONTEXT_OVERFLOW", "PREFILL_DECODE", "GEN_DECODE"]
        for index, name in enumerate(errors):
            with self.subTest(error=name):
                self.assertRegex(self.cpp, r"PERR_%s = %d" % (name, index))
                self.assertRegex(self.kt, r"ERR_%s = %d" % (name, index))


class JniBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cpp = CPP.read_text(encoding="utf-8")
        cls.bridge = read("LlamaBridge.kt")

    def test_native_run_signature_matches(self):
        # Kotlin external decl and JNI impl must take the same parameters.
        decl = re.search(
            r"external fun nativeRun\((.*?)\)", self.bridge, re.S)
        self.assertIsNotNone(decl)
        kt_params = [p.strip().split(":")[0]
                     for p in decl.group(1).split(",") if p.strip()]
        self.assertEqual(kt_params,
                         ["modelPath", "prompt", "nCtx", "nThreads",
                          "nPredict", "cancelMailbox", "progressMailbox"])
        impl = re.search(
            r"Java_ai_edgefit_diag_LlamaBridge_nativeRun\((.*?)\)",
            self.cpp, re.S)
        self.assertIsNotNone(impl)
        for token in ("j_model_path", "j_prompt", "n_ctx", "n_threads",
                      "n_predict", "cancel_mailbox", "progress_mailbox"):
            self.assertIn(token, impl.group(1))

    def test_progress_mailbox_capacity_checked(self):
        self.assertIn("GetDirectBufferCapacity", self.cpp)
        self.assertIn("PROG_CELLS * 4", self.cpp)

    def test_cancel_still_polled(self):
        self.assertGreaterEqual(len(re.findall(r"cancel->load\(\)", self.cpp)), 2)

    def test_no_token_or_prompt_logging(self):
        self.assertNotIn("android/log.h", self.cpp)
        self.assertNotIn("__android_log", self.cpp)
        self.assertNotIn("LOGD", self.cpp)
        # Progress flows only through relaxed mailbox stores.
        self.assertGreater(len(re.findall(r"pwrite\(progress", self.cpp)), 5)
        self.assertIn("__ATOMIC_RELAXED", self.cpp)

    def test_batch_ownership_preserved(self):
        self.assertEqual(re.findall(r"llama_batch_free\s*\(", self.cpp), [])


class WorkloadTransparencyTest(unittest.TestCase):
    RECORDED_SHA = (
        "36104db0657a294048410f9043d4efc8510a77088ca1e2f17c0b6c8359c44382"
    )

    @classmethod
    def setUpClass(cls):
        cls.contract = read("BenchContract.kt")
        cls.view = read("BenchWorkloadView.kt")
        cls.manifest = (ROOT / "android/app/src/main/AndroidManifest.xml"
                        ).read_text(encoding="utf-8")

    def test_single_prompt_source_of_truth(self):
        # Main source holds exactly one literal; the JVM freeze test in
        # test sources intentionally repeats it to detect drift.
        hits = []
        for path in sorted((ROOT / "android/app/src/main").rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            if "Explain in exactly" in text:
                hits.append(path.name)
        self.assertEqual(hits, ["BenchContract.kt"])
        self.assertIn("BenchContract.WORKLOAD_PROMPT", self.view)

    def test_prompt_hash_matches_recorded_identity(self):
        match = re.search(r'WORKLOAD_PROMPT =\s*"(.*?)" \+\s*"(.*?)"',
                          self.contract, re.S)
        self.assertIsNotNone(match)
        prompt = match.group(1) + match.group(2)
        self.assertEqual(
            hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
            self.RECORDED_SHA)
        self.assertIn("edgefit-prompt-v1", self.contract)

    def test_product_name(self):
        self.assertIn('android:label="EdgeFit AI"', self.manifest)
        self.assertNotIn("EdgeFitDiag", self.manifest)


class ContractStabilityTest(unittest.TestCase):
    def test_benchmark_contract_untouched(self):
        contract = read("BenchContract.kt")
        self.assertIn('CONTRACT_VERSION = 1', contract)
        for status in ("COMPLETED", "CANCELLED", "FAILED", "TIMEOUT"):
            self.assertIn(status, contract)

    def test_execution_states_complete(self):
        progress = read("BenchProgress.kt")
        for state in ("IDLE", "VALIDATING_MODEL", "LOADING_MODEL",
                      "PROCESSING_PROMPT", "GENERATING", "FINALIZING",
                      "COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"):
            self.assertRegex(progress, r"\b%s = " % state)


if __name__ == "__main__":
    unittest.main(verbosity=2)
