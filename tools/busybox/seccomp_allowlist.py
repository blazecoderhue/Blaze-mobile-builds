#!/usr/bin/env python3
"""v0.30.2: which of these syscalls does Android's *app* seccomp filter kill (SIGSYS)?

The filter allows bionic's SYSCALLS.TXT + SECCOMP_ALLOWLIST_COMMON/APP and removes SECCOMP_BLOCKLIST_APP/COMMON;
anything else gets SECCOMP_RET_TRAP. Lists are read from AOSP (android11-release used WHITELIST/BLACKLIST names).

  strace -f -o t.txt ./busybox ash -c '...'                         # arm64
  qemu-arm -strace ./busybox chmod 600 f 2> t.txt                   # armeabi-v7a
  python3 tools/busybox/seccomp_allowlist.py arm64 android11-release t.txt
"""
import base64, re, sys, urllib.request

def fetch(branch, name):
    url = f"https://android.googlesource.com/platform/bionic/+/refs/heads/{branch}/libc/{name}?format=TEXT"
    try: return base64.b64decode(urllib.request.urlopen(url).read()).decode()
    except Exception: return ""

def parse(text, arch):
    out = set()
    for line in text.splitlines():
        line = line.split('#')[0].strip()
        m = re.match(r'^\S+\s+\*?\s*([^(]+)\(.*\)\s+(\S+)\s*$', line)
        if not m: continue
        names, arches = m.group(1).strip(), set(m.group(2).split(','))
        if not ('all' in arches or arch in arches or (arch == 'arm64' and 'lp64' in arches) or (arch == 'arm' and 'lp32' in arches)): continue
        for n in (names.split(':', 1)[1].split(',') if ':' in names else [names.split('|')[0]]): out.add(n.strip())
    return out

def allowed(arch, branch):
    a = parse(fetch(branch, "SYSCALLS.TXT"), arch)
    for k in ("ALLOWLIST", "WHITELIST"):
        a |= parse(fetch(branch, f"SECCOMP_{k}_COMMON.TXT"), arch) | parse(fetch(branch, f"SECCOMP_{k}_APP.TXT"), arch)
    for k in ("BLOCKLIST", "BLACKLIST"):
        a -= parse(fetch(branch, f"SECCOMP_{k}_APP.TXT"), arch) | parse(fetch(branch, f"SECCOMP_{k}_COMMON.TXT"), arch)
    return a

if __name__ == "__main__":
    arch, branch, trace = sys.argv[1], sys.argv[2], sys.argv[3]
    ok = allowed(arch, branch)
    used = set()
    for l in open(trace, errors="replace"):
        m = re.match(r'^(?:\d+\s+)?([a-z_0-9]+)\(', l)
        if m: used.add(m.group(1))
    bad = sorted(u for u in used if u not in ok)
    print("killed by Android's app filter:" if bad else "all allowed", " ".join(bad))
