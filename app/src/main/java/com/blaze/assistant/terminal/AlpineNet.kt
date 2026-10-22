package com.blaze.assistant.terminal

/**
 * v0.25.5, pure (AlpineNetTest): where Blaze's Alpine Linux comes from. Several official Alpine mirrors (from
 * mirrors.alpinelinux.org, each checked to carry v3.20 for aarch64 / armv7 / x86_64) so one slow or blocked server
 * doesn't stop the download, the minirootfs SHA-256s (published next to each tarball as `.sha256`), the
 * `/etc/apk/repositories` text for a mirror, and a resolv.conf that tries the phone's own DNS first.
 */
object AlpineNet {
    const val VER = "3.20.3"
    val BRANCH = "v" + VER.substringBeforeLast('.')

    /** Official mirrors, best first: Fastly's CDN, kernel.org's EU and US edges, Morocco (MARWAN), Aachen, Aalborg. */
    val MIRRORS = listOf(
        "https://dl-cdn.alpinelinux.org/alpine",
        "https://eu.edge.kernel.org/alpine",
        "https://mirrors.edge.kernel.org/alpine",
        "https://mirror.marwan.ma/alpine",
        "https://ftp.halifax.rwth-aachen.de/alpine",
        "https://mirrors.dotsrc.org/alpine",
    )

    /** SHA-256 of alpine-minirootfs-3.20.3-<arch>.tar.gz (the `.sha256` files on dl-cdn, same on every mirror). */
    val ROOTFS_SHA256 = mapOf(
        "aarch64" to "041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de",
        "armv7" to "ea8823fb4c4cf5f71f1d180e47904fb36ae74d3ded06c980230116b129fc5f07",
        "x86_64" to "d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c",
    )

    /** Alpine's name for the phone's CPU (first supported ABI). */
    fun arch(abi: String?): String = when {
        abi == null -> "armv7"
        abi.startsWith("arm64") -> "aarch64"
        abi == "x86_64" -> "x86_64"
        else -> "armv7"
    }

    /** The minirootfs on every mirror, in mirror order. */
    fun rootfsUrls(arch: String): List<String> = MIRRORS.map { "$it/$BRANCH/releases/$arch/alpine-minirootfs-$VER-$arch.tar.gz" }

    /** `/etc/apk/repositories` for one mirror (main + community, which has XFCE and TigerVNC). */
    fun repositories(mirror: String): String = "${mirror.trimEnd('/')}/$BRANCH/main\n${mirror.trimEnd('/')}/$BRANCH/community\n"

    /** Short host name for status lines ("eu.edge.kernel.org"). */
    fun host(mirror: String): String = mirror.substringAfter("://").substringBefore('/')

    /**
     * resolv.conf with the phone's own DNS servers first (some Wi-Fi networks block 8.8.8.8 / 1.1.1.1), then Google
     * and Cloudflare. musl reads at most three; scoped IPv6 (fe80::1%wlan0) and junk are skipped.
     */
    fun resolvConf(phoneDns: List<String>): String {
        val ok = Regex("^[0-9a-fA-F:.]{2,45}$")
        val list = (phoneDns.map { it.trim().removePrefix("/") }.filter { it.matches(ok) && !it.startsWith("127.") && it != "::1" } +
            listOf("8.8.8.8", "1.1.1.1")).distinct().take(3)
        return list.joinToString("") { "nameserver $it\n" } + "options timeout:3 attempts:2\n"
    }
}
