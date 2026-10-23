package com.blaze.assistant.agent

import com.blaze.assistant.browser.SecureInput
import com.blaze.assistant.browser.SignInHints
import com.blaze.assistant.browser.Vault
import com.blaze.assistant.phone.PhoneControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.20.0: orient loop, secure forms (placeholders, vault, scrubbing), password-free sites, phone-control states. */
class OrientBrowserTest {
    private val screen = """
        Chrome (com.android.chrome). Elements, use the [number]:
        [0] text "GitHub"
        [1] text "Sign in to GitHub"
        [2] text "This account does not support password sign-in."
        [3] field "" hint "Username or email address"
        [4] field ""
        [5] btn "Sign in"
        [6] btn "Continue with Google"
        [7] link "Forgot password?"
        [8] text "New to GitHub?"
        [9] link "Create an account"
    """.trimIndent()

    @After fun tidy() { SecureInput.clear(); Vault.fileOverride = null }

    // ---------- orient ----------
    @Test fun numberedTapOnUnseenScreenIsHeldBack() {
        val s = Orient.State()
        assertEquals(Orient.Decision.LOOK_FIRST, Orient.decide("tap", JSONObject().put("element", 5), s, "com.android.chrome"))
        s.saw("com.android.chrome")
        assertEquals(Orient.Decision.GO, Orient.decide("tap", JSONObject().put("element", 5), s, "com.android.chrome"))
        // The app changed under us: look again.
        assertEquals(Orient.Decision.LOOK_FIRST, Orient.decide("type_text", JSONObject().put("element", 3).put("text", "x"), s, "com.whatsapp"))
    }

    @Test fun textTapsGlanceAndOpeningsGo() {
        val s = Orient.State()
        assertEquals(Orient.Decision.GLANCE, Orient.decide("tap", JSONObject().put("text", "Sign in"), s, "com.android.chrome"))
        assertEquals(Orient.Decision.GO, Orient.decide("open_app", JSONObject().put("app_name", "YouTube"), s, ""))
        val plan = JSONObject().put("steps", JSONArray().put(JSONObject().put("action", "open_app").put("app", "YouTube")))
        assertEquals(Orient.Decision.GO, Orient.decide("run_plan", plan, s, "x"))
        assertEquals(Orient.Decision.GO, Orient.decide("web_search", JSONObject(), s, "x"))
    }

    @Test fun guardCannotLoop() {
        val s = Orient.State(); s.bounced = 2
        assertEquals(Orient.Decision.GLANCE, Orient.decide("tap", JSONObject().put("element", 1), s, "a"))
        s.reset(); assertFalse(s.oriented); assertEquals(0, s.bounced)
    }

    @Test fun compactKeepsFieldsButtonsAndErrors() {
        val c = Orient.compact(screen, maxLines = 6)
        assertTrue(c, c.startsWith("Screen: Chrome (10 elements)"))
        assertTrue(c.contains("[3] field")); assertTrue(c.contains("[4] field")); assertTrue(c.contains("[5] btn \"Sign in\""))
        assertTrue("errors are kept", c.contains("does not support password"))
        assertTrue(c.contains("(+4 more; read_screen for all)"))
        assertTrue(c.length < screen.length)
        assertTrue(Orient.compact("").startsWith("Screen:"))
        assertTrue(Orient.lookFirst("tap", screen).startsWith("Not done yet"))
        assertEquals("", Orient.contextNote(""))
    }

    // ---------- secure input ----------
    @Test fun fieldsParseKindsAndUniqueNames() {
        val f = SecureInput.parseFields(JSONArray("""[{"name":"email","kind":"email"},{"name":"pw","label":"GitHub password"},"code",{"name":"email","kind":"username","target":3}]"""))
        assertEquals(listOf("email", "pw", "code", "email_2"), f.map { it.name })
        assertEquals(SecureInput.Kind.PASSWORD, f[1].kind)
        assertEquals(SecureInput.Kind.OTP, f[2].kind)
        assertEquals("3", f[3].target)
        assertFalse(SecureInput.Kind.OTP.saveable); assertTrue(SecureInput.Kind.PASSWORD.saveable); assertTrue(SecureInput.Kind.PASSWORD.masked)
        assertTrue(SecureInput.isCardField(SecureInput.Field("cc", "Card number", SecureInput.Kind.TEXT)))
        assertEquals(SecureInput.Kind.API_KEY, SecureInput.kindOf("groq_api_key"))
        assertEquals(SecureInput.Kind.PHONE, SecureInput.kindOf("mobile"))
    }

    @Test fun placeholdersResolveAndScrub() {
        assertNull(SecureInput.resolve("{{secret:password}}", "github.com") { _, _ -> null }.first)
        assertEquals(listOf("password"), SecureInput.resolve("{{secret:password}}", "github.com") { _, _ -> null }.second)
        SecureInput.put("password", "hunter2-very-secret")
        assertEquals("hunter2-very-secret", SecureInput.resolve("{{secret:password}}", "github.com") { _, _ -> null }.first)
        // Anything that slips into page text or an echo is turned back into the placeholder.
        assertEquals("Typed {{secret:password}} ok", SecureInput.scrub("Typed hunter2-very-secret ok"))
        val sum = SecureInput.summary(listOf(SecureInput.Field("password", "Password", SecureInput.Kind.PASSWORD)), saved = true, fromVault = false)
        assertTrue(sum.contains("{{secret:password}}")); assertFalse(sum.contains("hunter2"))
        assertEquals("github.com", SecureInput.hostOf("https://www.github.com/session?x=1"))
    }

    @Test fun vaultRoundTripPerSite() {
        Vault.fileOverride = File.createTempFile("vault", ".json").apply { delete(); deleteOnExit() }
        Vault.seal = { "s:" + it.reversed() }; Vault.open = { it.removePrefix("s:").reversed() }
        Vault.save("github.com", "email", "sam@example.com"); Vault.save("github.com", "password", "pw12345")
        assertEquals("pw12345", Vault.get("github.com", "password"))
        assertNull(Vault.get("gitlab.com", "password"))
        assertTrue(Vault.has("github.com", listOf("email", "password")))
        assertFalse(Vault.fileOverride!!.readText().contains("pw12345"))   // sealed on disk
        // Saved values fill placeholders on the same site only.
        assertEquals("pw12345", SecureInput.resolve("{{secret:password}}", "github.com").first)
        assertEquals("••••••", Vault.masked("password", "pw1"))
        assertEquals("sa•••@example.com", Vault.masked("email", "sam@example.com"))
        Vault.remove("github.com", "password"); assertNull(Vault.get("github.com", "password"))
        assertEquals(listOf("github.com" to listOf("email")), Vault.sites())
        assertTrue(Vault.forget("github.com")); assertTrue(Vault.sites().isEmpty())
    }

    // ---------- password-free sites ----------
    @Test fun githubNoPasswordIsSpotted() {
        val items = screen.lines().filter { it.startsWith("[") }
        val note = SignInHints.check(screen, items)
        assertNotNull(note)
        assertTrue(note!!.contains("Continue with Google")); assertTrue(note.contains("ask_user"))
        assertNull(SignInHints.check("Sign in to GitHub. Username or email address. Password.", items))
        assertTrue(SignInHints.blocked("Password sign-in is disabled for your organisation"))
    }

    // ---------- phone control, heads-ups ----------
    @Test fun phoneControlStates() {
        assertEquals(PhoneControl.State.CONNECTED, PhoneControl.state(connected = true, enabledInSettings = false))
        assertEquals(PhoneControl.State.RECONNECTING, PhoneControl.state(connected = false, enabledInSettings = true))
        assertEquals(PhoneControl.State.OFF, PhoneControl.state(connected = false, enabledInSettings = false))
        assertEquals("", PhoneControl.banner(PhoneControl.State.CONNECTED).first)
        assertEquals("Turn on", PhoneControl.banner(PhoneControl.State.OFF).second)
    }

    @Test fun headsUpsOnlySpeakWhenSwitchedOn() {
        assertFalse(com.blaze.assistant.proactive.Proactive.shouldSpeak(speakOn = false, canTalk = true))
        assertFalse(com.blaze.assistant.proactive.Proactive.shouldSpeak(speakOn = true, canTalk = false))
        assertTrue(com.blaze.assistant.proactive.Proactive.shouldSpeak(speakOn = true, canTalk = true))
    }
}
