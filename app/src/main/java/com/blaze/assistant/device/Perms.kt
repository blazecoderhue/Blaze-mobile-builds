package com.blaze.assistant.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ask for runtime permissions from anywhere (agent tools, services): opens a see-through
 * activity that shows Android's own permission dialog and reports back.
 */
object Perms {
    private var pending: CompletableDeferred<Boolean>? = null

    fun has(ctx: Context, vararg perms: String) = perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** True when every permission is granted (asking first if needed). */
    suspend fun ensure(ctx: Context, vararg perms: String): Boolean {
        if (has(ctx, *perms)) return true
        val d = CompletableDeferred<Boolean>()
        pending?.complete(false)
        pending = d
        return try {
            ctx.startActivity(Intent(ctx, PermissionActivity::class.java).putExtra("perms", perms)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            withTimeoutOrNull(60_000) { d.await() } ?: has(ctx, *perms)
        } catch (_: Exception) { false }
    }

    internal fun result(ok: Boolean) { pending?.complete(ok); pending = null }

    /** What to tell the model when the user said no. */
    fun denied(what: String) = "The user didn't allow $what. Don't retry; tell them they can allow it under App info → Permissions if they change their mind."
}

class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = intent.getStringArrayExtra("perms") ?: emptyArray()
        if (perms.isEmpty()) { Perms.result(false); finish(); return }
        if (savedInstanceState == null) requestPermissions(perms, 41)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Perms.result(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        finish()
        overridePendingTransition(0, 0)
    }
}
