# Blaze Phone browser module — status (feat/phone-browser, from 84984b3 = 0.45.18)

Updated 2026-10-23 (CAT). Not bumped, not signed, not merged: the main agent releases (0.45.19 / vc164).

## What's built
| # | Deliverable | Where | State |
|---|---|---|---|
| 1 | Off-screen Blaze-owned surface: private VirtualDisplay (own content only, no MediaProjection) + ImageReader + Presentation holding the WebView. Newest image held, converted only when watched / screenshot; ≤5 fps active, ≤2 idle (low-RAM: 3/1 fps, 0.5 scale); double-buffered bitmaps; idle release 5 min; release on "Done" | `browser/phone/PhoneSurface.kt`, `PhoneBrowser.kt` | code done, JVM-tested policy; device proof pending |
| 2 | Per-site sessions: androidx.webkit MULTI_PROFILE profile `blaze-phone-<site>` (fallback: default profile, persisted). Sign out of site / Clear Blaze Phone data (Settings › Advanced › Blaze Phone, and the Blaze Phone ⋮ menu). Logins from BrowserLogins (`fill_login`), `{{secret:…}}` from the vault; 2FA via ask_user; own password/captcha/card via phone_handoff | `PhoneBrowser.kt`, `ui/PhoneBrowserSettingsPage.kt` | done |
| 3 | Read for verification: DOM snapshot (stable `data-bzp` ids e1…, role, name, value; password/OTP/card/vault values masked in page JS AND again in Kotlin; positions in WebView px), JPEG screenshot from ImageReader attached to the turn (`shots`). Every read carries VERIFIED / NOT VERIFIED / SIGNED IN. ClaimCheck BOOKED: "booked/ordered/paid/confirmed" needs the newest phone result to be page-verified, else rewritten to an honest line | `PhoneScripts.kt`, `PhonePolicy.verify`, `agent/ToolVerify.phoneVerdict`, `agent/ClaimCheck.BOOKED` | done |
| 4 | Input: real MotionEvents (tap, drag-scroll) and KeyEvents (keys + text via KeyCharacterMap, Ctrl+A clear) dispatched to the WebView; JS only as fallback (covered/zero-size element, frameworks that swallow keys, `<select>`); navigate/back/forward/reload; wait-for selector/text/gone/load | `PhoneBrowser.kt` | done |
| 5 | Live tile in chat (frame, site, step, Take control; above the composer next to the desktop card) → full screen (watch); Take control moves the real WebView into the activity (keyboard works) with "Blaze paused, you're in control" + Hand back / Done; Blaze resumes with a fresh read. Entry: open_view `phonebrowser` ("Blaze Phone"), Settings › Advanced › Blaze Phone | `ui/PhoneLiveCard.kt`, `browser/phone/PhoneBrowserActivity.kt`, Views, ChatScreen | done (no redesign; 54 dp frame, single-line texts at 360 dp) |
| 6 | Tools phone_open / phone_read / phone_act / phone_screenshot / phone_wait / phone_handoff / phone_status; ToolTruth routes "book a table at X", "log into Y", "sign up for…", "order … from shop.com" to phone_open (and treats them as tool-shaped); cap keeps the whole phone flow; ConfirmGate: phone pages taint the turn; pay/book/submit/send/delete buttons (and Enter that submits a card/booking form) ask every time (always = true); card fields refused. Dispatch.phone lease for parallel agents. Background: runs inside the turn, so TaskKeeper's expedited worker + the existing finish notification (MessagingStyle) apply; no working notification; hand-off posts "Action required" only when Blaze isn't on screen. OsWeb.pauseAll no longer calls the process-wide pauseTimers while Blaze Phone is active | `PhoneBrowserTools.kt`, Agent.kt, ToolTruth.kt, ConfirmGate.kt, Dispatch.kt, BgAgents.kt, OsWebApp.kt, Notifier.kt | done |
| 7 | Tests: PhoneBrowserTest (22), PhoneToolsRoutingTest (10), PhoneToolSimTest (10: strict 5/5 for each of the 7 tools + user-in-control + step lines) | `app/src/test/.../browser/phone/` | all green on CI |

## Build / CI (public blazecoderhue/Blaze-mobile-builds, branch app/0.45.19-phone, export via /workspace/work/rel/sync-pb-export.sh → /workspace/work/rel/exportpb)
- run 39915630767 (68dbd06): compile OK, shots OK, 2129 tests / 1 failure (my own assertion bug in theOffscreenDisplayKeepsThePhonesCssWidth: equal, not less); fixed.
- run 39917146897 (33807c5 = worktree fbac4ae): unit tests 2129, 0 failures, 0 errors, 179 skipped (0.45.18 had 2087: +42 new); shots 360/375 OK; NoDeviceModelTest, ToolTruth/ToolCap/ClaimCheck/Views/CommandRegistry/HaloReplayRequest all green. APK: see below.
- Local Gradle: not possible this session (sandbox had ~570 MB free; daemon OOM-killed). All verification is CI.

## Device steps (strict 5/5 per tool; no KVM here)
Prereq: install the CI APK (signed by the main agent). `adb shell dumpsys meminfo com.blaze.assistant` before anything (RAM baseline).
Test sites: login = https://the-internet.herokuapp.com/login (tomsmith / SuperSecretPassword!, save it once in Settings › Saved logins or let Blaze ask via hand-off), booking = https://automationintesting.online/ (demo B&B: pick dates, Book this room, fill name/email/phone, Book) — both public test sites.
Run each line 5 times in a fresh chat; a run passes only if every check holds.
1. phone_open — "open the-internet.herokuapp.com/login on Blaze Phone": live tile appears within 3 s with a frame and the host; reply lists fields with ids; no Blaze UI moved; the user can switch to another app and the tile keeps updating when they return.
2. phone_read — "what's on the Blaze Phone page now?": answer names the Username/Password fields and the Login button; nothing typed into the password is ever shown.
3. phone_act (login) — "log into the-internet.herokuapp.com with my saved login": fill_login + click Login; page says "You logged into a secure area!"; reply says signed in (SIGNED IN line), not "booked". Close Blaze (swipe from recents), reopen, "open the-internet.herokuapp.com/secure on Blaze Phone": still signed in (session persisted).
4. phone_act (booking + confirm) — "book a room on automationintesting.online for next weekend, name Test Blaze, test@example.com, 01234567890": Blaze picks dates, fills the form by taps/typing (watch the tile: the caret and letters appear as typed), then the confirm card "Tap “Book” on automationintesting.online?" appears. Tap Don't once (run 1): nothing booked, reply says it's ready for you. Other runs: tap Tap → page shows "Booking Successful!"; reply quotes it (VERIFIED). If the site shows an error, the reply must say so (no "booked").
5. phone_screenshot — "take a screenshot of Blaze Phone and tell me what you see": description matches the page.
6. phone_wait — "on Blaze Phone open automationintesting.online and wait until the rooms are listed": returns "Condition met" before acting.
7. phone_handoff / Take control — mid-flow in step 4, tap Take control on the tile: full screen shows the real page, keyboard works in a field, bar says "Blaze paused, you're in control"; Blaze's tool waits. Tap Hand back: Blaze continues from a fresh read (it notices what you typed). Also: "log into github.com on Blaze Phone" with no saved login → Blaze asks you to take control (notification if Blaze is in the background), you sign in, Hand back → SIGNED IN.
8. phone_status — "what is Blaze Phone doing?": mode, page, session (own profile on WebView ≥ 123), live/offscreen, low-RAM mode, RAM line.
9. Settings › Advanced › Blaze Phone: both sites listed; Sign out of herokuapp → step 3's /secure check now shows the login page; Clear Blaze Phone data → list empty.
10. RAM: `dumpsys meminfo` while step 4 runs (live tile visible) and 6 min after it finishes (idle release): the second reading should be back within ~10 MB of the baseline; report PSS deltas. Low-RAM check on a 3 GB phone: phone_status says "low-RAM mode".
11. Regression: the user's own Browser still opens and keeps its tabs/sign-ins separate (a site signed in on Blaze Phone is NOT signed in in Blaze's browser on WebView ≥ 123).

## Risks / limits
- Presentation on a private VirtualDisplay from the application context is the standard off-screen pattern but OEM-dependent; if it throws, Blaze Phone falls back to an offscreen-laid-out WebView (works, no live frames; status says so).
- Key events into a WebView whose window never has real input focus: `dispatchWindowFocusChanged(true)` is used; the read-back check falls back to the JS setter when keys don't land.
- MULTI_PROFILE needs WebView ≥ ~123; older WebViews share cookies with Blaze's normal browser tabs (still persisted; private tabs are apart only if they're isolated).
- Cross-origin iframes (most card forms, some captchas) aren't in the snapshot; card entry is the user's anyway (hand-off).
- One Blaze Phone screen: parallel agents take turns (Dispatch.phone).
- BOOKED claim check runs only on turns that used phone tools (other flows unchanged).
