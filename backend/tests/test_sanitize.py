"""Regression test: sanitize() must neutralize dangerous URL schemes even when
obfuscated with control chars / whitespace (matters for the EPUB-export path).

Run with the project venv: cd backend && .venv/bin/python tests/test_sanitize.py
"""

import sys, pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from bs4 import BeautifulSoup
from app.scraper.parser import sanitize

ok = []
def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(f"[{name}] {'PASS' if cond else 'FAIL'} {extra}")

def clean(html):
    soup = BeautifulSoup(html, "html.parser")
    body = soup.find("body") or soup
    sanitize(body)
    return body

# Each of these must have its dangerous attribute stripped.
evasions = [
    ("plain-js", "<a href='javascript:alert(1)'>x</a>", "href"),
    ("tab-js", "<a href='java\tscript:alert(1)'>x</a>", "href"),
    ("cr-js", "<a href='java\rscript:alert(1)'>x</a>", "href"),
    ("newline-js", "<a href='java\nscript:alert(1)'>x</a>", "href"),
    ("leading-nul", "<a href='\x00javascript:alert(1)'>x</a>", "href"),
    ("spaced", "<a href='   javascript:alert(1)'>x</a>", "href"),
    ("uppercase", "<a href='JAVASCRIPT:alert(1)'>x</a>", "href"),
    ("entity-tab", "<a href='java&#9;script:alert(1)'>x</a>", "href"),
    ("data-uri", "<img src='data:text/html,<b>1</b>'>", "src"),
    ("vbscript", "<a href='vbscript:msgbox(1)'>x</a>", "href"),
]
for name, html, attr in evasions:
    tag = clean(html).find(True)
    still_there = tag is not None and tag.get(attr) is not None
    check(f"strip-{name}", not still_there, "(attr survived!)" if still_there else "")

# Legit URLs must survive untouched.
body = clean("<a href='https://example.com/x'>ok</a><img src='pics/a.jpg'>")
check("keep-http", body.find("a").get("href") == "https://example.com/x")
check("keep-relative-img", body.find("img").get("src") == "pics/a.jpg")

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
