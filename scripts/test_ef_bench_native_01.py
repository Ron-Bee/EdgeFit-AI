"""EdgeFit EF-BENCH-01 native ownership regression test.

Guards the SIGABRT fix: bench_jni.cpp uses only non-owning
llama_batch_get_one views, which must NEVER be passed to
llama_batch_free (pinned proof: llama.cpp v0.7.0 src/llama-batch.cpp,
get_one allocates nothing while free()s every member).

If a future change introduces llama_batch_init (owning), update this
test to require paired init/free rather than zero frees.

Run: python scripts/test_ef_bench_native_01.py
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

JNI = Path(__file__).resolve().parents[1] / "android" / "app" / "src" / "main" / "cpp" / "bench_jni.cpp"


class NativeOwnershipTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = JNI.read_text(encoding="utf-8")

    def test_no_batch_free_of_borrowed_views(self):
        frees = re.findall(r"llama_batch_free\s*\(", self.source)
        self.assertEqual(frees, [], "borrowed batch passed to llama_batch_free")

    def test_get_one_views_documented(self):
        self.assertIn("NON-OWNING", self.source)
        views = re.findall(r"llama_batch_get_one\s*\(", self.source)
        self.assertEqual(len(views), 2, "expected prefill + step views")

    def test_model_context_sampler_still_released(self):
        for symbol in ("llama_sampler_free", "llama_free(", "llama_model_free"):
            with self.subTest(symbol=symbol):
                self.assertIn(symbol, self.source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
