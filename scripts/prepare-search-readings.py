"""Generate a small, offline polyphonic Han reading table from python-pinyin (MIT)."""
import gzip
import json
from pathlib import Path
import unicodedata
from urllib.request import urlopen

REVISION = "aab5e696eac5c3a92575a18521db28547c1476bb"
BASE = f"https://raw.githubusercontent.com/mozillazg/python-pinyin/{REVISION}"
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "android/app/src/main/assets"


def plain(value):
    return "".join(c for c in unicodedata.normalize("NFD", value).replace("u\u0308", "v")
                   if unicodedata.category(c) != "Mn").replace("u:", "v")


data = json.load(urlopen(f"{BASE}/pypinyin/pinyin_dict.json"))
lines = []
for code, reading in sorted(data.items(), key=lambda pair: int(pair[0])):
    code = int(code)
    if 0x4E00 <= code <= 0x9FFF:
        readings = list(dict.fromkeys(plain(r) for r in reading.split(",")))
        # Decompose accented ü before stripping tones.
        readings = [r for r in readings if r and r.isascii()]
        if readings:
            lines.append(chr(code) + "\t" + ",".join(readings))
payload = "\n".join(lines).encode("utf-8")
(DEST / "rhythm-pinyin.bin").write_bytes(gzip.compress(payload, mtime=0))
license_text = urlopen(f"{BASE}/LICENSE.txt").read().decode("utf-8")
(DEST / "rhythm-pinyin-LICENSE.txt").write_text(
    f"python-pinyin character readings, revision {REVISION}\n"
    "Source: https://github.com/mozillazg/python-pinyin\n"
    "Derived data: U+4E00..U+9FFF, tone marks removed, all listed readings retained.\n\n"
    + license_text, encoding="utf-8")
print(len(lines), "characters;", (DEST / "rhythm-pinyin.bin").stat().st_size, "bytes compressed")
