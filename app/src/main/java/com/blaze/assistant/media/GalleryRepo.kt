package com.blaze.assistant.media

import android.Manifest
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.blaze.assistant.media.GalleryModel.Entry
import com.blaze.assistant.media.GalleryModel.Src
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * v0.30.0 Gallery, Android side: reads shared storage (MediaStore) next to Blaze's own gallery folder, keeps the
 * user's albums, makes thumbnails / cover art / durations, and does share, delete, rename and set-as-wallpaper.
 *
 * Without any storage permission Android still shows Blaze everything Blaze itself saved to shared storage (songs and
 * videos it downloaded to Music/Blaze and Movies/Blaze, Download/Blaze, Pictures/Blaze). Screenshots and Camera need
 * the "Photos and videos" permission, which the Albums tab offers.
 */
object GalleryRepo {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phone = MutableStateFlow<List<Entry>>(emptyList())
    val phone: StateFlow<List<Entry>> = _phone
    private val _blaze = MutableStateFlow<List<Entry>>(emptyList())
    val blaze: StateFlow<List<Entry>> = _blaze
    private val _albums = MutableStateFlow<List<AlbumStore.Album>>(emptyList())
    val albums: StateFlow<List<AlbumStore.Album>> = _albums
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var store: AlbumStore? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Media.init(app)
        store = AlbumStore(File(app.filesDir, "gallery_albums.json"))
        _albums.value = store!!.albums
        scope.launch { Media.items.collect { list -> _blaze.value = list.map { blazeEntry(it) } } }
    }

    private fun albumStore(): AlbumStore = store ?: AlbumStore(null).also { store = it }

    // ---------------------------------------------------------------- permissions

    fun phonePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** True once the phone lets Blaze see Screenshots and Camera (full or "selected photos" access). */
    fun canReadPhone(): Boolean {
        if (!::app.isInitialized) return false
        val granted = phonePermissions().any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
        val partial = Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(app, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
        return granted || partial
    }

    // ---------------------------------------------------------------- loading

    private var refreshJob: Job? = null

    fun refresh() {
        if (!::app.isInitialized) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _loading.value = true
            try {
                Media.refresh()
                _phone.value = queryPhone()
            } catch (_: Exception) { } finally { _loading.value = false }
        }
    }

    private val metaCache = HashMap<String, Triple<Long, Int, Int>>()   // key path@modified → duration, w, h

    private fun blazeEntry(it: Media.Item): Entry {
        val f = it.file
        val key = f.absolutePath + "@" + f.lastModified()
        val (dur, w, h) = synchronized(metaCache) { metaCache[key] } ?: run {
            val t = when (it.kind) {
                "image" -> runCatching {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, o); Triple(0L, o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0))
                }.getOrDefault(Triple(0L, 0, 0))
                else -> retrieve { r -> r.setDataSource(f.absolutePath); metaOf(r) } ?: Triple(0L, 0, 0)
            }
            synchronized(metaCache) { metaCache[key] = t }; t
        }
        return Entry(id = "file:" + f.absolutePath, name = f.name, title = it.title, kind = it.kind, src = Src.BLAZE, time = it.time,
            size = f.length(), durationMs = dur, width = w, height = h, folder = "Blaze gallery")
    }

    private fun metaOf(r: MediaMetadataRetriever): Triple<Long, Int, Int> = Triple(
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0)

    private fun <T> retrieve(block: (MediaMetadataRetriever) -> T): T? {
        val r = MediaMetadataRetriever()
        return try { block(r) } catch (_: Throwable) { null } finally { try { r.release() } catch (_: Throwable) { } }
    }

    private fun queryPhone(): List<Entry> {
        val cr = app.contentResolver
        val files = MediaStore.Files.getContentUri("external")
        val q = Build.VERSION.SDK_INT >= 29
        val all = canReadPhone()
        val proj = ArrayList(listOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT))
        if (q) proj += listOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DURATION)
        else proj.add(MediaStore.MediaColumns.DATA)
        val types = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val args = ArrayList<String>()
        val where = if (q) {
            val ors = arrayListOf("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            args += "%Blaze%"; args += app.packageName
            if (all) { ors += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"; args += "%Screenshots%"; ors += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"; args += "DCIM/%" }
            "$types AND (" + ors.joinToString(" OR ") + ")"
        } else {
            val d = MediaStore.MediaColumns.DATA
            args += "%/Blaze/%"; args += "%/Screenshots/%"; args += "%/DCIM/%"
            "$types AND ($d LIKE ? OR $d LIKE ? OR $d LIKE ?)"
        }
        val root = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/storage/emulated/0")
        val out = ArrayList<Entry>()
        try {
            cr.query(files, proj.toTypedArray(), where, args.toTypedArray(), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                fun col(n: String) = c.getColumnIndex(n)
                val iId = col(MediaStore.MediaColumns._ID); val iName = col(MediaStore.MediaColumns.DISPLAY_NAME); val iSize = col(MediaStore.MediaColumns.SIZE)
                val iMod = col(MediaStore.MediaColumns.DATE_MODIFIED); val iAdd = col(MediaStore.MediaColumns.DATE_ADDED)
                val iType = col(MediaStore.Files.FileColumns.MEDIA_TYPE); val iW = col(MediaStore.MediaColumns.WIDTH); val iH = col(MediaStore.MediaColumns.HEIGHT)
                val iRel = if (q) col(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val iOwn = if (q) col(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) else -1
                val iDur = if (q) col(MediaStore.MediaColumns.DURATION) else -1
                val iData = if (!q) col(MediaStore.MediaColumns.DATA) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val (kind, base) = when (c.getInt(iType)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> "audio" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> "video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                    val name = c.getString(iName) ?: continue
                    val rel = if (iRel >= 0) c.getString(iRel).orEmpty()
                        else c.getString(iData).orEmpty().removePrefix(root).trimStart('/').substringBeforeLast('/', "") + "/"
                    var src = GalleryModel.sourceOf(rel, kind)
                    if (src == Src.OTHER && iOwn >= 0 && c.getString(iOwn) == app.packageName)
                        src = when (kind) { "audio" -> Src.MUSIC; "video" -> Src.VIDEOS; else -> Src.PICTURES }
                    if (src == Src.OTHER) continue
                    if (!all && (src == Src.SCREENSHOTS || src == Src.CAMERA)) continue
                    val mod = c.getLong(iMod).takeIf { it > 0 } ?: c.getLong(iAdd)
                    out += Entry(id = ContentUris.withAppendedId(base, id).toString(), name = name,
                        title = name.substringBeforeLast('.').replace('_', ' ').ifBlank { name }, kind = kind, src = src, time = mod * 1000,
                        size = c.getLong(iSize), durationMs = if (iDur >= 0) c.getLong(iDur) else 0,
                        width = if (iW >= 0) c.getInt(iW) else 0, height = if (iH >= 0) c.getInt(iH) else 0, folder = rel)
                    // v0.34.0 big camera rolls (10k+): the grid fills page by page (first 600, then every 2000) while the rest loads.
                    val n = out.size
                    if (n == 600 || (n > 600 && (n - 600) % 2000 == 0)) _phone.value = out.toList()
                }
            }
        } catch (_: Exception) { }
        // Durations the index doesn't have yet (just-downloaded files) come from the file itself: only Blaze's own
        // music/video/downloads and at most 150 of them, so a big Camera roll never stalls the first load.
        var budget = 150
        return out.map { e ->
            if (e.kind == "image" || e.durationMs > 0 || e.src == Src.CAMERA || e.src == Src.SCREENSHOTS || budget-- <= 0) e
            else retrieve { r -> r.setDataSource(app, Uri.parse(e.id)); metaOf(r) }?.let { (d, w, h) ->
                e.copy(durationMs = d, width = if (e.width > 0) e.width else w, height = if (e.height > 0) e.height else h)
            } ?: e
        }
    }

    /** Re-reads shared storage a moment after anything in it changes (a download finishing, a screenshot). */
    fun watch(): () -> Unit {
        if (!::app.isInitialized) return {}
        var pending: Job? = null
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                pending?.cancel(); pending = scope.launch { delay(800); refresh() }
            }
        }
        return try {
            app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, obs)
            ({ try { app.contentResolver.unregisterContentObserver(obs) } catch (_: Exception) { }; pending?.cancel() })
        } catch (_: Exception) { {} }
    }

    // ---------------------------------------------------------------- uris and pictures

    fun uri(e: Entry): Uri = if (e.isFile) FileProvider.getUriForFile(app, app.packageName + ".files", File(e.path)) else Uri.parse(e.id)

    /** Uri for in-app playback (MediaPlayer / VideoView read the file directly, no grant needed). */
    fun playUri(e: Entry): Uri = if (e.isFile) Uri.fromFile(File(e.path)) else Uri.parse(e.id)

    fun mime(e: Entry): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.name.substringAfterLast('.', "").lowercase())
        ?: when (e.kind) { "image" -> "image/*"; "audio" -> "audio/*"; "video" -> "video/*"; else -> "*/*" }

    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt().coerceIn(8 shl 20, 48 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val noArt = HashSet<String>()

    fun cachedThumb(e: Entry, px: Int): Bitmap? = thumbs.get(e.id + "@" + px)

    /** A small picture for a tile: the photo, a video frame, or a song's cover art. Null when there is none. */
    suspend fun thumb(e: Entry, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = e.id + "@" + px
        thumbs.get(key)?.let { return@withContext it }
        if (synchronized(noArt) { key in noArt }) return@withContext null
        // At most 3 decodes at once so a fast fling on a low-end phone never starves the UI thread's CPU;
        // tiles that scroll away cancel their request before it starts.
        val b = decodeGate.withPermit {
            thumbs.get(key) ?: runCatching { diskThumb(e, px) ?: loadThumb(e, px)?.also { saveDiskThumb(e, px, it) } }.getOrNull()
        }
        if (b != null) thumbs.put(key, b) else synchronized(noArt) { noArt += key }
        b
    }

    private val decodeGate = Semaphore(3)

    /**
     * Thumbnails of Blaze's own files (and song covers) are kept as small JPEGs in cache/gallery_thumbs, so the second
     * visit doesn't decode a 1024 px picture or open an MP3 again. Shared-storage pictures use Android's own thumbnail
     * cache (loadThumbnail) and skip this.
     */
    private fun diskKey(e: Entry, px: Int): File? {
        if (!e.isFile && e.kind == "image") return null
        val stamp = if (e.isFile) File(e.path).lastModified() else e.time
        val dir = File(app.cacheDir, "gallery_thumbs").apply { mkdirs() }
        return File(dir, Integer.toHexString(e.id.hashCode()) + "_" + e.id.length + "_" + px + "_" + stamp + ".jpg")
    }

    private fun diskThumb(e: Entry, px: Int): Bitmap? {
        val f = diskKey(e, px)?.takeIf { it.exists() && it.length() > 0 } ?: return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun saveDiskThumb(e: Entry, px: Int, b: Bitmap) {
        val f = diskKey(e, px) ?: return
        runCatching {
            val small = scale(b, px)
            f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
    }

    private fun loadThumb(e: Entry, px: Int): Bitmap? {
        val cr = app.contentResolver
        if (!e.isFile && Build.VERSION.SDK_INT >= 29) {
            runCatching { cr.loadThumbnail(Uri.parse(e.id), Size(px, px), null) }.getOrNull()?.let { return it }
        }
        return when (e.kind) {
            "image" -> decode(e, px)
            "video" -> if (e.isFile && Build.VERSION.SDK_INT >= 29) runCatching { ThumbnailUtils.createVideoThumbnail(File(e.path), Size(px, px), null) }.getOrNull()
                else retrieve { r -> src(r, e); r.getFrameAtTime(1_000_000) }?.let { scale(it, px) }
            "audio" -> retrieve { r -> src(r, e); r.embeddedPicture }?.let { bytes -> decodeBytes(bytes, px) } ?: sidecarArt(e, px)
            else -> null
        }
    }

    /** A Blaze-made song can carry its art as "name.jpg"/"name.png" next to it. */
    private fun sidecarArt(e: Entry, px: Int): Bitmap? {
        if (!e.isFile) return null
        val f = File(e.path)
        val pic = listOf("jpg", "jpeg", "png", "webp").map { File(f.parentFile, f.nameWithoutExtension + "." + it) }.firstOrNull { it.exists() } ?: return null
        return decodeFile(pic.absolutePath, px)
    }

    private fun src(r: MediaMetadataRetriever, e: Entry) { if (e.isFile) r.setDataSource(e.path) else r.setDataSource(app, Uri.parse(e.id)) }

    private fun scale(b: Bitmap, px: Int): Bitmap {
        val m = maxOf(b.width, b.height); if (m <= px) return b
        val s = px.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun sample(w: Int, h: Int, px: Int): Int { var s = 1; while (maxOf(w, h) / (s * 2) >= px) s *= 2; return s }

    private fun decodeBytes(bytes: ByteArray, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, px) })
    }

    private fun decodeFile(path: String, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, px) })
    }

    /** A picture at most [px] on its long side, the right way up (ImageDecoder reads the camera's rotation). */
    fun decode(e: Entry, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                val source = if (e.isFile) ImageDecoder.createSource(File(e.path)) else ImageDecoder.createSource(app.contentResolver, Uri.parse(e.id))
                return ImageDecoder.decodeBitmap(source) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    d.setTargetSampleSize(sample(info.size.width, info.size.height, px))
                }
            }
        }
        return runCatching {
            if (e.isFile) decodeFile(e.path, px)
            else {
                val cr = app.contentResolver; val u = Uri.parse(e.id)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, px) }) }
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- actions

    fun shareIntent(list: List<Entry>): Intent {
        val uris = ArrayList(list.map { uri(it) })
        val mimes = list.map { mime(it) }.distinct()
        val type = mimes.singleOrNull() ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        if (uris.isNotEmpty()) send.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Deletes what it can. Shared-storage items Blaze didn't save need the phone's own confirmation: that comes back
     * as an [IntentSender] for the screen to launch (Android 10+); after it, call [refresh].
     */
    suspend fun delete(list: List<Entry>): IntentSender? = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val blocked = ArrayList<Uri>()
        var recover: IntentSender? = null
        for (e in list) {
            if (e.isFile) { Media.delete(File(e.path)); continue }
            val u = Uri.parse(e.id)
            try { cr.delete(u, null, null) }
            catch (ex: SecurityException) {
                blocked += u
                if (Build.VERSION.SDK_INT == 29 && ex is android.app.RecoverableSecurityException && recover == null) recover = ex.userAction.actionIntent.intentSender
            } catch (_: Exception) { }
        }
        albumStore().forget(list.map { it.id }.filter { id -> blocked.none { it.toString() == id } })
        _albums.value = albumStore().albums
        val sender = if (blocked.isNotEmpty() && Build.VERSION.SDK_INT >= 30) runCatching { MediaStore.createDeleteRequest(cr, blocked).intentSender }.getOrNull() else recover
        refresh()
        sender
    }

    /** Renames the file (keeping its extension). Returns null on success or a short reason. */
    suspend fun rename(e: Entry, input: String): String? = withContext(Dispatchers.IO) {
        val newName = GalleryModel.cleanName(input, e.name) ?: return@withContext "That name is empty."
        if (newName == e.name) return@withContext null
        if (e.isFile) {
            val f = File(e.path); val to = File(f.parentFile, newName)
            if (to.exists()) return@withContext "There's already a file called $newName."
            if (!f.renameTo(to)) return@withContext "Couldn't rename it."
            val side = File(f.path + ".json")
            val meta = runCatching { JSONObject(side.readText()) }.getOrNull() ?: JSONObject()
            side.delete()
            File(to.path + ".json").writeText(meta.put("title", newName.substringBeforeLast('.')).toString())
            albumStore().renameItem(e.id, "file:" + to.absolutePath)
        } else {
            try {
                val n = app.contentResolver.update(Uri.parse(e.id), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null)
                if (n <= 0) return@withContext "Couldn't rename it."
            } catch (_: SecurityException) {
                return@withContext "Android only lets Blaze rename files Blaze saved. Rename this one in your phone's Files app."
            } catch (ex: Exception) { return@withContext "Couldn't rename it: ${ex.message}" }
        }
        _albums.value = albumStore().albums
        refresh(); null
    }

    /** [which]: WallpaperManager.FLAG_SYSTEM (home), FLAG_LOCK, or both. Null on success, else a reason. */
    suspend fun setWallpaper(e: Entry, which: Int): String? = withContext(Dispatchers.IO) {
        try {
            val wm = WallpaperManager.getInstance(app)
            val cr = app.contentResolver
            val input = if (e.isFile) File(e.path).inputStream() else cr.openInputStream(Uri.parse(e.id)) ?: return@withContext "Couldn't open the picture."
            input.use { wm.setStream(it, null, true, which) }
            null
        } catch (ex: Exception) { "Couldn't set the wallpaper: ${ex.message ?: ex.javaClass.simpleName}" }
    }

    // ---------------------------------------------------------------- albums

    private fun publish() { _albums.value = albumStore().albums }
    fun createAlbum(name: String, items: List<String> = emptyList()): AlbumStore.Album? = albumStore().create(name, items).also { publish() }
    fun renameAlbum(id: String, name: String): Boolean = albumStore().rename(id, name).also { publish() }
    fun deleteAlbum(id: String): Boolean = albumStore().delete(id).also { publish() }
    fun addToAlbum(id: String, items: List<String>): Boolean = albumStore().add(id, items).also { publish() }
    fun removeFromAlbum(id: String, items: List<String>): Boolean = albumStore().remove(id, items).also { publish() }
    fun moveToAlbum(from: String, to: String, items: List<String>): Boolean = albumStore().move(from, to, items).also { publish() }
}
