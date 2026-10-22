#!/usr/bin/env python3
"""Run a command under a seccomp filter that traps (SIGSYS) the syscalls Android's app filter blocks
(SECCOMP_BLOCKLIST_APP: setgid/setuid family, mount, chroot, ...). aarch64 numbers. Usage: androidsec.py prog argv0 args..."""
import ctypes, os, struct, sys
BLOCK = [144,146,143,145,149,151,152,159, 171,266,112,51,40,39,224,225,162,161,142,116,105,106,89]  # setgid setuid setregid setreuid setresgid setfsuid setfsgid setgroups adjtimex clock_adjtime clock_settime chroot mount umount2 swapon swapoff setdomainname sethostname reboot syslog init_module delete_module acct
AUDIT_ARCH_AARCH64 = 0xC00000B7
def ins(code, jt, jf, k): return struct.pack("HBBI", code, jt, jf, k)
LD_W_ABS=0x20; JEQ=0x15; RET=0x06
prog = ins(LD_W_ABS,0,0,4) + ins(JEQ,1,0,AUDIT_ARCH_AARCH64) + ins(RET,0,0,0x7fff0000) + ins(LD_W_ABS,0,0,0)
n=len(BLOCK)
for i,nr in enumerate(BLOCK): prog += ins(JEQ, n-i, 0, nr)
prog += ins(RET,0,0,0x7fff0000) + ins(RET,0,0,0x00030000)  # ALLOW, TRAP
buf = ctypes.create_string_buffer(prog)
class FP(ctypes.Structure): _fields_=[("len",ctypes.c_ushort),("filter",ctypes.c_void_p)]
fp = FP(len(prog)//8, ctypes.cast(buf, ctypes.c_void_p))
libc = ctypes.CDLL(None, use_errno=True)
assert libc.prctl(38,1,0,0,0)==0
assert libc.prctl(22,2,ctypes.byref(fp),0,0)==0, ctypes.get_errno()
os.execv(sys.argv[1], sys.argv[2:])
