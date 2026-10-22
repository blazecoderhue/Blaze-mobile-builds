package com.blaze.assistant.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Blaze knows itself: what it is, which apps make up Blaze, where its files live, what is set up
 * and what isn't. A short ABOUT YOU block rides in every prompt; about_blaze returns the full,
 * live report (every nook and cranny) so Blaze never asks the user what Blaze is.
 */
object SelfKnowledge {
    const val TOOL = "about_blaze"

    /** Facts about the Blaze family that never change at runtime. */
    const val FAMILY = "Blaze is an AI assistant made by Luke (Lackson Kapapa). It comes as three apps that share one Blaze account: " +
        "Blaze for Android (you, this app), Blaze Desktop (Windows/macOS/Linux) and Blaze CLI (terminal). " +
        "The Blaze account (Settings › Blaze account; sign in with Google, GitHub or email) syncs model keys, MCP servers and your own skills between them. " +
        "Phone and PC connect with Blaze Link: on the PC open Blaze Desktop › Phone › Show pairing QR code, " +
        "then on the phone open /views › Blaze Desktop › Scan QR code (or paste the blaze://pair link); same Wi-Fi, or anywhere when both are on Tailscale. After that the phone can ask_pc and the PC can use the phone. " +
        "All three use free AI providers by default (Auto), plus BlazeRouter (Desktop, and on the phone) for routing presets like auto/best-free with a web dashboard."

    private fun safe(f: () -> String): String = try { f() } catch (_: Throwable) { "unknown" }

    /** One compact block for every prompt (~120 tokens). */
    fun promptBlock(ctx: Context): String = buildString {
        append("\nABOUT YOU: ").append(FAMILY)
        append(" You are Blaze for Android v").append(safe { version(ctx) }).append(".")
        append(" Paired PC: ").append(safe { if (com.blaze.assistant.link.DesktopLink.paired) com.blaze.assistant.link.DesktopLink.pcName else "none" }).append(".")
        append(" Blaze account: ").append(safe { com.blaze.assistant.connect.BlazeAccount.view.value.user?.let { "signed in" } ?: "not signed in" }).append(".")
        append(" Questions about Blaze itself, its apps, settings, files, what's installed, or connecting phone and PC: answer from this, call about_blaze for live setup and blaze_docs_search for docs (repos, versions, features, settings). Never ask the user what Blaze is or which apps they mean.")
    }

    fun version(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (build ${if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()})" }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Everything about Blaze itself, live: the Blaze apps (Android, Desktop, CLI) and how they connect, this app's version, the phone, " +
            "brains/providers and which have keys (never the keys), Blaze account, paired PC, connected apps, MCP servers, skills, memory, routines, " +
            "views and commands, files and folders, Linux/Termux, voice, permissions and what's missing. Use it before answering any question about Blaze or its setup.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("section", JSONObject().put("type", "string").put("description", "optional: family, app, phone, brains, accounts, link, skills, memory, files, linux, voice, setup, views. Empty = everything."))))
    )

    /** The full report, or one [section]. */
    fun report(ctx: Context, section: String = ""): String {
        val want = section.trim().lowercase()
        fun on(s: String) = want.isEmpty() || want == s
        return buildString {
            if (on("family")) { append("## Blaze family\n").append(FAMILY).append("\n\n") }
            if (on("app")) {
                append("## This app\n")
                append("Blaze for Android v").append(safe { version(ctx) }).append(", package ").append(ctx.packageName).append(".\n")
                append("Mode: ").append(safe { Modes.work.value + " · " + Modes.approval.value }).append(". Personality: ").append(safe { Settings.personaId }).append(".\n")
                append("Screen: Chat | Projects switch, Search, BlazeRouter dot, avatar menu (Account, Settings, Theme, Updates). Composer + menu: attach, @ files, voice, call, Plan/Build, Auto/Autopilot, views.\n")
                append("Projects: ").append(safe { Projects.all.value.joinToString { "${it.name} (${it.chats.size} chats)" }.ifBlank { "none" } }).append(".\n\n")
            }
            if (on("phone")) { append("## Phone\n").append(safe { com.blaze.assistant.device.DeviceStatus.identity(ctx) }).append("\n\n") }
            if (on("brains")) {
                append("## Brains (AI providers)\n")
                append("Answering with: Blaze Cloud (the gateway picks the best free model per message; the user can't and needn't choose models or add keys).\n")
                append("Signed in to Blaze: ").append(safe { if (Settings.ready) "yes" else "no (Settings › Account)" }).append(".\n")
                append("BlazeRouter (this phone): ").append(safe { val st = BlazeRouter.state.value; if (st.running) "running in ${BlazeRouter.label(st.where)} on ${BlazeRouter.BASE}/v1 (dashboard /views › BlazeRouter)" else "stopped (blazerouter start; runner ${BlazeRouter.runner})" }).append(".\n")
                append("Fast lane (on-device): ").append(safe { if (FastLane.ready(ctx)) "installed" else "not installed" }).append(".\n\n")
            }
            if (on("accounts")) {
                append("## Accounts\n")
                append("Blaze account: ").append(safe { com.blaze.assistant.connect.BlazeAccount.view.value.user?.let { u -> "signed in as ${u.email.ifBlank { u.name }}" } ?: "not signed in" }).append(".\n")
                append("Connected apps: ").append(safe { com.blaze.assistant.connect.Connectors.all.joinToString { c -> c.name + if (com.blaze.assistant.connect.Connectors.connected(c.id)) " ✓" else " –" } }).append(".\n")
                append("MCP servers: ").append(safe { com.blaze.assistant.connect.Mcp.servers.value.joinToString { s -> s.name + if (s.enabled) "" else " (off)" }.ifBlank { "none" } }).append(".\n\n")
            }
            if (on("link")) {
                append("## Blaze Link (phone ↔ PC)\n")
                append(safe { if (com.blaze.assistant.link.DesktopLink.paired) "Paired with ${com.blaze.assistant.link.DesktopLink.pcName}. Use ask_pc to work on it." else "Not paired. To pair: on the PC open Blaze Desktop › Phone › Show pairing QR code; on the phone open /views › Blaze Desktop › Scan QR code (or paste the blaze://pair link). Same Wi-Fi, or anywhere when both are on Tailscale." })
                append("\n\n")
            }
            if (on("skills")) { append("## Skills\n").append(safe { SkillPacks.all().joinToString { it.name }.ifBlank { "none" } }).append("\nFolder: ~/skills (manage in /views › Skills).\n\n") }
            if (on("memory")) {
                append("## Memory and routines\n")
                append("Remembered facts: ").append(safe { Memory.facts.value.size.toString() }).append(".\n")
                append("Running things (alarms, routines, triggers, jobs): ").append(safe { Processes.countLine().ifBlank { "none" } }).append(".\n\n")
            }
            if (on("files")) {
                append("## Files\n")
                val f = ctx.filesDir
                append("App data: ").append(f.absolutePath).append("\n")
                append("Home (terminal ~, run_code, file_*): ").append(safe { com.blaze.assistant.terminal.Terminal.home().absolutePath }).append("\n")
                append("Chats: ").append(File(f, "chats").absolutePath).append(" (").append(safe { (File(f, "chats").list()?.size ?: 0).toString() }).append(" saved)\n")
                append("Gallery (things Blaze made), wake model: ").append(File(f, "wake-model").absolutePath).append("\n")
                append("Free space: ").append(safe { "%.1f GB".format(f.usableSpace / 1e9) }).append("\n\n")
            }
            if (on("linux")) {
                append("## Linux and terminal\n")
                append("Alpine Linux (proot): ").append(safe { if (com.blaze.assistant.terminal.Linux.installed()) "installed" else "not installed (blaze_self install linux)" }).append(".\n")
                append("Desktop / linux=true Linux (user's choice on the Desktop page): ").append(safe {
                    val d = com.blaze.assistant.desktop.LinuxDesktop.distro()
                    "${d.name} (${d.pm.tool}), desktop " + (if (com.blaze.assistant.desktop.LinuxDesktop.installed()) "installed" else "not installed") +
                        "; linux=true commands run in " + com.blaze.assistant.terminal.Terminal.defaultDistro()
                }).append(".\n")
                append("Termux: ").append(safe { if (com.blaze.assistant.terminal.Terminal.termuxInstalled(ctx)) "installed (termux_run)" else "not installed" }).append(".\n")
                append("Local dashboard: ").append(safe { if (com.blaze.assistant.dashboard.Dashboard.running.value) com.blaze.assistant.dashboard.Dashboard.localUrl().substringBefore("?") else "off" }).append(".\n\n")
            }
            if (on("voice")) {
                append("## Voice\n")
                append("Wake word model: ").append(safe { if (com.blaze.assistant.voice.WakeModel.ready(ctx)) "installed" else "not installed" }).append(".\n\n")
            }
            if (on("views")) {
                append("## Views (/views, or open_view)\n")
                append(com.blaze.assistant.ui.Views.all.joinToString("\n") { "- ${it.name} (/${it.id}): ${it.desc}" }).append("\n")
                append("Blaze uses its views on its own, every turn, without being asked: output that fits a view (flashcards → Learn, anything visual or interactive (3D scenes, 2D art, sites, UI mockups, diagrams, charts, posters, slides, floor plans) → Design via the design tool, with follow-on edits changing the same design, reminders → Schedule, pictures and beats → Gallery, methods → Skills, facts → Memory) is made there, opened or linked, and answered briefly in chat. Matching skills are looked up before every turn.\n")
                append("Commands: ").append(safe { Commands.all().take(60).joinToString(" ") { "/" + it.name } }).append("\n\n")
            }
            if (on("setup")) {
                append("## Setup check\n")
                append(safe { SelfSetup.audit(ctx).joinToString("\n") { (if (it.ok) "✓ " else "✕ ") + it.name + if (it.ok) "" else " — " + it.fix } })
                append("\n")
            }
        }.trim().ifBlank { "Unknown section. Try: family, app, phone, brains, accounts, link, skills, memory, files, linux, voice, setup, views." }
    }
}
