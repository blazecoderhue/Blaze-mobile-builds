package com.blaze.kiln.runtime

import android.os.Build
import java.lang.reflect.Field
import java.lang.reflect.Method

// The single door to non-SDK framework members (ENGINE_DESIGN.md 1.8 and hard part 1). Everything Kiln reflects
// on goes through here, so one file lists our whole exposure and each Android release is checked against it.
// On API 28+ the hidden-API blocklist is lifted with AndroidHiddenApiBypass (Apache-2.0, ENGINE_DEPS.md).
// Nothing here throws on failure: callers get null and Kiln reports an unsupported-platform error instead of
// crashing Blaze.

object HiddenApi {
    @Volatile private var exemptionsTried = false
    @Volatile var exemptionsGranted = false
        private set

    /** Lifts the non-SDK blocklist for this process. Safe to call more than once; no-op below API 28. */
    @Synchronized
    fun unseal(): Boolean {
        if (Build.VERSION.SDK_INT < 28) { exemptionsGranted = true; return true }
        if (exemptionsTried) return exemptionsGranted
        exemptionsTried = true
        exemptionsGranted = try {
            val cls = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
            val m = cls.getMethod("addHiddenApiExemptions", Array<String>::class.java)
            m.invoke(null, arrayOf("L")) as? Boolean ?: true
        } catch (t: Throwable) {
            KilnLog.w("hidden-API exemptions unavailable: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        return exemptionsGranted
    }

    fun classOrNull(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (t: Throwable) {
        KilnLog.w("missing class $name")
        null
    }

    fun method(owner: Class<*>?, name: String, vararg params: Class<*>): Method? {
        if (owner == null) return null
        unseal()
        var c: Class<*>? = owner
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *params).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            } catch (t: Throwable) {
                KilnLog.w("method $name on ${owner.name}: ${t.javaClass.simpleName}")
                return null
            }
        }
        KilnLog.w("no method $name on ${owner.name}")
        return null
    }

    fun field(owner: Class<*>?, name: String): Field? {
        if (owner == null) return null
        unseal()
        var c: Class<*>? = owner
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (t: Throwable) {
                KilnLog.w("field $name on ${owner.name}: ${t.javaClass.simpleName}")
                return null
            }
        }
        KilnLog.w("no field $name on ${owner.name}")
        return null
    }

    fun get(field: Field?, target: Any?): Any? = try { field?.get(target) } catch (t: Throwable) { KilnLog.w("get ${field?.name}: $t"); null }

    fun set(field: Field?, target: Any?, value: Any?): Boolean = try {
        field?.set(target, value); field != null
    } catch (t: Throwable) { KilnLog.w("set ${field?.name}: $t"); false }

    fun call(method: Method?, target: Any?, vararg args: Any?): Any? = try {
        method?.invoke(target, *args)
    } catch (t: Throwable) {
        KilnLog.w("call ${method?.name}: ${t.cause ?: t}")
        null
    }

    /** Every non-SDK member Kiln touches, for the per-release compatibility check. */
    val SURFACE: List<String> = listOf(
        "android.app.ActivityThread#currentActivityThread()",
        "android.app.ActivityThread#getPackageInfo(ApplicationInfo,CompatibilityInfo,int)",
        "android.app.ActivityThread#mH",
        "android.app.ActivityThread#sPackageManager",
        "android.app.ActivityThread#getApplicationThread()",
        "android.app.ActivityManager#IActivityManagerSingleton",
        "android.app.ActivityTaskManager#IActivityTaskManagerSingleton",
        "android.util.Singleton#mInstance",
        "android.os.ServiceManager#sCache",
        "android.os.ServiceManager#getService(String)",
        "android.app.servertransaction.ClientTransaction#getCallbacks()",
        "android.app.servertransaction.LaunchActivityItem#mIntent",
        "android.app.servertransaction.LaunchActivityItem#mInfo",
        "android.app.ActivityThread\$ActivityClientRecord#intent",
        "android.app.ActivityThread\$ActivityClientRecord#activityInfo",
        "android.content.pm.ApplicationInfo#credentialProtectedDataDir",
        "android.content.pm.ApplicationInfo#deviceProtectedDataDir",
        "android.content.pm.ApplicationInfo#scanSourceDir",
    )
}

/** Logging that never pulls a third-party SDK in and never prints guest content. */
object KilnLog {
    var enabled: Boolean = true
    private const val TAG = "BlazeKiln"

    fun d(msg: String) { if (enabled) android.util.Log.d(TAG, msg) }
    fun w(msg: String) { if (enabled) android.util.Log.w(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) { if (enabled) android.util.Log.e(TAG, msg, t) }
}
