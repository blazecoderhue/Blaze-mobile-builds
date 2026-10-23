// Blaze Player (Apache-2.0). Added by Blazeinchue.
package com.blaze.player

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ViewManager

/** JS side of the agent interface: queue persistence, library mirror, and the command signal. */
class AgentModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    override fun getName() = "BlazeAgent"

    init {
        AgentCommandBus.signal = { emit("BlazeAgentCommand", "") }
        AgentService.queueChanged = { emit("BlazeQueueChanged", "") }
    }

    private fun emit(event: String, payload: String) {
        if (ctx.hasActiveReactInstance()) {
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(event, payload)
        }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Double) {}

    @ReactMethod
    fun consumePendingCommand(promise: Promise) = promise.resolve(AgentCommandBus.consume())

    @ReactMethod
    fun getQueue(promise: Promise) = promise.resolve(AgentQueue.list(ctx).toString())

    @ReactMethod
    fun setQueue(json: String, promise: Promise) {
        AgentQueue.set(ctx, json)
        promise.resolve(true)
    }

    @ReactMethod
    fun setLibrary(json: String) = AgentSearch.setLibrary(ctx, json)
}

class AgentPackage : ReactPackage {
    override fun createNativeModules(ctx: ReactApplicationContext): List<NativeModule> = listOf(AgentModule(ctx))
    override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
