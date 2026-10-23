package com.blaze.engine.runtime

import android.content.Intent
import android.os.Build
import com.blaze.engine.route.IdentityRewriter
import com.blaze.engine.route.LaunchEnvelope
import com.blaze.engine.route.SlotId

// Step 2 of the launch loop (ENGINE_DESIGN.md 1.3): catch startActivity before it leaves the process, swap a guest
// target for its leased slot, and carry the guest's own Intent along in the slot intent's extras.
//
// The singleton we replace: android.app.ActivityTaskManager.IActivityTaskManagerSingleton (API 29+) or
// android.app.ActivityManager.IActivityManagerSingleton (26-28); both are android.util.Singleton with an mInstance
// field holding the IInterface the whole process uses.

class LaunchInterceptor(
    private val rewriter: IdentityRewriter,
    /** Resolves a guest intent to (guestPackage, guestClass, slotClass, envelope); null when the target is not a guest. */
    private val resolver: (Intent) -> LaunchEnvelope?,
) {
    fun install(): Boolean {
        val installed = installOn(
            if (Build.VERSION.SDK_INT >= 29) "android.app.ActivityTaskManager" to "IActivityTaskManagerSingleton"
            else "android.app.ActivityManager" to "IActivityManagerSingleton"
        )
        // 26-28 route some calls through ActivityManager even on newer releases; wrap both when present.
        if (Build.VERSION.SDK_INT >= 29) installOn("android.app.ActivityManager" to "IActivityManagerSingleton")
        return installed
    }

    private fun installOn(target: Pair<String, String>): Boolean {
        val (className, fieldName) = target
        val cls = HiddenApi.classOrNull(className) ?: return false
        val singleton = HiddenApi.get(HiddenApi.field(cls, fieldName), null) ?: return false
        val singletonCls = HiddenApi.classOrNull("android.util.Singleton") ?: return false
        // Force the singleton to create the real instance before we take it.
        HiddenApi.call(HiddenApi.method(singletonCls, "get"), singleton)
        val instanceField = HiddenApi.field(singletonCls, "mInstance")
        val real = HiddenApi.get(instanceField, singleton) ?: return false
        if (java.lang.reflect.Proxy.isProxyClass(real.javaClass)) return true // already ours
        val shim = ServiceShim.wrap(className, real, rewriter, AndroidArgAdapters.all(), handlers())
        return HiddenApi.set(instanceField, singleton, shim)
    }

    private fun handlers(): Map<String, ShimHandler> {
        val h = ShimHandler { call -> rewriteStart(call) }
        return listOf("startActivity", "startActivityAsUser", "startActivityAsCaller", "startActivities",
            "startActivityIntentSender", "startActivityAndWait", "startNextMatchingActivity")
            .associateWith { h }
    }

    /** Rewrites the Intent arguments in place; always returns [PassThrough] so the real service does the start. */
    private fun rewriteStart(call: ShimCall): Any {
        for (i in call.args.indices) {
            val a = call.args[i]
            if (a is Intent) call.args[i] = mapIntent(a) ?: a
            if (a is Array<*> && a.isArrayOf<Intent>()) {
                @Suppress("UNCHECKED_CAST") val arr = a as Array<Intent?>
                for (j in arr.indices) arr[j]?.let { arr[j] = mapIntent(it) ?: it }
            }
        }
        return PassThrough
    }

    /** Guest target → slot intent carrying the original; non-guest target → unchanged. */
    fun mapIntent(intent: Intent): Intent? {
        if (isSlotIntent(intent)) return null
        val env = resolver(intent) ?: return null
        val slot = SlotId.parse(env.slotClass) ?: return null
        val out = Intent(intent)
        out.setClassName(rewriter.hostPackage, slot.className)
        out.`package` = null
        out.putExtra(LaunchEnvelope.K_ORIGINAL_INTENT, Intent(intent))
        for ((k, v) in env.toMap()) out.putExtra(k, v)
        return out
    }

    private fun isSlotIntent(intent: Intent): Boolean =
        intent.component?.className?.let { SlotId.isSlot(it) } == true ||
            intent.hasExtra(LaunchEnvelope.K_TOKEN)

    companion object {
        /** Reads the envelope back out of a slot intent (step 4). */
        fun envelopeOf(intent: Intent?): LaunchEnvelope? {
            if (intent == null) return null
            val m = HashMap<String, String?>()
            for (k in listOf(LaunchEnvelope.K_PKG, LaunchEnvelope.K_CLASS, LaunchEnvelope.K_PROFILE,
                LaunchEnvelope.K_SLOT, LaunchEnvelope.K_TOKEN, LaunchEnvelope.K_TASK, LaunchEnvelope.K_REQ,
                LaunchEnvelope.K_VERSION)) {
                m[k] = intent.getStringExtra(k)
            }
            return LaunchEnvelope.fromMap(m)
        }

        @Suppress("DEPRECATION")
        fun originalIntent(intent: Intent?): Intent? = intent?.getParcelableExtra(LaunchEnvelope.K_ORIGINAL_INTENT)
    }
}
