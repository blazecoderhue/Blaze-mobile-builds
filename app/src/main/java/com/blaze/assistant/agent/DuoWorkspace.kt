package com.blaze.assistant.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * v0.33.0 Duo Workspaces: Blaze and Halo run a long project over days (a business plan, an app, a thesis). Each
 * workspace has a goal, a shared folder (~/workspaces/<slug>, reachable by the file and terminal tools), a progress
 * board (todo / doing / done, each card owned by Blaze, Halo or you) and a running log. An optional daily standup runs
 * at the time you pick: the duo reports what moved, what's next and what's blocked, does the next card, and posts a
 * short note. Pure board ops in [Board] (DuoWorkspaceTest).
 */
object DuoWorkspace {
    const val TOOL = "duo_workspace"

    data class Card(val id: Int, val text: String, val col: String, val owner: String)
    data class Ws(val slug: String, val name: String, val goal: String, val created: Long, val cards: List<Card>, val log: List<String>, val standup: String)

    object Board {
        val COLS = listOf("todo", "doing", "done")
        fun add(w: Ws, text: String, owner: String): Ws {
            val id = (w.cards.maxOfOrNull { it.id } ?: 0) + 1
            val o = owner.lowercase().takeIf { it in setOf("blaze", "halo", "you") } ?: "blaze"
            return w.copy(cards = w.cards + Card(id, text.trim().take(200), "todo", o))
        }
        fun move(w: Ws, id: Int, col: String): Ws? {
            val c = col.lowercase().let { if (it == "in progress" || it == "doing") "doing" else it }
            if (c !in COLS || w.cards.none { it.id == id }) return null
            return w.copy(cards = w.cards.map { if (it.id == id) it.copy(col = c) else it })
        }
        fun progress(w: Ws): Int = if (w.cards.isEmpty()) 0 else w.cards.count { it.col == "done" } * 100 / w.cards.size
        fun render(w: Ws): String = buildString {
            append("${w.name}: ${w.goal} (${progress(w)}% done)\n")
            COLS.forEach { col -> val l = w.cards.filter { it.col == col }
                append(col.uppercase()).append(": ").append(if (l.isEmpty()) "none" else l.joinToString("; ") { "#${it.id} ${it.text} [${it.owner}]" }).append('\n') }
            if (w.log.isNotEmpty()) append("Recent log: ").append(w.log.takeLast(5).joinToString(" | "))
        }.trim()
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "workspace" }
    }

    private fun dir(): File = File(com.blaze.assistant.terminal.Terminal.home(), "workspaces").apply { mkdirs() }
    private fun file(slug: String) = File(dir(), "$slug/.workspace.json")

    fun load(slug: String): Ws? = try { val o = JSONObject(file(slug).readText())
        val a = o.optJSONArray("cards") ?: JSONArray(); val l = o.optJSONArray("log") ?: JSONArray()
        Ws(slug, o.optString("name"), o.optString("goal"), o.optLong("created"),
            (0 until a.length()).map { a.getJSONObject(it) }.map { Card(it.optInt("id"), it.optString("text"), it.optString("col"), it.optString("owner")) },
            (0 until l.length()).map { l.getString(it) }, o.optString("standup"))
    } catch (_: Exception) { null }

    private fun save(w: Ws) { file(w.slug).apply { parentFile?.mkdirs() }.writeText(JSONObject().put("name", w.name).put("goal", w.goal).put("created", w.created)
        .put("cards", JSONArray(w.cards.map { JSONObject().put("id", it.id).put("text", it.text).put("col", it.col).put("owner", it.owner) }))
        .put("log", JSONArray(w.log.takeLast(60))).put("standup", w.standup).toString()) }

    fun all(): List<Ws> = (dir().listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { load(it.name) }.sortedByDescending { it.created }

    private fun find(q: String): Ws? = all().firstOrNull { it.slug == Board.slug(q) } ?: all().firstOrNull { it.name.contains(q, true) } ?: if (q.isBlank()) all().firstOrNull() else null

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Duo Workspaces: long projects Blaze and Halo run over days. create (name, goal), list, show (board + folder), add (task, owner blaze|halo|you), " +
            "move (id, col todo|doing|done), note (log line), standup (time HH:MM daily, or off). Files live in ~/workspaces/<name>/ for file/terminal tools.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("create", "list", "show", "add", "move", "note", "standup"))))
            .put("workspace", JSONObject().put("type", "string")).put("goal", JSONObject().put("type", "string"))
            .put("task", JSONObject().put("type", "string")).put("owner", JSONObject().put("type", "string"))
            .put("id", JSONObject().put("type", "integer")).put("col", JSONObject().put("type", "string"))
            .put("text", JSONObject().put("type", "string")).put("time", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    fun run(ctx: Context, a: JSONObject): String {
        val ws = a.optString("workspace")
        return when (a.optString("action")) {
            "create" -> { val n = ws.trim().ifBlank { return "Name the workspace." }; val slug = Board.slug(n)
                if (load(slug) != null) return "A workspace called $n already exists."
                val w = Ws(slug, n, a.optString("goal").trim(), System.currentTimeMillis(), emptyList(), listOf("Created"), ""); save(w)
                "Workspace \u201c$n\u201d made, folder ~/workspaces/$slug. Next: break the goal into 5-10 cards with add (split between blaze, halo and you), then offer a daily standup." }
            "list" -> all().joinToString("\n") { "${it.name}: ${Board.progress(it)}% · ${it.cards.size} cards" + (if (it.standup.isNotBlank()) " · standup ${it.standup}" else "") }.ifBlank { "No workspaces yet." }
            "show" -> find(ws)?.let { w -> Board.render(w) + "\nFolder ~/workspaces/${w.slug}: " + (File(dir(), w.slug).list()?.filterNot { it.startsWith(".") }?.take(20)?.joinToString(", ")?.ifBlank { "empty" } ?: "empty") } ?: "No such workspace."
            "add" -> find(ws)?.let { w -> val t = a.optString("task").ifBlank { return "What's the task?" }; val n = Board.add(w, t, a.optString("owner")); save(n); "Added #${n.cards.last().id}." } ?: "No such workspace."
            "move" -> find(ws)?.let { w -> Board.move(w, a.optInt("id"), a.optString("col"))?.let { n -> save(n.copy(log = n.log + "#${a.optInt("id")} → ${a.optString("col")}")); "Moved. ${Board.progress(n)}% done." } ?: "No card #${a.optInt("id")} or bad column." } ?: "No such workspace."
            "note" -> find(ws)?.let { w -> save(w.copy(log = w.log + a.optString("text").take(240))); "Logged." } ?: "No such workspace."
            "standup" -> find(ws)?.let { w ->
                val t = a.optString("time").trim()
                if (t.equals("off", true) || t.isBlank()) { save(w.copy(standup = "")); WorkManager.getInstance(ctx).cancelUniqueWork("standup_${w.slug}"); return "Standup off." }
                val m = Regex("""^(\d{1,2}):(\d{2})$""").find(t) ?: return "Give a time like 08:30."
                val now = Calendar.getInstance(); val at = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt().coerceIn(0, 23)); set(Calendar.MINUTE, m.groupValues[2].toInt().coerceIn(0, 59)); set(Calendar.SECOND, 0)
                    if (before(now)) add(Calendar.DAY_OF_MONTH, 1) }
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("standup_${w.slug}", ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<StandupWorker>(24, TimeUnit.HOURS).setInitialDelay(at.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf("slug" to w.slug)).build())
                save(w.copy(standup = t)); "Daily standup for ${w.name} at $t."
            } ?: "No such workspace."
            else -> "Unknown action."
        }
    }

    fun standupPrompt(w: Ws): String = "@all Daily standup for the Duo Workspace \u201c${w.name}\u201d. Board:\n${Board.render(w)}\n" +
        "Blaze and Halo: say in two lines each what moved since yesterday, what's next and what's blocked; then actually do the next card you own " +
        "(files go in ~/workspaces/${w.slug}/), move cards with duo_workspace, and add a log note. Ask the user only about cards they own."
}

class StandupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val w = DuoWorkspace.load(inputData.getString("slug").orEmpty()) ?: return Result.success()
        try { android.os.Handler(android.os.Looper.getMainLooper()).post { Agent.runRoutine("${w.name} standup", DuoWorkspace.standupPrompt(w)) } } catch (_: Throwable) { }
        return Result.success()
    }
}
