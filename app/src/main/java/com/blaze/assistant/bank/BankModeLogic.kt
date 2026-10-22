package com.blaze.assistant.bank

/**
 * v0.43.0 Bank mode, pure part (BankModeTest). Banking apps refuse to run, or blank their screens, when they see an
 * accessibility service from an app outside the store, apps drawing over other apps, the screen being read, and
 * similar. Bank mode makes Blaze step back while a bank app is used: its phone-control service turns itself off, the
 * floating orb and other overlays go away, screen glances and camera looks stop and notification reading pauses.
 * Turning it off puts back exactly what was on before.
 *
 * Here: which installed apps look like banking / payment / wallet apps ([BankApps]), and the on / off / restore rules
 * with the "turned on by itself when a bank app opened, off again a few seconds after you leave it" behaviour ([reduce]).
 */
object BankApps {
    /** Well-known banking, payment and wallet apps around the world (by package). */
    val KNOWN = setOf(
        "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash", "com.revolut.revolut", "com.transferwise.android",
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.samsung.android.spay", "com.samsung.android.samsungpay.gear",
        "com.chase.sig.android", "com.infonow.bofa", "com.wf.wellsfargomobile", "com.citi.citimobile", "com.konylabs.capitalone", "com.usaa.mobile.android.usaa",
        "com.americanexpress.android.acctsvcs.us", "com.discoverfinancial.mobile", "com.ally.MobileBanking", "com.htsu.hsbcpersonalbanking",
        "uk.co.hsbc.hsbcukmobilebanking", "com.barclays.android.barclaysmobilebanking", "com.grppl.android.shell.CMBlloydsTSB73", "co.uk.getmondo",
        "com.starlingbank.android", "com.db.pwcc.dbmobile", "de.number26.android", "com.bunq.android", "com.ing.mobile", "nl.rabomobiel",
        "com.bbva.bbvacontigo", "es.lacaixa.mobile.android.newwapicon", "com.santander.app", "fr.creditagricole.androidapp", "com.bnpp.easybanking",
        "com.commbank.netbank", "org.westpac.bank", "au.com.nab.mobile", "com.anz.android.gomoney", "com.td", "com.rbc.mobile.android", "com.cibc.android.mobi",
        "com.bmo.mobile", "com.scotiabank.banking", "com.sbi.lotusintouch", "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.axis.mobile",
        "net.one97.paytm", "com.phonepe.app", "in.org.npci.upiapp", "com.mercadopago.wallet", "com.nu.production", "com.itau", "br.com.bb.android",
        "com.safaricom.mpesa.lifestyle", "com.mtn.momo", "com.alipay.android.app", "com.eg.android.AlipayGphone", "com.tencent.mm.wallet",
        "com.coinbase.android", "com.binance.dev", "com.kraken.invest.app", "com.robinhood.android", "com.etoro.openbook", "com.klarna.android",
        "com.afterpay.android", "com.affirm.central", "com.zellepay.zelle", "com.chime.android", "com.sofi.mobile", "com.monese.monese.live",
    )

    /** Package parts and name words that mean money (matched as whole words / name parts, never inside other words). */
    private val pkgWord = Regex("(^|[._])(bank|banking|mobilebanking|netbank|ebank|banca|banco|banque|wallet|paypal|pay|payments?|upi|fintech|creditunion|momo|mpesa|finance|invest|trading|broker|crypto)([._]|$)", RegexOption.IGNORE_CASE)
    private val pkgStart = Regex("(^|[._])((bank|pay|wallet|banco|banca)[a-z0-9]*|[a-z0-9]*bank(ing)?)([._]|$)", RegexOption.IGNORE_CASE)
    private val labelWord = Regex("(?i)\\b(bank|banking|banco|banca|banque|bancaire|sparkasse|credit union|wallet|pay|paypal|payments?|money transfer|mobile money|net ?banking|e-?banking|finance|invest(ing|ments?)?|trading|broker|crypto|card services?)\\b|\\bpay[a-z]+")
    /** Things that look like money but aren't (games, the Play Store, display settings…). */
    private val notBank = Regex("(?i)\\b(game|games|piggy|play store|display|replay|payload|bankroll|blood bank|food bank|power ?bank|sound ?bank|word ?bank|question bank)\\b|^com\\.android\\.vending$|^com\\.google\\.android\\.gms$|^com\\.android\\.(settings|systemui)$")

    /** Blaze itself, the launcher's own apps and system bits are never treated as banks. */
    fun looksLikeBank(pkg: String, label: String = "", self: String = "com.blaze.assistant"): Boolean {
        val p = pkg.trim(); if (p.isEmpty() || p == self || p.startsWith("$self.")) return false
        if (p in KNOWN) return true
        if (notBank.containsMatchIn(p) || notBank.containsMatchIn(label)) return false
        return pkgWord.containsMatchIn(p) || pkgStart.containsMatchIn(p) || labelWord.containsMatchIn(label)
    }

    /** The user's list wins: [added] always count, [removed] never do, the rest by [looksLikeBank]. */
    fun isBank(pkg: String, label: String, added: Set<String>, removed: Set<String>, self: String = "com.blaze.assistant"): Boolean = when {
        pkg in removed -> false
        pkg in added -> true
        else -> looksLikeBank(pkg, label, self)
    }

    /** First-time list: the installed apps (package to name) that look like banks, by name. */
    fun preseed(installed: Map<String, String>, self: String = "com.blaze.assistant"): List<String> =
        installed.filter { (p, l) -> looksLikeBank(p, l, self) }.entries.sortedBy { it.value.lowercase() }.map { it.key }

    /** Keyboards, the status bar / lock screen and the fingerprint prompt pop up over a bank app without leaving it. */
    private val passing = Regex("(?i)^(com\\.android\\.systemui|com\\.android\\.permissioncontroller|com\\.google\\.android\\.permissioncontroller|android|com\\.android\\.keychain|com\\.google\\.android\\.gms)$|inputmethod|keyboard|\\.ime($|\\.)|swiftkey|gboard|honeyboard|biometric")
    fun passing(pkg: String): Boolean = passing.containsMatchIn(pkg)
}

object BankModeLogic {
    /** What Blaze had on before Bank mode, so turning it off puts back exactly that. */
    data class Saved(
        val accessibility: Boolean = false,
        val orb: Boolean = false,
        val notifications: Boolean = false,
        val screenGlances: Boolean = false,
        val cameraLooks: Boolean = false,
    )

    data class State(
        val on: Boolean = false,
        /** Turned on by itself because a bank app came to the front (so leaving it turns it off again). */
        val auto: Boolean = false,
        val bankPkg: String = "",
        val saved: Saved = Saved(),
        /** When the user left the bank app (auto mode): off once [LEAVE_GRACE_MS] has passed without coming back. */
        val leftAt: Long = 0L,
    )

    sealed class Event {
        data class TurnOn(val now: Saved) : Event()
        object TurnOff : Event()
        /** [bank] = the app in front is on the bank list; [now] = what is on right now (for the save). */
        data class Front(val pkg: String, val bank: Boolean, val at: Long, val now: Saved = Saved()) : Event()
        data class Tick(val at: Long) : Event()
    }

    enum class Action {
        DISABLE_ACCESSIBILITY, HIDE_OVERLAYS, PAUSE_GLANCES, PAUSE_CAMERA, PAUSE_NOTIFICATIONS, SHOW_NOTICE, WATCH_FRONT,
        RESTORE_ACCESSIBILITY, SHOW_ORB, RESUME_GLANCES, RESUME_CAMERA, RESUME_NOTIFICATIONS, CLEAR_NOTICE, STOP_WATCH,
    }

    /** Coming back within this long (a payment page in the browser, a code from SMS) keeps Bank mode on. */
    const val LEAVE_GRACE_MS = 4_000L

    /** What stepping back does, given what was on. */
    fun enter(s: Saved): List<Action> = buildList {
        add(Action.HIDE_OVERLAYS)
        if (s.screenGlances) add(Action.PAUSE_GLANCES)
        if (s.cameraLooks) add(Action.PAUSE_CAMERA)
        if (s.notifications) add(Action.PAUSE_NOTIFICATIONS)
        if (s.accessibility) add(Action.DISABLE_ACCESSIBILITY)
        add(Action.SHOW_NOTICE); add(Action.WATCH_FRONT)
    }

    /** What coming back does: only what [s] says was on. */
    fun exit(s: Saved): List<Action> = buildList {
        if (s.accessibility) add(Action.RESTORE_ACCESSIBILITY)
        if (s.orb) add(Action.SHOW_ORB)
        if (s.screenGlances) add(Action.RESUME_GLANCES)
        if (s.cameraLooks) add(Action.RESUME_CAMERA)
        if (s.notifications) add(Action.RESUME_NOTIFICATIONS)
        add(Action.CLEAR_NOTICE); add(Action.STOP_WATCH)
    }

    /** One step of the state machine. [autoDetect] = the user's "turn on by itself for bank apps" switch. */
    fun reduce(st: State, e: Event, autoDetect: Boolean): Pair<State, List<Action>> = when (e) {
        is Event.TurnOn -> if (st.on) st.copy(auto = false, leftAt = 0L) to emptyList() else State(on = true, auto = false, saved = e.now) to enter(e.now)
        is Event.TurnOff -> if (!st.on) st to emptyList() else State() to exit(st.saved)
        is Event.Front -> when {
            BankApps.passing(e.pkg) -> st to emptyList()
            !st.on && e.bank && autoDetect -> State(on = true, auto = true, bankPkg = e.pkg, saved = e.now) to enter(e.now)
            st.on && st.auto && e.bank -> st.copy(bankPkg = e.pkg, leftAt = 0L) to emptyList()
            st.on && st.auto && !e.bank -> (if (st.leftAt == 0L) st.copy(leftAt = e.at) else st) to emptyList()
            else -> st to emptyList()
        }
        is Event.Tick -> if (st.on && st.auto && st.leftAt > 0L && e.at - st.leftAt >= LEAVE_GRACE_MS) State() to exit(st.saved) else st to emptyList()
    }

    // ---------- words ----------
    const val NOTICE_TITLE = "Bank mode on \u2014 Blaze is stepping back"
    const val NOTICE_TEXT = "Phone control, the floating button and screen reading are off so your bank app can run."
    const val NOTICE_ACTION = "Turn Blaze back on"
    const val SETTING_LINE = "Turns off phone control, the floating button and screen reading so banking apps work. Blaze never reads or touches banking apps."
    const val STILL_BLOCKED = "If a bank still refuses: some banks also block phones with USB debugging, developer options or root turned on. " +
        "Blaze can't change those; turn them off in Android settings, or ask your bank."
    const val RESTORE_ASK = "Turn phone control back on: tap Blaze, then the switch."

    /**
     * v0.45.0 (#24): what the "turn phone control back on" notification says. [ignored]: how many earlier asks in a row
     * were left without phone control coming back. [simple]: Simple mode (no adb talk). After a couple of ignored asks it
     * explains the trade-off once in everyday words; after [RESTORE_GIVE_UP] it stops asking (Bank mode's card says so).
     */
    data class RestoreAsk(val title: String, val text: String)
    const val RESTORE_GIVE_UP = 5
    fun restoreAsk(ignored: Int, simple: Boolean): RestoreAsk? = when {
        ignored >= RESTORE_GIVE_UP -> null
        ignored >= 2 -> RestoreAsk("Phone control is still off",
            "Bank apps won't open while Blaze can control the phone, so Bank mode switched it off. Without it Blaze can't tap or read the screen for you. " +
                "Tap here, then Blaze's switch, to turn it back on." + if (simple) "" else " (The optional computer command in Bank mode does this by itself.)")
        else -> RestoreAsk("Bank mode is off", RESTORE_ASK)
    }

    /** The line on Bank mode's card when Blaze stopped asking. */
    const val RESTORE_STOPPED = "Blaze stopped reminding you to turn phone control back on after Bank mode. Turn it on in Blaze's settings whenever you want Blaze to use the phone again."
    const val ADB_GRANT = "adb shell pm grant com.blaze.assistant android.permission.WRITE_SECURE_SETTINGS"
    const val ADB_LINE = "Optional: with this one-time computer command, Blaze switches phone control back on by itself after Bank mode:"
    const val REFUSE = "This is a banking app. Blaze doesn't read or touch banking apps. Ask the user to do this step themselves."

    /** Edits Android's enabled_accessibility_services list: [component] added (once) or removed, other services kept. */
    fun editServices(current: String?, component: String, enable: Boolean): String {
        val list = (current ?: "").split(':').map { it.trim() }.filter { it.isNotEmpty() }
        fun norm(x: String): String { val p = x.substringBefore('/'); val c = x.substringAfter('/'); return p + "/" + (if (c.startsWith(".")) p + c else c) }
        val want = norm(component)
        fun same(x: String): Boolean = norm(x) == want
        val rest = list.filterNot { same(it) }
        return (if (enable) rest + component else rest).joinToString(":")
    }
}
