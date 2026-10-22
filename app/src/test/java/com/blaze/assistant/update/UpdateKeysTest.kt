package com.blaze.assistant.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.45.15 key rotation: updates pin the private release key; the old public key alone is refused; no key in the repo. */
class UpdateKeysTest {
    @Test fun releaseKeyIsPinned() {
        assertTrue(UpdateKeys.pinned(setOf(UpdateKeys.RELEASE_CERT)))
        assertTrue(UpdateKeys.pinned(setOf(UpdateKeys.RELEASE_CERT.uppercase())))
        assertFalse(UpdateKeys.pinned(setOf(UpdateKeys.BURNED_CERT)))
        assertFalse(UpdateKeys.pinned(emptySet()))
    }

    @Test fun noSigningKeyInTheRepo() {
        fun f(p: String) = listOf(File(p), File("../$p")).firstOrNull { it.exists() }
        val g = (f("app/build.gradle.kts") ?: f("build.gradle.kts"))!!.readText()
        assertFalse(g.contains("storePassword"))
        assertFalse(g.contains("blaze.keystore"))
        assertTrue(f("keystore/blaze.keystore") == null)
    }
}
