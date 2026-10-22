package com.blaze.assistant.terminal

/**
 * v0.30.2 `blaze-doctor`: a small self-test that lives in every installed Linux (/usr/local/bin/blaze-doctor) and
 * that Blaze also runs once per build in the background (Linux.selfTest). It checks the system calls a shell needs
 * under PRoot on Android: cd into ~/phone and /sdcard, getcwd, stat, the file tools, fork. Pure parts (LinuxDoctorTest).
 *
 * Why: up to v0.30.1 the bundled PRoot answered chdir()/getcwd() with ENOSYS under Android's app seccomp filter, so in
 * Alpine `cd phone/` said "can't cd to phone/: Function not implemented" (and so did every other cd). PRoot emulates
 * those two calls by cancelling them and writing the result itself; that build's cancel landed in Android's filter as an
 * unknown syscall, its SIGSYS handler answered ENOSYS. v0.30.2 ships Termux's current proot (5.1.107.96), which handles it.
 */
object LinuxDoctor {
    const val PATH_IN_ROOTFS = "usr/local/bin/blaze-doctor"

    /** One check: [name] as printed, [cmd] run by `sh -c`, [needs] a path that must exist for the check to apply. */
    data class Check(val name: String, val cmd: String, val needs: String? = null)

    val CHECKS: List<Check> = listOf(
        Check("cd ~/phone", "cd /root && cd phone/ && cd /root/phone", needs = "/root/phone"),
        Check("cd /sdcard", "cd /sdcard", needs = "/sdcard"),
        Check("cd", "cd /tmp && cd / && cd /etc && cd .."),
        Check("getcwd", "cd /tmp && [ \"\$(pwd -P)\" = /tmp ]"),
        Check("realpath", "cd /etc && [ \"\$(realpath .)\" = /etc ]"),
        Check("stat", "stat /etc/hostname >/dev/null 2>&1 || stat / >/dev/null"),
        Check("test -r/-x", "test -r /etc/passwd && test -x /bin/sh"),
        Check("files", "d=/tmp/.blaze-doctor.\$\$ && mkdir -p \$d && cd \$d && echo x > f && chmod 600 f && ln -s f s && ln f h && " +
            "cat h >/dev/null && mkdir e && rmdir e && rm -f f s h && cd / && rmdir \$d"),
        Check("fork", "sleep 0 & wait"),
        Check("pipe", "echo ok | grep -q ok"),
    )

    /** The script written into the rootfs. POSIX sh, so it runs in Alpine's ash and in bash. Exit status = failures. */
    fun script(): String = buildString {
        append("#!/bin/sh\n")
        append("# blaze-doctor: checks what a Linux in Blaze needs (cd, getcwd, stat, file tools, fork). Safe to run any time.\n")
        append("ok=0; bad=0\n")
        append("chk() {\n")
        append("  if [ -n \"\$3\" ] && [ ! -e \"\$3\" ]; then echo \"skip \$1\"; return; fi\n")
        append("  if out=\$(sh -c \"\$2\" 2>&1); then echo \"ok   \$1\"; ok=\$((ok+1)); else echo \"FAIL \$1: \$(printf '%s\\n' \"\$out\" | tail -n 1)\"; bad=\$((bad+1)); fi\n")
        append("}\n")
        for (c in CHECKS) append("chk ").append(q(c.name)).append(' ').append(q(c.cmd)).append(' ').append(q(c.needs.orEmpty())).append('\n')
        append("echo \"blaze-doctor: \$ok ok, \$bad failed\"\n")
        append("[ \$bad -eq 0 ] || echo \"Something is off. Copy this output to Blaze.\"\n")
        append("exit \$bad\n")
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    data class Report(val ok: List<String>, val failed: Map<String, String>, val complete: Boolean) {
        val passed get() = complete && failed.isEmpty()
        /** The failures that make the shell unusable (cd / getcwd), as opposed to one missing tool. */
        val cdBroken get() = failed.keys.any { it.startsWith("cd") || it == "getcwd" || it == "realpath" }
    }

    /** Reads blaze-doctor's output. [complete] = the summary line was there (the run didn't die half way). */
    fun parse(output: String): Report {
        val ok = mutableListOf<String>(); val failed = linkedMapOf<String, String>(); var complete = false
        for (raw in output.lines()) {
            val l = raw.trimEnd()
            when {
                l.startsWith("ok   ") -> ok += l.removePrefix("ok   ").trim()
                l.startsWith("FAIL ") -> { val r = l.removePrefix("FAIL "); val i = r.indexOf(": "); if (i > 0) failed[r.substring(0, i)] = r.substring(i + 2) else failed[r.trim()] = "" }
                l.startsWith("blaze-doctor: ") -> complete = true
            }
        }
        return Report(ok, failed, complete)
    }

    /**
     * PRoot's seccomp acceleration (PROOT_NO_SECCOMP=0) is the default. Turn it off only when the doctor failed with it
     * on and passed, or at least got cd working, with it off.
     */
    fun noSeccomp(withSeccomp: Report?, withoutSeccomp: Report?): Boolean {
        if (withSeccomp == null || withSeccomp.passed) return false
        if (withoutSeccomp == null || !withoutSeccomp.complete) return false
        return withoutSeccomp.passed || (withSeccomp.cdBroken && !withoutSeccomp.cdBroken) ||
            (withoutSeccomp.failed.size < withSeccomp.failed.size && !withoutSeccomp.cdBroken)
    }

    /** The per-build cache of that decision (filesDir/linux/.proot_mode): "<build key> seccomp|noseccomp". */
    fun modeStamp(key: String, noSeccomp: Boolean) = "$key ${if (noSeccomp) "noseccomp" else "seccomp"}"

    /** null when there's no decision for this build yet. */
    fun parseMode(key: String, stamp: String?): Boolean? {
        val p = stamp?.trim()?.split(' ') ?: return null
        if (p.size != 2 || p[0] != key) return null
        return when (p[1]) { "noseccomp" -> true; "seccomp" -> false; else -> null }
    }
}
