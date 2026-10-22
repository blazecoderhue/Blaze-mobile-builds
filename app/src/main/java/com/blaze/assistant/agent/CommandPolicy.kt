package com.blaze.assistant.agent

/**
 * v0.45.3 (user: "normal build mode makes me have to approve every single command"): what the Normal approval mode
 * (Build + Auto, the + menu's "Auto") asks before running a command the agent wants to run: shell (run_shell), git,
 * and typing / keys / opening apps in the Linux desktop.
 *
 *  - [Level.SAFE] runs with no card: read-only commands (ls, cat, grep, git status/diff/log…), and build / test commands
 *    inside the project workspace (gradle, make, cargo/go build+test, npm/yarn run|test|build, pytest, tsc…).
 *  - [Level.ASK] shows the card, which also offers "Allow for this chat" and "Always allow this command" ([Grants]).
 *  - [Level.RISKY] always shows the card and can't be pre-allowed: rm / deletes, sudo / su, package installs from the
 *    network (apt, apk, pip, npm install, curl | sh…), writes outside the workspace, force pushes / hard resets, disk
 *    tools, and anything that texts, calls or pays.
 *
 * Several commands the model sends in one reply (a multi-step plan) are approved with one card ([batch]).
 * Autopilot keeps its own rules (Agent / ConfirmGate); this file is only consulted in Normal. Pure Kotlin;
 * CommandPolicyTest.
 */
object CommandPolicy {
    enum class Level { SAFE, ASK, RISKY }
    enum class Kind { SHELL, GIT, DESKTOP_TYPE, DESKTOP_KEY, DESKTOP_OPEN }

    /** v0.45.6: music_ops actions that run with no card in Normal mode (play, pause, next…: local, undoable). */
    val IN_APP_SAFE = setOf("play", "pause", "resume", "next", "previous", "prev", "stop", "now", "skip")

    /** Paths inside the workspace (besides relative paths and ~): Blaze's home, the Linux /root, /tmp. */
    val DEFAULT_ROOTS = listOf("/root", "/tmp", "/home")

    private val readOnly = setOf("ls", "ll", "la", "dir", "cat", "head", "tail", "less", "more", "wc", "pwd", "echo", "printf", "grep", "egrep", "fgrep",
        "rg", "ag", "ack", "which", "whereis", "type", "file", "stat", "du", "df", "env", "printenv", "date", "whoami", "id", "uname", "hostname",
        "tree", "sort", "uniq", "cut", "tr", "jq", "yq", "diff", "cmp", "comm", "md5sum", "sha1sum", "sha256sum", "basename", "dirname", "realpath",
        "readlink", "true", "false", "test", "[", "ps", "free", "uptime", "nproc", "lscpu", "cd", "column", "nl", "fold", "xxd", "od", "hexdump",
        "strings", "seq", "sleep", "history", "alias", "time", "locale", "tput", "clear", "awk", "sed", "find", "java", "javac", "kotlinc", "node",
        "python", "python3", "go", "rustc", "cargo", "gcc", "g++", "clang", "make")

    /** Build / test tools and the sub-commands that only build, check or test (inside the workspace). */
    private val buildSubs = mapOf(
        "gradle" to null, "gradlew" to null, "./gradlew" to null, "mvn" to setOf("test", "package", "compile", "verify", "validate", "clean"),
        "make" to null, "ninja" to null, "cmake" to null, "cargo" to setOf("build", "test", "check", "clippy", "fmt", "run", "bench", "doc", "tree", "metadata"),
        "go" to setOf("build", "test", "vet", "run", "fmt", "version", "env", "list", "doc"),
        "npm" to setOf("test", "t", "run", "run-script", "build", "start", "ls", "list", "outdated", "view", "audit", "pack", "version"),
        "pnpm" to setOf("test", "run", "build", "start", "ls", "list", "lint", "outdated"), "yarn" to setOf("test", "run", "build", "start", "lint", "list", "outdated"),
        "bun" to setOf("test", "run", "build"), "pytest" to null, "tsc" to null, "eslint" to null, "prettier" to null, "jest" to null, "vitest" to null,
        "flutter" to setOf("test", "build", "analyze", "doctor", "--version"), "dart" to setOf("test", "analyze", "format", "--version"),
        "dotnet" to setOf("build", "test", "--version"), "swift" to setOf("build", "test"), "ruff" to null, "mypy" to null, "black" to setOf("--check"),
    )

    private val runners = setOf("python", "python3", "node", "java", "javac", "kotlinc", "kotlin", "deno", "ruby", "perl", "php", "lua", "Rscript", "bash", "sh", "zsh")
    private val gitLocal = setOf("add", "commit", "checkout", "switch", "merge", "restore", "init", "mv", "cherry-pick", "revert", "am", "apply", "notes", "worktree", "bisect", "format-patch", "archive")
    private val gitRead = setOf("status", "diff", "log", "show", "blame", "ls-files", "rev-parse", "describe", "shortlog", "grep", "ls-tree", "cat-file",
        "rev-list", "show-ref", "for-each-ref", "reflog", "branch", "tag", "remote", "stash", "config", "fetch", "version", "help")
    private val gitRisky = Regex("""(?i)(--force\b|\s-f\b|--force-with-lease|reset\s+--hard|clean\s+-[a-z]*f|branch\s+-D|filter-branch|filter-repo|push\s+\S+\s+:|push\s+--delete|update-ref\s+-d|reflog\s+(expire|delete)|gc\s+--prune)""")

    private val superUser = setOf("sudo", "su", "doas", "pkexec", "runas")
    private val diskTools = Regex("""^(mkfs(\.\w+)?|dd|fdisk|parted|sfdisk|wipefs|shutdown|reboot|poweroff|halt|init|systemctl|mount|umount|swapoff|swapon|losetup)$""")
    private val deleters = setOf("rm", "rmdir", "shred", "unlink", "srm", "trash", "truncate")
    private val pkgMgr = setOf("apt", "apt-get", "aptitude", "apk", "dnf", "yum", "pacman", "zypper", "brew", "snap", "flatpak", "port", "pkg", "emerge", "nix-env", "termux-setup-storage")
    private val pkgVerb = Regex("""^(install|add|remove|del|purge|upgrade|update|dist-upgrade|full-upgrade|autoremove|-S\w*|-R\w*|-U\w*|-Sy\w*|i|uninstall|reinstall|fix)$""")
    private val langInstall = mapOf("pip" to setOf("install", "uninstall", "download"), "pip3" to setOf("install", "uninstall", "download"), "pipx" to setOf("install", "uninstall", "run"),
        "npm" to setOf("install", "i", "add", "ci", "uninstall", "remove", "rm", "update", "upgrade", "link", "exec", "publish"),
        "pnpm" to setOf("install", "i", "add", "remove", "update", "dlx", "publish"), "yarn" to setOf("add", "install", "remove", "upgrade", "dlx", "publish"),
        "bun" to setOf("install", "add", "remove", "x"), "gem" to setOf("install", "uninstall", "update"), "cargo" to setOf("install", "uninstall", "publish"),
        "go" to setOf("install", "get"), "composer" to setOf("install", "require", "update", "remove"), "conda" to setOf("install", "remove", "update", "create"),
        "npx" to null, "pnpx" to null, "bunx" to null, "uvx" to null, "flatpak" to null)
    private val talksOrPays = Regex("""(?i)^(termux-sms-send|termux-telephony-call|termux-telephony-\w+|sendmail|mail|mailx|mutt|ssmtp|msmtp)$|android\.intent\.action\.(CALL|SENDTO|SEND)\b""")
    private val writers = setOf("cp", "mv", "ln", "touch", "mkdir", "install", "tee", "chmod", "chown", "chgrp", "rsync", "scp", "tar", "unzip", "zip", "patch", "git", "sed", "wget", "curl", "dd", "truncate")
    private val networkFetch = setOf("curl", "wget", "aria2c", "scp", "rsync", "ssh", "sftp", "ftp", "nc", "ncat", "telnet")

    /** Splits a command line into simple commands (on ; && || | & and new lines), outside quotes. */
    fun segments(cmd: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        var q = 0.toChar(); var i = 0
        while (i < cmd.length) {
            val ch = cmd[i]
            if (q != 0.toChar()) { cur.append(ch); if (ch == q) q = 0.toChar(); i++; continue }
            when {
                ch == '\'' || ch == '"' -> { q = ch; cur.append(ch) }
                ch == '\n' || ch == ';' -> { out += cur.toString(); cur.clear() }
                // "2>&1", ">&2", "&> file" and "|&" targets are redirects, not separators
                ch == '&' && (i > 0 && (cmd[i - 1] == '>' || cmd[i - 1] == '<') || i + 1 < cmd.length && cmd[i + 1] == '>') -> cur.append(ch)
                ch == '&' || ch == '|' -> { out += cur.toString(); cur.clear(); if (i + 1 < cmd.length && cmd[i + 1] == ch) i++ }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Words of one simple command, quotes removed, leading VAR=value assignments dropped. */
    fun words(seg: String): List<String> {
        val w = Regex("""'[^']*'|"(?:[^"\\]|\\.)*"|\S+""").findAll(seg).map { it.value.trim('\'', '"') }.toMutableList()
        while (w.isNotEmpty() && Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""").matches(w[0])) w.removeAt(0)
        return w
    }

    /** Is [p] (a path word) inside the workspace? Relative paths, ~ and the [roots] are; ".." escaping the start isn't. */
    fun inside(p: String, roots: List<String> = DEFAULT_ROOTS): Boolean {
        val t = p.trim()
        if (t.isEmpty()) return true
        if (t.startsWith("~")) return !t.contains("/../")
        if (!t.startsWith("/")) return !(t == ".." || t.startsWith("../"))
        if (t.contains("/../")) return false
        return roots.any { r -> t == r || t.startsWith(r.trimEnd('/') + "/") }
    }

    private fun pathWords(w: List<String>): List<String> = w.drop(1).filter { !it.startsWith("-") && (it.startsWith("/") || it.startsWith("~") || it.startsWith("..")) }

    private fun max(a: Level, b: Level): Level = if (a.ordinal >= b.ordinal) a else b

    /** The level of one simple command. */
    fun segLevel(seg: String, roots: List<String> = DEFAULT_ROOTS): Level {
        // Redirects: writing outside the workspace is risky; ">" to /dev/null or a workspace file is fine.
        var lvl = Level.SAFE
        for (m in Regex("""(?:^|[^0-9&<>])(>{1,2}|&>)\s*([^\s;&|]+)""").findAll(seg)) {
            val target = m.groupValues[2].trim('\'', '"')
            if (target == "/dev/null" || target.startsWith("&")) continue
            if (target.startsWith("/dev/")) return Level.RISKY
            if (!inside(target, roots)) return Level.RISKY
        }
        if (seg.contains("$(") || seg.contains('`') || seg.contains("<(")) lvl = Level.ASK
        val w = words(seg.replace(Regex("""\d?>{1,2}\s*\S+|&>\s*\S+|<\s*\S+"""), " "))
        if (w.isEmpty()) return lvl
        val cmd0 = w[0]
        val cmd = cmd0.substringAfterLast('/').let { if (cmd0 == "./gradlew") "./gradlew" else it }
        val args = w.drop(1)
        val sub = args.firstOrNull { !it.startsWith("-") }.orEmpty()
        val paths = pathWords(w)
        val outside = paths.any { !inside(it, roots) }
        when {
            // v0.45.6: Blaze Music's transport typed as a command ("music_ops play") is a safe local action in Normal mode.
            cmd == "music_ops" && sub.lowercase() in IN_APP_SAFE -> return lvl
            cmd in superUser -> return Level.RISKY
            diskTools.matches(cmd) -> return Level.RISKY
            cmd in deleters -> return Level.RISKY
            talksOrPays.containsMatchIn(cmd) || talksOrPays.containsMatchIn(seg) -> return Level.RISKY
            cmd in pkgMgr && (args.any { pkgVerb.matches(it) } || cmd == "termux-setup-storage") -> return Level.RISKY
            langInstall.containsKey(cmd) && (langInstall[cmd] == null || sub in langInstall[cmd]!!) -> return Level.RISKY
            (cmd == "python" || cmd == "python3") && args.take(3).joinToString(" ").matches(Regex("""-m\s+pip\s+(install|uninstall).*""")) -> return Level.RISKY
            cmd == "find" && args.any { it == "-delete" || it == "-exec" || it == "-execdir" || it == "-ok" } -> return if (seg.contains(Regex("""\brm\b"""))) Level.RISKY else max(lvl, Level.ASK)
            cmd == "kill" || cmd == "killall" || cmd == "pkill" -> return max(lvl, Level.ASK)
            cmd == "chmod" || cmd == "chown" || cmd == "chgrp" -> return if (outside) Level.RISKY else max(lvl, Level.ASK)
            cmd == "git" -> return max(lvl, gitLevel(args))
            cmd in networkFetch -> {
                // Fetching to the screen is a network call (ask once); saving outside the workspace is risky.
                val dest = args.zipWithNext().firstOrNull { (a, _) -> a == "-o" || a == "-O" || a == "--output" || a == "-P" }?.second
                return if (dest != null && !inside(dest, roots)) Level.RISKY else max(lvl, Level.ASK)
            }
            cmd in writers && outside -> return Level.RISKY
            cmd == "sed" && args.any { it == "-i" || it.startsWith("-i") || it.startsWith("--in-place") } -> return if (outside) Level.RISKY else max(lvl, Level.ASK)
            cmd == "awk" && seg.contains(Regex("""system\s*\(|print\s*>|-i\s+inplace""")) -> return max(lvl, Level.ASK)
            // Writes inside the workspace (cp, mv, mkdir, tar, unzip, patch, tee…) are ordinary build work.
            cmd in writers -> return lvl
            buildSubs.containsKey(cmd) -> {
                val subs = buildSubs[cmd]
                if (subs == null || sub in subs || args.isEmpty() && cmd != "npm") return if (outside) max(lvl, Level.ASK) else lvl
                return max(lvl, Level.ASK)
            }
            // Running the project's own code (python3 app.py, node build.js, java -jar out/app.jar, kotlinc …) inside the workspace.
            cmd in setOf("bash", "sh", "zsh", "dash") && args.contains("-c") -> return max(max(lvl, Level.SAFE), shell(args.getOrNull(args.indexOf("-c") + 1).orEmpty(), roots))
            cmd in runners -> return if (outside || args.any { it == "-c" || it == "-e" || it == "--eval" }) max(lvl, Level.ASK) else lvl
            cmd in readOnly -> return if (outside) max(lvl, Level.ASK) else lvl
        }
        return max(lvl, Level.ASK)
    }

    /** git as the git tool or a shell line: read-only sub-commands are safe, history rewrites / force pushes risky. */
    fun gitLevel(args: List<String>): Level {
        val a = args.dropWhile { it == "-C" }.let { if (args.firstOrNull() == "-C") args.drop(2) else args }.filter { it != "--no-pager" }
        val joined = a.joinToString(" ")
        if (gitRisky.containsMatchIn(" $joined")) return Level.RISKY
        val sub = a.firstOrNull { !it.startsWith("-") } ?: return Level.SAFE
        if (sub in gitRead) {
            // Read-only only when the arguments don't change things (git branch NAME, git tag NAME, git stash push…).
            return when (sub) {
                "branch" -> if (a.drop(1).any { !it.startsWith("-") } || a.any { it in setOf("-d", "-m", "-M", "-c", "-C", "--delete", "--move") }) Level.ASK else Level.SAFE
                "tag" -> if (a.drop(1).any { !it.startsWith("-") } && !a.contains("-l") && !a.contains("--list")) Level.ASK else Level.SAFE
                "stash" -> if (a.size > 1 && a[1] in setOf("list", "show")) Level.SAFE else Level.ASK
                "remote" -> if (a.size == 1 || a[1] in setOf("-v", "show", "get-url")) Level.SAFE else Level.ASK
                "config" -> if (a.any { it.startsWith("--get") || it == "--list" || it == "-l" }) Level.SAFE else Level.ASK
                else -> Level.SAFE
            }
        }
        // Local history work is undoable and stays on the phone; push / pull / clone / rebase / git rm ask.
        if (sub in gitLocal) return Level.SAFE
        return Level.ASK
    }

    /** The level of a command line (the riskiest of its parts). [roots] = the workspace's absolute folders. */
    fun shell(cmd: String, roots: List<String> = DEFAULT_ROOTS): Level {
        val segs = segments(cmd)
        if (segs.isEmpty()) return Level.SAFE
        // curl … | sh: running a script straight from the network.
        if (Regex("""(?i)\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(ba|z|da|k)?sh\b|\b(ba)?sh\s+<\(\s*(curl|wget)""").containsMatchIn(cmd)) return Level.RISKY
        return segs.map { segLevel(it, roots) }.maxByOrNull { it.ordinal } ?: Level.SAFE
    }

    /** The level for any [kind] of command. [risky] = the caller's own danger check (DesktopExtras.dangerous, Git.risky…). */
    fun level(kind: Kind, text: String, risky: Boolean = false, roots: List<String> = DEFAULT_ROOTS): Level = when {
        risky -> Level.RISKY
        kind == Kind.SHELL -> shell(text, roots)
        kind == Kind.GIT -> gitLevel(words(text).let { if (it.firstOrNull() == "git") it.drop(1) else it })
        kind == Kind.DESKTOP_TYPE -> {
            // Typed into the desktop: a recognisable command is judged as one; other text (prose into an app) asks once.
            val first = words(text.trim().lineSequence().firstOrNull().orEmpty()).firstOrNull().orEmpty()
            val known = first in readOnly || buildSubs.containsKey(first) || first == "git" || first in superUser || first in deleters || first in pkgMgr || langInstall.containsKey(first)
            if (known) max(shell(text, roots), Level.SAFE) else Level.ASK
        }
        kind == Kind.DESKTOP_KEY -> if (Regex("""(?i)ctrl\+alt\+(delete|backspace)|super\+l""").containsMatchIn(text)) Level.RISKY else Level.ASK
        else -> Level.ASK
    }

    /** What "Always allow this command" remembers: the program and its sub-command ("npm run", "git commit", "desktop type"). */
    fun key(kind: Kind, text: String): String = when (kind) {
        Kind.DESKTOP_TYPE -> "desktop type"
        Kind.DESKTOP_KEY -> "desktop keys"
        Kind.DESKTOP_OPEN -> "desktop open " + text.trim().take(60).lowercase()
        Kind.GIT -> "git " + words(text).let { if (it.firstOrNull() == "git") it.drop(1) else it }.firstOrNull { !it.startsWith("-") }.orEmpty()
        Kind.SHELL -> segments(text).joinToString(" && ") { s ->
            val w = words(s); val c = w.firstOrNull().orEmpty().substringAfterLast('/')
            val sub = w.drop(1).firstOrNull { !it.startsWith("-") && !it.contains('/') && !it.contains('.') && it.length <= 24 }
            if (sub != null && (buildSubs.containsKey(c) || c == "git" || langInstall.containsKey(c) || c in pkgMgr)) "$c $sub" else c
        }.take(120)
    }.trim()

    enum class Decision { RUN, ASK, ASK_RISKY }

    /**
     * Normal mode: [level] of the command, [key] for grants, [chat] / [always] = keys allowed, [tainted] = this turn
     * read outside content (web, messages, the screen): then allowances don't apply and the card shows.
     * [batchOk] = the user already approved this exact command on this step's batch card.
     */
    fun decide(level: Level, key: String, chat: Set<String>, always: Set<String>, tainted: Boolean = false, batchOk: Boolean = false): Decision = when {
        batchOk -> Decision.RUN
        level == Level.RISKY -> Decision.ASK_RISKY
        level == Level.SAFE -> Decision.RUN
        !tainted && (key in chat || key in always) -> Decision.RUN
        else -> Decision.ASK
    }

    /** One card for several commands in one reply. Risky ones are marked. */
    fun batch(items: List<Pair<String, Level>>): Pair<String, String> {
        val title = if (items.size == 1) "Run this command?" else "Run these ${items.size} commands?"
        val detail = items.take(10).mapIndexed { i, (c, l) -> "${i + 1}. " + (if (l == Level.RISKY) "\u26a0 " else "") + c.replace('\n', ' ').take(140) }.joinToString("\n") +
            (if (items.size > 10) "\n\u2026and ${items.size - 10} more" else "")
        return title to detail
    }

    /** The answer on a command card. */
    enum class Grant { NO, ONCE, CHAT, ALWAYS }

    /** "Allow for this chat" / "Always allow" are offered only for [Level.ASK] commands (never risky ones). */
    fun grantsOffered(level: Level): Boolean = level == Level.ASK

    /**
     * Allowances: per chat (until the app restarts) and always (kept in prefs by [load] / [save]; one key per line).
     * Thread-safe.
     */
    class Grants(private val load: () -> String, private val save: (String) -> Unit) {
        private val chats = HashMap<String, MutableSet<String>>()
        @Volatile private var alwaysCache: Set<String>? = null
        @Synchronized fun chat(id: String): Set<String> = chats[id]?.toSet() ?: emptySet()
        @Synchronized fun always(): Set<String> = alwaysCache ?: load().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet().also { alwaysCache = it }
        @Synchronized fun grant(chat: String, key: String, g: Grant) {
            if (key.isBlank()) return
            when (g) {
                Grant.CHAT -> chats.getOrPut(chat) { HashSet() }.add(key)
                Grant.ALWAYS -> { val n = always() + key; alwaysCache = n; save(n.sorted().joinToString("\n")) }
                else -> {}
            }
        }
        @Synchronized fun forget(key: String) { val n = always() - key; alwaysCache = n; save(n.sorted().joinToString("\n")) }
    }
}
