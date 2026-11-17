#!/usr/bin/env python3
"""Read-only FAT16 inventory for a raw NON-HLOS volume (no mount required)."""
import argparse
import hashlib
import pathlib
import re
import struct

def u16(b, p):
    return struct.unpack_from("<H", b, p)[0]

def u32(b, p):
    return struct.unpack_from("<I", b, p)[0]

def inspect(image, out):
    with image.open("rb") as f:
        boot = f.read(512)
        bps, spc = u16(boot, 11), boot[13]
        reserved, fats = u16(boot, 14), boot[16]
        entries, spf = u16(boot, 17), u16(boot, 22)
        total = u16(boot, 19) or u32(boot, 32)
        if bps not in (512, 1024, 2048, 4096) or not spc or not spf or not entries or not fats:
            raise ValueError("Unsupported FAT geometry")
        root_sectors = (entries * 32 + bps - 1) // bps
        root_start = reserved + fats * spf
        data_start = root_start + root_sectors
        clusters = (total - data_start) // spc
        if not 4085 <= clusters < 65525:
            raise ValueError("Not a FAT16 volume")
        f.seek(reserved * bps)
        fat = f.read(spf * bps)
        cluster_bytes = bps * spc
        def chain(start):
            seen = set()
            c = start
            while 2 <= c < 0xfff8:
                if c in seen or c > clusters + 1 or c * 2 + 2 > len(fat):
                    raise ValueError("Invalid FAT chain")
                seen.add(c)
                f.seek((data_start + (c - 2) * spc) * bps)
                block = f.read(cluster_bytes)
                if len(block) != cluster_bytes:
                    raise ValueError("Truncated cluster")
                yield block
                c = u16(fat, c * 2)
                if c < 2 or 0xfff0 <= c < 0xfff8:
                    raise ValueError("Invalid FAT next cluster")
        def directory(data):
            lfn = {}
            for i in range(0, len(data) - 31, 32):
                e = data[i:i+32]
                if e[0] == 0: break
                if e[0] == 0xe5:
                    lfn.clear()
                    continue
                attr = e[11]
                if attr == 0x0f:
                    if e[0] & 0x40: lfn.clear()
                    lfn[e[0] & 0x1f] = e[1:11] + e[14:26] + e[28:32]
                    continue
                base = e[:8].decode("cp437", errors="replace").rstrip()
                ext = e[8:11].decode("cp437", errors="replace").rstrip()
                name = base + ("." + ext if ext else "")
                if lfn:
                    name = b"".join(lfn[k] for k in sorted(lfn)).decode("utf-16le", errors="replace").split("\x00")[0].rstrip("\uffff") or name
                lfn.clear()
                if attr & 8 or name in (".", ".."): continue
                yield name, attr, u16(e, 26), u32(e, 28)
        f.seek(root_start * bps)
        root = f.read(root_sectors * bps)
        rows = []
        visited = set()
        def walk(data, prefix="", depth=0):
            if depth > 32: raise ValueError("Directory nesting limit")
            for name, attr, c, size in directory(data):
                path = prefix + "/" + name
                if attr & 16:
                    if c in visited: continue
                    visited.add(c)
                    walk(b"".join(chain(c)), path, depth + 1)
                else:
                    rows.append((path, size, c))
        walk(root)
        out.mkdir(parents=True, exist_ok=True)
        def report(name, rows):
            (out / name).write_text("".join(f"{size}\t{path}\n" for path, size, _ in rows), encoding="utf-8")
        report("files.txt", sorted(rows))
        modem = [x for x in rows if re.fullmatch(r"modem\.(b\d\d|mdt)", x[0].rsplit("/",1)[-1], re.I)]
        report("modem-segments.txt", sorted(modem))
        (out / "modem-total-bytes.txt").write_text(str(sum(x[1] for x in modem))+"\n")
        report("mcfg-files.txt", [x for x in rows if "mcfg" in x[0].lower()])
        report("rf-related.txt", [x for x in rows if re.search(r"rfsa|rffe|qdsp6m|modem|mba", x[0], re.I)])
        (out / "geometry.txt").write_text(f"FAT16\nbytes_per_sector={bps}\nsectors_per_cluster={spc}\nclusters={clusters}\nfiles={len(rows)}\n")
        for path, size, c in rows:
            if path.rsplit("/",1)[-1].lower() in ("modemr.jsn","modemuw.jsn") and 0 < size <= 65536:
                data = b"".join(chain(c))[:size]
                label = path.strip("/").replace("/", "_")
                (out / (label+".txt")).write_text(data.decode("utf-8",errors="replace"),encoding="utf-8")
        print(f"Files: {len(rows)}; modem segment bytes: {sum(x[1] for x in modem)}; reports: {out}")

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("image", type=pathlib.Path)
    p.add_argument("output", type=pathlib.Path)
    a = p.parse_args()
    inspect(a.image, a.output)
