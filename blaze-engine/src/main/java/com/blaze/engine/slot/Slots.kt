package com.blaze.engine.slot

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// Placeholder activities declared in the host manifest (ENGINE_DESIGN.md 1.1). When the launch swap works, Android
// never instantiates these: the guest's own activity class is created in their place. If one does run, the swap
// failed on this Android release, and it says so instead of crashing. M1 declares process :g0 only.

abstract class SlotActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "This app could not start in Blaze Phone on this Android version."
            setPadding(48, 96, 48, 48)
        })
        com.blaze.engine.runtime.EngineLog.w("slot ${javaClass.simpleName} ran without a guest (launch swap missed)")
    }
}

class A0_0_0 : SlotActivity()
class A0_0_1 : SlotActivity()
class A0_0_2 : SlotActivity()
class A0_0_3 : SlotActivity()
class A0_0_4 : SlotActivity()
class A0_0_5 : SlotActivity()
class A0_0_6 : SlotActivity()
class A0_0_7 : SlotActivity()
class A0_0_8 : SlotActivity()
class A0_0_9 : SlotActivity()
class A0_0_10 : SlotActivity()
class A0_0_11 : SlotActivity()
class A0_1_0 : SlotActivity()
class A0_1_1 : SlotActivity()
class A0_1_2 : SlotActivity()
class A0_1_3 : SlotActivity()
class A0_2_0 : SlotActivity()
class A0_2_1 : SlotActivity()
class A0_2_2 : SlotActivity()
class A0_2_3 : SlotActivity()
class A0_3_0 : SlotActivity()
class A0_3_1 : SlotActivity()

/** Bootstrap provider (process :g0): created before any activity in the process, so interception is in place first. */
class P0 : android.content.ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        com.blaze.engine.runtime.BlazeEngine.get(ctx).installHooks()
        return true
    }
    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
