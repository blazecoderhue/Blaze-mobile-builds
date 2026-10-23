package com.blaze.kiln

import com.blaze.kiln.manifest.ANDROID_NS
import com.blaze.kiln.manifest.AndroidAttr
import com.blaze.kiln.manifest.AxmlTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Test-only writer for binary XML (UTF-16 string pool, the other pool encoding the reader must handle), so tests can
 * build tiny guest manifests. Layout per AOSP ResourceTypes.h.
 */
object TestAxml {
    class A(val android: Boolean, val name: String, val resId: Int, val type: Int, val data: Int, val raw: String?)
    class E(val name: String, val attrs: List<A>, val children: List<E>)

    fun str(name: String, resId: Int, v: String) = A(true, name, resId, AxmlTypes.TYPE_STRING, -1, v)
    fun bool(name: String, resId: Int, v: Boolean) = A(true, name, resId, AxmlTypes.TYPE_INT_BOOLEAN, if (v) -1 else 0, null)
    fun int(name: String, resId: Int, v: Int) = A(true, name, resId, AxmlTypes.TYPE_INT_DEC, v, null)
    fun ref(name: String, resId: Int, v: Int) = A(true, name, resId, AxmlTypes.TYPE_REFERENCE, v, null)
    fun plain(name: String, v: String) = A(false, name, 0, AxmlTypes.TYPE_STRING, -1, v)
    fun e(name: String, vararg attrs: A, children: List<E> = emptyList()) = E(name, attrs.toList(), children)

    fun write(root: E): ByteArray {
        // Strings: attribute names that have resource ids first (index-aligned with the resource map), then the rest.
        val withId = LinkedHashMap<String, Int>()
        val others = LinkedHashSet<String>()
        fun walk(x: E) {
            others.add(x.name)
            for (a in x.attrs) {
                if (a.resId != 0) withId.putIfAbsent(a.name, a.resId) else others.add(a.name)
                a.raw?.let { others.add(it) }
            }
            x.children.forEach { walk(it) }
        }
        walk(root)
        others.add(ANDROID_NS)
        val pool = ArrayList<String>(withId.keys)
        for (s in others) if (s !in pool) pool.add(s)
        fun idx(s: String) = pool.indexOf(s)

        val body = ByteArrayOutputStream()
        body.write(stringPool(pool))
        body.write(chunk(AxmlTypes.CHUNK_XML_RESOURCE_MAP, 8, ints(withId.values.toList())))
        fun emit(x: E) {
            val ext = ByteArrayOutputStream()
            ext.write(le32(-1)); ext.write(le32(idx(x.name)))
            ext.write(le16(20)); ext.write(le16(20)); ext.write(le16(x.attrs.size))
            ext.write(le16(0)); ext.write(le16(0)); ext.write(le16(0))
            for (a in x.attrs) {
                ext.write(le32(if (a.android) idx(ANDROID_NS) else -1))
                ext.write(le32(idx(a.name)))
                ext.write(le32(a.raw?.let { idx(it) } ?: -1))
                ext.write(le16(8)); ext.write(0); ext.write(a.type)
                ext.write(le32(if (a.type == AxmlTypes.TYPE_STRING && a.raw != null) idx(a.raw) else a.data))
            }
            body.write(chunk(AxmlTypes.CHUNK_XML_START_ELEMENT, 16, le32(1) + le32(-1) + ext.toByteArray()))
            x.children.forEach { emit(it) }
            body.write(chunk(AxmlTypes.CHUNK_XML_END_ELEMENT, 16, le32(1) + le32(-1) + le32(-1) + le32(idx(x.name))))
        }
        emit(root)
        return chunk(AxmlTypes.CHUNK_XML, 8, body.toByteArray())
    }

    /** Minimal guest manifest: one launcher activity (+ optional extras). */
    fun simpleGuest(
        pkg: String = "org.example.calc",
        extraApp: List<E> = emptyList(),
        extraAppAttrs: List<A> = emptyList(),
        minSdk: Int = 21,
        launchMode: Int? = null,
    ): ByteArray {
        val actAttrs = mutableListOf(str("name", AndroidAttr.NAME, ".MainActivity"), bool("exported", AndroidAttr.EXPORTED, true))
        if (launchMode != null) actAttrs += int("launchMode", AndroidAttr.LAUNCH_MODE, launchMode)
        val main = E("activity", actAttrs, listOf(
            e("intent-filter", children = listOf(
                e("action", str("name", AndroidAttr.NAME, "android.intent.action.MAIN")),
                e("category", str("name", AndroidAttr.NAME, "android.intent.category.LAUNCHER")),
            )),
        ))
        val app = E("application", listOf(ref("theme", AndroidAttr.THEME, 0x7f0f0001), str("label", AndroidAttr.LABEL, "Calc")) + extraAppAttrs,
            listOf(main) + extraApp)
        return write(e("manifest", plain("package", pkg), int("versionCode", AndroidAttr.VERSION_CODE, 7),
            str("versionName", AndroidAttr.VERSION_NAME, "1.2"),
            children = listOf(
                e("uses-sdk", int("minSdkVersion", AndroidAttr.MIN_SDK_VERSION, minSdk), int("targetSdkVersion", AndroidAttr.TARGET_SDK_VERSION, 33)),
                app,
            )))
    }

    /** Builds an APK-shaped zip in [dir]. */
    fun apk(dir: File, name: String, manifest: ByteArray, entries: Map<String, ByteArray> = mapOf("classes.dex" to ByteArray(112))): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("resources.arsc")); z.write(ByteArray(16)); z.closeEntry()
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    /** A little-endian ELF header with PT_LOAD segments of the given alignments. */
    fun elf(is64: Boolean, vararg aligns: Long): ByteArray {
        val phentsize = if (is64) 56 else 32
        val phoff = if (is64) 64 else 52
        val b = ByteArray(phoff + phentsize * aligns.size)
        b[0] = 0x7f.toByte(); b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = (if (is64) 2 else 1).toByte(); b[5] = 1.toByte()
        fun put16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun put32(o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
        fun put64(o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v shr (8 * i)).toByte() }
        if (is64) { put64(0x20, phoff.toLong()); put16(0x36, phentsize); put16(0x38, aligns.size) }
        else { put32(0x1c, phoff.toLong()); put16(0x2a, phentsize); put16(0x2c, aligns.size) }
        aligns.forEachIndexed { i, a ->
            val p = phoff + i * phentsize
            put32(p, 1)
            if (is64) put64(p + 0x30, a) else put32(p + 0x1c, a)
        }
        return b
    }

    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (s in strings) {
            offsets.add(data.size())
            data.write(le16(s.length))
            data.write(s.toByteArray(Charsets.UTF_16LE))
            data.write(le16(0))
        }
        while (data.size() % 4 != 0) data.write(0)
        val header = 28
        val stringsStart = header + 4 * strings.size
        val payload = le32(strings.size) + le32(0) + le32(0) + le32(stringsStart) + le32(0) +
            ints(offsets) + data.toByteArray()
        return chunk(AxmlTypes.CHUNK_STRING_POOL, header, payload)
    }

    /** Chunk = type, headerSize, size, then [rest] (which already contains the header's remaining fields). */
    private fun chunk(type: Int, headerSize: Int, rest: ByteArray): ByteArray = le16(type) + le16(headerSize) + le32(8 + rest.size) + rest

    private fun ints(v: List<Int>): ByteArray { val o = ByteArrayOutputStream(); v.forEach { o.write(le32(it)) }; return o.toByteArray() }
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
