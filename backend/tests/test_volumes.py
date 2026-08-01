"""Unit test for volume grouping (orchestrator._volumes).

Regression: incremental updates used to start a fresh volume per batch, so a
novel updated one chapter at a time got one volume per chapter. _volumes now
assigns volumes by GLOBAL reading position, so new chapters fill the last partial
volume instead.

Run: cd backend && .venv/bin/python tests/test_volumes.py
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from app.scraper.orchestrator import _volumes

ok = []


def check(name, cond, extra=""):
    ok.append(cond)
    print(f"[{name}] {'PASS' if cond else 'FAIL'} {extra}")


def shape(n, size, start):
    return [(vol, len(chs)) for vol, chs in _volumes(list(range(n)), size, start)]


# Fresh scrape: 6 chapters, 4/vol -> vol1(4), vol2(2)
check("fresh", shape(6, 4, 0) == [(1, 4), (2, 2)], str(shape(6, 4, 0)))

# Incremental: book has 6 (vol2 partial, 2/4). Add 3 -> vol2 fills to 4, vol3 gets 1.
check("append-partial", shape(3, 4, 6) == [(2, 2), (3, 1)], str(shape(3, 4, 6)))

# The reported bug: one chapter per update must NOT create a new volume each time.
check("one-at-a-time-1", shape(1, 4, 6) == [(2, 1)], str(shape(1, 4, 6)))  # into vol2
check("one-at-a-time-2", shape(1, 4, 7) == [(2, 1)], str(shape(1, 4, 7)))  # still vol2
check("one-at-a-time-3", shape(1, 4, 8) == [(3, 1)], str(shape(1, 4, 8)))  # vol2 full -> vol3

# Exact boundary: 2 full volumes (pos 1-8), add 4 -> a clean vol3(4).
check("boundary", shape(4, 4, 8) == [(3, 4)], str(shape(4, 4, 8)))

# Large batch spanning several volumes from a partial start.
check("multi-span", shape(10, 4, 6) == [(2, 2), (3, 4), (4, 4)], str(shape(10, 4, 6)))

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
