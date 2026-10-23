# Blaze Phone UI fixes (0.45.19, fix/19-phui from rel/0.45.19 a82f7aa)

Not bumped, not signed, not published, not merged. gateway/ untouched.

## Fix 3 — contrast (AA everywhere on the tile and the Blaze Phone screen)
- Cause: `StatusPill` drew white text on `Glass.blue` (#E6E6EA) = 1.2:1 ("Reading Blaze Phone's page" unreadable). The tile
  and screen also hard-coded the dark-only `Glass.*` tokens, so light themes got a black slab with grey text.
- `ui/PhoneColors.kt`: one opaque palette per (theme accent, light/dark): card, title, sub, chip/onChip (theme accent moved
  until black/white text on it reaches 4.5:1), quiet button, strong button, screen ground/text/host, bottom bar, address
  field, page frame text, PiP label. WCAG 2.x maths in pure Kotlin.
- `PhoneLiveCard`, `PhoneBrowserActivity`, `PhonePipView` draw only with `phonePalette()`; `StatusPill` takes chip/onChip
  (default now dark ink on the light fill, so the desktop cards are fixed too).
- Test `ui/PhoneContrastTest` (5 tests, strict 5/5): every picker theme x light/dark (+ Classic with 25 accents per run,
  + 400 random accents per run) x 15 text/ground pairs >= 4.5:1; WCAG maths cross-checked against `Contrast.ratio`;
  regression (old pair < 1.5); source checks (no Glass.blue/Glass.text/Glass.sub/Color.White text on the tile/screen).

## Fix 4 — picture-in-picture
- `browser/phone/PhoneUiRules.kt` (`PhonePip`, pure) + `PhonePipHost.kt` (Android). Works from the chat (MainActivity)
  and from the Blaze Phone screen (PhoneBrowserActivity); both `supportsPictureInPicture="true"` (MainActivity also got
  `configChanges=screenSize|smallestScreenSize|screenLayout|orientation` so the PiP resize doesn't recreate it).
- "Working" = mode AGENT/WAITING and the chat whose turn last used a phone_ tool (`PhoneBrowser.own`, set in Agent's
  phone branch) is still busy. API 31+: `setPictureInPictureParams(setAutoEnterEnabled(working))`, kept current from a
  flow; API 26-30: `onUserLeaveHint` -> `enterPictureInPictureMode`. Never while the app lock is up, never while the
  user is in control. Blaze opening its own screens adds FLAG_ACTIVITY_NO_USER_ACTION so that isn't "leaving".
- In PiP only the live view renders (`PhonePipView`: the frame + a one-word label "Stopped"/"Done"/"Blaze needs you");
  the chat stays composed (draft kept) but is not placed.
- RemoteActions: Stop (while working) and Open Blaze -> `PhonePipReceiver` (not exported). Stop = `PhoneBrowser.stopTask`:
  `BgAgents.cancel(agent)` for a background agent and `Agent.stopChat(chat)` (new) for the turn, cancels the hand-back
  wait, clears agentWaiting, step "Stopped". Open/expand brings the chat task to the front (AppTask.moveToFront).
- Tests in `browser/phone/PhoneUiFixesTest` (strict 5/5): working truth table, entry per API level, own-screen guard,
  aspect clamp, actions/labels, Stop really cancels (main turn and background agent, Robolectric), wiring/manifest.

## Fix 6 — Take control: only "Hand back"
- `PhoneUiRules.buttons(control)` = ["Hand back"] in control, ["Take control"] watching; "Done" removed. Hand back with no
  agent waiting saves and closes to the chat; closing (Back / top-left arrow) while in control hands back first.

## Fix 7 — Back returns to the chat, one central rule
- `ui/BackRule.kt`. BlazeApp installs an ActivityLifecycleCallbacks that wraps every Blaze activity's Window.Callback, so
  Back is taken before any Compose BackHandler / onBackPressed (new activities are covered automatically).
  MainActivity (`BackRule.Shelled`): ChatScreen publishes `shellNow` (what is in front, read at key time) and `toChat`
  (closes settings pages, Views sheet, + menu, arc, profile menu, Search, Gallery, meeting room, theme picker, Design,
  Projects/project chat, Home). Other activities: `ToChat` hook (Blaze Phone hands back; Video keeps a playing video in
  PiP) else finish + bring the chat forward.
- Keeps its own Back on purpose: the chat itself (root; private chat still asks), app lock, first-run setup/gate, a live
  call (hang-up lands on the chat), PermissionActivity and CameraActivity (result helpers).
- Behaviour changes to flag: Back from Chat no longer drops to Home in swipe mode (Home<->Chat was a loop); Back no
  longer steps through inner levels (settings sub-page, Design levels, browser history, BlazeOS apps, video fullscreen,
  Music player tab, terminal sheet) — it goes straight to the chat, as asked ("every"). The on-screen back arrows inside
  those screens still step back. If any of these should keep inner steps, add it to `OWN_BACK`/an exemption.
- Tests: `ui/BackRuleTest` (7 tests, 5/5: every Views id, every settings page, every overlay, every manifest activity,
  key steps, wiring incl. openView specials == BackRule's, no enableOnBackInvokedCallback), `ui/BackRuleWindowTest`
  (Robolectric, real KeyEvents through a real window: taken before an OnBackPressedCallback, once per press, held Back
  once, other keys untouched).

## Fix 9 — sweep (bugs found and fixed)
1. Race: `Live` was updated read-modify-write from the frame thread (frameNo) and the main thread (mode/step); a frame
   could overwrite "You're in control". Now `MutableStateFlow.update` (atomic).
2. Leak/race: a cancelled turn (Stop) waiting for Hand back left `agentWaiting=true` and a dead `handBack`, so the next
   Hand back kept the WebView alive forever. Cleared on cancellation; stale deferred not reused.
3. Tile never left after Blaze finished (its 2-minute window had no clock). Ticks every 5 s while in the window.
4. Step line showed snapshot ids ("Tapping e61"); now "Tapping on Blaze Phone", then the element's real label.
5. Screen destroyed while the WebView's context pointed at it (not in USER mode) -> `PhoneBrowser.forget(activity)`.
6. Hosted WebView detached from its FrameLayout when the AndroidView is released.
7. "Signed out …"/"Cleared …" notes were set on a screen that closed in the same tap (never shown) -> Toast.
8. `watch()` counter under a lock.
Not changed (noted): live frames are a 2-bitmap double buffer, so a frame drawn exactly while the reader thread writes
the other buffer twice can tear (cosmetic, ≤5 fps); a third buffer would cost ~10 MB on a 1080p phone.

## CI (public blazecoderhue/Blaze-mobile-builds, branch app/0.45.19-phui; export /workspace/work/rel/export19phui via rel/sync-19phui-export.sh)
- run 39927479375 (export 1405f30 = 19-phui 7ab1fe3): compiled first time; apk OK, shots OK; unit-tests 2233 / 1 failure
  (my own source check found the word "Done" quoted in a comment) -> comment reworded.
- run 39929262267 (export b5efddf = 19-phui 4d3a895): ALL GREEN. unit-tests 2233 tests, 0 failures, 0 errors, 179 skipped
  (PhoneContrastTest 5/5, BackRuleTest 7/7, BackRuleWindowTest 1/1, PhoneUiFixesTest 8/8, PhoneToolSimTest 10/10,
  WaveA1PlayerBackTest 6/6, DesignNavTest 4/4); unsigned release APK built (artifact apk-unsigned); shots 360/375 OK.

## Needs a real phone
- PiP: auto-enter on swipe-home (API 31+) and Home on API 26-30; the window shows only the live page; Stop stops the
  task (tile says Stopped, no further taps on the page, the chat turn ends) for a main-chat task and a "Passed to
  Blaze"/background one; Open Blaze/expand returns to the chat full screen; X closes it and Blaze is fine on reopen;
  opening the Blaze Phone screen or the browser from the chat does NOT start PiP; no PiP with app lock on.
- Back from every screen on Samsung gesture nav and 3-button nav (Settings pages, Search, Gallery, Projects, Home,
  Design, Browser, Music, Video (playing -> PiP), Terminal, BlazeOS, Site apps, Blaze Phone watch + in control).
- Contrast on real panels in light (Rose Dawn, Desert Dune) and dark themes.
