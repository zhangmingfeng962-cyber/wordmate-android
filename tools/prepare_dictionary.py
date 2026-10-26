"""Build the lookup-only ECDICT asset. Never inserts entries in the user's wordbook."""
import argparse
import csv
import hashlib
import json
import pathlib
import sqlite3
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--csv", type=pathlib.Path, required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
asset = root / "app/src/main/assets"
asset.mkdir(parents=True, exist_ok=True)
rows = []
with args.csv.open(encoding="utf-8-sig", newline="") as handle:
    for row in csv.DictReader(handle):
        word = row["word"].strip()
        if not word or not row["translation"].strip() or not all(c.isascii() and (c.isalpha() or c in "'-") for c in word):
            continue
        rank = int(row.get("frq") or 0) or int(row.get("bnc") or 0) or 10000000
        rows.append((rank, word.lower(), row.get("phonetic", ""), row["translation"].replace("\\n", "\n"), row.get("exchange", "")))
rows.sort()
connection = sqlite3.connect(asset / "dictionary.db")
connection.execute("CREATE TABLE IF NOT EXISTS dictionary(word TEXT PRIMARY KEY COLLATE NOCASE, phonetic TEXT NOT NULL, translation TEXT NOT NULL)")
connection.execute("DELETE FROM dictionary")
forms = {}
for _, word, phonetic, meaning, exchange in rows[:35000]:
    connection.execute("INSERT OR IGNORE INTO dictionary VALUES(?,?,?)", (word, phonetic, meaning))
    for item in exchange.split("/"):
        kind, _, variants = item.partition(":")
        if kind not in {"p", "d", "i", "3", "r", "t", "s"}:
            continue
        for variant in variants.split(","):
            variant = variant.strip().lower()
            if variant != word and variant and all(c.isascii() and (c.isalpha() or c in "'-") for c in variant):
                forms.setdefault(variant, set()).add(word)
connection.execute("CREATE TABLE IF NOT EXISTS forms(form TEXT PRIMARY KEY COLLATE NOCASE, base TEXT NOT NULL)")
connection.execute("DELETE FROM forms")
connection.executemany("INSERT INTO forms VALUES(?,?)", [(form, next(iter(bases))) for form, bases in sorted(forms.items()) if len(bases) == 1])
form_count = connection.execute("SELECT COUNT(*) FROM forms").fetchone()[0]
connection.commit()
count = connection.execute("SELECT COUNT(*) FROM dictionary").fetchone()[0]
connection.execute("VACUUM")
connection.close()
metadata = {
    "source": "https://github.com/skywind3000/ECDICT",
    "csv_sha256": hashlib.sha256(args.csv.read_bytes()).hexdigest(),
    "selection": "First 35000 alphabetic English entries by frq, falling back to bnc",
    "entries": count,
    "inflected_forms": form_count,
    "forms_selection": "Unambiguous exchange variants of included base entries; exact entries take priority; never substitute base IPA",
    "purpose": "Lookup only; user wordbook starts empty"
}
(asset / "dictionary-source.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
if not (asset / "ECDICT-LICENSE.txt").exists():
    with urllib.request.urlopen("https://raw.githubusercontent.com/skywind3000/ECDICT/master/LICENSE", timeout=30) as response:
        (asset / "ECDICT-LICENSE.txt").write_bytes(response.read())
print(json.dumps(metadata, ensure_ascii=False))
