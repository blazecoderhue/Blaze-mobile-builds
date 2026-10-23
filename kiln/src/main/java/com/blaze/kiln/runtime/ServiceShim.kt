package com.blaze.kiln.runtime

import android.os.IBinder
import com.blaze.kiln.route.IdentityRewriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

// Java-level binder interception (ENGINE_DESIGN.md 1.4). Each system service a process talks to is an AIDL
// IInterface proxy cached in this process; we replace the cached instance with a java.lang.reflect.Proxy that
// rewrites identity on the way out and answers some calls locally. The real binder still does the work, so an
// unhandled method on a new Android release degrades to plain pass-through instead of breaking.

/** Handles one method of one service. Return [PassThrough] to let the real service answer. */
fun interface ShimHandler {
    fun handle(call: ShimCall): Any?
}

class ShimCall(
    val serviceName: String,
    val method: Method,
    val args: Array<Any?>,
    val rewriter: IdentityRewriter,
) {
    fun invokeReal(target: Any): Any? = try {
        method.invoke(target, *args)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.cause ?: e
    }
}

object PassThrough

class ServiceShim(
    private val serviceName: String,
    private val real: Any,
    private val rewriter: IdentityRewriter,
    private val adapters: List<IdentityRewriter.ArgAdapter>,
    private val handlers: Map<String, ShimHandler>,
) : InvocationHandler {

    override fun invoke(proxy: Any?, method: Method, rawArgs: Array<Any?>?): Any? {
        val args = rawArgs ?: emptyArray()
        // asBinder must keep returning the real binder: the system compares it by identity.
        if (method.name == "asBinder") return (real as? android.os.IInterface)?.asBinder() ?: method.invoke(real)
        try {
            // Handlers see the guest's own arguments (e.g. getPackageInfo("org.guest")); only what goes to the
            // real service is rewritten to Blaze's identity.
            handlers[method.name]?.let { h ->
                val r = h.handle(ShimCall(serviceName, method, args, rewriter))
                if (r !== PassThrough) return r
            }
            rewriter.rewriteArgs(args, adapters)
            return method.invoke(real, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        } catch (t: Throwable) {
            KilnLog.e("$serviceName.${method.name} shim failed; passing through", t)
            return try { method.invoke(real, *args) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.cause ?: e }
        }
    }

    companion object {
        /** Wraps [real] (an AIDL IInterface) in a proxy implementing the same interfaces. */
        fun wrap(
            serviceName: String,
            real: Any,
            rewriter: IdentityRewriter,
            adapters: List<IdentityRewriter.ArgAdapter> = emptyList(),
            handlers: Map<String, ShimHandler> = emptyMap(),
        ): Any = Proxy.newProxyInstance(
            real.javaClass.classLoader,
            allInterfaces(real.javaClass),
            ServiceShim(serviceName, real, rewriter, adapters, handlers),
        )

        private fun allInterfaces(cls: Class<*>): Array<Class<*>> {
            val out = LinkedHashSet<Class<*>>()
            var c: Class<*>? = cls
            while (c != null) { out.addAll(c.interfaces); c = c.superclass }
            if (out.isEmpty()) out.add(IBinder::class.java)
            return out.toTypedArray()
        }
    }
}

/** Framework-type argument rewrites (ComponentName, AttributionSource) kept beside the pure rewriter. */
object AndroidArgAdapters {
    fun componentName() = object : IdentityRewriter.ArgAdapter {
        override fun accepts(value: Any) = value is android.content.ComponentName
        override fun rewrite(value: Any, rw: IdentityRewriter): Any {
            val cn = value as android.content.ComponentName
            if (!rw.isGuest(cn.packageName)) return cn
            val (p, c) = rw.outboundComponent(cn.packageName, cn.className)
            return android.content.ComponentName(p, c)
        }
    }

    /** Android 12+ AttributionSource carries uid + package and is checked against the caller. */
    fun attributionSource() = object : IdentityRewriter.ArgAdapter {
        private val cls = HiddenApi.classOrNull("android.content.AttributionSource")
        override fun accepts(value: Any) = cls != null && cls.isInstance(value)
        override fun rewrite(value: Any, rw: IdentityRewriter): Any {
            val pkg = HiddenApi.call(HiddenApi.method(value.javaClass, "getPackageName"), value) as? String ?: return value
            if (!rw.isGuest(pkg)) return value
            val builderCls = HiddenApi.classOrNull("android.content.AttributionSource\$Builder") ?: return value
            return try {
                val b = builderCls.getConstructor(Int::class.javaPrimitiveType).newInstance(android.os.Process.myUid())
                HiddenApi.call(HiddenApi.method(builderCls, "setPackageName", String::class.java), b, rw.hostPackage)
                HiddenApi.call(HiddenApi.method(builderCls, "build"), b) ?: value
            } catch (t: Throwable) {
                KilnLog.w("AttributionSource rewrite failed: ${t.javaClass.simpleName}")
                value
            }
        }
    }

    fun all(): List<IdentityRewriter.ArgAdapter> =
        if (android.os.Build.VERSION.SDK_INT >= 31) listOf(componentName(), attributionSource()) else listOf(componentName())
}
