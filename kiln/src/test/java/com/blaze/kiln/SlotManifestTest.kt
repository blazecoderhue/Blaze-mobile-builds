package com.blaze.kiln

import com.blaze.kiln.manifest.LaunchMode
import com.blaze.kiln.route.GuestProcesses
import com.blaze.kiln.route.SlotId
import com.blaze.kiln.route.SlotKind
import com.blaze.kiln.route.SlotPlan
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The slot manifest Android actually starts must match the allocator's plan exactly (M1: process :g0). */
class SlotManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val doc by lazy {
        val f = File(System.getProperty("kiln.manifest") ?: "src/main/AndroidManifest.xml")
        check(f.isFile) { "manifest not found: ${f.absolutePath}" }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
    }
    private fun elements(tag: String): List<Element> {
        val nl = doc.getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }
    private fun Element.a(name: String): String? = getAttributeNS(ns, name).ifEmpty { null }

    @Test
    fun activitySlotsMatchThePlan() {
        val acts = elements("activity")
        val declared = acts.map { it.a("name")!! }.toSet()
        val planned = HashSet<String>()
        for (p in 0 until GuestProcesses.DECLARED) for ((mode, n) in SlotPlan.ACTIVITIES) for (i in 0 until n)
            planned += SlotId(p, SlotKind.ACTIVITY, mode, i).className
        assertEquals(declared, planned)
        assertEquals(acts.size, declared.size, "no duplicates")
    }

    @Test
    fun everySlotIsPrivateInG0WithItsLaunchModeAndRelaunchesOnRotation() {
        for (e in elements("activity")) {
            val name = e.a("name")!!
            val id = SlotId.parse(name)!!
            assertEquals(e.a("process"), ":g${id.process}", name)
            assertEquals(e.a("exported"), "false", name)
            assertEquals(e.a("taskAffinity"), "com.blaze.kiln.task.g${id.process}", name)
            val mode = when (id.launchMode) {
                LaunchMode.STANDARD -> "standard"; LaunchMode.SINGLE_TOP -> "singleTop"
                LaunchMode.SINGLE_TASK -> "singleTask"; LaunchMode.SINGLE_INSTANCE -> "singleInstance"
                else -> "?"
            }
            assertEquals(e.a("launchMode"), mode, name)
            assertFalse(e.hasAttributeNS(ns, "configChanges"), "$name: M1 relaunches the guest on config changes")
        }
    }

    @Test
    fun bootstrapProviderStartsTheGuestProcessFirst() {
        val p = elements("provider").single()
        assertEquals(p.a("name"), "com.blaze.kiln.slot.P0")
        assertEquals(p.a("process"), ":g0")
        assertEquals(p.a("exported"), "false")
        assertEquals(p.a("authorities"), "\${applicationId}.kiln.p0")
        assertTrue((p.a("initOrder") ?: "0").toInt() > 0)
    }

    @Test
    fun everyDeclaredClassExistsInTheModule() {
        val names = elements("activity").map { it.a("name")!! } + elements("provider").map { it.a("name")!! }
        for (n in names) Class.forName(n, false, javaClass.classLoader)
    }
}
