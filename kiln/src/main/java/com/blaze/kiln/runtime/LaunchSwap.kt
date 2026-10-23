package com.blaze.kiln.runtime

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Message

// Step 4 of the launch loop (ENGINE_DESIGN.md 1.3): before ActivityThread handles the launch, swap the slot's
// Intent and ActivityInfo for the guest's own. From there AOSP builds the guest LoadedApk, Application, Activity,
// resources and theme by itself.
//
// API 28+: one EXECUTE_TRANSACTION message carrying a ClientTransaction whose callbacks include LaunchActivityItem
//          (fields mIntent / mInfo).
// API 26-27: LAUNCH_ACTIVITY with an ActivityClientRecord (fields intent / activityInfo).

class LaunchSwap(
    /** Called on the main thread right before the guest activity is created. */
    private val onGuestLaunch: (pkg: String, profile: Int, abi: String?) -> Unit,
    private val abiOf: (String) -> String?,
    private val tokens: com.blaze.kiln.route.LaunchToken,
) {
    private val LAUNCH_ACTIVITY = 100
    private val EXECUTE_TRANSACTION = 159

    fun install(): Boolean {
        val handler = ActivityThreadRef.handler() ?: return false
        val callbackField = HiddenApi.field(Handler::class.java, "mCallback") ?: return false
        val existing = HiddenApi.get(callbackField, handler) as? Handler.Callback
        if (existing is Swap) return true
        return HiddenApi.set(callbackField, handler, Swap(existing))
    }

    private inner class Swap(private val next: Handler.Callback?) : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            try {
                when (msg.what) {
                    EXECUTE_TRANSACTION -> if (Build.VERSION.SDK_INT >= 28) swapTransaction(msg.obj)
                    LAUNCH_ACTIVITY -> if (Build.VERSION.SDK_INT < 28) swapRecord(msg.obj)
                }
            } catch (t: Throwable) {
                KilnLog.e("launch swap failed; the AppComponentFactory fallback takes over", t)
            }
            // false = let ActivityThread handle the message as usual.
            return next?.handleMessage(msg) ?: false
        }
    }

    private fun swapTransaction(obj: Any?) {
        val tx = obj ?: return
        // Android 15 moved the items to getTransactionItems(); earlier releases use getCallbacks().
        val getter = (if (Build.VERSION.SDK_INT >= 35) HiddenApi.method(tx.javaClass, "getTransactionItems") else null)
            ?: HiddenApi.method(tx.javaClass, "getCallbacks")
        @Suppress("UNCHECKED_CAST")
        val callbacks = HiddenApi.call(getter, tx) as? List<Any> ?: return
        for (item in callbacks) {
            if (item.javaClass.name != "android.app.servertransaction.LaunchActivityItem") continue
            val intentField = HiddenApi.field(item.javaClass, "mIntent")
            val infoField = HiddenApi.field(item.javaClass, "mInfo")
            val intent = HiddenApi.get(intentField, item) as? Intent ?: continue
            val guest = guestFor(intent) ?: continue
            HiddenApi.set(intentField, item, guest.first)
            HiddenApi.set(infoField, item, guest.second)
        }
    }

    private fun swapRecord(obj: Any?) {
        val record = obj ?: return
        val intentField = HiddenApi.field(record.javaClass, "intent")
        val infoField = HiddenApi.field(record.javaClass, "activityInfo")
        val intent = HiddenApi.get(intentField, record) as? Intent ?: return
        val guest = guestFor(intent) ?: return
        HiddenApi.set(intentField, record, guest.first)
        HiddenApi.set(infoField, record, guest.second)
    }

    /** The guest's own Intent and ActivityInfo for a slot launch, or null when this is a normal Blaze activity. */
    private fun guestFor(slotIntent: Intent): Pair<Intent, ActivityInfo>? {
        val env = LaunchInterceptor.envelopeOf(slotIntent) ?: return null
        if (!tokens.verify(env.token, env.guestPackage, env.guestClass, env.profile)) {
            KilnLog.w("slot intent with an unknown token; ignoring")
            return null
        }
        val abi = abiOf(env.guestPackage)
        onGuestLaunch(env.guestPackage, env.profile, abi)
        val guestApp = GuestApp.loaded(env.guestPackage) ?: return null
        val info = guestApp.activity(env.guestClass) ?: run {
            KilnLog.w("guest activity not in its manifest")
            return null
        }
        val original = LaunchInterceptor.originalIntent(slotIntent) ?: Intent()
        val intent = Intent(original)
        intent.setClassName(env.guestPackage, env.guestClass)
        return intent to info
    }
}
