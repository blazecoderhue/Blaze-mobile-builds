package com.blaze.assistant.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.34.0 agent tool `gallery_ops`: the Gallery's new tools from the chat. Clean-up report (near-duplicates and blurry
 * photos, never deletes by itself: the user confirms in Gallery › Clean up), On this day, the Trash, and a backup plan.
 */
object GalleryOps {
    const val TOOL = "gallery_ops"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Blaze Gallery tools. action: clean_up (finds near-duplicate and blurry photos and how much space they take; nothing is " +
            "deleted: tell the user to open Gallery and tap the Clean up (magic wand) button to review and move them to Trash), on_this_day (photos " +
            "from today's date in earlier years), trash (what's in the 30-day Trash), backup_plan (how much there is to back up and which cloud sizes fit).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("clean_up", "on_this_day", "trash", "backup_plan")))))
            .put("required", JSONArray(listOf("action")))))

    fun describe(args: JSONObject): String = when (args.optString("action")) {
        "clean_up" -> "Looking for duplicates and blurry photos"; "backup_plan" -> "Working out a backup plan"; else -> "Looking in the Gallery"
    }

    private suspend fun everything(ctx: Context): List<GalleryModel.Entry> = withContext(Dispatchers.IO) {
        GalleryRepo.init(ctx)
        if (GalleryRepo.phone.value.isEmpty() && GalleryRepo.blaze.value.isEmpty()) { GalleryRepo.refresh(); kotlinx.coroutines.delay(2500) }
        GalleryModel.merge(GalleryRepo.blaze.value, GalleryRepo.phone.value)
    }

    suspend fun run(ctx: Context, args: JSONObject): String {
        val all = everything(ctx)
        val now = System.currentTimeMillis()
        return when (args.optString("action")) {
            "clean_up" -> {
                val scans = GalleryClean.scan(all, 1500)
                val d = GalleryTools.duplicates(scans); val b = GalleryTools.blurry(scans)
                "Checked ${scans.size} photos: ${d.size} groups of near-duplicates (${d.sumOf { it.size - 1 }} extra copies, ${GalleryModel.size(GalleryTools.savings(d))}), " +
                    "${b.size} blurry. Nothing was deleted; open Gallery and tap Clean up to review them and move them to the Trash."
            }
            "on_this_day" -> GalleryTools.onThisDay(all, now).joinToString("\n") { (y, l) -> "${GalleryTools.yearsAgo(y, now)}: ${l.size} (${l.take(3).joinToString(", ") { it.title }})" }
                .ifBlank { "Nothing from this date in earlier years." }
            "trash" -> GalleryClean.trashed(ctx).let { t -> if (t.isEmpty()) "The Gallery Trash is empty." else
                "${t.size} in the Trash: " + t.take(10).joinToString("; ") { "${it.title} (${GalleryTools.daysLeft(it, now)} days left)" } }
            "backup_plan" -> GalleryTools.plan(all, now).let { p ->
                "Total ${GalleryModel.size(p.totalBytes)} (photos ${GalleryModel.size(p.photos)}, videos ${GalleryModel.size(p.videos)}, music ${GalleryModel.size(p.music)}); " +
                    "grows ~${GalleryModel.size(p.perMonth)}/month.\n" + p.lines.joinToString("\n") }
            else -> "Unknown action."
        }
    }
}
