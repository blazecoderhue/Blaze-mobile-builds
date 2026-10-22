package com.blaze.assistant.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v0.29.0: the Home board at runtime: the store in blaze.db, the widgets and their content as flows for the UI, refreshing
 * (per widget, pull-to-refresh, WorkManager every ≥ 1 h with Wi-Fi-only / Data Saver respected), seeding and the board's
 * look (background, accent). Board prefs are new keys only (`home_*`); nothing old changes.
 */
object HomeBoard {
    private lateinit var app: Context
    @Volatile private var store: HomeStore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Low-RAM phones: at most 3 fetches at a time. */
    private val gate = Semaphore(3)

    private val _widgets = MutableStateFlow<List<Widget>>(emptyList())
    val widgets: StateFlow<List<Widget>> = _widgets
    private val _data = MutableStateFlow<Map<String, WidgetData>>(emptyMap())
    val data: StateFlow<Map<String, WidgetData>> = _data
    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    const val WORK = "home_refresh"
    /** A widget's content counts as fresh for this long when the board opens / scrolls it into view. */
    const val FRESH_MS = 50 * 60_000L

    // ---------------- prefs ----------------
    var onboarded: Boolean get() = Settings.bool("home_onboarded", false); set(v) = Settings.setBool("home_onboarded", v)
    var wifiOnly: Boolean get() = Settings.bool("home_wifi_only", false); set(v) { Settings.setBool("home_wifi_only", v); schedule() }
    var refreshHours: Int get() = Settings.str("home_refresh_hours", HomeSchedule.DEFAULT_HOURS.toString()).toIntOrNull() ?: HomeSchedule.DEFAULT_HOURS
        set(v) { Settings.setStr("home_refresh_hours", HomeSchedule.plan(v, false).hours.toString()); schedule() }
    /** "glass" (default, v0.31) | "plain" | "gradient" | "image". The old default "sky" (and an unset value) reads as "glass". */
    var background: String get() = normBackground(Settings.str("home_bg", "glass")); set(v) = Settings.setStr("home_bg", normBackground(v))
    /** v0.31: the retired sky default and blanks map to the glass look. */
    fun normBackground(v: String): String = when (v.trim()) { "", "sky" -> "glass"; else -> v.trim() }
    /** One-time v0.31 migration: users on the old default look (sky, or Solid with the default swatch) move to glass. */
    private fun migrateLook() {
        try {
            if (Settings.bool("home_look_glass_v31", false)) return
            val raw = Settings.str("home_bg", "")
            if (raw.isBlank() || raw == "sky" || (raw == "plain" && (Settings.str("home_bg_color", "0").toIntOrNull() ?: 0) == 0)) Settings.setStr("home_bg", "glass")
            Settings.setBool("home_look_glass_v31", true)
        } catch (_: Throwable) { }
    }
    /** Index into the swatch list (solid / gradient colour). */
    var bgColor: Int get() = Settings.str("home_bg_color", "0").toIntOrNull() ?: 0; set(v) = Settings.setStr("home_bg_color", v.toString())
    /** Accent theme name ("" = Blaze's own). */
    var accent: String get() = Settings.str("home_accent", ""); set(v) = Settings.setStr("home_accent", v)
    var lastRefresh: Long get() = Settings.str("home_last_refresh", "0").toLongOrNull() ?: 0L; set(v) = Settings.setStr("home_last_refresh", v.toString())
    /** Stacks the user removed widgets from (suggestions skip them). */
    val removedCategories: Set<String> get() = Settings.str("home_removed_cats", "").split('\n').filter { it.isNotBlank() }.toSet()

    val ready: Boolean get() = store != null

    fun init(ctx: Context) {
        if (store != null) return
        app = ctx.applicationContext
        migrateLook()
        scope.launch {
            try {
                store = open()
                reload()
                schedule()
            } catch (_: Throwable) { }
        }
    }

    /** blaze.db when it is the memory store (normally); otherwise a small side database so the board still works. */
    private fun open(): HomeStore {
        com.blaze.assistant.agent.Mind.init(app)
        (com.blaze.assistant.agent.Mind.db() as? com.blaze.assistant.agent.MindStore)?.let { return HomeStore(it.sql) }
        return HomeStore(com.blaze.assistant.agent.Mind.openBlaze(java.io.File(app.filesDir, "home.db")))
    }

    /** For tests / screenshots. */
    fun useForTests(s: HomeStore?) { store = s; if (s != null) { _widgets.value = s.all(); _data.value = s.allData() } }

    fun reload() {
        val s = store ?: return
        _widgets.value = s.all(); _data.value = s.allData()
        try { com.blaze.assistant.agent.TurnContextSource.invalidate() } catch (_: Throwable) { }   // v0.45.9: cached weather feeds the [context] block
    }

    fun storeOrNull(): HomeStore? = store

    // ---------------- network ----------------

    fun online(ctx: Context = app): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) { true }

    private fun metered(): Boolean = try { app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true } catch (_: Throwable) { false }

    private fun dataSaver(): Boolean = try {
        app.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (_: Throwable) { false }

    // ---------------- refresh ----------------

    /** Refreshes one widget now (per-widget Refresh, a new widget). */
    fun refresh(id: String) { scope.launch { refreshNow(id) } }

    suspend fun refreshNow(id: String) {
        val s = store ?: return
        val w = s.get(id) ?: return
        if (id in _loading.value) return
        val on = online()
        _offline.value = !on
        if (!on && WidgetTypes.online(w.type)) {
            // Keep the cached content; the card shows a quiet stale tag.
            if (s.data(id) == null) { s.saveData(id, WidgetData.failed("No connection right now. I'll fill this in when you're back online.")); reload() }
            return
        }
        _loading.value = _loading.value + id
        try {
            gate.withPermit {
                val r = HomeSources.fetch(app, w)
                r.learned?.let { l -> s.get(id)?.let { cur -> val c = cur.cfg(); for (k in l.keys()) c.put(k, l.get(k)); s.update(cur.copy(config = c.toString())) } }
                s.saveData(id, r.data)
            }
        } finally {
            _loading.value = _loading.value - id
            reload()
        }
    }

    /** Pull-to-refresh / the background worker: every shown widget (only stale ones when [onlyDue]). */
    suspend fun refreshAll(onlyDue: Boolean, maxAgeMs: Long = FRESH_MS) {
        val s = store ?: return
        _refreshing.value = true
        try {
            val now = System.currentTimeMillis()
            val data = s.allData()
            val todo = s.all().filter { !it.hidden && (!onlyDue || HomeSchedule.due(data[it.id]?.fetched ?: 0L, now, maxAgeMs)) }
            val jobs = todo.map { w -> scope.launch { refreshNow(w.id) } }
            jobs.forEach { it.join() }
            lastRefresh = System.currentTimeMillis()
        } finally { _refreshing.value = false }
    }

    fun pullToRefresh() { scope.launch { refreshAll(onlyDue = false) } }

    /** A card came into view: fetch it when it never was or is older than [FRESH_MS] (lazy loading). */
    fun visible(id: String) {
        val d = _data.value[id]
        if (id in _loading.value) return
        if (d == null || HomeSchedule.due(d.fetched, System.currentTimeMillis(), FRESH_MS) && (d.error.isBlank() || d.fetched > 0L || d.action.isBlank())) refresh(id)
    }

    /** (Re)schedules the periodic background refresh to the current settings. */
    fun schedule() {
        try {
            val p = HomeSchedule.plan(refreshHours, wifiOnly)
            val wm = WorkManager.getInstance(app)
            if ((store?.count() ?: 0) == 0) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<HomeRefreshWorker>(p.hours.toLong(), TimeUnit.HOURS)
                .setConstraints(constraints(p)).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        } catch (_: Throwable) { }
    }

    fun constraints(p: HomeSchedule.Plan): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (p.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(p.batteryNotLow).build()

    /** The worker's pass. */
    suspend fun backgroundPass() {
        if (!HomeSchedule.mayRun(metered(), dataSaver(), wifiOnly)) return
        refreshAll(onlyDue = true, maxAgeMs = (refreshHours * 60 - 10) * 60_000L)
        // v0.30.5: new things on the board since the user last looked become one short heads-up.
        store?.let { s -> HomeNews.afterRefresh(s.all(), s.allData()) }
    }

    /** v0.30.5: the user is looking at Home, so what's on it counts as seen. */
    fun markSeen() { scope.launch { store?.let { s -> HomeNews.markSeen(s.all(), s.allData()) } } }

    // ---------------- edits from the UI ----------------

    private fun edit(block: (HomeStore) -> Unit) { val s = store ?: return; scope.launch { block(s); reload(); schedule() } }

    fun add(spec: WidgetSpec, refreshAfter: Boolean = true) = edit { s ->
        val dupe = s.all().firstOrNull { it.type == spec.type && it.cfg().toString() == spec.config.toString() }
        val id = dupe?.id ?: s.add(spec.toWidget()).id
        if (dupe?.hidden == true) s.update(dupe.copy(hidden = false))
        if (refreshAfter) refresh(id)
    }

    fun addAll(specs: List<WidgetSpec>) = edit { s -> specs.forEach { sp ->
        if (s.all().none { it.type == sp.type && it.cfg().toString() == sp.config.toString() }) s.add(sp.toWidget()) } }

    fun remove(id: String) = edit { s ->
        s.get(id)?.let { w -> if (w.category.isNotBlank()) Settings.setStr("home_removed_cats", (removedCategories + w.category).joinToString("\n")) }
        s.remove(id)
    }
    fun move(id: String, delta: Int) = edit { it.move(id, delta) }
    fun reorder(ids: List<String>) = edit { it.reorder(ids) }
    fun resize(id: String, size: String) = edit { s -> s.get(id)?.let { s.update(it.copy(size = WidgetSize.norm(size))) } }
    fun setHidden(id: String, hidden: Boolean) = edit { s -> s.get(id)?.let { s.update(it.copy(hidden = hidden)) } }
    fun tapped(id: String) = edit { it.tapped(id) }

    /** Updates a widget's config keys (e.g. a city typed on the card), dropping what was looked up for the old value. */
    fun setConfig(id: String, kv: Map<String, Any>) = edit { s ->
        s.get(id)?.let { w ->
            val c = w.cfg(); kv.forEach { (k, v) -> c.put(k, v) }
            if ("city" in kv) { c.remove("lat"); c.remove("lon"); c.remove("geo_city"); c.remove("place") }
            s.update(w.copy(config = c.toString())); refresh(id)
        }
    }

    /** Creators: the user looked at the new videos. */
    fun markSeen(id: String) = edit { s -> s.get(id)?.let { w -> val c = w.cfg(); c.put("seen_at", System.currentTimeMillis()); s.update(w.copy(config = c.toString())) } }

    // ---------------- seeding ----------------

    /**
     * First open of Home after the interest picker: widgets for the chosen interests, saved as a memory too. Skip seeds
     * from what Blaze remembers when it is confident, else 3–4 generic starters.
     */
    fun finishOnboarding(chosen: Set<String>, subs: Set<String>, typed: String, skipped: Boolean) {
        onboarded = true
        if (skipped) { seedDefault(); return }
        val line = Interests.memoryLine(chosen, subs, typed)
        if (line.isNotBlank()) try { com.blaze.assistant.agent.Mind.save("preference", "Interests", line) } catch (_: Throwable) { }
        val specs = Interests.seed(chosen, subs, typed)
        edit { s ->
            // The "tell me what you're into" card has done its job.
            s.all().filter { it.type == WidgetTypes.INTO }.forEach { s.remove(it.id) }
            specs.forEach { sp -> if (s.all().none { it.type == sp.type && it.cfg().toString() == sp.config.toString() }) s.add(sp.toWidget()) }
            scope.launch { refreshAll(onlyDue = true) }
        }
    }

    /** From memory when confident, else generic starters (only adds to an empty board). */
    fun seedDefault() = edit { s ->
        if (s.count() > 0) return@edit
        val mems = try { com.blaze.assistant.agent.Mind.all().map { it.text } } catch (_: Throwable) { emptyList() }
        val sug = Interests.fromMemories(mems, removedCategories)
        val specs = if (Interests.confident(sug)) listOf(Interests.starterWeather()) + sug.filter { it.confidence >= 0.5 }.take(5).map { it.spec } +
            WidgetSpec(WidgetTypes.ROUTINES, "Next routine", "Today", JSONObject())
            else Interests.starters()
        specs.forEach { sp -> if (s.all().none { it.type == sp.type && it.cfg().toString() == sp.config.toString() }) s.add(sp.toWidget()) }
        scope.launch { refreshAll(onlyDue = true) }
    }

    // ---------------- tools ----------------

    fun runTool(name: String, args: JSONObject): String {
        val s = store ?: return "The Home board is still starting. Try again in a moment."
        return HomeTools.run(s, name, args) { id -> reload(); schedule(); if (id.isNotBlank()) refresh(id) }
    }
}

class HomeRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            HomeBoard.init(applicationContext)
            var n = 0
            while (!HomeBoard.ready && n++ < 50) kotlinx.coroutines.delay(100)
            HomeBoard.backgroundPass()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
        return Result.success()
    }
}
