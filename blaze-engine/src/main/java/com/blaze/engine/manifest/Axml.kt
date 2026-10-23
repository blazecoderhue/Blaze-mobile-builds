package com.blaze.engine.manifest

// Reader for Android's compiled binary XML (the format AAPT2 writes for AndroidManifest.xml).
// Written from the chunk layout documented in AOSP frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
// (Apache-2.0): ResChunk_header, ResStringPool_header, ResXMLTree_node, ResXMLTree_attrExt, ResXMLTree_attribute, Res_value.
// Pure Kotlin: no android.* imports, so it runs in JVM unit tests and on device alike.

class AxmlFormatException(message: String) : Exception(message)

object AxmlTypes {
    const val CHUNK_STRING_POOL = 0x0001
    const val CHUNK_XML = 0x0003
    const val CHUNK_XML_START_NAMESPACE = 0x0100
    const val CHUNK_XML_END_NAMESPACE = 0x0101
    const val CHUNK_XML_START_ELEMENT = 0x0102
    const val CHUNK_XML_END_ELEMENT = 0x0103
    const val CHUNK_XML_CDATA = 0x0104
    const val CHUNK_XML_RESOURCE_MAP = 0x0180

    const val POOL_FLAG_UTF8 = 0x100

    const val TYPE_NULL = 0x00
    const val TYPE_REFERENCE = 0x01
    const val TYPE_ATTRIBUTE = 0x02
    const val TYPE_STRING = 0x03
    const val TYPE_FLOAT = 0x04
    const val TYPE_DIMENSION = 0x05
    const val TYPE_FRACTION = 0x06
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12
    const val TYPE_LAST_INT = 0x1f

    const val NO_INDEX = -1 // 0xFFFFFFFF
}

const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** One attribute. [raw] is the original string when AAPT kept one; [type]/[data] are the typed Res_value. */
data class AxmlAttr(
    val namespace: String?,
    val name: String,
    val resId: Int,
    val type: Int,
    val data: Int,
    val raw: String?,
) {
    val isReference: Boolean get() = type == AxmlTypes.TYPE_REFERENCE

    fun asString(): String? = raw

    fun asBool(): Boolean? = when (type) {
        AxmlTypes.TYPE_INT_BOOLEAN -> data != 0
        AxmlTypes.TYPE_STRING -> raw?.lowercase()?.let { if (it == "true") true else if (it == "false") false else null }
        else -> null
    }

    fun asInt(): Int? = when {
        type in AxmlTypes.TYPE_INT_DEC..AxmlTypes.TYPE_LAST_INT -> data
        type == AxmlTypes.TYPE_STRING -> raw?.trim()?.let { s ->
            if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toLongOrNull(16)?.toInt() else s.toIntOrNull()
        }
        else -> null
    }

    /** Resource id for a reference value, else null. */
    fun asRef(): Int? = if (type == AxmlTypes.TYPE_REFERENCE) data else null
}

class AxmlElement(
    val namespace: String?,
    val name: String,
    val attrs: List<AxmlAttr>,
    val line: Int,
) {
    val children: MutableList<AxmlElement> = mutableListOf()

    /** Finds an android: attribute by its framework resource id first (works on obfuscated manifests), then by name. */
    fun attr(resId: Int, name: String): AxmlAttr? =
        attrs.firstOrNull { it.resId == resId && resId != 0 }
            ?: attrs.firstOrNull { it.name == name && (it.namespace == ANDROID_NS || it.namespace == null) }

    /** A plain (un-namespaced) attribute such as manifest/@package. */
    fun plain(name: String): AxmlAttr? = attrs.firstOrNull { it.name == name && it.namespace == null }

    fun childrenNamed(name: String): List<AxmlElement> = children.filter { it.name == name }

    fun firstChild(name: String): AxmlElement? = children.firstOrNull { it.name == name }
}

object AxmlReader {
    private const val MAX_STRINGS = 1_000_000
    private const val MAX_DEPTH = 256

    fun parse(bytes: ByteArray): AxmlElement {
        val r = Bytes(bytes)
        if (bytes.size < 8) throw AxmlFormatException("too short")
        val type = r.u16(0)
        val headerSize = r.u16(2)
        val total = r.u32(4)
        if (type != AxmlTypes.CHUNK_XML) throw AxmlFormatException("not binary XML (type 0x${type.toString(16)})")
        if (total > bytes.size || total < headerSize || headerSize < 8) throw AxmlFormatException("bad XML chunk size")

        var strings: Array<String> = emptyArray()
        var resMap = IntArray(0)
        val stack = ArrayDeque<AxmlElement>()
        var root: AxmlElement? = null

        var pos = headerSize
        while (pos + 8 <= total) {
            val cType = r.u16(pos)
            val cHeader = r.u16(pos + 2)
            val cSize = r.u32(pos + 4)
            if (cSize < 8 || cHeader < 8 || cHeader > cSize || pos.toLong() + cSize > total) {
                throw AxmlFormatException("bad chunk at $pos")
            }
            when (cType) {
                AxmlTypes.CHUNK_STRING_POOL -> strings = readPool(r, pos, cHeader, cSize)
                AxmlTypes.CHUNK_XML_RESOURCE_MAP -> {
                    val n = (cSize - cHeader) / 4
                    resMap = IntArray(n) { r.s32(pos + cHeader + 4 * it) }
                }
                AxmlTypes.CHUNK_XML_START_ELEMENT -> {
                    val line = r.u32(pos + 8)
                    val ext = pos + cHeader
                    r.need(ext, 20)
                    val ns = str(strings, r.s32(ext))
                    val name = str(strings, r.s32(ext + 4)) ?: throw AxmlFormatException("element without name at $pos")
                    val attrStart = r.u16(ext + 8)
                    val attrSize = r.u16(ext + 10)
                    val attrCount = r.u16(ext + 12)
                    if (attrCount > 0 && attrSize < 20) throw AxmlFormatException("attribute size $attrSize")
                    val attrs = ArrayList<AxmlAttr>(attrCount)
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        if (a + 20 > pos + cSize) throw AxmlFormatException("attribute past chunk end")
                        val aNs = str(strings, r.s32(a))
                        val nameIdx = r.s32(a + 4)
                        val aName = str(strings, nameIdx) ?: ""
                        val rawIdx = r.s32(a + 8)
                        val dataType = r.u8(a + 15)
                        val data = r.s32(a + 16)
                        var raw = str(strings, rawIdx)
                        if (raw == null && dataType == AxmlTypes.TYPE_STRING) raw = str(strings, data)
                        val resId = if (nameIdx in resMap.indices) resMap[nameIdx] else 0
                        attrs.add(AxmlAttr(aNs, aName, resId, dataType, data, raw))
                    }
                    val el = AxmlElement(ns, name, attrs, line)
                    val parent = stack.lastOrNull()
                    if (parent == null) {
                        if (root != null) throw AxmlFormatException("second root element <$name>")
                        root = el
                    } else {
                        parent.children.add(el)
                    }
                    stack.addLast(el)
                    if (stack.size > MAX_DEPTH) throw AxmlFormatException("too deep")
                }
                AxmlTypes.CHUNK_XML_END_ELEMENT -> {
                    if (stack.isEmpty()) throw AxmlFormatException("unbalanced end element at $pos")
                    stack.removeLast()
                }
                else -> Unit // namespaces, CDATA, unknown chunks: skipped by size
            }
            pos += cSize
        }
        if (stack.isNotEmpty()) throw AxmlFormatException("unclosed element <${stack.last().name}>")
        return root ?: throw AxmlFormatException("no root element")
    }

    private fun str(strings: Array<String>, idx: Int): String? = if (idx >= 0 && idx < strings.size) strings[idx] else null

    private fun readPool(r: Bytes, pos: Int, header: Int, size: Int): Array<String> {
        r.need(pos, 28)
        val count = r.u32(pos + 8)
        val flags = r.u32(pos + 16)
        val stringsStart = r.u32(pos + 20)
        if (count > MAX_STRINGS) throw AxmlFormatException("string pool too large")
        val utf8 = flags and AxmlTypes.POOL_FLAG_UTF8 != 0
        val offsets = pos + header
        r.need(offsets, count * 4)
        val base = pos + stringsStart
        val end = pos + size
        return Array(count) { i ->
            val o = base + r.u32(offsets + 4 * i)
            if (o < base || o >= end) throw AxmlFormatException("string $i out of pool")
            if (utf8) readUtf8(r, o, end) else readUtf16(r, o, end)
        }
    }

    private fun readUtf8(r: Bytes, start: Int, end: Int): String {
        var o = start
        // UTF-16 length (skipped), then UTF-8 byte length; each 1 or 2 bytes (high bit = 2-byte form).
        var b = r.u8(o); o += if (b and 0x80 != 0) 2 else 1
        b = r.u8(o); o++
        var len = b
        if (b and 0x80 != 0) { len = ((b and 0x7f) shl 8) or r.u8(o); o++ }
        if (o + len > end) throw AxmlFormatException("utf8 string past pool")
        return String(r.b, o, len, Charsets.UTF_8)
    }

    private fun readUtf16(r: Bytes, start: Int, end: Int): String {
        var o = start
        var len = r.u16(o); o += 2
        if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or r.u16(o); o += 2 }
        if (o + len * 2 > end) throw AxmlFormatException("utf16 string past pool")
        return String(r.b, o, len * 2, Charsets.UTF_16LE)
    }

    private class Bytes(val b: ByteArray) {
        fun need(o: Int, n: Int) { if (o < 0 || n < 0 || o.toLong() + n > b.size) throw AxmlFormatException("read past end at $o") }
        fun u8(o: Int): Int { need(o, 1); return b[o].toInt() and 0xff }
        fun u16(o: Int): Int { need(o, 2); return (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) }
        fun s32(o: Int): Int {
            need(o, 4)
            return (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        }
        /** Unsigned 32-bit that must fit an Int (sizes, offsets, counts). */
        fun u32(o: Int): Int {
            val v = s32(o)
            if (v < 0) throw AxmlFormatException("value too large at $o")
            return v
        }
    }
}
