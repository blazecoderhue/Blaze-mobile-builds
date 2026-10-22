package com.blaze.assistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.blaze.assistant.R
import com.blaze.assistant.ui.Overlay

/** Home-screen widget: last reply, an Ask box and a mic button. Both open Blaze over your screen. */
class BlazeWidget : AppWidgetProvider() {
    companion object {
        const val ASK = "com.blaze.assistant.WIDGET_ASK"
        const val MIC = "com.blaze.assistant.WIDGET_MIC"

        fun update(ctx: Context, last: String? = null, status: String = "Ready") {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BlazeWidget::class.java))
            if (ids.isEmpty()) return
            val prefs = ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE)
            if (last != null) prefs.edit().putString("widget_last", last).apply()
            val text = prefs.getString("widget_last", null) ?: "Ask me anything, or tell me what to do on your phone."
            val v = RemoteViews(ctx.packageName, R.layout.widget_blaze)
            v.setTextViewText(R.id.w_last, text)
            v.setTextViewText(R.id.w_status, status.uppercase())
            v.setOnClickPendingIntent(R.id.w_ask, pi(ctx, ASK))
            v.setOnClickPendingIntent(R.id.w_last, pi(ctx, ASK))
            v.setOnClickPendingIntent(R.id.w_mic, pi(ctx, MIC))
            mgr.updateAppWidget(ids, v)
        }

        private fun pi(ctx: Context, action: String) = PendingIntent.getBroadcast(ctx, action.hashCode(),
            Intent(ctx, BlazeWidget::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ASK -> Overlay.openPanel(ctx, listen = false)
            MIC -> Overlay.openPanel(ctx, listen = true)
            else -> super.onReceive(ctx, intent)
        }
    }
}
