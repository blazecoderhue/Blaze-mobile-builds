#!/usr/bin/env python3
"""Native libraries in an APK: ABI, ELF class (32/64-bit), LOAD segment alignment (16 KB page ready?), size.

Usage: apk_native_report.py app.apk [baseline.apk]
16 KB ready = every PT_LOAD segment has p_align >= 16384. (Blaze packs libs compressed / extracted at install,
useLegacyPackaging = true, so zip-entry alignment doesn't matter; only the ELF alignment does.)
"""
import struct, sys, zipfile


def elf_info(b):
    if b[:4] != b"\x7fELF":
        return None
    cls = b[4]  # 1 = 32-bit, 2 = 64-bit
    le = "<" if b[5] == 1 else ">"
    if cls == 2:
        phoff, = struct.unpack_from(le + "Q", b, 0x20)
        phentsize, phnum = struct.unpack_from(le + "HH", b, 0x36)
    else:
        phoff, = struct.unpack_from(le + "I", b, 0x1C)
        phentsize, phnum = struct.unpack_from(le + "HH", b, 0x2A)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type, = struct.unpack_from(le + "I", b, off)
        if p_type != 1:  # PT_LOAD
            continue
        if cls == 2:
            p_align, = struct.unpack_from(le + "Q", b, off + 0x30)
        else:
            p_align, = struct.unpack_from(le + "I", b, off + 0x1C)
        aligns.append(p_align)
    return (64 if cls == 2 else 32), (min(aligns) if aligns else 0)


def report(path):
    rows = []
    with zipfile.ZipFile(path) as z:
        for e in z.infolist():
            if not (e.filename.startswith("lib/") and e.filename.endswith(".so")):
                continue
            abi = e.filename.split("/")[1]
            info = elf_info(z.read(e.filename))
            bits, align = info if info else (0, 0)
            rows.append((abi, e.filename.split("/")[-1], bits, align, e.file_size, e.compress_size))
    return rows


def main():
    apk = sys.argv[1]
    rows = report(apk)
    import os
    print(f"APK {os.path.basename(apk)}: {os.path.getsize(apk):,} bytes")
    abis = sorted({r[0] for r in rows})
    print("ABIs:", ", ".join(abis) or "none")
    bad = []
    for abi, name, bits, align, size, csize in sorted(rows):
        ok16 = align >= 16384
        if not ok16 and "64" in abi:  # 16 KB pages exist only on 64-bit (arm64-v8a / x86_64) devices
            bad.append(f"{abi}/{name}")
        print(f"{abi:12} {name:34} {bits}-bit align={align:>6} {'16KB-ok' if ok16 else 'NOT-16KB'} {size:>10,} B ({csize:,} packed)")
    for abi in abis:
        want = 64 if "64" in abi else 32
        wrong = [r[1] for r in rows if r[0] == abi and r[2] != want]
        if wrong:
            print(f"WARN {abi}: wrong ELF class in {wrong}")
    eng = [r for r in rows if r[1] == "libblackbox.so"]
    print("Blaze Phone engine (libblackbox.so):", ", ".join(f"{r[0]} {r[2]}-bit align {r[3]}" for r in eng) or "MISSING")
    print("16 KB pages (64-bit ABIs):", "all LOAD segments >= 16 KB" if not bad else "not aligned: " + ", ".join(bad))
    if len(sys.argv) > 2:
        base = sys.argv[2]
        print(f"Delta vs {os.path.basename(base)}: {os.path.getsize(apk) - os.path.getsize(base):+,} bytes")


if __name__ == "__main__":
    main()
