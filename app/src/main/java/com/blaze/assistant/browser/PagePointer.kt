package com.blaze.assistant.browser

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject

/**
 * v0.24.6: the summonable Blaze pointer for web pages (the in-app browser and web-based views).
 * Pure parts (tested in PagePointerTest): what kind of element the tip is on, which actions fit it, where the
 * action menu goes so it never leaves the screen, and which media URLs can be downloaded.
 */
object PagePointer {
    enum class Kind { VIDEO, AUDIO, IMAGE, LINK, TEXT, OTHER }

    enum class Action { DOWNLOAD, COPY_LINK, PIP, SAVE_IMAGE, COPY_IMAGE_LINK, SEARCH_IMAGE, OPEN, OPEN_BG, SHARE, COPY_TEXT, TRANSLATE, EXPLAIN, SUMMARISE, ASK }

    /** What the pointer's tip is on. Rect in WebView pixels (l, t, w, h). [sel] = the page's current text selection. */
    data class Hit(val kind: Kind, val tag: String = "", val src: String = "", val href: String = "", val text: String = "",
                   val alt: String = "", val l: Float = 0f, val t: Float = 0f, val w: Float = 0f, val h: Float = 0f, val sel: String = "") {
        /** The words the text actions work on: the selection if there is one, else the element's text. */
        val words: String get() = sel.ifBlank { text }.trim()
        val describe: String get() = when (kind) {
            Kind.VIDEO -> "Video"; Kind.AUDIO -> "Audio"; Kind.IMAGE -> alt.ifBlank { "Image" }.take(40)
            Kind.LINK -> text.ifBlank { href }.take(40); Kind.TEXT -> words.take(40); Kind.OTHER -> "<$tag>"
        }
    }

    /** Element type → the actions in its menu, in order. Every element ends with Ask Blaze. */
    fun actionsFor(kind: Kind): List<Action> = when (kind) {
        Kind.VIDEO -> listOf(Action.DOWNLOAD, Action.COPY_LINK, Action.PIP, Action.ASK)
        Kind.AUDIO -> listOf(Action.DOWNLOAD, Action.COPY_LINK, Action.ASK)
        Kind.IMAGE -> listOf(Action.SAVE_IMAGE, Action.COPY_IMAGE_LINK, Action.ASK, Action.SEARCH_IMAGE)
        Kind.LINK -> listOf(Action.OPEN, Action.OPEN_BG, Action.COPY_LINK, Action.SHARE, Action.ASK)
        Kind.TEXT -> listOf(Action.COPY_TEXT, Action.TRANSLATE, Action.EXPLAIN, Action.SUMMARISE, Action.ASK)
        Kind.OTHER -> listOf(Action.ASK)
    }

    /** Short label (fits a 76 dp tile). */
    fun label(a: Action, kind: Kind): String = when (a) {
        Action.DOWNLOAD -> "Download"; Action.COPY_LINK -> "Copy link"; Action.PIP -> "Pop out"
        Action.SAVE_IMAGE -> "Save"; Action.COPY_IMAGE_LINK -> "Copy image link"; Action.SEARCH_IMAGE -> "Search image"
        Action.OPEN -> "Open"; Action.OPEN_BG -> "Open in background"; Action.SHARE -> "Share"
        Action.COPY_TEXT -> "Copy"; Action.TRANSLATE -> "Translate"; Action.EXPLAIN -> "Explain"; Action.SUMMARISE -> "Summarise"
        Action.ASK -> if (kind == Kind.IMAGE) "Ask Blaze" else "Ask Blaze about this"
    }

    /** Spoken description for TalkBack. */
    fun spoken(a: Action, kind: Kind): String = when (a) {
        Action.PIP -> "Picture-in-picture"
        else -> label(a, kind)
    }

    // ---------------- menu layout ----------------

    const val TILE_W = 84f
    const val TILE_H = 76f
    const val PAD = 10f
    const val HEAD = 30f

    /** Columns for [n] actions: one row up to 4, else two rows (3 columns for 5–6). Never more than 4 across. */
    fun columns(n: Int): Int = when { n <= 4 -> n.coerceAtLeast(1); else -> ((n + 1) / 2).coerceAtMost(4) }

    /** Menu size in dp for [n] actions, shrunk to fit [maxW] dp (tiles narrow down, never below 64 dp). */
    fun menuSize(n: Int, maxW: Float = Float.MAX_VALUE): Pair<Float, Float> {
        val cols = columns(n); val rows = (n + cols - 1) / cols
        val tile = ((maxW - PAD * 2) / cols).coerceIn(64f, TILE_W)
        return (tile * cols + PAD * 2) to (TILE_H * rows + PAD * 2 + HEAD)
    }

    /** Where the menu goes: (x, y) of its top-left plus whether it sits below the element. */
    data class Placement(val x: Float, val y: Float, val below: Boolean)

    /**
     * Places a [mw]×[mh] menu for an element spanning [elTop]..[elBottom] with the pointer tip at ([ax], [ay]) on a
     * [sw]×[sh] area. Prefers just below the element, then just above it; for an element taller than the room
     * (a page-sized div, a full-width video) it goes next to the tip instead, flipping above when there's no room
     * below. Horizontally it centres on the tip and is clamped [margin] inside the edges. Always fully on screen
     * (when it fits at all).
     */
    fun place(ax: Float, ay: Float, elTop: Float, elBottom: Float, mw: Float, mh: Float, sw: Float, sh: Float,
              margin: Float, gap: Float): Placement {
        val maxY = (sh - margin - mh).coerceAtLeast(margin)
        val belowEl = elBottom + gap
        val aboveEl = elTop - gap - mh
        val (y, below) = when {
            belowEl + mh <= sh - margin && belowEl >= margin -> belowEl to true
            aboveEl >= margin && elTop <= sh -> aboveEl to false
            ay + gap * 2 + mh <= sh - margin -> (ay + gap * 2) to true
            ay - gap * 2 - mh >= margin -> (ay - gap * 2 - mh) to false
            else -> ((sh - mh) / 2f).coerceIn(margin, maxY) to true
        }
        val maxX = (sw - margin - mw).coerceAtLeast(margin)
        val x = (ax - mw / 2f).coerceIn(margin, maxX)
        return Placement(x, y.coerceIn(margin, maxY), below)
    }

    // ---------------- downloads ----------------

    /**
     * Null when [url] is a plain file the page exposes that DownloadManager can fetch; else the reason it can't.
     * Streams (blob:, MediaSource, HLS / DASH manifests) and anything protected are refused: Blaze never works
     * around DRM or a site's streaming protection.
     */
    fun cantDownload(url: String): String? {
        val u = url.trim()
        val low = u.lowercase()
        return when {
            u.isBlank() -> "This element doesn't expose a file to download."
            low.startsWith("blob:") || low.startsWith("mediasource:") -> "This video is streamed in pieces (blob:), so there's no file to download. Blaze doesn't get around streaming protection."
            low.startsWith("data:") -> "This is embedded in the page itself; use Copy instead."
            !(low.startsWith("http://") || low.startsWith("https://")) -> "Only web files (http/https) can be downloaded."
            Regex("""\.(m3u8|mpd|ism)(\?|#|$)""").containsMatchIn(low) || low.contains("/manifest") -> "This is a streaming playlist, not a file. Blaze doesn't download streams."
            else -> null
        }
    }

    // ---------------- page side ----------------

    /** Hit-tests the element under view pixel ([px], [py]) and returns its kind, URLs, text and rect in view pixels. */
    fun hitJs(px: Float, py: Float): String = HIT_JS.replace("__PX__", px.toString()).replace("__PY__", py.toString())

    private const val HIT_JS = """(function(px,py){try{
var d=window.devicePixelRatio||1,vv=window.visualViewport,s=vv?vv.scale:1,ox=vv?vv.offsetLeft:0,oy=vv?vv.offsetTop:0;
var x=ox+px/(d*s),y=oy+py/(d*s);
var el=document.elementFromPoint(x,y);if(!el)return null;
function up(e,f){while(e&&e.tagName&&e!==document.documentElement){if(f(e))return e;e=e.parentElement;}return null;}
var media=up(el,function(e){return e.tagName=='VIDEO'||e.tagName=='AUDIO';});
if(!media&&document.elementsFromPoint){var all=document.elementsFromPoint(x,y);for(var i=0;i<all.length&&i<8;i++){if(all[i].tagName=='VIDEO'||all[i].tagName=='AUDIO'){media=all[i];break;}}}
var img=media?null:up(el,function(e){return e.tagName=='IMG'||e.tagName=='PICTURE';});
var link=up(el,function(e){return e.tagName=='A'&&!!e.href;});
var kind='other',t=el,src='',href=link?link.href:'';
if(media){t=media;kind=media.tagName=='VIDEO'?'video':'audio';src=media.currentSrc||media.src||'';if(!src){var so=media.querySelector('source[src]');if(so)src=so.src;}}
else if(img){t=img.tagName=='PICTURE'?(img.querySelector('img')||img):img;kind='image';src=t.currentSrc||t.src||'';}
else if(link){t=link;kind='link';}
else{var tg=el.tagName;var txt=(tg=='INPUT'||tg=='TEXTAREA'||tg=='SELECT')?'':(el.innerText||el.textContent||'').trim();
 if(txt.length>0&&el!==document.body){kind='text';}
 else{var bg=getComputedStyle(el).backgroundImage;var m=bg&&bg.match(/url\(["']?([^"')]+)/);if(m){kind='image';src=m[1];}}}
if(kind=='image'&&!src)kind='other';
var r=t.getBoundingClientRect();
var text=(kind=='text'||kind=='link')?(t.innerText||t.textContent||'').trim().slice(0,1500):'';
var alt=(t.getAttribute&&(t.getAttribute('alt')||t.getAttribute('aria-label')||t.getAttribute('title')))||'';
var sel='';try{sel=String(window.getSelection()||'').slice(0,1500);}catch(e){}
return {kind:kind,tag:(t.tagName||'').toLowerCase(),src:src,href:href,text:text,alt:alt.slice(0,200),
l:(r.left-ox)*s*d,t:(r.top-oy)*s*d,w:r.width*s*d,h:r.height*s*d,sel:sel};
}catch(e){return null;}})(__PX__,__PY__)"""

    /** Parses what [hitJs] returned (evaluateJavascript's JSON text); null for "null" or junk. */
    fun parse(json: String?): Hit? {
        if (json.isNullOrBlank() || json == "null") return null
        return try {
            val o = JSONObject(json)
            val kind = when (o.optString("kind")) {
                "video" -> Kind.VIDEO; "audio" -> Kind.AUDIO; "image" -> Kind.IMAGE; "link" -> Kind.LINK; "text" -> Kind.TEXT; else -> Kind.OTHER
            }
            Hit(kind, o.optString("tag"), o.optString("src"), o.optString("href"), o.optString("text"), o.optString("alt"),
                o.optDouble("l", 0.0).toFloat(), o.optDouble("t", 0.0).toFloat(), o.optDouble("w", 0.0).toFloat(), o.optDouble("h", 0.0).toFloat(),
                o.optString("sel"))
        } catch (_: Exception) { null }
    }

    /** Plays the video under the tip and scrolls it to the middle (before picture-in-picture). */
    fun focusVideoJs(px: Float, py: Float): String = """(function(px,py){try{var d=window.devicePixelRatio||1,vv=window.visualViewport,s=vv?vv.scale:1;
var x=(vv?vv.offsetLeft:0)+px/(d*s),y=(vv?vv.offsetTop:0)+py/(d*s);var all=document.elementsFromPoint(x,y);
for(var i=0;i<all.length;i++){if(all[i].tagName=='VIDEO'){var v=all[i];v.scrollIntoView({block:'center'});v.play();return true;}}}catch(e){}return false;})($px,$py)"""

    /** The prompt for a text action or Ask Blaze. */
    fun prompt(a: Action, h: Hit, pageTitle: String, pageUrl: String): String {
        val where = "on the page \u201c${pageTitle.take(80)}\u201d ($pageUrl)"
        return when (a) {
            Action.EXPLAIN -> "Explain this text $where in simple words:\n\n\u201c${h.words.take(1200)}\u201d"
            Action.SUMMARISE -> "Summarise this text $where in a few bullet points:\n\n\u201c${h.words.take(1500)}\u201d"
            else -> when (h.kind) {
                Kind.IMAGE -> "Tell me about this image $where. Its address is ${h.src.take(300)}" + (if (h.alt.isNotBlank()) " and its description is \u201c${h.alt}\u201d." else ".")
                Kind.LINK -> "What is this link $where? Text: \u201c${h.text.take(200)}\u201d, address: ${h.href.take(300)}"
                Kind.VIDEO, Kind.AUDIO -> "Tell me about this ${h.kind.name.lowercase()} $where (${h.src.take(200).ifBlank { "streamed" }})."
                Kind.TEXT -> "About this part $where:\n\n\u201c${h.words.take(1200)}\u201d"
                Kind.OTHER -> "Tell me about the <${h.tag}> element I'm pointing at $where."
            }
        }
    }
}

/** Runs a pointer action. Downloads only on the user's tap, only plain files the page exposes. */
object PointerActions {
    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun copy(ctx: Context, label: String, s: String) {
        if (s.isBlank()) { toast(ctx, "Nothing to copy here."); return }
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, s))
        toast(ctx, "Copied")
    }

    fun download(ctx: Context, url: String, web: WebView?, isPrivate: Boolean) {
        PagePointer.cantDownload(url)?.let { toast(ctx, it); return }
        try {
            val name = URLUtil.guessFileName(url, null, null)
            val req = DownloadManager.Request(Uri.parse(url)).setTitle(name).setDescription("Downloading with Blaze")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!isPrivate) CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            web?.settings?.userAgentString?.let { req.addRequestHeader("User-Agent", it) }
            web?.url?.takeIf { it.startsWith("http") }?.let { req.addRequestHeader("Referer", it) }
            ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            toast(ctx, "Downloading $name")
        } catch (e: Exception) { toast(ctx, "Download failed: ${e.message}") }
    }

    fun share(ctx: Context, s: String) {
        try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    fun translateUrl(text: String, lang: String = java.util.Locale.getDefault().language.ifBlank { "en" }): String =
        "https://translate.google.com/?sl=auto&tl=$lang&op=translate&text=" + Uri.encode(text.take(1500))

    fun lensUrl(src: String): String = "https://lens.google.com/uploadbyurl?url=" + Uri.encode(src)
}
