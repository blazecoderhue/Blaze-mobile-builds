package com.blaze.assistant

import android.graphics.Color
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.notify.Notifier
import com.blaze.assistant.voice.Voice
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.ChatScreen

class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(false)
    private var leftAt = 0L

    companion object { const val EXTRA_LISTEN = "listen"; const val ACTION_CALL = "com.blaze.assistant.CALL" }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.blaze.assistant.ui.MotionTokens.refresh(this)
        super.onCreate(savedInstanceState)
        // v0.24.7: one Blaze window. "Open Blaze" from the hold-Home panel starts MainActivity through
        // startAssistantActivity, which Android puts in a separate (assistant) task even with singleTask, so two Blaze
        // cards showed in Recents. If another task already holds MainActivity, hand it this intent and close this copy.
        if (savedInstanceState == null && joinExistingTask()) return
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        locked.value = com.blaze.assistant.agent.Settings.appLock
        setContent { BlazeTheme {
            if (locked.value) com.blaze.assistant.ui.LockScreen { unlock() } else ChatScreen()
        } }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        if (savedInstanceState == null) handle(intent) else authLink(intent)   // v0.45.4: a sign-in link must land even after a restore
        // Long-press the Blaze icon → "Call Blaze".
        try {
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
            sm?.dynamicShortcuts = listOf(android.content.pm.ShortcutInfo.Builder(this, "call")
                .setShortLabel("Call Blaze").setLongLabel("Call Blaze")
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, applicationInfo.icon))
                .setIntent(IntentKey.sign(this, android.content.Intent(this, MainActivity::class.java).setAction(ACTION_CALL))).build())
        } catch (_: Exception) { }
    }

    /** True when an older Blaze task was brought to the front and this duplicate task is being closed. */
    private fun joinExistingTask(): Boolean {
        val am = getSystemService(android.app.ActivityManager::class.java) ?: return false
        val tasks = try { am.appTasks } catch (_: Exception) { return false }
        val rows = tasks.mapNotNull { t ->
            try {
                val info = t.taskInfo
                @Suppress("DEPRECATION")
                val id = if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.id
                TaskDedupe.Row(id, info.baseActivity?.className ?: info.baseIntent?.component?.className)
            } catch (_: Exception) { null }
        }
        val keep = TaskDedupe.keep(taskId, rows, MainActivity::class.java.name) ?: return false
        val other = tasks.getOrNull(rows.indexOfFirst { it.id == keep }) ?: return false
        return try {
            handle(intent)
            other.moveToFront()
            finishAndRemoveTask()
            true
        } catch (_: Exception) { false }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); handle(intent)
    }

    /**
     * v0.45.4: Google / GitHub sign-in comes back as blaze://auth/cb?code=..&state=.. . BlazeAccount only accepts the
     * state it started (PKCE-bound), so a stray link does nothing. True when [i] was such a link (now consumed).
     */
    private fun authLink(i: android.content.Intent?): Boolean {
        val u = i?.data?.toString() ?: return false
        if (i.action != android.content.Intent.ACTION_VIEW || !com.blaze.assistant.connect.AuthReturn.isAuthLink(u)) return false
        i.data = null; i.action = null
        com.blaze.assistant.connect.BlazeAccount.onAppRedirect(applicationContext, u)
        return true
    }

    /** Assistant button, wake word, quick tile → listen. Share sheet → ask about the shared text. */
    private fun handle(i: android.content.Intent?) {
        i ?: return
        if (authLink(i)) return
        // v0.45.0: internal extras only count when Blaze itself sent the intent (IntentKey); another app can still open
        // Blaze, use the assistant button / voice command, or share into the message box.
        val ok = IntentKey.Policy.allowed(i.action, i.getBooleanExtra(EXTRA_LISTEN, false), i.getStringExtra(Notifier.EXTRA_OPEN_CHAT) != null,
            i.getBooleanExtra(Notifier.EXTRA_OPEN_FORM, false), IntentKey.trusted(this, i), ACTION_CALL)
        val chatId = i.getStringExtra(Notifier.EXTRA_OPEN_CHAT)
        val askedListen = i.getBooleanExtra(EXTRA_LISTEN, false) || i.action == ACTION_CALL
        i.removeExtra(Notifier.EXTRA_OPEN_FORM); i.removeExtra(Notifier.EXTRA_OPEN_CHAT); i.removeExtra(EXTRA_LISTEN); i.removeExtra(IntentKey.EXTRA)
        if (askedListen && !ok.listen && !ok.call) com.blaze.assistant.device.CrashLog.write("Ignored a listen/call request from outside Blaze")
        // "Action required" notification: open the chat that's waiting on the secure form (the card shows there).
        if (ok.openForm) { Agent.showFormChat(); Notifier.clearAction() }
        // v0.28.3: a reply notification from a project chat opens that chat in Projects (the shell follows Agent's chat).
        if (ok.openChat && chatId != null) Agent.openFromLink(chatId)
        if (i.action == ACTION_CALL) { i.action = null; if (ok.call) Voice.callRequests.value = Voice.callRequests.value + 1; return }
        if (ok.listen) {
            i.action = null
            Voice.listenRequests.value = Voice.listenRequests.value + 1
        }
        if (i.action == android.content.Intent.ACTION_SEND) {
            val shared = i.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty().trim()
            @Suppress("DEPRECATION")
            val stream = i.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            i.action = null
            // Never sent on its own: another app could share instructions the user didn't write.
            // It waits in the message box until the user taps Send.
            if (stream != null) {
                // App scope: this activity may be a duplicate that closes right away (joinExistingTask).
                kotlinx.coroutines.MainScope().launch {
                    val a = com.blaze.assistant.agent.Attachments.load(applicationContext, stream)
                    Agent.sharedDraft.value = Agent.SharedDraft(shared, a)
                }
                return
            }
            if (shared.isNotEmpty()) Agent.sharedDraft.value = Agent.SharedDraft(shared)
        }
    }

    private val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Fingerprint / face / PIN. If the phone has no lock set up, Blaze stays open. */
    private fun unlock() {
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { locked.value = false; return }
        BiometricPrompt(this, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { locked.value = false }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Blaze").setAllowedAuthenticators(auth).build())
    }

    override fun onStart() {
        super.onStart()
        if (com.blaze.assistant.agent.Settings.appLock && leftAt > 0 && System.currentTimeMillis() - leftAt > 30_000) locked.value = true
        if (locked.value) window.decorView.post { unlock() }
    }

    override fun onStop() { leftAt = System.currentTimeMillis(); super.onStop() }

    override fun onResume() {
        com.blaze.assistant.ui.MotionTokens.refresh(this)   // v0.27.0: follow "Remove animations"
        super.onResume(); Agent.inForeground = true
        com.blaze.assistant.agent.SelfControl.activity = java.lang.ref.WeakReference(this)
        Notifier.clearReplies(); Notifier.visible(true)
        com.blaze.assistant.voice.WakeWordService.start(this)
        com.blaze.assistant.connect.CloudSync.foreground(true)   // v0.40.0: a round now and every minute while open (when on)
        com.blaze.assistant.agent.LlmClient.prewarm()   // v0.45.1: DNS + TLS to Blaze Cloud while the user reads / types (once a minute at most)
        kotlin.concurrent.thread(isDaemon = true, name = "entitlements") { runCatching { com.blaze.assistant.connect.Entitlements.refresh(applicationContext) } }   // v0.45.18: plan + limits, when stale
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // v0.25.0: no key catcher any more (Blaze Cloud holds the keys); nothing reads the clipboard here.
    }

    override fun onPause() {
        Agent.inForeground = false
        com.blaze.assistant.connect.CloudSync.foreground(false)
        if (Agent.mood.value != com.blaze.assistant.agent.Mood.IDLE) Notifier.working(Agent.lastStep())
        Notifier.visible(false)
        super.onPause()
    }
}
