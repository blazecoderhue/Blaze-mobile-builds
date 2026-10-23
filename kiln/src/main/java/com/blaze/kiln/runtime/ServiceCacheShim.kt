package com.blaze.kiln.runtime

import android.os.IBinder
import android.os.IInterface
import com.blaze.kiln.route.IdentityRewriter

// Wraps system services that a guest reaches through ServiceManager (ENGINE_DESIGN.md 1.4). AOSP's
// ServiceManager.getService(name) returns sCache[name] when present, and every AIDL Stub.asInterface(binder) first asks
// binder.queryLocalInterface(DESCRIPTOR). So we put a binder into sCache that delegates everything to the real one but
// answers queryLocalInterface with a ServiceShim proxy: managers created afterwards (every new ContextImpl's) talk to
// the shim, which rewrites the guest's identity to Blaze's and records refusals. Must run before the guest's first
// getSystemService, which the :gN bootstrap provider guarantees. Sources: AOSP ServiceManager.java, Binder/IBinder,
// AIDL-generated Stub.asInterface (Apache-2.0).

object ServiceCacheShim {
    fun install(names: List<String>, rewriter: IdentityRewriter, adapters: List<IdentityRewriter.ArgAdapter>): List<String> {
        val sm = HiddenApi.classOrNull("android.os.ServiceManager") ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val cache = HiddenApi.get(HiddenApi.field(sm, "sCache"), null) as? MutableMap<String, IBinder> ?: return emptyList()
        val getService = HiddenApi.method(sm, "getService", String::class.java)
        val done = ArrayList<String>()
        for (name in names) {
            try {
                val existing = cache[name]
                if (existing is ShimBinder) { done += name; continue }
                val real = existing ?: (HiddenApi.call(getService, null, name) as? IBinder) ?: continue
                val descriptor = real.interfaceDescriptor ?: continue
                val stub = HiddenApi.classOrNull("$descriptor\$Stub") ?: continue
                val iface = HiddenApi.call(HiddenApi.method(stub, "asInterface", IBinder::class.java), null, real) ?: continue
                val proxy = ServiceShim.wrap(name, iface, rewriter, adapters) as? IInterface ?: continue
                cache[name] = ShimBinder(real, descriptor, proxy)
                done += name
            } catch (t: Throwable) {
                KilnLog.w("service cache shim for $name failed: ${t.javaClass.simpleName}")
            }
        }
        return done
    }
}

/** Delegates every IBinder call to [real]; only the local-interface lookup returns our shim. */
class ShimBinder(private val real: IBinder, private val descriptor: String, private val local: IInterface) : IBinder by real {
    override fun queryLocalInterface(descriptor: String): IInterface? =
        if (descriptor == this.descriptor) local else real.queryLocalInterface(descriptor)
}
