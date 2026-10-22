# Bundled BusyBox (app/src/main/jniLibs/*/libbusybox.so)

| ABI | Version | Build | Notes |
|---|---|---|---|
| arm64-v8a | 1.36.1 (2025-11-23) | static, musl | patched, see below |
| armeabi-v7a | 1.31.0 (2019-06-10) | static, musl (busybox.net style) | patched, see below |

## Why the phone tab said "[Process completed (signal 31)]" (fixed in v0.29.8, shipped as #84; the branch called it v0.30.1)

Both builds have `CONFIG_FEATURE_SUID=y`. At the start of every applet (`ash`, `ls`, `top`…), BusyBox's
`check_suid()` drops privileges for applets marked `BB_SUID_DROP`: `setgid(getgid()); setuid(getuid())`.
For a normal app these calls change nothing, but Android's seccomp filter for app processes blocks the whole
setuid/setgid family (bionic `libc/SECCOMP_BLOCKLIST_APP.TXT`) and kills the caller with **SIGSYS, signal 31**.
So `ash -l` died before printing anything, and Termux's terminal printed `[Process completed (signal 31) - press Enter]`.
(The agent's `run_shell` uses Android's sh, so it only lost the BusyBox tools; see the next section.)

Termux's own BusyBox build turns `CONFIG_FEATURE_SUID` off for the same reason.

**Fix:** `patch_setid.py` makes the one branch in `check_suid()` that guards the drop unconditional, so the two
calls are jumped over. One 4-byte instruction per binary, nothing else changes. `su`/`login` still refuse ("must be
suid"), as they would anyway without root.

Reproduce / verify on an arm64 Linux box (the CI sandbox is aarch64):

```sh
ln -s $PWD/app/src/main/jniLibs/arm64-v8a/libbusybox.so /tmp/ash
python3 tools/busybox/android_seccomp.py /tmp/ash ash -c 'echo ok'   # before: "Bad system call" (exit 159), after: ok
```

`android_seccomp.py` runs a program under a seccomp filter that traps the same syscalls Android blocks for apps.

Also fixed in v0.30.1: `Terminal.setup()` ran `libbusybox.so --list`, and BusyBox called as `libbusybox.so`
answers "applet not found", so no applet was ever linked into `usr/bin`. It now runs BusyBox through a `busybox`
symlink, probes it (`busybox sh -c 'echo ok'`) and, if the probe fails on some phone, uses Android's own
`/system/bin/sh` + toybox instead (`TermLogic.phoneLaunch`, `PhoneShellTest`).

If you replace a binary: build with `CONFIG_FEATURE_SUID` off (or re-find `check_suid` and update `patch_setid.py`
and `PhoneShellTest`).

## v0.30.2: every syscall checked against Android's real allowlist, both ABIs

`android_seccomp.py` only traps the setuid family. Android's app filter is an **allowlist**: bionic's `SYSCALLS.TXT` +
`SECCOMP_ALLOWLIST_COMMON/APP` minus `SECCOMP_BLOCKLIST_*`; anything else is SIGSYS. `seccomp_allowlist.py` checks a
trace against those lists (Android 11 and 12 branches):

- **arm64-v8a (1.36.1, patched):** `strace -f` over ash start-up, `-c` scripts, cd/pwd, ls, cp, mv, ln, chmod, rm -r,
  find, ps, top, df, du, tar, grep/sed/awk, sleep &, vi, ping, wget, nslookup, ifconfig: **nothing outside the allowlist**
  (no setresuid/setresgid/setgroups/faccessat2/clone3/rseq). Start-up is fine on Android 11/12.
- **armeabi-v7a (1.31.0, patched):** `qemu-arm -strace`: ash start-up and most applets are fine, but the old path
  syscalls are not in Android's 32-bit app allowlist (only the `*at()` forms are): `chmod`, `chown32`, `link`, `symlink`,
  `rmdir`, `mknod`. So on a phone that runs Blaze as 32-bit, `busybox chmod`, `ln`, `rmdir`, `rm -r`, `cp -a`, `tar x`,
  `mkfifo` die with signal 31. Setup now runs those through a second probe and, if it fails, leaves
  `TermLogic.LEGACY_SYSCALL_APPLETS` to Android's toybox in /system/bin.

Start-up (`Terminal.setup`, v0.30.2): `busybox true`, then `busybox sh -c 'echo ok'` (5 s timeout each, killed if it
hangs). Any failure: the phone tab starts on `/system/bin/sh` directly. The verdict is kept per build in
`filesDir/usr/.bb` (`v<versionCode>:<lastUpdateTime> ok|ok nolegacy|broken`). If a BusyBox tab still dies (signal 31 at
any time, or any error within 5 s of starting) it is marked broken for this build and swapped at once, in place, for a
tab on Android's sh with a one-line note (`TermLogic.autoFallback`, `TermSessions.respawnWithAndroidShell`).
