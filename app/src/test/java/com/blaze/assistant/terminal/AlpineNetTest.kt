package com.blaze.assistant.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.25.5: Alpine mirrors, checksums, arch names and DNS. */
class AlpineNetTest {
    @Test fun mirrorsAndRootfs() {
        assertEquals("https://dl-cdn.alpinelinux.org/alpine", AlpineNet.MIRRORS.first())
        assertTrue(AlpineNet.MIRRORS.size >= 4); assertTrue(AlpineNet.MIRRORS.all { it.startsWith("https://") && !it.endsWith("/") })
        val u = AlpineNet.rootfsUrls("aarch64")
        assertEquals("https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz", u[0])
        assertEquals(AlpineNet.MIRRORS.size, u.toSet().size)
        listOf("aarch64", "armv7", "x86_64").forEach { assertEquals(64, AlpineNet.ROOTFS_SHA256.getValue(it).length) }
        assertEquals("https://x/alpine/v3.20/main\nhttps://x/alpine/v3.20/community\n", AlpineNet.repositories("https://x/alpine/"))
        assertEquals("eu.edge.kernel.org", AlpineNet.host("https://eu.edge.kernel.org/alpine"))
    }

    @Test fun archNames() {
        assertEquals("aarch64", AlpineNet.arch("arm64-v8a")); assertEquals("armv7", AlpineNet.arch("armeabi-v7a"))
        assertEquals("x86_64", AlpineNet.arch("x86_64")); assertEquals("armv7", AlpineNet.arch(null))
    }

    @Test fun phoneDnsFirstThenPublic() {
        assertEquals("nameserver 192.168.1.1\nnameserver 8.8.8.8\nnameserver 1.1.1.1\noptions timeout:3 attempts:2\n",
            AlpineNet.resolvConf(listOf("/192.168.1.1", "fe80::1%wlan0", "127.0.0.1", "bad host!")))
        assertEquals("nameserver 8.8.8.8\nnameserver 1.1.1.1\noptions timeout:3 attempts:2\n", AlpineNet.resolvConf(emptyList()))
        assertEquals(3, AlpineNet.resolvConf(listOf("10.0.0.1", "10.0.0.2", "2001:db8::1")).lines().count { it.startsWith("nameserver") })
    }
}
