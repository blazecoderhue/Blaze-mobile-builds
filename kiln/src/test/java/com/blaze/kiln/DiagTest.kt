package com.blaze.kiln

import com.blaze.kiln.diag.DiagEntry
import com.blaze.kiln.diag.DiagKind
import com.blaze.kiln.diag.DiagLog
import com.blaze.kiln.diag.HarnessReport
import com.blaze.kiln.diag.HarnessStep
import com.blaze.kiln.diag.RefusalClassifier
import com.blaze.kiln.diag.RefusalClassifier.Frame
import com.blaze.kiln.diag.StepResult
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/** M1 harness diagnostics: log format, refusal classification, step report (all pure, no Android). */
class DiagTest {

    @Test
    fun logLineRoundTripsHostileText() {
        val nasty = "tab\there\nnew line\r\\back\\slash \\t literal ünïcødé"
        val e = DiagEntry(1_700_000_000_123L, ":g0", DiagKind.REFUSAL, "notification.enqueueToast\tx", false, nasty)
        val line = DiagLog.format(e)
        assertFalse(line.contains('\n'), "one event = one line")
        assertEquals(line.count { it == '\t' }, 5, "exactly six fields")
        assertEquals(DiagLog.parse(line), e)
        for (ok in listOf(true, false, null)) assertEquals(DiagLog.parse(DiagLog.format(e.copy(ok = ok)))!!.ok, ok)
    }

    @Test
    fun parserSkipsTornAndForeignLines() {
        val good = DiagLog.format(DiagEntry(5, "main", DiagKind.STEP, "INSTALL", true, "ok"))
        val text = listOf("", "garbage", "12\tmain\tSTEP\tX\t1", "abc\tmain\tSTEP\tX\t1\td", "12\tmain\tNOPE\tX\t1\td",
            "12\tmain\tSTEP\tX\tmaybe\td", good, good.dropLast(3) + "\t\t").joinToString("\n")
        val all = DiagLog.parseAll(text)
        assertEquals(all.size, 1)
        assertEquals(all[0].key, "INSTALL")
    }

    @Test
    fun longDetailIsCapped() {
        val e = DiagEntry(1, "main", DiagKind.INFO, "k", null, "x".repeat(DiagLog.MAX_FIELD * 3))
        assertEquals(DiagLog.parse(DiagLog.format(e))!!.detail.length, DiagLog.MAX_FIELD)
    }

    @Test
    fun unescapeLeavesUnknownEscapes() {
        assertEquals(DiagLog.unesc("a\\qb\\"), "a\\qb\\")
        assertEquals(DiagLog.unesc(DiagLog.esc("\\\\t")), "\\\\t")
    }

    private fun throwableWith(cls: String, vararg frames: Pair<String, String>): SecurityException =
        SecurityException("Package org.guest does not belong to 10234").apply {
            stackTrace = frames.map { StackTraceElement(it.first, it.second, "X.java", 1) }.toTypedArray()
        }

    @Test
    fun unhookedServiceNamedFromBinderProxyFrame() {
        val t = throwableWith("x",
            "android.os.Parcel" to "createExceptionOrNull",
            "android.os.Parcel" to "readException",
            "android.app.INotificationManager\$Stub\$Proxy" to "enqueueToast",
            "android.widget.Toast" to "show",
            "org.guest.Main" to "onClick")
        val r = RefusalClassifier.classify(t)!!
        assertEquals(r.aidlInterface, "INotificationManager")
        assertEquals(r.method, "enqueueToast")
        assertEquals(r.serviceName, "notification")
        assertTrue(r.security)
        assertTrue(r.hooked, "notification is wrapped in M1")
        assertTrue(r.describe().contains("INotificationManager.enqueueToast (service \"notification\")"))
    }

    @Test
    fun serviceOutsideM1IsReportedNotHooked() {
        val t = throwableWith("x", "android.location.ILocationManager\$Stub\$Proxy" to "getLastLocation", "android.location.LocationManager" to "getLastKnownLocation")
        val r = RefusalClassifier.classify(t)!!
        assertEquals(r.serviceName, "location")
        assertFalse(r.hooked)
        assertTrue(r.describe().contains("NOT hooked in M1"))
    }

    @Test
    fun innermostSecurityExceptionWinsOverWrapper() {
        val inner = throwableWith("x", "com.android.internal.view.IInputMethodManager\$Stub\$Proxy" to "startInputOrWindowGainedFocus")
        val outer = RuntimeException("Unable to start activity", inner).apply {
            stackTrace = arrayOf(StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "A.java", 1))
        }
        val r = RefusalClassifier.classify(outer)!!
        assertEquals(r.exception, "SecurityException")
        assertEquals(r.serviceName, "input_method")
        assertTrue(r.hooked)
    }

    @Test
    fun managerFrameIsTheFallbackWhenNoProxyFrame() {
        val t = IllegalStateException("boom").apply {
            stackTrace = arrayOf(StackTraceElement("android.content.ContentResolver", "query", "C.java", 1),
                StackTraceElement("org.guest.Main", "onCreate", "M.java", 1))
        }
        val r = RefusalClassifier.classify(t)!!
        assertNull(r.aidlInterface)
        assertEquals(r.method, "ContentResolver.query")
        assertFalse(r.security)
        assertFalse(r.hooked)
    }

    @Test
    fun selfCausedCycleTerminates() {
        val a = RuntimeException("a"); val b = RuntimeException("b", a)
        a.initCause(b)
        assertNotNull(RefusalClassifier.classify(b))
        assertNull(RefusalClassifier.classify(emptyList<Triple<String, String?, List<Frame>>>()))
    }

    @Test
    fun hookedListsAreConsistent() {
        assertTrue(RefusalClassifier.HOOKED_SERVICES.containsAll(RefusalClassifier.CACHE_SHIMMED))
        assertTrue(RefusalClassifier.SERVICE_OF.values.toSet().containsAll(RefusalClassifier.HOOKED_SERVICES))
        assertEquals(RefusalClassifier.CACHE_SHIMMED.toSet().size, RefusalClassifier.CACHE_SHIMMED.size)
        // activity/package are shimmed through their process singletons, never through the binder cache.
        for (s in listOf("activity", "activity_task", "package")) assertFalse(s in RefusalClassifier.CACHE_SHIMMED)
    }

    private fun step(at: Long, s: HarnessStep, ok: Boolean, d: String = "", proc: String = ":g0") =
        DiagEntry(at, proc, DiagKind.STEP, s.name, ok, d)

    private fun mainSteps(ok: Boolean = true) = listOf(
        StepResult(HarnessStep.READ_APK, true, "pkg"), StepResult(HarnessStep.INSTALL, true, ""),
        StepResult(HarnessStep.NATIVE_LIBS, true, ""), StepResult(HarnessStep.PREPARE_LAUNCH, true, ""),
        StepResult(HarnessStep.START_SLOT, ok, ""))

    @Test
    fun allStepsPass() {
        val guest = listOf(HarnessStep.GUEST_HOOKS, HarnessStep.GUEST_LOADED, HarnessStep.GUEST_SWAP).mapIndexed { i, s -> step(1000L + i, s, true) }
        val local = mainSteps() + StepResult(HarnessStep.GUEST_ACTIVITY, true, "") + StepResult(HarnessStep.GUEST_STORAGE, true, "")
        val r = HarnessReport.build(1000, local, guest)
        assertNull(r.firstFailure)
        assertTrue(r.verdict.startsWith("PASS"), r.verdict)
        assertEquals(r.lines().count { it.startsWith("OK") }, HarnessStep.entries.size)
    }

    @Test
    fun firstFailedStepIsNamedAndStaleEventsIgnored() {
        val entries = listOf(
            step(10, HarnessStep.GUEST_HOOKS, false, "old run"),           // before the session: ignored
            step(2000, HarnessStep.GUEST_HOOKS, true),
            step(2001, HarnessStep.GUEST_LOADED, false, "could not read the guest APK"),
            step(2002, HarnessStep.GUEST_SWAP, false, "guest not loaded"),
            DiagEntry(2003, ":g0", DiagKind.REFUSAL, "notification.enqueueToast", false, "SecurityException: x (hooked by Kiln)"),
            DiagEntry(2003, ":g0", DiagKind.REFUSAL, "notification.enqueueToast", false, "SecurityException: x (hooked by Kiln)"),
        )
        val r = HarnessReport.build(1500, mainSteps(), entries)
        assertEquals(r.firstFailure, HarnessStep.GUEST_LOADED)
        assertEquals(r.verdict, "FAILED at step 7: ${HarnessStep.GUEST_LOADED.label}")
        assertEquals(r.steps.first { it.step == HarnessStep.GUEST_HOOKS }.ok, true)
        assertEquals(r.refusals.size, 1, "duplicates collapse")
        assertTrue(r.lines().any { it.contains("System services that refused") })
    }

    @Test
    fun newestEventPerStepWins() {
        val entries = listOf(step(2000, HarnessStep.GUEST_SWAP, false, "first try"), step(2500, HarnessStep.GUEST_SWAP, true, "retry"))
        val r = HarnessReport.build(1000, mainSteps(), entries.reversed())
        assertEquals(r.steps.first { it.step == HarnessStep.GUEST_SWAP }.detail, "retry")
    }

    @Test
    fun crashAfterLastGoodStepBlamesTheNextStep() {
        val entries = listOf(step(2000, HarnessStep.GUEST_HOOKS, true), step(2001, HarnessStep.GUEST_LOADED, true),
            step(2002, HarnessStep.GUEST_SWAP, true),
            DiagEntry(2003, ":g0", DiagKind.CRASH, "java.lang.SecurityException", false, "SecurityException from IClipboard.getPrimaryClip"))
        val r = HarnessReport.build(1000, mainSteps(), entries)
        assertEquals(r.firstFailure, HarnessStep.GUEST_ACTIVITY)
        assertEquals(r.crashes.single(), "[:g0] SecurityException from IClipboard.getPrimaryClip")
    }

    @Test
    fun mainProcessFailureStopsTheReportThere() {
        val r = HarnessReport.build(1000, mainSteps(ok = false), emptyList())
        assertEquals(r.firstFailure, HarnessStep.START_SLOT)
        val notStarted = HarnessReport.build(1000, emptyList(), emptyList())
        assertEquals(notStarted.verdict, "Not started")
        val partial = HarnessReport.build(1000, mainSteps(), emptyList())
        assertTrue(partial.verdict.startsWith("In progress: 5/10"), partial.verdict)
    }

    @Test
    fun storageMustBeExactlyTheGuestDataDir() {
        val d = "/data/user/0/com.blaze.assistant.kilntest/files/kiln/data/u0/com.blaze.kiln.testguest"
        assertTrue(HarnessReport.storageOk(d, "$d/files"))
        assertTrue(HarnessReport.storageOk("$d/", "$d/files/"))
        assertFalse(HarnessReport.storageOk(d, null))
        assertFalse(HarnessReport.storageOk(d, "/data/user/0/com.blaze.kiln.testguest/files"), "real app dir = not redirected")
        assertFalse(HarnessReport.storageOk(d, "${d}2/files"), "sibling package prefix")
        assertFalse(HarnessReport.storageOk(d, "$d/files/../../other/files"))
        assertFalse(HarnessReport.storageOk(d, "$d/cache"))
    }

    @Test
    fun keyValuesParse() {
        val kv = HarnessReport.parseKeyValues("created=12\nfilesDir=/a=b\n=bad\nnoeq\npid=7\n")
        assertEquals(kv, mapOf("created" to "12", "filesDir" to "/a=b", "pid" to "7"))
    }

    @Test
    fun everyStepHasAUniqueLabelAndGuestStepsComeLast() {
        assertEquals(HarnessStep.entries.map { it.label }.toSet().size, HarnessStep.entries.size)
        val firstGuest = HarnessStep.entries.indexOfFirst { it.inGuestProcess }
        assertTrue(HarnessStep.entries.drop(firstGuest).all { it.inGuestProcess })
    }
}
