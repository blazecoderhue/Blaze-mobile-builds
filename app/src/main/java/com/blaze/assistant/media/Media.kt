package com.blaze.assistant.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Blaze's gallery: images it generates (free, keyless Pollinations) and anything it makes in the
 * terminal (beats, charts, edited photos). Everything lives in filesDir/gallery with a small
 * sidecar .json (what it is, the prompt). Chats show items inline; the Gallery screen lists them.
 */
object Media {
    data class Item(val file: File, val kind: String, val title: String, val time: Long)

    private lateinit var app: Context
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    val audioExt = setOf("wav", "mp3", "ogg", "m4a", "flac", "aac", "mid", "midi")
    val videoExt = setOf("mp4", "webm", "mkv", "3gp")

    fun init(ctx: Context) { app = ctx.applicationContext; refresh() }
    fun dir(): File = File(app.filesDir, "gallery").apply { mkdirs() }

    fun kindOf(f: File): String = when (f.extension.lowercase()) {
        in imageExt -> "image"; in audioExt -> "audio"; in videoExt -> "video"; else -> "file"
    }
    fun isMedia(f: File) = kindOf(f) != "file"

    fun refresh() {
        if (!::app.isInitialized) return
        _items.value = (dir().listFiles() ?: emptyArray()).filter { it.isFile && isMedia(it) }
            .map { f ->
                val meta = File(f.path + ".json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                Item(f, kindOf(f), meta?.optString("title").orEmpty().ifBlank { f.nameWithoutExtension }, f.lastModified())
            }.sortedByDescending { it.time }
    }

    /** Copies a file Blaze made (terminal, download) into the gallery and returns the copy. */
    fun add(src: File, title: String = ""): File {
        val d = dir()
        if (src.parentFile?.canonicalPath == d.canonicalPath) { if (title.isNotBlank()) meta(src, title); refresh(); return src }
        var out = File(d, src.name)
        if (out.exists()) out = File(d, src.nameWithoutExtension + "_" + System.currentTimeMillis() % 100000 + "." + src.extension)
        src.copyTo(out, true)
        meta(out, title.ifBlank { src.nameWithoutExtension.replace('_', ' ') })
        refresh(); return out
    }

    private fun meta(f: File, title: String, extra: JSONObject = JSONObject()) =
        File(f.path + ".json").writeText(extra.put("title", title).put("time", System.currentTimeMillis()).toString())

    fun delete(f: File) { f.delete(); File(f.path + ".json").delete(); refresh() }

    // ---------- Image generation ----------

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(36).ifBlank { "image" }

    /** Free keyless text-to-image. Returns the saved file or an error message. */
    suspend fun generate(prompt: String, width: Int = 1024, height: Int = 1024, seed: Int? = null, model: String = ""): Result<File> = withContext(Dispatchers.IO) {
        val p = prompt.trim()
        if (p.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty prompt"))
        val w = width.coerceIn(256, 1536); val h = height.coerceIn(256, 1536)
        val s = seed ?: (Math.random() * 1_000_000).toInt()
        val q = "width=$w&height=$h&seed=$s&nologo=true&private=true" + (if (model.isNotBlank()) "&model=" + URLEncoder.encode(model, "UTF-8") else "")
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(p.take(900), "UTF-8").replace("+", "%20") + "?" + q
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 120_000
                c.setRequestProperty("User-Agent", "Blaze-Android")
                val code = c.responseCode
                if (code !in 200..299) { last = Exception("image service answered $code"); c.disconnect(); return@repeat }
                val type = c.contentType.orEmpty()
                if (!type.startsWith("image")) { last = Exception("image service sent $type"); c.disconnect(); return@repeat }
                val ext = if ("png" in type) "png" else "jpg"
                val f = File(dir(), "${slug(p)}_${System.currentTimeMillis() % 1_000_000}.$ext")
                c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                c.disconnect()
                if (f.length() < 2000) { f.delete(); last = Exception("got an empty image"); return@repeat }
                meta(f, p.take(80), JSONObject().put("prompt", p).put("seed", s).put("source", "pollinations"))
                refresh()
                return@withContext Result.success(f)
            } catch (e: Exception) { last = e; if (attempt == 0) kotlinx.coroutines.delay(1500) }
        }
        Result.failure(last ?: Exception("unknown error"))
    }

    private val directRe = Regex("""^(?:please\s+|can you\s+|could you\s+|blaze,?\s+)?(?:(draw|paint|sketch|illustrate)\s+(?:me\s+)?(?:an?\s+)?(?:(?:image|picture|pic|drawing|painting|sketch|illustration)\s+of\s+)?|(?:generate|create|make|design)\s+(?:me\s+)?(?:an?\s+)?(?:image|picture|pic|photo|drawing|painting|illustration|wallpaper|poster|artwork|art)\s+(?:of|showing|with)\s+)(.{3,400}?)[\s.!?]*$""", RegexOption.IGNORE_CASE)

    /** The subject when [text] is a plain "draw X" / "make an image of X" request, else null. */
    fun directPrompt(text: String): String? {
        val t = text.trim()
        if (t.length > 420 || t.contains('\n')) return null
        val m = directRe.find(t) ?: return null
        val subject = m.groupValues[2].trim()
        // "draw it again", "draw that", anything leaning on context goes to the full agent.
        if (subject.split(Regex("\\s+")).size < 2 && subject.lowercase() in setOf("it", "that", "this", "again", "one", "more")) return null
        if (Regex("""^(it|that|this|them|the same|another one)\b""", RegexOption.IGNORE_CASE).containsMatchIn(subject)) return null
        return subject
    }

    // ---------- Phone side: save / share ----------

    fun saveToPhone(f: File): String {
        val kind = kindOf(f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val (coll, rel) = when (kind) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES + "/Blaze"
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC + "/Blaze"
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES + "/Blaze"
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS + "/Blaze"
                }
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                }
                val uri = app.contentResolver.insert(coll, v) ?: return "Couldn't save it."
                app.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                "Saved to ${rel}/${f.name}."
            } else {
                @Suppress("DEPRECATION")
                val d = File(Environment.getExternalStoragePublicDirectory(if (kind == "audio") Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_PICTURES), "Blaze").apply { mkdirs() }
                f.copyTo(File(d, f.name), true); "Saved to ${d.name}/${f.name}."
            }
        } catch (e: Exception) { "Couldn't save it: ${e.message}" }
    }

    fun shareIntent(f: File): Intent {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun viewIntent(f: File): Intent {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---------- Terminal helpers ----------

    /** Small pure-Python helper library written into the terminal home: beats with no pip installs. */
    fun installHelpers(home: File) {
        try {
            val d = File(home, "blaze_lib").apply { mkdirs() }
            val f = File(d, "beats.py")
            if (!f.exists() || f.length().toInt() != BEATS_PY.length) f.writeText(BEATS_PY)
        } catch (_: Exception) { }
    }

    // ---------- Tools ----------

    val names = setOf("generate_image", "gallery")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters",
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun i(d: String) = JSONObject().put("type", "integer").put("description", d)

    fun tools(): List<JSONObject> = listOf(
        fn("generate_image", "Create an image from a description (free, no key). The picture appears in the chat and in the user's Gallery. Write a rich English prompt: subject, style, lighting, colours. For exact graphics (charts, text layouts, edits of an existing photo) use run_code with Python/Pillow instead.",
            JSONObject().put("prompt", s("Detailed description of the image")).put("width", i("Pixels, default 1024")).put("height", i("Pixels, default 1024 (use 1080x1920 for a phone wallpaper)"))
                .put("seed", i("Optional, reuse to get the same image again")).put("model", s("Optional: flux (default), turbo")), listOf("prompt")),
        fn("gallery", "The user's Blaze Gallery (images, beats/audio, video Blaze made). list = recent items; add = put a file you made in the terminal into the gallery and show it in chat; save = copy an item to the phone's Pictures/Music; share = open the share sheet; delete = remove (asks first).",
            JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("list", "add", "save", "share", "delete"))))
                .put("path", s("add: file path in the terminal (e.g. ~/beat.wav). save/share/delete: the gallery file name"))
                .put("title", s("add: a short title")), listOf("action")),
    )

    fun find(name: String): File? {
        val n = name.trim().substringAfterLast('/')
        return _items.value.firstOrNull { it.file.name == n }?.file ?: _items.value.firstOrNull { it.file.name.contains(n, true) || it.title.contains(n, true) }?.file
    }

    fun list(): String {
        refresh()
        val l = _items.value
        if (l.isEmpty()) return "The gallery is empty."
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
        return "Gallery (${l.size}):\n" + l.take(20).joinToString("\n") { "- ${it.file.name} [${it.kind}] \u201c${it.title.take(50)}\u201d ${fmt.format(java.util.Date(it.time))}" }
    }

    // A stdlib-only drum machine + bass so beats work right after Linux installs (no numpy needed).
    private val BEATS_PY = """
#!/usr/bin/env python3
""${'"'}Blaze beat maker: pure Python (stdlib only).
Usage: python3 /root/phone/blaze_lib/beats.py --style trap --bpm 140 --bars 8 --key A --out /root/phone/beat.wav
Styles: trap, boombap, afro (amapiano-ish log drum), house, lofi. Use --seed for variations.
In code: import sys; sys.path.insert(0, '/root/phone/blaze_lib'); from beats import make; make(style='afro', bpm=112, bars=8, out='/root/phone/beat.wav')
""${'"'}
import argparse, math, os, random, struct, wave

SR = 22050
NOTES = {'C':0,'C#':1,'Db':1,'D':2,'D#':3,'Eb':3,'E':4,'F':5,'F#':6,'Gb':6,'G':7,'G#':8,'Ab':8,'A':9,'A#':10,'Bb':10,'B':11}

def freq(key, octave, semis=0):
    n = NOTES.get(key, 9) + semis + 12 * (octave + 1)
    return 440.0 * 2 ** ((n - 69) / 12)

def kick(dur=0.35, punch=1.0):
    out = []
    for i in range(int(SR * dur)):
        t = i / SR
        f = 45 + 110 * math.exp(-t * 30)
        out.append(math.sin(2 * math.pi * f * t) * math.exp(-t * 7) * punch)
    return out

def snare(dur=0.22, rnd=random):
    return [((rnd.random() * 2 - 1) * 0.7 + math.sin(2 * math.pi * 190 * i / SR) * 0.3) * math.exp(-i / SR * 18) for i in range(int(SR * dur))]

def clap(dur=0.18, rnd=random):
    out = []
    for i in range(int(SR * dur)):
        t = i / SR
        env = sum(math.exp(-(t - o) * 60) for o in (0, 0.012, 0.024) if t >= o)
        out.append((rnd.random() * 2 - 1) * min(env, 1.0) * 0.8)
    return out

def hat(dur=0.05, open_=False, rnd=random):
    d = 0.25 if open_ else dur
    prev, out = 0.0, []
    for i in range(int(SR * d)):
        n = rnd.random() * 2 - 1
        hp = n - prev; prev = n
        out.append(hp * math.exp(-i / SR * (9 if open_ else 60)) * 0.35)
    return out

def tone(f, dur, kind='sine', decay=3.0, glide=0.0):
    out, ph = [], 0.0
    for i in range(int(SR * dur)):
        t = i / SR
        ff = f * (1 + glide * math.exp(-t * 12))
        ph += 2 * math.pi * ff / SR
        if kind == 'saw': v = 2 * ((ph / (2 * math.pi)) % 1) - 1
        elif kind == 'square': v = 1.0 if math.sin(ph) > 0 else -1.0
        elif kind == 'log':  # amapiano log drum: sine with pitch drop + soft clip
            v = math.tanh(2.2 * math.sin(ph))
        else: v = math.sin(ph) + 0.25 * math.sin(2 * ph)
        a = min(1.0, t * 200)
        out.append(v * a * math.exp(-t * decay))
    return out

def chord_pad(fs, dur):
    out = [0.0] * int(SR * dur)
    for f in fs:
        for i, v in enumerate(tone(f, dur, 'sine', decay=0.6)):
            out[i] += v * 0.12
    return out

PATTERNS = {
    # 16 steps per bar: k=kick s=snare c=clap h=hat o=open hat
    'trap':    dict(bpm=140, k='x.....x...x.....', s='........x.......', c='', h='x.x.x.x.xxx.x.xx', o='', bass='808', scale=[0,3,5,7,10]),
    'boombap': dict(bpm=90,  k='x.....x..x......', s='....x.......x...', c='', h='x.x.x.x.x.x.x.x.', o='', bass='saw', scale=[0,3,5,7,10]),
    'afro':    dict(bpm=112, k='x...x...x...x...', s='', c='....x.......x...', h='..x...x...x...x.', o='......x.......x.', bass='log', scale=[0,2,3,7,9]),
    'house':   dict(bpm=124, k='x...x...x...x...', s='', c='....x.......x...', h='..x...x...x...x.', o='..x...x...x...x.', bass='saw', scale=[0,3,7,10]),
    'lofi':    dict(bpm=80,  k='x......x..x.....', s='....x.......x...', c='', h='x.x.x.x.x.x.x.x.', o='', bass='sine', scale=[0,3,5,7,10]),
}

def mix_in(buf, sig, start, gain=1.0):
    for j, v in enumerate(sig):
        k = start + j
        if k >= len(buf): break
        buf[k] += v * gain

def make(style='trap', bpm=None, bars=8, key='A', out='beat.wav', seed=None, swing=None):
    p = PATTERNS.get(style, PATTERNS['trap'])
    rnd = random.Random(seed)
    bpm = bpm or p['bpm']
    step = 60.0 / bpm / 4
    if swing is None: swing = 0.12 if style in ('lofi', 'boombap', 'afro') else 0.0
    total = int(SR * step * 16 * bars) + SR
    buf = [0.0] * total
    K, S, C = kick(), snare(rnd=rnd), clap(rnd=rnd)
    H, O = hat(rnd=rnd), hat(open_=True, rnd=rnd)
    prog = [0, -4, -2, -5] if 3 in p['scale'] else [0, 5, 7, 5]
    for bar in range(bars):
        root = prog[bar % 4]
        for s in range(16):
            off = step * (16 * bar + s) + (step * swing if s % 2 else 0)
            i = int(off * SR)
            if p['k'][s] == 'x': mix_in(buf, K, i, 0.95)
            if p['s'] and p['s'][s] == 'x': mix_in(buf, S, i, 0.5)
            if p['c'] and p['c'][s] == 'x': mix_in(buf, C, i, 0.5)
            if p['h'][s] == 'x': mix_in(buf, H, i, 0.55 + rnd.random() * 0.25)
            if p['o'] and p['o'][s] == 'x': mix_in(buf, O, i, 0.35)
            if style == 'trap' and s in (13, 14, 15) and bar % 2 and rnd.random() < 0.5:  # hat rolls
                for r in range(3): mix_in(buf, H, i + int(step * SR * r / 3), 0.4)
            # bass on the kick (and a few off-beats)
            if p['k'][s] == 'x' or (style == 'afro' and s in (3, 11) and rnd.random() < 0.6):
                semis = root + (rnd.choice(p['scale']) if rnd.random() < 0.3 else 0)
                f = freq(key, 1, semis)
                kind = {'808': 'sine', 'saw': 'saw', 'log': 'log', 'sine': 'sine'}[p['bass']]
                dur = step * (6 if p['bass'] == '808' else 3)
                mix_in(buf, tone(f, dur, kind, decay=2.5 if p['bass'] == '808' else 5.0, glide=0.4 if p['bass'] in ('808', 'log') else 0.0), i, 0.5 if kind == 'saw' else 0.8)
        if style in ('lofi', 'house', 'afro'):
            pad_root = freq(key, 3, root)
            third = 3 if 3 in p['scale'] else 4
            mix_in(buf, chord_pad([pad_root, pad_root * 2 ** (third / 12), pad_root * 2 ** (7 / 12)], step * 16), int(step * 16 * bar * SR), 1.0)
    peak = max(1e-6, max(abs(v) for v in buf))
    g = 0.9 / peak
    out = os.path.expanduser(out)
    with wave.open(out, 'w') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1.0, min(1.0, math.tanh(v * g * 1.2))) * 32000)) for v in buf))
    return out

if __name__ == '__main__':
    a = argparse.ArgumentParser()
    a.add_argument('--style', default='trap'); a.add_argument('--bpm', type=int); a.add_argument('--bars', type=int, default=8)
    a.add_argument('--key', default='A'); a.add_argument('--out', default='/root/phone/beat.wav' if os.path.isdir('/root/phone') else 'beat.wav'); a.add_argument('--seed', type=int)
    x = a.parse_args()
    print('saved', make(x.style, x.bpm, x.bars, x.key, x.out, x.seed))
""".trimStart()
}
