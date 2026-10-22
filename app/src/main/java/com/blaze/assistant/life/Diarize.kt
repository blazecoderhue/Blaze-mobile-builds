package com.blaze.assistant.life

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * v0.33.0: telling voices apart in a life log, on the phone. Vosk's speaker model gives each utterance an x-vector
 * (a voice fingerprint). Utterances long enough to trust are grouped by average-linkage clustering on cosine
 * similarity; short ones join the nearest group (or the previous speaker). Groups are matched against voices Blaze
 * already knows (named in earlier reviews), so "Mum" stays "Mum" across days. Pure (LifeLog2Test).
 */
object Diarize {
    /** Same speaker when the average cosine similarity between two groups is at least this. */
    const val MERGE = 0.50
    /** A known voice is recognised at this similarity. */
    const val KNOWN = 0.58
    /** Utterances with fewer speaker frames than this are too short to trust on their own. */
    const val MIN_FRAMES = 120

    data class Utt(val startMs: Long, val endMs: Long, val text: String, val vec: DoubleArray?, val frames: Int, val rms: Int = 0)
    data class Known(val name: String, val vec: DoubleArray, val n: Int)

    fun cos(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else d / (sqrt(na) * sqrt(nb))
    }

    fun mean(vs: List<DoubleArray>): DoubleArray {
        val m = DoubleArray(vs.first().size)
        vs.forEach { v -> for (i in m.indices) m[i] += v[i] }
        for (i in m.indices) m[i] /= vs.size
        return m
    }

    /** Speaker group (0-based, in order of first appearance) per utterance. */
    fun cluster(utts: List<Utt>): IntArray {
        val out = IntArray(utts.size) { -1 }
        val strong = utts.indices.filter { utts[it].vec != null && utts[it].frames >= MIN_FRAMES }.take(600)
        val groups = strong.map { mutableListOf(it) }.toMutableList()
        val sim = Array(groups.size) { i -> DoubleArray(groups.size) { j -> if (i == j) 0.0 else cos(utts[strong[i]].vec!!, utts[strong[j]].vec!!) } }
        val alive = BooleanArray(groups.size) { true }
        val size = IntArray(groups.size) { 1 }
        while (true) {
            var bi = -1; var bj = -1; var best = MERGE
            for (i in groups.indices) if (alive[i]) for (j in i + 1 until groups.size) if (alive[j] && sim[i][j] >= best) { best = sim[i][j]; bi = i; bj = j }
            if (bi < 0) break
            for (k in groups.indices) if (alive[k] && k != bi && k != bj) {
                val v = (sim[bi][k] * size[bi] + sim[bj][k] * size[bj]) / (size[bi] + size[bj])
                sim[bi][k] = v; sim[k][bi] = v
            }
            groups[bi].addAll(groups[bj]); size[bi] += size[bj]; alive[bj] = false
        }
        val live = groups.indices.filter { alive[it] }.map { groups[it] }
        live.forEachIndexed { g, members -> members.forEach { out[it] = g } }
        val cents = live.map { m -> mean(m.map { utts[it].vec!! }) }
        for (i in utts.indices) if (out[i] < 0) {
            val v = utts[i].vec
            val near = if (v != null && cents.isNotEmpty()) cents.indices.maxByOrNull { cos(v, cents[it]) } else null
            out[i] = when {
                near != null && cos(v!!, cents[near]) >= MERGE - 0.1 -> near
                i > 0 && out[i - 1] >= 0 -> out[i - 1]
                else -> near ?: 0
            }
        }
        val map = HashMap<Int, Int>()
        return IntArray(out.size) { i -> map.getOrPut(out[i]) { map.size } }
    }

    /** Group centroids (groups with no vectors left out). */
    fun centroids(utts: List<Utt>, groups: IntArray): Map<Int, DoubleArray> =
        groups.indices.groupBy { groups[it] }.mapNotNull { (g, idx) ->
            val vs = idx.mapNotNull { utts[it].vec }; if (vs.isEmpty()) null else g to mean(vs) }.toMap()

    /** Group → a known voice's name, when one matches (each known voice used once, best match first). */
    fun matchKnown(cents: Map<Int, DoubleArray>, known: List<Known>): Map<Int, String> {
        val pairs = cents.flatMap { (g, c) -> known.map { k -> Triple(g, k.name, cos(c, k.vec)) } }.filter { it.third >= KNOWN }.sortedByDescending { it.third }
        val out = HashMap<Int, String>(); val used = HashSet<String>()
        for ((g, n, _) in pairs) if (g !in out && n !in used) { out[g] = n; used += n }
        return out
    }

    /** Folds a newly named group's centroid into the known voices (running mean). */
    fun learn(known: List<Known>, name: String, vec: DoubleArray, weight: Int): List<Known> {
        val k = known.firstOrNull { it.name.equals(name, true) }
        return if (k == null) known + Known(name, vec, weight)
        else known.map { if (it !== k) it else Known(k.name, DoubleArray(vec.size) { i -> (k.vec[i] * k.n + vec[i] * weight) / (k.n + weight) }, k.n + weight) }
    }

    fun knownToJson(known: List<Known>): String = JSONArray().apply {
        known.forEach { k -> put(JSONObject().put("name", k.name).put("n", k.n).put("vec", JSONArray().apply { k.vec.forEach { put(it) } })) }
    }.toString()

    fun knownFromJson(s: String): List<Known> = try {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            val v = o.optJSONArray("vec") ?: return@let null
            Known(o.optString("name"), DoubleArray(v.length()) { v.optDouble(it) }, o.optInt("n", 1)) } }.filter { it.name.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    /** Vosk result JSON → its "spk" vector and "spk_frames". */
    fun voskVector(o: JSONObject): Pair<DoubleArray?, Int> {
        val a = o.optJSONArray("spk") ?: return null to 0
        return DoubleArray(a.length()) { a.optDouble(it) } to o.optInt("spk_frames")
    }
}
