package com.blaze.assistant.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v0.33.0 Desktop Bridge: the dashboard grows into Blaze on a big screen. Besides chats (which can still drive the
 * phone), a laptop on the same Wi-Fi gets the phone's clipboard both ways, Blaze's files (Gallery: pictures, music,
 * videos, documents Blaze made) to download, and uploads straight into the phone's Download/Blaze folder. Open it by
 * scanning the QR code in Settings › Dashboard with a laptop/tablet camera, or typing the short link. Every call needs
 * the secret token. Pure path checks in [safeName] / [allowed] (BridgeTest).
 */
object Bridge {
    const val MAX_UPLOAD = 12 * 1024 * 1024

    fun safeName(n: String): String = n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").trim().take(80).ifBlank { "upload.bin" }

    /** A file may be served only from inside one of [roots] (no ../ escapes). */
    fun allowed(f: File, roots: List<File>): Boolean = try {
        val c = f.canonicalFile; roots.any { r -> c.path.startsWith(r.canonicalFile.path + File.separator) } && c.isFile
    } catch (_: Exception) { false }

    fun roots(ctx: Context): List<File> = listOfNotNull(
        try { com.blaze.assistant.media.Media.init(ctx); com.blaze.assistant.media.Media.items.value.firstOrNull()?.file?.parentFile } catch (_: Throwable) { null },
        uploadDir(), File(ctx.filesDir, "mediaapps"))

    fun uploadDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Blaze").apply { mkdirs() }

    fun files(ctx: Context): JSONArray {
        val a = JSONArray()
        try { com.blaze.assistant.media.Media.init(ctx); com.blaze.assistant.media.Media.items.value.take(200).forEach { m ->
            a.put(JSONObject().put("name", m.file.name).put("kind", m.kind).put("title", m.title).put("size", m.file.length()).put("time", m.time).put("path", m.file.absolutePath)) } } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Bridge: Media.initctx ", e) }
        uploadDir().listFiles()?.sortedByDescending { it.lastModified() }?.take(100)?.forEach { f ->
            a.put(JSONObject().put("name", f.name).put("kind", "upload").put("title", f.name).put("size", f.length()).put("time", f.lastModified()).put("path", f.absolutePath)) }
        return a
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var r: T? = null; val l = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { try { r = block() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("Bridge: r block", e) } finally { l.countDown() } }
        l.await(3, TimeUnit.SECONDS); return r
    }

    /** Android only lets the foreground app read the clipboard; null means "open Blaze on the phone first". */
    fun clipboard(ctx: Context): String? = onMain { ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }

    fun setClipboard(ctx: Context, text: String): Boolean = onMain { ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("From your computer", text)); true } == true

    fun upload(name: String, b64: String): String {
        val bytes = try { Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT) } catch (_: Exception) { return "!bad data" }
        if (bytes.size > MAX_UPLOAD) return "!too big (max 12 MB)"
        var f = File(uploadDir(), safeName(name)); var i = 1
        while (f.exists()) { f = File(uploadDir(), safeName(name).let { n -> n.substringBeforeLast('.') + " ($i)" + (if (n.contains('.')) "." + n.substringAfterLast('.') else "") }); i++ }
        f.writeBytes(bytes); return f.name
    }

    fun mime(n: String): String = when (n.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; "pdf" -> "application/pdf"; "txt", "md" -> "text/plain; charset=utf-8"; else -> "application/octet-stream" }

    /** QR code bitmap for the link (zxing, already bundled for Blaze Desktop pairing). */
    fun qr(text: String, size: Int = 640): Bitmap? = try { com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size) } catch (_: Throwable) { null }

    const val CARDS = """<div class="card"><div class="lab" style="margin-bottom:6px">Clipboard</div>
<textarea id="clip" rows="3" style="width:100%;background:transparent;color:var(--ink);border:1px solid var(--hair);font:13px Inter,sans-serif"></textarea>
<div style="display:flex;gap:6px;margin-top:6px"><button onclick="getClip()" style="padding:8px">From phone</button><button onclick="putClip()" style="padding:8px">To phone</button></div><div class="lab" id="clipn"></div></div>
<div class="card"><div class="lab" style="margin-bottom:6px">Files</div><input type="file" id="up" multiple onchange="upl(this.files)" style="font-size:12px"><div class="lab" id="upn"></div>
<div id="files" style="max-height:260px;overflow:auto;margin-top:6px"></div></div>"""

    const val SCRIPT = """
function getClip(){api('/api/clipboard').then(function(r){if(r.text==null){document.getElementById('clipn').textContent='Open Blaze on the phone, then try again (Android only shares the clipboard with the app on screen).'}else{document.getElementById('clip').value=r.text;document.getElementById('clipn').textContent='Copied from phone'}})}
function putClip(){api('/api/clipboard',{method:'POST',body:JSON.stringify({text:document.getElementById('clip').value})}).then(function(r){document.getElementById('clipn').textContent=r.ok?'On the phone clipboard':'Failed'})}
function kb(n){return n>1048576?(n/1048576).toFixed(1)+' MB':Math.max(1,Math.round(n/1024))+' KB'}
function files(){api('/api/files').then(function(l){document.getElementById('files').innerHTML=l.map(function(f){return '<div class="row"><a style="color:var(--ink)" href="/api/file?t='+T+'&path='+encodeURIComponent(f.path)+'" download="'+esc(f.name)+'">'+esc(f.title||f.name)+'</a><small>'+f.kind+' · '+kb(f.size)+'</small></div>'}).join('')})}
function upl(fs){Array.prototype.forEach.call(fs,function(f){var r=new FileReader();r.onload=function(){document.getElementById('upn').textContent='Sending '+f.name+'…';
 api('/api/upload',{method:'POST',body:JSON.stringify({name:f.name,data:r.result})}).then(function(x){document.getElementById('upn').textContent=x.ok?('Saved to Download/Blaze/'+x.name):('Failed: '+x.error);files()})};r.readAsDataURL(f)})}
files();setInterval(files,30000);
"""
}
