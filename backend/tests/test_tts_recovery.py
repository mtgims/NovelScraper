"""TTS resilience: a runtime synthesis failure (e.g. a mid-chapter GPU cuBLAS
allocation error) should rebuild the model and retry once, so one bad chunk
doesn't kill playback. Uses a fake Kokoro — no real model/GPU needed.

Run with the project venv: cd backend && .venv/bin/python tests/test_tts_recovery.py
"""

import sys, pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import numpy as np
from app.tts import _TTS

ok = []
def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(f"[{name}] {'PASS' if cond else 'FAIL'} {extra}")


class FakeKokoro:
    def __init__(self, fail_times):
        self.calls = 0
        self.fail_times = fail_times

    def create(self, text, voice, speed, lang):
        self.calls += 1
        if self.calls <= self.fail_times:
            raise RuntimeError("CUBLAS failure 3: the resource allocation failed")
        return (np.zeros(2400, dtype="float32"), 24000)


def make(fail_times):
    t = _TTS()
    fk = FakeKokoro(fail_times)
    t._kokoro = fk
    resets = {"n": 0}

    def fake_reset():
        resets["n"] += 1
        t._kokoro = fk  # simulate a rebuilt (healed) session

    t._reset = fake_reset
    return t, fk, resets


# 1) recovers from a single transient failure (rebuild + retry succeeds)
t, fk, resets = make(fail_times=1)
wav = t.synth_wav("hi there", "af_heart", 1.0)
check("recovers-after-one-failure",
      len(wav) > 44 and fk.calls == 2 and resets["n"] == 1,
      f"calls={fk.calls} resets={resets['n']}")

# 2) a persistent failure still raises, but only after one retry
t2, fk2, _ = make(fail_times=5)
raised = False
try:
    t2.synth_wav("hi", "af_heart", 1.0)
except Exception:
    raised = True
check("persistent-failure-raises", raised and fk2.calls == 2, f"calls={fk2.calls}")

# 3) normal path is unaffected (no reset, one create)
t3, fk3, resets3 = make(fail_times=0)
wav3 = t3.synth_wav("hello", "af_heart", 1.0)
check("normal-synth-ok",
      len(wav3) > 44 and fk3.calls == 1 and resets3["n"] == 0)

# 4) the CUDA provider options bound the arena (the VRAM-leak fix). Pure helper,
#    so no onnxruntime/CUDA load needed.
opts = _TTS._cuda_provider_options()
check("cuda-arena-bounded",
      opts.get("arena_extend_strategy") == "kSameAsRequested"
      and opts.get("gpu_mem_limit", 0) > 0, str(opts))

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
