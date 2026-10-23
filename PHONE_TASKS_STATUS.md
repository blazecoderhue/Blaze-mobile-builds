# 0.45.19 phone tasks: status

Worktree `rel/phone-tasks`, branch `fix/phone-tasks` from `84984b3` (live 0.45.18 source). Not version-bumped,
not signed, not published, not merged. Reported by the user testing 0.45.18 on a Samsung (One UI), Fri 23 Oct 09:21–09:22 CAT.

## 1. Every Blaze message is one Blaze conversation

Before: the check-in ("Did 'lovely' hit different today…") was a plain BigText notification on `blaze_proactive`
(no avatar, no MessagingStyle, no quick replies); call offers were on `blaze_calls` at IMPORTANCE_DEFAULT (never
heads-up); follow-ups on their own channel; only task results used MessagingStyle, with Android's generated replies
("Weird" / "Great!"). The phone's ringer was muted in the screenshot, so part of the silence was the system.

Now (`notify/BlazeThread.kt`, `notify/Notifier.kt`):
- one builder `Notifier.post(text, kind, …)` for replies, acks, Done ✓ results, failures, check-ins, heads-ups,
  questions, follow-ups and call offers (`reply`, `proactive`, `question`, `FollowUps.notifyAsk`, `Jarvis.offerCall`
  all go through it);
- MessagingStyle with ONE Person "Blaze" (bot, important, the app icon as a round avatar instead of the letter B),
  a long-lived dynamic conversation shortcut `blaze_conversation` (`setShortcutId`), one notification id: the shade
  shows one Blaze thread (last 5 lines);
- quick replies that fit the message (`QuickReplies.forMessage`, RemoteInput choices sent back as a reply):
  "Dark mode is on" -> Turn it off / Thanks; failure -> Try again / What happened?; honest fallback -> Check it again /
  Thanks; yes/no question -> Yes / No / Later; short either-or -> its options; a check-in question -> Ask me later /
  Skip this one; ack -> none. `setAllowGeneratedReplies(false)` (no more "Weird / Great!"); inline Reply kept;
- one channel `blaze_messages_v2`, IMPORTANCE_HIGH (heads-up; sound and vibration are the channel defaults, i.e. the
  phone's settings; no setBypassDnd, no forced sound, so DND and silent mode win). Migration: the old message channels
  `blaze_reply`, `blaze_proactive`, `blaze_calls`, `blaze_followups` are deleted at start-up (an app can't raise a
  channel's importance once created, so a new id was needed). `blaze_work` (status, LOW) and `blaze_action` stay.
- Tests: `notify/BlazeThreadTest` (channel/migration, one builder for every sender, thread, titles, quick-reply fit
  5/5 per class, never Weird/Great).

## 2. Verify toggles by phone state; one honest reply

Root cause of "I couldn't confirm it: couldn't read the switch back. Dark mode is now turned on.": switching the theme
recreates the Settings activity, so the switch node read back after a fixed `settle(1200, 250)` was stale
(`refresh()` false -> `switchState` null -> "Not confirmed"), and `UnconfirmedEcho` PREPENDED "I couldn't confirm it"
to a reply that also said it worked. Quick replies came from Android's generator.

Now (`phone/StateCheck.kt`, `phone/PhoneStateReader.kt`, `agent/ActCheck.kt`, `agent/Agent.kt`):
- STATE first: dark mode = `UiModeManager.getNightMode()` + `Resources.getSystem().configuration.uiMode` night bits;
  Wi-Fi `WifiManager.isWifiEnabled`; Bluetooth `BluetoothManager.adapter.isEnabled`; airplane `Settings.Global.AIRPLANE_MODE_ON`;
  DND `NotificationManager.currentInterruptionFilter`; mobile data, location, auto-rotate, battery saver, NFC;
  ringer `AudioManager.ringerMode`; brightness `Settings.System.SCREEN_BRIGHTNESS`. The switch label picks the setting
  ("Use Dark theme", "Dark mode", "Wi-Fi", "Power saving"…).
- No system state (in-app switch): the same node, then the switch re-found by its label, re-read (`isChecked`).
- Polled on accessibility events / every 100 ms up to 1.5 s (`BlazeAccessibilityService.awaitEvent`); no fixed settle.
- The tap tool and `run_plan` switch steps both use it (`ToggleVerify`).
- Replies: verified -> "Done ✓ Dark mode is on." (`ToggleReply`, only for a short claim about that switch);
  can't read anything -> "I turned it on but couldn't check it; it should be on."; state says the other way ->
  "Not confirmed: Wi-Fi still reads off"; a reply that both says it couldn't confirm and that it worked is replaced by
  ONE honest line (`UnconfirmedEcho.fix`), never prefixed.
- Tests: `phone/StateCheckTest`: dark mode on, dark mode off, Wi-Fi, Bluetooth, brightness, ringer, 2 in-app toggles,
  each strict 5/5 over settle latencies 0/100/300/700/1200 ms on fakes of the state APIs; contradiction, honest
  fallback, no-contradiction (incl. the exact 0.45.18 text), quick-reply fit, source checks.

## 3. Screen reading speed

Before (0.45.18): every read walked the whole active window (one binder call per child the system cache doesn't
hold), reused only when NO event at all had arrived since (`ScreenReadCache`, 1 s) – in an agent loop some event
almost always arrives, so it was a full walk. After a tap: `settle(1200, 250)` then the read-back, then
`screenAfter`'s `settle(1200, 200)` + another read.

Now (`phone/ScreenTree.kt`, wired in `BlazeAccessibilityService.readScreen`):
- event-driven cached tree: subtrees keyed by window + node id; TYPE_WINDOW_CONTENT_CHANGED / VIEW_TEXT_CHANGED /
  VIEW_SCROLLED / VIEW_SELECTED mark their source's whole subtree + ancestors dirty; window changes, events with no
  source, sources never walked, > 40 events, 30 s age or every 12th read -> full walk. Events of other windows (status
  bar, Blaze's own overlay) are ignored. `typeViewScrolled` added to the service config.
- same output: same lines, same pre-order, same 200-element cap (ScreenTreeTest: identical to a full walk over
  5 seeds × 60 random edits incl. adds/removes/visibility/window changes). The serialization was already compact
  (visible + actionable/text nodes only, numeric ids, no paths) and is unchanged so prompts and element numbers stay
  valid.
- cold walks of a big window fetch the root's subtrees in parallel (3 daemon threads; `ScreenTree.parallel` kill switch).
- no screenshot unless the text isn't enough (unchanged since v0.33.3: `look_at_screen` reads the tree first).
- after a result verified by state, `screenAfter` settles 500/120 ms instead of 1200/200.

### Numbers (JVM, GitHub CI run 39922165472, ubuntu-latest) – NOT device numbers

Per read_screen walk + serialization, 36 reads per row; "before" = the 0.45.18 full walk (the same code, kept as
`ScreenTree.walkFull`), measured first in the same run; "after" = cached tree with 1–3 on-screen nodes changed between
reads (a switch, a clock, a counter), including the safety full walk every 12th read.

| tree | modelled binder cost / child fetch | before p50 / p95 | after p50 / p95 | child fetches per read before → after |
|---|---|---|---|---|
| Settings page, 298 nodes | 0.30 ms | 89.3 / 89.4 ms | 14.2 / 88.5 ms | 297 → 67 |
| heavy app, 1525 nodes | 0.30 ms | 195.5 / 195.6 ms | 21.4 / 24.5 ms | 651 → 103 |
| Settings page, 298 nodes | 0.05 ms | 14.9 / 14.9 ms | 2.4 / 14.9 ms | 297 → 67 |
| heavy app, 1525 nodes | 0.05 ms | 32.7 / 32.7 ms | 3.6 / 4.1 ms | 651 → 103 |

The Settings p95 after is the safety full walk (1 read in 12; its root has one child, so no parallel walk). Target
"read < 300 ms p95" holds in every row, before as well: on this model the walk itself was not the 300 ms problem;
the waits around it were (next line), and on a real low-end phone a fetch can cost more than 0.3 ms.

Act-to-verified (one toggle, timing model of the waits on the code path, 2000 seeded trials): before p50 1500 ms / p95 1873 ms → after p50 775 ms / p95 1007 ms (target < 1.5 s). The gain is the removed fixed
`settle(1200, 250)` before the read-back (state is polled on events instead) plus the cached find; glide + ripple
(~360–660 ms, the visible pointer the user asked for in 0.45.14) are unchanged. Before, the dark-mode read-back then
failed anyway (stale node), so the real 0.45.18 outcome was "couldn't confirm".

Honesty notes: the binder cost per child fetch is modelled (0.30 ms = no prefetch, pessimistic; 0.05 ms = with the
system's prefetch/cache), the latencies in the act model are assumptions (glide 120–420 ms, ripple 240 ms, first
event 16–120 ms, screen animation 150–1000 ms, system state flip 30–400 ms). The phone must measure the real thing.

## On-device checks still needed (Samsung test phone)

1. Check-in / heads-up / Done ✓ / failure all show as ONE "Blaze" conversation with the Blaze icon avatar, in the
   Conversations section; heads-up with sound when the ringer is on; silent with the ringer muted / DND on.
2. Settings › Notifications › Blaze: only "Blaze messages", "Blaze status", "Action required" (old categories deleted).
3. Quick replies: "turn on dark mode" from another app -> "Done ✓ Dark mode is on." with Turn it off / Thanks; tapping
   Turn it off works; no Weird / Great!.
4. Dark mode on/off, Wi-Fi, Bluetooth, brightness, ringer, plus two in-app switches (e.g. WhatsApp "Conversation
   tones", YouTube "Autoplay"): verified, no "couldn't confirm" when it did change.
5. `speed_stats` / logcat: read times p50/p95 on Settings and a heavy app (Instagram/Facebook feed) before vs after,
   and act-to-verified for one toggle (target < 300 ms p95 read, < 1.5 s act-to-verified).
6. The cached tree stays correct while scrolling long lists and in apps that send few events (WebView pages, games).

## Commits

- b3e8cc8 0.45.19 phone tasks (1/3): every Blaze message is one Blaze conversation
- 504939f 0.45.19 phone tasks (2/3): verify toggles by phone state, one honest reply
- 3ff53c2 0.45.19 phone tasks (3/3): event-driven cached accessibility tree
- 56928d1 phone tasks: ignore events from nodes outside the read (past the cap / invisible); bench changes on-screen nodes; ScreenTreeTest counts fetches before the reference walk; question leaves the Blaze thread once answered

CI: public export `rel/exportpt` branch `app/0.45.19-phonetasks` on blazecoderhue/Blaze-mobile-builds (export drops
gateway/, keystore, secrets, replay tools; script `rel/sync-phonetasks-export.sh`).
- run 39921049171 (export 191afd8): compiled; 2122 tests, 2 failures (ScreenTreeTest counted the reference walk's
  fetches; the cached tree went full on events from never-walked nodes) – fixed, run cancelled.
- run 39922165472 (export 2c07483): ALL GREEN – unit-tests 2122 tests / 0 failures / 0 errors / 179 skipped
  (BlazeThreadTest 12/12, StateCheckTest 14/14, ScreenTreeTest 7/7, ScreenReadBench 2/2, ToolCapTest 9/9,
  AckTest 12/12, AckBackgroundTest 13/13, StatusNoteTest 6/6); unsigned release APK built; shots green.
