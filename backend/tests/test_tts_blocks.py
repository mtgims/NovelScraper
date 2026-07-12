"""segment_blocks() must interleave images with text paragraphs in document
order, while segment_paragraphs() (derived from it) stays byte-for-byte what it
was — so read-along highlighting keeps lining up with the audio chunks.

Run with the project venv: cd backend && .venv/bin/python tests/test_tts_blocks.py
"""

import sys, pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from app.tts import build_chunks, segment_blocks, segment_paragraphs

ok = []
def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(f"[{name}] {'PASS' if cond else 'FAIL'} {extra}")

# (a) image interleaved between paragraphs, in document order
html = ("<p>First para. Second sentence.</p>"
        "<img src='/api/books/1/images/a.jpg' alt='pic'>"
        "<p>Third para.</p>")
b = segment_blocks(html)
check("block-order", [x["type"] for x in b] == ["text", "image", "text"],
      str([x["type"] for x in b]))
check("image-fields", b[1]["src"] == "/api/books/1/images/a.jpg" and b[1]["alt"] == "pic")
check("text-fields",
      b[0]["sentences"] == ["First para.", "Second sentence."]
      and b[2]["sentences"] == ["Third para."])

# derived paragraphs exclude images
check("paragraphs-derivation",
      segment_paragraphs(html) == [["First para.", "Second sentence."], ["Third para."]])

# (b) segment_paragraphs unchanged for representative cases
check("p-case", segment_paragraphs("<p>Hello world. Bye.</p>") == [["Hello world.", "Bye."]])
check("no-p-fallback",
      segment_paragraphs("Line one.\n\nLine two.") == [["Line one."], ["Line two."]])
check("empty-p", segment_paragraphs("<p></p>") == [])
check("single-line", segment_paragraphs("Just text no tags") == [["Just text no tags"]])

# invariant: flattened text-block sentences == flattened segment_paragraphs,
# and chunk indices therefore address the same sentence stream
flat_blocks = [s for bl in segment_blocks(html) if bl["type"] == "text" for s in bl["sentences"]]
flat_paras = [s for p in segment_paragraphs(html) for s in p]
check("invariant-flat-equal", flat_blocks == flat_paras)
_flat, chunks = build_chunks(segment_paragraphs(html))
max_idx = max((i for ch in chunks for i in ch), default=-1)
check("chunk-indices-in-range", max_idx == len(flat_blocks) - 1, f"max={max_idx} n={len(flat_blocks)}")

# (c) image inside a text-only <p> still becomes an image block
b2 = segment_blocks("<p><img src='/api/books/1/images/x.png'></p><p>Body.</p>")
check("img-in-empty-p",
      [x["type"] for x in b2] == ["image", "text"] and b2[1]["sentences"] == ["Body."])

# images with no src are dropped
check("img-no-src-dropped",
      all(x["type"] != "image" for x in segment_blocks("<p>Hi.</p><img>")))

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
