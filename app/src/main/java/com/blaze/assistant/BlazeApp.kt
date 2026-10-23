package com.blaze.assistant

import android.app.Application
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.launch

class BlazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Blaze Kiln guest processes (":g0".., only declared in the kilnTest build) run guest apps, not Blaze: none of
        // Blaze's start-up (agents, router, overlays, workers) may run there.
        if (isKilnGuestProcess()) return
        val t0 = android.os.SystemClock.elapsedRealtime()
        com.blaze.assistant.device.CrashLog.init(this)
        com.blaze.assistant.device.CrashLog.shieldMainThread()
        // 0.45.19 fix 7: one Back rule for every Blaze window (Back returns to the chat).
        try { com.blaze.assistant.ui.BackRule.install(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: BackRule", e) }
        com.blaze.assistant.device.StartGuard.begin(this)   // 0.45.16: crash-loop detection (safe start)
        com.blaze.assistant.device.MainWatch.start()        // 0.45.16: ANR / stall log with the main thread's stack
        com.blaze.assistant.device.StartGuard.step("Settings.init") { Settings.init(this) }
        com.blaze.assistant.device.StartGuard.step("Agent.preload") { Agent.preload(this) }   // v0.45.0 (#2): the main chat is read and parsed on a background thread while the rest starts
        com.blaze.assistant.device.StartGuard.step("agent.SimpleMode.load") { com.blaze.assistant.agent.SimpleMode.load() }   // v0.39.0 Simple / Advanced
        com.blaze.assistant.device.StartGuard.step("agent.SwipeNav.load") { com.blaze.assistant.agent.SwipeNav.load() }     // v0.39.0 Navigation: Swipe or Classic tabs
        try { com.blaze.assistant.bank.BankMode.init(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: BankMode.initth", e) }   // v0.43.0 Bank mode (back on after a restart mid-banking)
        // v0.37.0 Awareness: what's playing, on screen and just happened, for every prompt.
        try { com.blaze.assistant.agent.AwarenessHooks.install(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: AwarenessHooks", e) }
        // v0.24.4: back up prefs and data once per build before the setup gate (or anything after it) runs.
        try {
            if (!com.blaze.assistant.setup.SetupGate.isDone()) com.blaze.assistant.setup.SetupGate.backup(this, com.blaze.assistant.update.Updater.installedCode(this))
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: SetupGate.i", e) }
        // v0.23.4: Auto's per-provider model lists and per-model cooldowns (new files, no prefs changes).
        try { com.blaze.assistant.agent.ProviderCatalog.init(filesDir); com.blaze.assistant.agent.ModelMemory.init(filesDir) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: ProviderCatalo", e) }
        try { com.blaze.assistant.agent.Projects.load() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: Projects.load", e) }
        // v0.45.0 (#14): background-only scheduling (file reads + WorkManager, nothing the first frame needs) leaves the main thread.
        val bgApp = this
        Thread({
            // v0.33.0 Life Log 2: recordings stopped but not yet reviewed (phone restarted, app killed) carry on.
            try { com.blaze.assistant.life.LifeLogWorker.resumePending(bgApp) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: LifeLogWorker.r", e) }
            try { com.blaze.assistant.notify.MoneyRadar.schedule(bgApp) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: MoneyRadar.sc", e) }
            try { com.blaze.assistant.agent.TimeCapsule.schedule(bgApp) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: TimeCapsule.sc", e) }
            try { com.blaze.assistant.agent.MindReview.schedule(bgApp) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: MindReview.sch", e) }
        }, "blaze-start-bg").start()
        // v0.33.0 Safe Walk survives a reboot / process restart; sealed time capsules keep their wake-up.
        try { com.blaze.assistant.phone.SafeWalk.handle(this, android.content.Intent.ACTION_BOOT_COMPLETED) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: SafeWalk.handl", e) }
        try { com.blaze.assistant.phone.CallCopilot.init(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: CallCopilot.in", e) }
        try { com.blaze.assistant.agent.OfflineBrain.init(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: OfflineBrain.i", e) }
        // v1.9.10 fix: a leftover "Auto" steps model silently sent phone steps through the free chain
        // even after the user picked a specific model. Clear it once; they can set it again.
        if (Settings.str("mig_steps_auto", "").isEmpty()) {
            if (Settings.providerId != com.blaze.assistant.agent.Providers.AUTO && Settings.stepsProvider == com.blaze.assistant.agent.Providers.AUTO) Settings.stepsProvider = ""
            Settings.setStr("mig_steps_auto", "1")
        }
        com.blaze.assistant.device.StartGuard.step("agent.IntentCache.init") { com.blaze.assistant.agent.IntentCache.init(this) }
        com.blaze.assistant.device.StartGuard.step("phone.Watchers.init") { com.blaze.assistant.phone.Watchers.init(this) }
        com.blaze.assistant.device.StartGuard.step("agent.Autonomy.init") { com.blaze.assistant.agent.Autonomy.init(this) }
        com.blaze.assistant.device.StartGuard.step("agent.Processes.init") { com.blaze.assistant.agent.Processes.init(this) }
        // One mind (v0.18.0): blaze.db first (mind.db until it is copied); Memory is now a view of its memories table.
        com.blaze.assistant.device.StartGuard.step("agent.Mind.init") { com.blaze.assistant.agent.Mind.init(this) }
        com.blaze.assistant.device.StartGuard.step("agent.Memory.init") { com.blaze.assistant.agent.Memory.init(this) }
        com.blaze.assistant.device.StartGuard.step("agent.Usage.init") { com.blaze.assistant.agent.Usage.init(this) }
        try { com.blaze.assistant.agent.ToolStats.init(this) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: ToolStats.init", e) }
        try { com.blaze.assistant.agent.ToolCache.init(filesDir) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: ToolCache.init", e) }
        com.blaze.assistant.device.StartGuard.step("agent.Learning.init") { com.blaze.assistant.agent.Learning.init(this) }
        com.blaze.assistant.device.StartGuard.step("connect.Mcp.load") { com.blaze.assistant.connect.Mcp.load() }
        com.blaze.assistant.device.StartGuard.step("connect.BlazeAccount.start") { com.blaze.assistant.connect.BlazeAccount.start() }
        // v0.40.0 Cloud sync: off by default; when on, live end-to-end encrypted sync with your other phones.
        try { com.blaze.assistant.connect.CloudSync.init(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: CloudSync.in", e) }
        com.blaze.assistant.device.StartGuard.step("agent.Traces.init") { com.blaze.assistant.agent.Traces.init(this) }
        // v0.30.7: phone steps that worked, per request, sent back as one run_plan next time.
        try { com.blaze.assistant.agent.TapLoop.Plans.init(java.io.File(filesDir, "plan_memory.json")) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: TapLoop.Plans.", e) }
        val tAgent = android.os.SystemClock.elapsedRealtime()
        com.blaze.assistant.device.StartGuard.step("Agent.init") { Agent.init(this) }
        val agentMs = android.os.SystemClock.elapsedRealtime() - tAgent
        com.blaze.assistant.device.StartGuard.step("agent.BgAgents.init") { com.blaze.assistant.agent.BgAgents.init(this) }
        com.blaze.assistant.device.StartGuard.step("routine.Routines.init") { com.blaze.assistant.routine.Routines.init(this) }
        // v0.29.0 Home board: widgets + cached content in blaze.db, refreshed by WorkManager (≥ 1 h).
        com.blaze.assistant.device.StartGuard.step("home.HomeBoard.init") { com.blaze.assistant.home.HomeBoard.init(this) }
        // v0.33.2 BlazeOS (Home's virtual phone): reads and once migrates its bos_* prefs; its disk opens at boot.
        try { com.blaze.assistant.blazeos.BlazeOs.init(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: BlazeOs.init", e) }
        com.blaze.assistant.device.StartGuard.step("notify.Notifier.init") { com.blaze.assistant.notify.Notifier.init(this) }
        try { com.blaze.assistant.update.Updater.checkInBackground(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: Updater.check", e) }
        com.blaze.assistant.device.StartGuard.step("Settings.tellIfSecretsDropped") { Settings.tellIfSecretsDropped() }
        com.blaze.assistant.device.StartGuard.step("voice.Voice.init") { com.blaze.assistant.voice.Voice.init(this) }
        // Wake word starts from the screen (MainActivity/assistant) and at boot: starting a
        // microphone service while Blaze is in the background makes Android 12+ kill the app.
        com.blaze.assistant.device.StartGuard.step("terminal.Terminal.init") { com.blaze.assistant.terminal.Terminal.init(this) }
        com.blaze.assistant.device.StartGuard.step("agent.SkillLibrary.init") { com.blaze.assistant.agent.SkillLibrary.init(this) }
        // BlazeRouter: start when it's the brain or set to start with Blaze (after Terminal/Linux are ready).
        if (!com.blaze.assistant.device.StartGuard.safeStart && (com.blaze.assistant.agent.BlazeRouter.autostart || Settings.providerId == com.blaze.assistant.agent.BlazeRouter.ID)) {
            val app = this
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                try { com.blaze.assistant.agent.BlazeRouter.start(app) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: BlazeRouter.st", e) }
            }
        }
        Thread { com.blaze.assistant.agent.SkillPacks.ensureStarters(); com.blaze.assistant.agent.SkillLibrary.seed(this) }.start()
        com.blaze.assistant.device.StartGuard.step("proactive.Triggers.init") { com.blaze.assistant.proactive.Triggers.init(this) }
        if (!com.blaze.assistant.device.StartGuard.safeStart) com.blaze.assistant.device.StartGuard.step("proactive.Proactive.start") { com.blaze.assistant.proactive.Proactive.start(this) }
        // v0.38.0 Jarvis mode: questions, screen glances, camera looks, its own routines (per level).
        if (!com.blaze.assistant.device.StartGuard.safeStart) try { com.blaze.assistant.proactive.FollowUps.init(this); com.blaze.assistant.proactive.Jarvis.start(this) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: FollowUps.", e) }
        com.blaze.assistant.device.StartGuard.step("ui.Overlay.watchTasks") { com.blaze.assistant.ui.Overlay.watchTasks(this) }
        if (!com.blaze.assistant.device.StartGuard.safeStart) com.blaze.assistant.device.StartGuard.step("link.DesktopLink.start") { com.blaze.assistant.link.DesktopLink.start() }
        if (!com.blaze.assistant.device.StartGuard.safeStart && Settings.bool("dash_auto", false)) com.blaze.assistant.device.StartGuard.step("Dashboard.start") { com.blaze.assistant.dashboard.Dashboard.start(this) }
        if (!com.blaze.assistant.device.StartGuard.safeStart) com.blaze.assistant.device.StartGuard.step("Overlay.showOrb") { if (Settings.orbPinned && android.provider.Settings.canDrawOverlays(this)) com.blaze.assistant.ui.Overlay.showOrb(this) }
        // v0.28.4 blaze-docs: index Blaze's own docs (+ the real tool list) into blaze.db when the build changed. Off the main
        // thread and a few seconds after start so it never slows opening the app; a no-op when nothing changed.
        // v0.45.0 (#14): how long start took on the main thread (and Agent.init's share), in the speed log; very slow starts in the crash log.
        val startMs = android.os.SystemClock.elapsedRealtime() - t0
        try { android.util.Log.i("BlazeStart", "onCreate ${startMs} ms (Agent.init ${agentMs} ms)") } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: android.util.Log.iBlazeStart onCreate st", e) }
        com.blaze.assistant.device.CrashLog.slow("app start (Agent.init ${agentMs} ms)", startMs)
        val app = this
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2_500)
            val tools = try { Agent.toolCatalog() } catch (_: Throwable) { null }
            try { com.blaze.assistant.agent.BlazeDocs.init(app, tools) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: BlazeDocs.init", e) }
            // v0.28.5: index each project's full instructions / brief / notes (only what changed) for project_docs_search.
            try { com.blaze.assistant.agent.ProjectDocs.init() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BlazeApp: ProjectDocs.in", e) }
        }
    }

    private fun isKilnGuestProcess(): Boolean {
        val name = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        } catch (_: Throwable) { return false }
        return Regex(":g[0-9]+$").containsMatchIn(name ?: "")
    }

    /**
     * 0.45.16 low memory (3 GB phones): drop the parse caches when Android asks, so the app is trimmed instead of
     * killed mid-chat. They refill from the chat on the next scroll.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (com.blaze.assistant.device.MemoryTrim.shouldDropCaches(level)) {
            try { com.blaze.assistant.device.MemoryTrim.dropCaches() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("onTrimMemory", e) }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        try { com.blaze.assistant.device.MemoryTrim.dropCaches() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("onLowMemory", e) }
    }
}
