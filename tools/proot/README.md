# Bundled PRoot (app/src/main/jniLibs/*/libproot*.so, libtalloc.so, libandroid-shmem.so)

Since v0.30.2 these are Termux's own builds, unchanged, renamed to `lib*.so` so Android unpacks them into
`nativeLibraryDir` (the only app directory it lets an app execute from):

| File in jniLibs | Termux package (termux-main) | From |
|---|---|---|
| `arm64-v8a/libproot.so`, `libproot_loader.so`, `libproot_loader32.so` | `proot_5.1.107.96_aarch64.deb` | `usr/bin/proot`, `usr/libexec/proot/loader`, `loader32` |
| `armeabi-v7a/libproot.so`, `libproot_loader.so` | `proot_5.1.107.96_arm.deb` | `usr/bin/proot`, `usr/libexec/proot/loader` |
| `*/libtalloc.so` | `libtalloc_2.5.0_<arch>.deb` | `usr/lib/libtalloc.so.2.5.0` (SONAME libtalloc.so.2) |
| `*/libandroid-shmem.so` | `libandroid-shmem_0.7_<arch>.deb` | `usr/lib/libandroid-shmem.so` |

Pool: https://packages.termux.dev/apt/termux-main/pool/main/ (p/proot, libt/libtalloc, liba/libandroid-shmem).
Source: https://github.com/termux/proot/tree/v5.1.107.96 (GPL-2.0), talloc (LGPL-3.0), https://github.com/termux/libandroid-shmem (BSD-3-Clause).
SHA-256 of every file is pinned in `LinuxDoctorTest.bundledProotIsTermux5_1_107_96`. `fetch_termux_proot.py` re-downloads
and unpacks them (`python3 tools/proot/fetch_termux_proot.py` from the repo root).

proot finds `libtalloc.so.2` and `libandroid-shmem.so` by name: `Linux.prepare()` links both from `nativeLibraryDir` into
`filesDir/linux/lib`, which is `LD_LIBRARY_PATH` for proot (`Linux.env()`).

## Why v0.30.2 replaced the old build: `cd phone/` → "Function not implemented"

Up to v0.30.1 Blaze shipped a different PRoot build (a fork with `--droid_files`, `--fix-selinux-xattr`, `--kill-report`,
older than Termux's July 2026 releases). In Alpine every `cd` failed with ENOSYS and `pwd` printed nothing:

    /bin/sh: cd: can't cd to phone/: Function not implemented

PRoot emulates `chdir`, `fchdir` and `getcwd` itself (the guest's working directory lives in PRoot, not the kernel): it
cancels the syscall by replacing its number with an "avoider" and writes the result. Inside an Android app, every
process also runs under the app seccomp filter (bionic `SECCOMP_*LIST_*.TXT`), which kills unknown syscall numbers with
SIGSYS. With the old build the cancelled call reached that filter, PRoot's SIGSYS handler answered "not implemented",
and the guest got ENOSYS. `PROOT_NO_SECCOMP=1` did not help.

Reproduced off-phone (2026-10-16): Termux's Docker image (bionic `linker64` + libc) in a chroot on an arm64 Linux 6.12
box, Alpine 3.22 minirootfs, Blaze's exact proot arguments, and a seccomp filter built from Android 11's and 12's bionic
allowlists (`SYSCALLS.TXT` + `SECCOMP_ALLOWLIST_*` − `SECCOMP_BLOCKLIST_*`, everything else `SECCOMP_RET_TRAP`):

| | `cd /root/phone`, `cd /sdcard`, `cd /`, `getcwd` | blaze-doctor |
|---|---|---|
| old libproot, no Android filter | ok | |
| old libproot, Android filter (PROOT_NO_SECCOMP=0 or 1) | ENOSYS | 4 ok, 6 failed |
| Termux 5.1.107.96, Android filter (0 or 1) | ok | 10 ok, 0 failed |

With 5.1.107.96 under the Android filter: getcwd, chdir, fchdir, statx, faccessat, openat2, getdents64 work;
faccessat2, clone3 and close_range return a clean ENOSYS (Android 11/12's filter doesn't allow them; musl and glibc
fall back to faccessat / clone / a close loop on ENOSYS, which is what happens in Termux too). Not reproducible off-phone:
arm64 kernels before 5.3 (the test phone's 4.19) run seccomp before the ptrace syscall-enter stop; Termux's v5.1.107.91–.95
fixes cover that order. If a phone still fails, Blaze's per-build self-test turns seccomp acceleration off when that
helps (`Linux.selfTest`, `LinuxDoctor.noSeccomp`) and `blaze-doctor` in the Linux shows what fails.
