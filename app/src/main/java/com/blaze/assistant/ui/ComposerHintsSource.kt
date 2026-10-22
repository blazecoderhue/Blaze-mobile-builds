package com.blaze.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.ComposerHints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gathers the on-device inputs for [ComposerHints] (v0.24.1): connected accounts, launchable apps (package manager,
 * the same launcher query as list_apps), person / project memories, reminders in the next day, calendar events in the
 * next day (only when READ_CALENDAR is already granted; never asks), and project names. Every source is optional:
 * a failure just leaves it out. Nothing is sent anywhere.
 */
object ComposerHintsSource {
    suspend fun load(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val connectors = safe { com.blaze.assistant.connect.Connectors.all.filter { com.blaze.assistant.connect.Connectors.connected(it.id) }.map { it.id } }
        val apps = safe {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() }.distinct()
        }
        val notes = safe {
            com.blaze.assistant.agent.Mind.all().filter { it.kind == "person" || it.kind == "project" }
                .map { ComposerHints.Note(it.kind, it.subject, it.text, it.updated) }
        }
        val reminders = safe {
            com.blaze.assistant.routine.Routines.all.value.filter { it.enabled && it.at > 0 }
                .map { ComposerHints.Upcoming(it.name, it.at, calendar = false) }
        }
        val events = safe { calendar(ctx, now) }
        val recent = safe { com.blaze.assistant.agent.Projects.all.value.sortedByDescending { it.updated }.map { it.name } }
        ComposerHints.build(ComposerHints.Sources(connectors, apps, notes, events + reminders, recent, now))
    }

    private fun calendar(ctx: Context, now: Long): List<ComposerHints.Upcoming> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now); android.content.ContentUris.appendId(it, now + 24 * 3_600_000L)
        }.build()
        val out = mutableListOf<ComposerHints.Upcoming>()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 3) {
                val title = c.getString(0).orEmpty(); val begin = c.getLong(1); val allDay = c.getInt(2) == 1
                if (title.isNotBlank() && !allDay && begin >= now) out += ComposerHints.Upcoming(title, begin, calendar = true)
            }
        }
        return out
    }

    /** Reduced motion: the phone's animator scale is off (Settings › Accessibility › Remove animations). */
    fun reducedMotion(ctx: Context): Boolean = try {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) { false }

    private inline fun <T> safe(block: () -> List<T>): List<T> = try { block() } catch (_: Throwable) { emptyList() }
}
