#!/usr/bin/env python3
"""Inventory already extracted Android partitions; no device writes."""
import argparse
import pathlib
import re
import hashlib

p = argparse.ArgumentParser()
p.add_argument("root", type=pathlib.Path)
p.add_argument("output", type=pathlib.Path)
a = p.parse_args()
if not a.root.is_dir():
    p.error("root must be an extracted partition directory")
a.output.mkdir(parents=True, exist_ok=True)
pattern = re.compile(r"euicc|esim|uim|lpa|vsim|secure.?element|omapi|telephony|qcril", re.I)
rows = []
xml = []
for f in sorted(a.root.rglob("*")):
    if f.is_symlink() or not f.is_file():
        continue
    rel = f.relative_to(a.root).as_posix()
    if pattern.search(f.name):
        rows.append(f"{f.stat().st_size}\t{rel}")
    if f.suffix == ".xml" and ("/etc/permissions/" in "/" + rel or "/etc/vintf/" in "/" + rel) and f.stat().st_size < 2_000_000:
        for n, line in enumerate(f.read_text(errors="replace").splitlines(), 1):
            if pattern.search(line):
                xml.append(f"{rel}:{n}: {line.strip()}")
(a.output / "inventory.txt").write_text("\n".join(rows)+"\n")
(a.output / "declarations.txt").write_text("\n".join(xml)+"\n")
print(f"Matched {len(rows)} files and {len(xml)} XML lines. Review text reports before sharing.")
