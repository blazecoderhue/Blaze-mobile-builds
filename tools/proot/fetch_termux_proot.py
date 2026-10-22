#!/usr/bin/env python3
"""Re-download Termux's proot 5.1.107.96 (+ libtalloc 2.5.0, libandroid-shmem 0.7) into app/src/main/jniLibs.
Run from the repo root. See README.md next to this file. Needs only the Python standard library."""
import io, os, tarfile, urllib.request

POOL = "https://packages.termux.dev/apt/termux-main/pool/main"
PKGS = {"proot": ("p/proot", "5.1.107.96"), "libtalloc": ("libt/libtalloc", "2.5.0"), "libandroid-shmem": ("liba/libandroid-shmem", "0.7")}
ABIS = {"aarch64": "arm64-v8a", "arm": "armeabi-v7a"}
PREFIX = "data/data/com.termux/files/usr/"
WANT = {  # file in the .deb -> name in jniLibs
    "bin/proot": "libproot.so", "libexec/proot/loader": "libproot_loader.so", "libexec/proot/loader32": "libproot_loader32.so",
    "lib/libtalloc.so.2.5.0": "libtalloc.so", "lib/libandroid-shmem.so": "libandroid-shmem.so",
}

def data_tar(deb: bytes) -> tarfile.TarFile:
    assert deb[:8] == b"!<arch>\n"
    i = 8
    while i < len(deb):
        name = deb[i:i + 16].strip().decode(); size = int(deb[i + 48:i + 58]); body = deb[i + 60:i + 60 + size]
        i += 60 + size + (size & 1)
        if name.startswith("data.tar"): return tarfile.open(fileobj=io.BytesIO(body))
    raise SystemExit("no data.tar")

for arch, abi in ABIS.items():
    for pkg, (path, ver) in PKGS.items():
        url = f"{POOL}/{path}/{pkg}_{ver}_{arch}.deb"
        t = data_tar(urllib.request.urlopen(url).read())
        for m in t.getmembers():
            rel = m.name.lstrip("./").removeprefix(PREFIX)
            if rel in WANT and m.isfile():
                out = f"app/src/main/jniLibs/{abi}/{WANT[rel]}"
                with open(out, "wb") as f: f.write(t.extractfile(m).read())
                print(out, "<-", url)
