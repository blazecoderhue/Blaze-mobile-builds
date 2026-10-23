package com.blaze.assistant.kiln

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.blaze.assistant.BuildConfig
import com.blaze.kiln.diag.HarnessReport
import com.blaze.kiln.diag.HarnessStep
import com.blaze.kiln.diag.StepResult
import com.blaze.kiln.runtime.BlazeKiln
import com.blaze.kiln.runtime.KilnDiag
import java.io.File

/**
 * Blaze Kiln M1 test harness (kilnTest build type only; BuildConfig.KILN_HARNESS gates it a second time). Installs the
 * bundled Kiln test guest, or an APK the tester picks, into Kiln, launches it in the :g0 guest process and shows which
 * step failed and which system service refused. Plain Views on purpose: nothing here shares code with Blaze's UI.
 */
class KilnHarnessActivity : Activity() {
    private lateinit var out: TextView
    private val kiln by lazy { BlazeKiln.get(this) }
    private var sessionStart = 0L
    private var local = ArrayList<StepResult>()
    private var pkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.KILN_HARNESS) { finish(); return }
        KilnDiag.init(this)
        sessionStart = savedInstanceState?.getLong("session") ?: 0L
        pkg = savedInstanceState?.getString("pkg")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        col.addView(TextView(this).apply { text = "Blaze Kiln test (M1)"; textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · " +
                "${Build.SUPPORTED_ABIS.firstOrNull()} · page ${kiln.device().pageSize} B · build ${BuildConfig.VERSION_NAME}"
            textSize = 12f
        })
        fun button(label: String, onClick: () -> Unit) = col.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
        button("1. Install + launch bundled test app") { installAndLaunch(bundledGuest()) }
        button("Pick an APK file to install + launch…") { pickApk() }
        button("Restart guest (cold) + launch again") { launchAgain() }
        button("Stop guest process") { val n = kiln.stopGuestProcesses(); toastLine("Stopped $n guest process(es).") }
        button("Refresh diagnostics") { render() }
        button("Copy diagnostics") { copy() }
        button("Clear Kiln data (uninstall guests)") { clearAll() }
        out = TextView(this).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, 24, 0, 0) }
        col.addView(out)
        setContentView(ScrollView(this).apply { addView(col) })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::out.isInitialized) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("session", sessionStart)
        pkg?.let { outState.putString("pkg", it) }
    }

    private fun bundledGuest(): File {
        val f = File(cacheDir, "kiln-testguest.apk")
        assets.open("kiln/kiln-testguest.apk").use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun pickApk() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        startActivityForResult(i, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val f = File(cacheDir, "kiln-picked.apk")
        try {
            contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        } catch (t: Throwable) {
            newSession(); local += StepResult(HarnessStep.READ_APK, false, "could not read the picked file: ${t.javaClass.simpleName}")
            render(); return
        }
        installAndLaunch(f)
    }

    private fun newSession() {
        sessionStart = System.currentTimeMillis()
        local = ArrayList()
    }

    private fun installAndLaunch(apk: File) {
        newSession()
        // A guest process left from an earlier run would keep the old code loaded.
        kiln.stopGuestProcesses()
        val outcome = try { kiln.installWithReport(apk) } catch (t: Throwable) {
            local += StepResult(HarnessStep.READ_APK, false, "${t.javaClass.simpleName}: ${t.message}"); render(); return
        }
        local.addAll(outcome.steps)
        val rec = outcome.record
        if (rec == null || outcome.failedStep != null) { render(); return }
        pkg = rec.packageName
        launch(rec.packageName, fresh = false)
    }

    private fun launchAgain() {
        val p = pkg ?: kiln.installed().firstOrNull()?.packageName
        if (p == null) { toastLine("Nothing installed yet: tap step 1 first."); return }
        pkg = p
        newSession()
        kiln.stopGuestProcesses()
        local += StepResult(HarnessStep.READ_APK, true, "already installed: $p")
        local += StepResult(HarnessStep.INSTALL, true, "already installed")
        local += StepResult(HarnessStep.NATIVE_LIBS, true, "already installed")
        launch(p, fresh = true)
    }

    private fun launch(p: String, fresh: Boolean) {
        val intent = try { if (fresh) kiln.relaunchIntent(p) else kiln.launchIntent(p) } catch (t: Throwable) {
            local += StepResult(HarnessStep.PREPARE_LAUNCH, false, "${t.javaClass.simpleName}: ${t.message}"); render(); return
        }
        if (intent == null) {
            local += StepResult(HarnessStep.PREPARE_LAUNCH, false, "no launcher activity or no free guest process")
            render(); return
        }
        local += StepResult(HarnessStep.PREPARE_LAUNCH, true, intent.component?.className?.substringAfterLast('.') ?: "")
        try {
            startActivity(intent)
            local += StepResult(HarnessStep.START_SLOT, true, "startActivity accepted")
        } catch (t: Throwable) {
            local += StepResult(HarnessStep.START_SLOT, false, "${t.javaClass.simpleName}: ${t.message}")
        }
        render()
    }

    /** Steps 9-10 come from the guest's own start file: it proves onCreate ran and where its files really went. */
    private fun guestFileSteps(p: String): List<StepResult> {
        val dataDir = kiln.layout.dataDir(0, p)
        val start = File(File(dataDir, "files"), "kiln-guest-start.txt")
        if (!start.isFile || start.lastModified() < sessionStart - 2000) return emptyList()
        val kv = HarnessReport.parseKeyValues(start.readText())
        val created = kv["created"]?.toLongOrNull() ?: start.lastModified()
        val out = ArrayList<StepResult>()
        out += StepResult(HarnessStep.GUEST_ACTIVITY, created >= sessionStart - 2000,
            "pid ${kv["pid"]}, ${kv["orientation"]}, recreated=${kv["recreated"]}, taps=${kv["taps"]}, package=${kv["package"]}")
        val ok = HarnessReport.storageOk(dataDir.path, kv["filesDir"])
        out += StepResult(HarnessStep.GUEST_STORAGE, ok, (if (ok) "" else "expected ${dataDir.path}/files, got ") + (kv["filesDir"] ?: "?"))
        return out
    }

    private fun render() {
        if (sessionStart == 0L) {
            out.text = "Tap step 1 to install and launch the bundled Kiln test app.\nInstalled guests: " +
                kiln.installed().joinToString { it.packageName }.ifEmpty { "none" }
            return
        }
        val p = pkg
        val steps = ArrayList(local)
        if (p != null) steps += guestFileSteps(p)
        val report = HarnessReport.build(sessionStart, steps, KilnDiag.read(this))
        out.text = report.lines().joinToString("\n")
    }

    private fun copy() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = "Blaze Kiln M1 · Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n" + out.text
        cm?.setPrimaryClip(ClipData.newPlainText("Kiln diagnostics", text))
        toastLine("Diagnostics copied.")
    }

    private fun clearAll() {
        kiln.stopGuestProcesses()
        kiln.installed().forEach { kiln.uninstall(it.packageName) }
        KilnDiag.clear(this)
        pkg = null; sessionStart = 0L; local = ArrayList()
        render()
    }

    private fun toastLine(s: String) {
        out.text = s + "\n\n" + out.text
        out.visibility = View.VISIBLE
    }

    companion object { private const val REQ_PICK = 41 }
}
