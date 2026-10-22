#!/usr/bin/env python3
"""v0.30.1: make the bundled BusyBox start on Android (see README.md next to this file).

BusyBox's check_suid() drops privileges at the start of every applet with setgid(getgid()); setuid(getuid()).
Android's app seccomp filter (bionic SECCOMP_BLOCKLIST_APP) kills setgid/setuid with SIGSYS (signal 31), so the phone
tab opened as "[Process completed (signal 31)]". The drop is a no-op for an app anyway (no setuid bit, real == effective
ids), so one conditional branch per binary is made unconditional and the two calls are jumped over. Idempotent.
Run from the repo root:  python3 tools/busybox/patch_setid.py
"""
import struct

PATCHES = [
    # (file, file offset, original word, patched word)
    ("app/src/main/jniLibs/arm64-v8a/libbusybox.so", 0x4758, 0x35000083, 0x14000004),   # va 0x404758: cbnz w3,+0x10 -> b +0x10 (BusyBox 1.36.1)
    ("app/src/main/jniLibs/armeabi-v7a/libbusybox.so", 0xF22C, 0x1A000003, 0xEA000003), # va 0x1f22c:  bne 0x1f240 -> b 0x1f240 (BusyBox 1.31.0)
]

for path, off, old, new in PATCHES:
    d = bytearray(open(path, "rb").read())
    cur = struct.unpack("<I", d[off:off + 4])[0]
    if cur == new:
        print(f"{path}: already patched"); continue
    assert cur == old, f"{path}: unexpected word {cur:#x} at {off:#x} (different BusyBox build? re-find check_suid)"
    d[off:off + 4] = struct.pack("<I", new)
    open(path, "wb").write(d)
    print(f"{path}: patched")
