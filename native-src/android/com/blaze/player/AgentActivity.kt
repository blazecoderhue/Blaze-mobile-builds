// Blaze Player (Apache-2.0). Added by Blazeinchue.
package com.blaze.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.Executors

/**
 * Exported, invisible activity for the agent's data actions (queue add/list/clear, search),
 * protected by com.blaze.player.permission.AGENT. Call with startActivityForResult; the result
 * Intent carries "json" (or "errorCode" + "error"). open/play/cast go to the AgentEntry alias.
 */
class AgentActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        try {
            when (i.action) {
                AgentContract.ACTION_QUEUE_ADD -> {
                    val uri = i.data?.toString() ?: i.getStringExtra(AgentContract.EXTRA_URI)
                    val id = AgentValidator.normalize(uri, i.getStringExtra(AgentContract.EXTRA_SOURCE_ID))
                    if (id.startsWith("local:")) AgentCommandBus.keepGrant(this, i, Uri.parse(id.removePrefix("local:")))
                    val n = AgentQueue.add(this, AgentQueue.itemFor(id, i.getStringExtra(AgentContract.EXTRA_TITLE)))
                    AgentService.queueChanged?.invoke()
                    ok("{\"size\":$n}")
                }
                AgentContract.ACTION_QUEUE_LIST -> ok(AgentQueue.list(this).toString())
                AgentContract.ACTION_QUEUE_CLEAR -> {
                    AgentQueue.clear(this)
                    AgentService.queueChanged?.invoke()
                    ok("{\"size\":0}")
                }
                AgentContract.ACTION_SEARCH -> {
                    val q = i.getStringExtra(AgentContract.EXTRA_QUERY) ?: ""
                    val sources = AgentService.parseSources(i.getStringExtra(AgentContract.EXTRA_SOURCES))
                    val limit = i.getIntExtra(AgentContract.EXTRA_LIMIT, 20).coerceIn(1, 100)
                    io.execute {
                        try {
                            val res = AgentSearch.search(this, q, sources, limit).toString()
                            runOnUiThread { ok(res) }
                        } catch (e: Exception) {
                            runOnUiThread { fail("E_NETWORK", e.message ?: "search failed") }
                        }
                    }
                }
                else -> fail("E_UNKNOWN_ACTION", "unknown action ${i.action}")
            }
        } catch (e: AgentValidator.Rejected) {
            fail(e.code, e.message ?: "rejected")
        }
    }

    private fun ok(json: String) {
        setResult(RESULT_OK, Intent().putExtra(AgentContract.EXTRA_RESULT_JSON, json))
        finish()
    }

    private fun fail(code: String, msg: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(AgentContract.EXTRA_ERROR_CODE, code).putExtra(AgentContract.EXTRA_ERROR, msg))
        finish()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
