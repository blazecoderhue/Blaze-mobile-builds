## v0.45.18: your plan's limits, instant replies with background tasks, safer screen actions (versionCode 163)

- Settings › Account › This month: your plan, how many song and video downloads are left, how many run at once, and
  when they reset, straight from your Blaze account. Download sheets show what's left; a gentle upgrade note appears
  only when you're close to a limit.
- Downloads check your plan's monthly limit before they start; a download is counted once, even if it retries.
- Blaze answers right away with a short note, then works in the background: you can keep chatting or leave the app,
  and you get a "Done" message (or an honest failure) when it finishes. No more "Blaze is working" notifications.
- After reading a web page, message, email, notification or file, Blaze asks before touching the screen, and always
  asks before a tap that sends, pays, posts, deletes, confirms or installs.
- Android 7 and 8 phones that can't update in place get clear steps to back up and reinstall.
- Build hygiene: test libraries come from Gradle, unused workflows removed, Go 1.26.10 for the network helpers.

## v0.45.17: media player, library and search upgrades, on top of the 0.45.16 reliability work (versionCode 162)

Release candidate for testing on a low-end Android 12 phone. Includes all of 0.45.16 (which was not published on its own).
- Player: no more swipe-to-seek or its hint; a thin scrub bar replaces the big slider; Back returns to the chat.
- Videos and Music: "For you" and "All" chips.
- Captions: "Translate to" a language, offline captions for downloads, readable audio track names.
- Search: results update as you type (debounced) with loading cards instead of a blank screen.
- Streaming: a disk cache buffers ahead and prefetches the next song, so a flaky network stalls less; recently played
  items replay offline.
- Library: press and hold to select several songs or videos, then Select all, Add to playlist, Share, Export or Delete;
  Back leaves selection first.

## v0.45.16: fewer crashes and freezes, smoother chat on low-end phones (versionCode 161)

Reliability release, no UX changes. Aimed at random crashes and stalls reported on a low-end Android 12 phone (3 GB).
- Crash guard at startup: every one of the ~28 startup steps in BlazeApp.onCreate now runs guarded and timed
  (StartGuard.step). A step that throws is logged and skipped instead of closing the app on every launch.
- Safe start: when the last two launches ended within 30 s, the optional extras (proactive helpers, Jarvis, orb,
  dashboard, desktop link, router autostart) wait for a calm start, so a crash loop recovers by itself.
- ANR and stall log: a watchdog writes "ANR" (main thread blocked 5 s+) and "STALL" (700 ms+) with the main thread's
  stack to the local crash log (Settings → crash report), so freezes can be traced to the line that blocked.
- The crash log no longer writes on the main thread (it read and rewrote a file of up to 400 KB on the caller's thread);
  lines go through one background thread, crashes still write at once.
- Low memory: parse caches are dropped when Android reports RUNNING_LOW/CRITICAL or the app is in the background
  (onTrimMemory/onLowMemory), so Blaze is trimmed instead of killed mid-chat. Hiding the UI alone keeps them.
- Streaming: the reply bubble updates at most every 50 ms (was every frame), cutting re-split and re-render work while a
  reply streams on low-end phones.
- Updates: when an old install can't take the new build in place, the updater says the signing key changed in 0.45.15
  and to uninstall once and reinstall from blazeinchue.pages.dev.
- Includes everything since 0.45.15: GitLab CI security scanning (Secret Detection, SAST, Dependency Scanning).

## v0.45.15: phone actions on the real route, and a new private signing key (versionCode 160)

Device /phoneqa on 0.45.14 (21 Oct 22:40, Android 12) scored 10/55, while the model-side replays said 65/65. The phone
sends its turns as Cloud runs (POST /v1/runs), and the replays used chat/completions. tools/runs_replay.py now replays
the runs route: the 0.45.14 app reproduced 11/60.
- Cloud runs now carry the named first-step tool (ToolTruth.choice) and the follow-up tool on tool_results. Before, every
  run said tool_choice "auto" + blaze_tools "auto". This needs gateway 0.8.19+.
- The app's own notes (the saved-plan "[Done before] … run_plan" hint, the orient screen) are never read as the ask, and an
  ask a direct tool handles gets no saved tap plan. Those notes made codestral write run_plan JSON as text.
- Tool calls codestral wrote as text blocks ("open_settings\npage: dnd\ntap\ntext: …") become real calls
  (TextCalls.blockCalls). Syntax that can't run triggers one re-prompt and is never shown.
- On the first step of a two-step flow only the named tool runs (no blind run_plan beside open_settings). "tomorrow" /
  "today" events land on the day asked (ReplyFix).
- "turn off the alarm" is no longer answered with today's agenda. "Drafted the text" needs compose_sms.
- Review 22 Oct: FastLane no longer says "Done" for alarms, timers, media keys or directions; those go through the
  verified agent path. A "No app" / error result is a failure. All re-prompt kinds share one budget of 2 per turn. A reply
  the tool gatekeeper chokes on has its malformed calls dropped.
- /phoneqa first-token time is measured per turn (it was the app's first send: 68027 / 7052 ms on every run).
- Signing key rotation (user-approved 22 Oct 08:32): the old key (keystore/blaze.keystore, androiddebugkey/android,
  cert e9183294…) was public and is burned. Releases are signed v2 with the old key (Android 7/8) and v3 with the private
  release key (cert e7b0281f…, API 28+) plus a lineage old -> new that leaves the old key only "installed data". The
  keystore and its password are out of the repo. Gradle uses the SDK debug key, releases are re-signed outside the repo,
  and the GitHub workflow is gone. Updater pins the release key as the current signer on Android 9+ (UpdateKeys).
- Tests: DevicePhoneQa0451Test (the device's own strings), ReplyFixTest, Review1022Test, UpdateKeysTest.
- Needs a phone: update 0.45.14 -> 0.45.15 in place (data kept), then /phoneqa; Stop chip; an old-key-only APK must be refused.

## v0.45.14: the screen-control pointer never pauses a run or blocks a tap (versionCode 159)

User bug on live 0.45.13 (21 Oct 21:38, Settings > Do not disturb): the "Paused · drag, double-tap to click | Resume | Stop" bar
covered the top of the screen and every action got force-paused, so Blaze retried or said it failed. Removed cleanly:
- No auto-pause. Blaze's own taps and gestures, screen changes, and the user's touches never pause a run. The paused mode
  is gone: the bar, drag and double-tap to click, Resume, the "Click '…'?" check, the paused edge, the paused turn ending,
  and the "Paused before …" results, along with their code and strings.
- The pointer window is FLAG_NOT_TOUCHABLE and not focusable, so it never takes or blocks a touch and never covers the
  target for input. The only control is a small Stop chip at the bottom-left edge (FLAG_NOT_TOUCH_MODAL, only the chip
  takes touches). It hides while Blaze's own gesture is in flight. Stop ends the task at the next screen step, and a
  glide in progress stops at once.
- Settings text now reads: "It never blocks your taps or Blaze's. Tap the small Stop chip at the bottom-left to stop."
- New strict case overlay-taps: "open Do Not Disturb settings and turn it on". It passes only when DND reads on, the
  pointer was on screen that run, and nothing in the turn says paused. It's in /phoneqa (11 cases now) and in the
  model-side replay.
- Tests: PointerTest (window flags, no pause/resume/gate/mode API left, Stop once) and PointerOverlayTest. Units: 1893
  tests, 0 fail, 99 skipped. Replays on gateway 0.8.18 at par 4: phone set 65/65 (overlay-taps 5/5), 0.45.9 set 100/100.
- Needs a phone: /phoneqa overlay-taps 5/5, and tap Stop mid-run to check the task ends.

## v0.45.13: phone actions are only "done" when the phone reads them back; "remove the alarm" really turns it off; past items stay past; no memory saves on read-only questions; an honest wake-word notification (versionCode 158)

Phone-control sweep 0 (user via HQ, 21 Oct 17:16). The fixes for the user's 0.45.9 screenshots from 17:11 to 17:14
come first. Numbering: 0.45.10, 0.45.11 and 0.45.12 are already scoped (quota UI, duo, code-card Save), so this release
is 0.45.13. Those three need numbers after 0.45.13 when they ship, because the update notice compares version names.

**Round 3 (strict replays on gateway 0.8.17: phone 60/60, 0.45.9 set 100/100; units 1893 tests, 0 fail, 99 skipped)**
- A switch tap split into two calls in one step (`tap{text:"Dark mode"}` + `tap{want:"on"}`) is merged into one tap
  with want on the phone, so the switch is read before the tap and read back after it. No extra model step.
- The replay fixture now returns the screen with `open_settings_page`, the same as the app (SettingsJump returns
  "Screen now:"), so the first tap can pick the right switch without a separate read_screen round trip.
- Needs a phone: dark mode on, Wi-Fi off/on and a split tap through /phoneqa; check the reply waits for the read-back.

**Round 2 (strict replays on gateway 0.8.15)**
- Two identical tool calls in one step (the same Settings page opened twice) no longer stop the next step from being
  named: after `open_settings_page` the next tool is still `tap`, and after `processes` it is still `process`.
- A tap that wasn't asked to end "on" (or wasn't read back as on) can't back "Dark mode is on" or "I turned X on". The
  reply now says what wasn't confirmed instead. "X is on" counts as a claim when the user asked to change a setting.
- "run ls in the terminal", "run git status", or a bare shell command like `ls` names the terminal tool (`run_shell`)
  on the first step, so the command runs instead of being written out as text.

**Fixes for the user's bugs (all seen on 0.45.9)**
- "remove the alarm" said "Alarm removed." and posted it as a notification. Samsung Clock still showed "… (Blaze A2)"
  at 18:15, switched ON. Cause: `process(delete)` only sent `AlarmClock.ACTION_DISMISS_ALARM`, which Samsung Clock
  ignores for an alarm that isn't ringing. It then dropped the alarm from Blaze's list, and its result was classed as
  verified by default. Now `ClockUi` drives the Clock app's own alarm list through phone control. It finds the row by
  its "(Blaze A2)" label, or by its time when only one row shows that time. It tries long-press → Delete; when the
  clock offers no Delete, it flips the row's switch off. Then it reads the list again. Only that second reading
  decides the result: "Deleted …" / "Turned off … (still listed)" with "Checked on the phone: …", or "Not confirmed: …
  Next step: …". The alarm leaves Blaze's list only once it's gone from the Clock app. With phone control off,
  the result says plainly that it isn't confirmed. "Alarm removed / deleted / turned off" is now a claim that needs a
  verified result (new `alarm_off` claim in ActionProof).
- 0.45.9 had already dropped A2 from Blaze's list. So `processes` now also lists the system's next Clock alarm when
  Blaze doesn't track it (`clock@18:15`), and `process` accepts `clock@HH:mm`, or an `A<n>` that is no longer tracked
  (found by its label).
- The chat said "reminder at 18:30" while the alarm was set for 18:15. Two causes. First, `set_alarm` only answered
  "Alarm set."; now it answers "Alarm set for 18:15, label “…”." (no instruction text in the result, so nothing like "Say this exact time." can leak into a reply). Second, a reply about the alarm set
  in that turn is held to that time (`TimeEcho`). In schedule answers, an alarm is called an alarm and a reminder a
  reminder. Two items that share a first word ("… app alarm" 18:15, "… app check" 18:30) now each need their own time
  in the reply, so naming one no longer counts as naming both.
- At 17:11, "anything else to do today?" offered the 10:15 class. Today's `[context]` line now marks what's over:
  `(3 items, 2 still ahead): 10:15 "CIT class" (passed); …`. An item counts as over at its end. That end is the span in
  its title when there is one ("Church 9:00–12:30"), otherwise one hour after a reminder starts, or as soon as an alarm
  has rung. The rule says passed items are never offered as upcoming. "Anything else / what's left / to do today /
  anything other than those" are schedule asks now. ScheduleTruth rebuilds a reply that offers a passed item as still
  to do, or that leaves out one still ahead, into "Today (…), still ahead: …".
- "Memory updated" appeared on "anything else to do today?", "that was in the morning so we're good", "okay yea but
  anything other than those that we setup together?" and "No, that's all". MemoryGate's read-only list now covers
  "anything else / other than / left / do I have / what's next …" after "okay yea but …". A new acknowledgement rule
  covers "we're good", "that's all", "never mind", "that was in the morning" and similar. Durable facts still save.
- The wake-word notification said "Offline. Turn off in Blaze settings." the whole time, even online. "Offline" meant
  that the wake word runs on the phone. Now it says what the listener is really doing: "On. Runs on this phone: no
  audio leaves it…" / Paused / Sleeping / can't hear the mic. It is re-posted whenever that changes (`WakeNote`).
- Version on the phone: the screenshots don't show it. The 17:11 answer came with no tool and no receipt, so the model
  answered from the `[context]` block, which exists only in 0.45.9. That points to 0.45.9 (inferred, not read off the
  phone). MemoryGate and ScheduleTruth were both in 0.45.9, and neither covered these cases.

**Verify after act (every phone action)**
- A result that read its own effect back ends with an `ActCheck` marker. `ToolVerify` trusts only that marker for those
  tools. New read-backs: timer (the Clock app's ongoing timer notification), calendar event (CalendarContract.Instances
  ±1 min), contact (ContactsContract name + number), SMS draft (the text is in the compose box and was NOT sent),
  YouTube / YouTube Music / Spotify play (the media session is PLAYING with a matching title), Maps directions (Maps in
  front, the place and a route on screen, for `navigate_to` and Maps direction links), a switch tapped with `want` (its
  state read back), and set_alarm hidden behind an earlier alarm (the Clock list row is ON).
- Opening an app or a settings page is step 1, never the goal. A "done" for a multi-step ask whose only verified
  actions were opens gets one nudge to carry the flow on, then the honest line (`GoalCheck`). The prompt rule says the
  same and also says: say the exact time / name / value the tool reports.

**Strict case set and the self-test runner**
- `PhoneCases`: alarm set (time + label), alarm off, calendar event, draft only (never sent), YouTube play, Wi-Fi +
  Bluetooth + DND on, 2-minute timer, Display settings → dark mode on, add contact, Maps directions. Each case has its
  proof tools and its own verifier.
- Hidden `/phoneqa` command (not in /help): it runs each case 5 times as real turns on the phone, one fresh chat per run.
  After each turn, the verifier reads the phone back on its own. Each run records the request id, route/model, tools with
  verdicts, first-token and total time, and the accessibility screen before and after. The report goes to
  Downloads/Blaze as JSON and Markdown. Checklist: files/qa/phone-control-0.45.x.md. Device 5/5: PENDING PHONE RUN.
- Model side: `HaloReplayRequestTest.writePhoneReplayRequests` writes the app's requests for those cases (full tool list
  with the phone tools, plus the verify rule). `tools/halo_replay.py` answers phone tools with fixtures shaped like the
  app's tagged results. `rel/check_replay_phone.py` judges strictly. This is model-side evidence, not device evidence.

**Fewer tools per request, named tools for phone asks (model-side replay 8/60 → 60/60)**
- At most 128 tools per request (`ToolTruth.cap`, in LlmClient and cloud runs). With the phone tools the app registers 136,
  and some gateway routes refused the request ("'tools': maximum number of items is 128"). The cap always keeps the named
  tool and its follow-up, the must-have and phone-core tools, and every tool already used in the chat. It then drops the
  least relevant ones in a fixed order: rarely used tools first, scored by the ToolRouter cues and the words of the ask.
  The original order is kept, so prompt caching still works.
- Phone asks name their tool on the first step (`ToolTruth.choice`): set an alarm → set_alarm; turn off / remove an alarm
  → processes, then process; draft a text → compose_sms; timer → set_timer; add a contact → add_contact; directions →
  open_url (a Maps directions link); play … on YouTube → search_in_app; Wi-Fi / Bluetooth / DND on → device_setting;
  open … settings and turn … on → open_settings_page, then tap (with want); add a calendar event → add_calendar_event.
  The 0.45.9 choices are unchanged.
- The set_alarm result no longer carries the instruction "Say this exact time." (it leaked into replies). It reads
  "Alarm set for 18:15, label “…”. [ID A2]".
- `UnconfirmedEcho`: when a phone action's last result in the turn is unconfirmed and the reply doesn't say so, the
  reply gets one honest line first ("I couldn't confirm it: … still reads ON in the Clock app.").
- ScheduleTruth: a plain "what's on today?" names the passed items as earlier ("still ahead: 14:00 NET class. Earlier:
  08:00 CIT class (passed)"). Only "what's left / anything else" asks may leave them out.

**Tests**: new PhoneControlSweepTest and ToolCapTest; Halo0459Test / HaloReplayRequestTest updated for the passed marks,
the cap and the named choices. Full testDebugUnitTest: 1886 tests in 274 classes, 1787 passed, 0 failed, 99 skipped
(ShotsTest 84 needs BLAZE_SHOTS=1; the native-SQLite ARM skips: MindDb 5, MindLogs 4, MindUpgrade 4, PrivateChat 1,
LongChatIndex 1). Model-side replay (live gateway 0.8.12, fixtures, NOT a device): 60/60 strict on the 12 phone cases after
the round-1 fixes; the rerun after the ScheduleTruth fix was cut off by the QA account's daily allowance. Device 5/5: PENDING
PHONE RUN (/phoneqa).

## v0.45.9: every chat request carries Blaze's full tool set again, "save this file" saves to Downloads, no turn runs longer than 5 minutes, and Halo and Blaze know your day (versionCode 157)

**The full tool set on every request (top priority, user 12:14)**
- Root cause: since 0.42/0.44 each agent request carried a guessed subset of the tools (ToolRouter's groups, then
  ToolPick's 36-tool cap). The chat lane and the @all duo chat carried none. Gateway 0.8.2 now validates every tool call
  against the request's own `tools` and drops a call to a tool that isn't there. So a request whose words missed a group
  ("save this file", "open example.com in the Linux browser") reached a model that had no such tool, and it said it
  couldn't. A live probe on 0.8.2 (rel/probe_drop.out) showed the same thing. With no save tool in the request, the
  reply was "I'm unable to save files directly. However, I can guide you on how to save the HTML file on your computer"
  (X-Blaze-Normalized `checked`), and the other run was `retried;refused although web_search is in tools`. With
  `save_file` in the request, the model called it in 0.5 to 0.6 s, 2 of 2.
- Now every agent request (Blaze, @halo, Cloud runs, Autopilot) carries the whole registered list (`ToolTruth`). Each
  name appears once, names are valid and parameters are objects, so the gateway's checks always match. Plan mode,
  private chats and background agents keep their deliberate filters.
- Lanes that send no tools hand anything tool-shaped to the full lane. That covers the chat lane, the @all duo chat and
  Halo's side lane while Blaze works (there the message joins Blaze's task at its next step).
- Each request's tool list is logged with a count and the names. It shows in What happened ("Tools sent: N: a, b, …"),
  the speed line and logcat (`BlazeTools`). The gateway's `X-Blaze-Normalized` is read into the trace ("Normalized" row).
- New rule in every prompt: never say "I don't have the capability" about a registered tool. A reply that does so (or
  gives desktop or text-editor advice) gets one re-prompt to use the tool.
- When an ask needs exactly one tool, the first request names that tool in `tool_choice` (`ToolTruth.choice`), and later
  steps stay on "auto". This covers "save this file" after a code reply (`save_file`), "View calendar" and a yes to a
  calendar offer (`calendar_events`), "open … in the Linux browser" (`desktop_task`), and "open Settings" / "open Design
  view" (`open_view`). Before this fix, the 0.45.9 build's replay on gateway 0.8.7 sent the Linux browser ask to
  `browser_open` (Blaze's built-in browser) 5 times out of 5. It refused "open Settings" 2 times out of 5 ("I don't have
  the capability to open settings"). One "save this file" took 32 s and came back empty.
- After a tool works, the reply says in one line what it did. A turn that saved a file always ends with the saved path:
  when the model's last step leaves the path out, the app puts "Saved to Downloads/Blaze/<name>." first
  (`SaveFile.withPath`). This is needed because on gateway 0.8.7 the model's step after save_file was only its follow-up
  chips, 5 runs of 5.
- `calendar_events` results now tell the model to list each event with its time and place. Before this, one "View
  calendar" run of 5 answered only "Open the calendar?".
- Reminders and memory name their tool on the first step, like save and calendar (`ToolTruth.choiceFor`). "Remind me…" /
  "set a reminder" now goes to `create_routine`, "what reminders do I have" to `list_routines`, "remember that…" to
  `memory_save` (only when the memory gate allows the write) and "what do you remember about…" to `memory_search`.
  On auto, live 5-run replays got: the reminder went to `create_trigger` or no tool 3 of 5 (one said "Reminder set" with
  nothing set), reminders listed no tool 5 of 5, and memory saved or searched nothing 10 of 10 ("Got it. I'll remember that",
  "I don't have any memories").
- When the app names the tool, the request no longer carries `blaze_tools: "auto"`. Gateway 0.8.7 dropped every tool
  schema (and the named tool_choice) on the two memory turns, since its action check didn't match them. That's been reported
  to the gateway with request ids.
- An empty `calendar_events` result now says it is only the Android calendar and that the [context] schedule line
  still holds the day's classes and reminders. Before this, one Wednesday run of 5 called the tool, got no events and
  answered "Nothing scheduled today" with CIT 08:00 and NET 14:00 on the schedule line.

**Save code to the phone (user 12:12)**
- New `save_file` tool. With no arguments it saves the last code block in the chat. It can also save content and a
  filename given by name. It picks the right extension (.html .js .py .css .json .svg .md .txt and more). The name comes
  from the HTML `<title>` or the language, for example `solar-system-3d.html` or `blaze-python.py`. The file goes to
  Downloads/Blaze through MediaStore (Android 10+, no permission needed). Older Android versions get the share / save
  sheet. The tool returns the saved path and Blaze tells you it. It runs in Normal mode with no card, since it's a safe
  write to Downloads.
- "save this", "save the file", "download it" and similar requests go to it. New rule: Blaze runs on a phone, so it
  never gives desktop or text-editor advice and never claims it can't save.

**A hard per-turn deadline**
- The reported 56-minute wait was a Retry the user tapped at 12:11 (HQ and Site correction). The gateway's 12:11:04 chat
  sample took 1.2 s to first token and 2.9 s in total. That request ran on the `chat` lane, so it most likely carried no
  tools at all, which is the tool-list bug above.
- The code review still found a way a turn could hang. A Cloud-run step followed the run's stream in an endless
  reconnect loop, and keep-alives counted as activity, so the 100 s silence watchdog never fired. The only turn clock
  was Autopilot's 45 minutes, checked only between steps. Now a Cloud-run step is cut at 75 s: the run is cancelled on
  the gateway and the step goes direct once. Every model step is capped at 150 s. A turn ends at 5 minutes, and
  Autopilot keeps its own budget only for long work. The cap is checked during a step too (`TurnDeadline`).

**An assistant, not a chat buddy (priority 1)**
- Blaze and Halo no longer claim feelings ("I'm feeling great!") or use stock lines ("How can I assist you today?",
  "What would you like to do next?", "How about you?"). Halo's soul lost its "feelings" line. The same assistant rules
  (`Duo.ASSISTANT_RULES`) go into every Blaze and Halo prompt: the agent, @all, reactions and Halo's side lane. They are
  worded to match the shared persona v1.0.1, which the app now bundles (`persona-v1.json`), so the two never disagree.
- The bundled persona wins over an older cached copy (`SharedPersona.pick` compares versions), so a phone that cached
  1.0.0 sends 1.0.1 right after the update. The gateway then never has to add the rules itself (no `X-Blaze-Rules: added`).
- On a greeting, small talk or thanks, the reply is a few words, then 1 to 3 named next steps from your real day.

**Real context every turn (priority 1)**
- A short `[context]` block (600 characters at most) goes at the end of the app's second system message: the real date
  and time, the schedule for the day you asked about (today if you named none), the Home card's weather, your next item
  on another day, an open task, and what's new in the app on its first turns. The shared persona stays first and
  byte-identical.
- Schedule lines are worked out on the phone (`ScheduleDay`) from reminders, routines and calendar events, so a Sunday
  item can't be read as Wednesday's. In the block, each day's line gives its item count and quotes each title
  (`schedule Sun 25 Oct 2026 (1 item): 08:30 "Church 9:00–12:30"`). Unquoted, the replay read "Church 9:00–12:30" as
  church plus a CIT class from 9:00 to 12:30, or answered "nothing scheduled". That happened in 4 of 5 Sunday runs. The "next" line now says it's another day ("next after that day (not that day): …").
  The context rule says the schedule line already holds that day's items, including earlier ones, and that an empty
  calendar or routine result doesn't mean the day is empty.
- Schedule answers are checked on the phone (`ScheduleTruth`). When a "what's on" answer made with no tool says the day
  is empty while that date's schedule line has items, leaves an item out, or names another day's item as today's, it
  is rebuilt from the line ("Today (Sun 25 Oct): 08:30 Church 9:00–12:30."). The follow-up chips stay. An empty answer
  is still EmptyReply's job. In the replay, codestral said "Nothing scheduled" on a Sunday with Church on it in 1 run
  of 5, in each of the last two full replays.
- The block is cached for 60 s and rebuilt when reminders, routines, alarms, events, weather or follow-ups change.
- `list_routines` starts with today's date and gives each routine's next date. Routine days match whole words
  ("sunset" is not Sunday), and "weekly" with no day named means today's weekday, not every day.

**Calendar: ask, never refuse (priority 2)**
- With calendar access off, `calendar_events` shows Android's one-tap prompt. If you don't allow it, Blaze gets
  `CALENDAR_OFF`: it says in one line that access is off and offers to ask again. It never says it can't see calendars.
- Calendar, meetings, class and "what's on" questions always carry the calendar and reminder tools (plurals too).
- A short yes to a calendar or reminder offer ("@halo Yes" after "I can check your Google Calendar, want me to?") now gets
  `calendar_events`, `list_routines` and `create_routine`, both on the main @halo turn and in Halo's side lane while
  Blaze is busy. It no longer goes to the plain chat lane. Before, the phone answered "I currently don't have the
  ability to access or view your calendar."
- Suggestion chips only offer what the phone can do right now (`ChipGate`). No "View your upcoming meetings" chip with
  calendar access off.

**Memory keeps only lasting facts (priority 3)**
- `MemoryGate`: greetings, thanks, moods ("doing good thanks!") and read-only questions ("anything on for today?",
  "how are you feeling??") never write memory. That covers `memory_save`, `memory_update` and `remember`, plus the
  after-turn auto-extract. So "Memory updated" shows only when something lasting was saved. A message that also tells
  something lasting ("thanks! I'm vegetarian by the way") still saves it.

**Names**
- Greetings use only your own name (Settings › Your name) or your Blaze account's first name, or no name at all. Names
  in Blaze's own docs are never used for you (0.45.8 said "Hey Luke" with no name set).

**Faster send (latency)**
- MCP tool lists refresh in the background. Before, the first Send after launch could wait up to 8 s per MCP server
  before the request went out.
- New "Before the wire" stage marks in What happened (turn, route, tools, prompt, lane, token, connect, and the
  request body size) show where the time goes between Send and the request leaving.
- **Measured (JVM bench, not a phone)**, `SendPathTest.contextBlockKeepsTheWireFast` (app/build/bench/start-0459.txt):

  - 0.45.8 path, no context block                      working   0.00 ms   on the wire   0.99 ms (fake gateway got byte 1 at   1.40 ms)
  - 0.45.9 warm (block from the 60 s cache)            working   0.00 ms   on the wire   1.31 ms (fake gateway got byte 1 at   1.36 ms)
  - 0.45.9 cold (block built on Send)                  working   0.00 ms   on the wire   1.13 ms (fake gateway got byte 1 at   1.32 ms)
  - Block build alone: p50 0.075 ms, p95 0.892 ms (200 runs, fixture: 3 routines, 1 weather line, 1 open task)
  - Block size: 309 chars (cap 600)

**Live replay (gateway 0.8.11, persona v1.0.1)**
- `tools/halo_replay.py` sends the app-built requests (`HaloReplayRequestTest`) to the live gateway in the client's
  shape and checks the replies. Final run (16:11 CAT, 20 strict cases x 5 runs): **100 of 100 pass, every case 5/5**.
  91 runs passed on the model's first try. 9 needed the phone's own recovery, which the replay now copies from
  `LlmClient.complete` + `Cloud.drive`: re-ask on an empty reply, flatten the tool turns after a refused shape, back off
  after a 503. One Sunday run was fixed by `ScheduleTruth`. The gateway faults behind the retries went to the gateway with
  request ids: a thought_signature 400 when a Gemini step follows another model's tool call, empty replies on a named
  tool_choice, and 503 busy. Full table and every run with its request ids: files/qa/halo-replay-0.45.9.md.
- Known, not fixed here: the replay's web_search result is a fixture with no rate in it, and the model still stated a rate
  ("32.50 ZMW per USD") 5 of 5 times. The tool was called every time, so the case passes its tool check, but the number
  is made up. A grounding check for numbers in search answers is queued for a later release.

**Tests**
- Full `testDebugUnitTest` (JVM, ARM sandbox): **1865 tests in 272 classes, 1766 passed, 0 failed, 99 skipped** (the known skips: ShotsTest 84 needs BLAZE_SHOTS=1; 15 native-SQLite tests are not supported on Linux aarch64 and run on x86 CI).
- New: ScheduleTruthTest (the live replies that were wrong on Sunday, right answers left alone, chips kept), ScheduleDayTest, Halo0459Test (context block, Sunday-only church, cap, CALENDAR_OFF, cache, weather, schedule
  tools, memory gate and its wiring, chips, greeting name, persona agreement, persona version pick, stage marks, a yes
  to a schedule offer), HaloReplayRequestTest (writes app/build/qa/replay-0459.json) and
  SendPathTest.contextBlockKeepsTheWireFast. Updated to the new wiring: DesktopRouteTest, PromptCacheBenchTest (the
  new names / assistant / context_rule prompt parts) and UpdatePath0458Test (versionCode 157). The ScheduleDayTest
  every-other-week case was wrong (a series from Mon 5 Oct does fall on Wed 21 Oct), so the test was fixed, not the code.

**Not in this release** (0.45.10, see PLAN.md): Halo and Blaze coordination (one reply per message, a reviewer
addendum, a shared tool plan, "remind me later"), plus the items moved from 0.45.8.

**Needs a phone (not checked here)**
- Schedule (/doing): CIT, NET and Church show the right day labels and times. Church shows Sunday only, and a "weekly"
  routine with no day named shows today's weekday.
- The calendar permission prompt: with Calendar off, "View calendar" shows Android's one-tap prompt. Allow lists today's
  events. Don't allow gets one line and an offer to ask again, never "I can't access your calendar".
- "@halo Yes" to Halo's calendar offer lists today's items, both with Blaze idle and while Blaze is busy (side lane).
- The "Before the wire" rows in What happened: the stage marks and their times next to Send → working and Send → on the
  wire. These are the real phone numbers for the targets (working under 100 ms, on the wire under 300 ms).
- The four Halo turns, "anything on for today?" on a class day and on a Sunday, and "hi Blaze": no feelings, no stock
  lines, only that day's items, your own name or none, and no "Memory updated" chip on any of them.
- Checklist: files/qa/phone-task-checklist-0.45.9.md, log: files/qa/phone-task-log-0.45.9.csv.

## v0.45.8: Blaze starts the instant you tap Send, one theme on every screen, a theme switcher everywhere, Design full screen (versionCode 156)

**One theme everywhere (priority 1)**
- The picked theme is now one shared state that every screen reads: Home, Projects, Chat, Settings, sheets, Music,
  Video, Design and the Terminal. Before this, Projects drew its own flat dark ground, Home drew its Home look (navy)
  and only Chat showed the theme's scene (user screenshots 07:28, desert theme).
- With a theme picked, Projects, Design and Home are see-through over the chat screen's scene. Settings, Search, the
  Gallery, Music, Video and the Music/Video player sheet draw the scene themselves. Home uses the theme's palette
  instead of forcing dark glass. Classic keeps every screen's own ground as before.
- **Terminal colours come from the theme.** The ground is the theme's darkest colour (in light mode, a paper tinted with
  the theme's accent). Text is at 7:1 or more. The cursor, the accent (selected tab, Ctrl/Alt) and ANSI colours 1 to 15
  are each pushed to 4.5:1 or more against the ground, using the 0.45.7 contrast math. ANSI 0 is a background slot, so
  text on it is kept at 4.5:1. The key caps, bars, status bar and the ⋮ / + sheet follow too. Open tabs repaint at once.
  Classic keeps the near-black terminal.

**A theme switcher on every screen (priority 2)**
- A palette button with the same options as the carousel is on Home, Projects, Settings, Music, Video and Design. The
  Terminal has ⋮ › Theme, and Chat keeps profile › Themes. Every switcher writes through one path, so a change made
  anywhere shows everywhere, including the other activities (Music, Video, Terminal), which observe the same state.

**Design is its own full-screen view (priority 3)**
- Design now covers the whole screen, like Music and Video: no chat top bar, no composer and no particles over the
  canvas, so it has more room. It has its own "Back to chat" arrow, and the system Back does the same. It still opens
  from its own entry (Views › Design, /design, open_view, "Open in Design?"). The 0.45.6 render check and the Beta
  label are unchanged. It is a full-screen view inside the main app, not a separate task. The 0.46.0 rebuild builds on
  this shell later.

**Home (priority 4)**
- **Bottom strip:** "Swipe up to chat" now sits on Home's own ground, which also reaches under the status bar. The
  scene behind Home (a light ink picture on a dark Home) no longer shows through below it. With a theme, Home and the
  strip both show the theme.
- **News card ghost stack:** the cards peeking under a collapsed stack are clipped to the thin strip below the card in
  front. Before, their edges showed through the see-through main card as doubled outlines.

**Send starts at once (latency)**
- A typed message no longer waits for more. Before, Send opened a "burst" and held the turn 0.6 s (up to 6 s while you
  kept typing) so quick follow-ups became one turn. Now the first message's turn starts at once, and messages sent right
  after join that turn at its next step, or start the next turn when it ends. Nothing is dropped or reordered, and there
  are never two turns at once. The old routing is kept only for the before/after bench.
- The on-device fast-lane model runs only offline. Online, it sat in front of every short message (starting its process
  and an inference) before the cloud request left. Online, the instant rules and the intent cache still answer direct
  commands with no model.
- The session token is kept in memory after its first decrypt, keyed by the stored value, so a new token is never stale.
- Send sets the working state (orb and work card) before anything else.
- Chat requests to Blaze Cloud carry the reply-language pick as `X-Blaze-Reply-Lang` (BCP 47). Auto sends no header.
- **Timing rows:** the "What happened" sheet now shows Send → working, Send → on the wire, Send → headers and
  Send → first token, timed on the monotonic clock from the tap. `speed stats` adds a "Send → working …, on the wire …"
  median line, and the speed log keeps both.
- **Measured (JVM bench, not a phone):** SendPathTest sends 5 typed messages (median, after a warm-up) through the old
  and the new path to a fake gateway on a local socket. The slow prep is faked: 1.5 s for the on-device model, 25 ms for
  the token decrypt. The routing, burst wait, fast-lane switch, rules and timing marks are the real code.

  | Send path (median of 5) | Send → working | Send → on the wire |
  |---|---|---|
  | Old (0.45.7), fast-lane model downloaded | 0.0 ms | 2126.4 ms |
  | Old (0.45.7), no fast-lane model | 0.0 ms | 626.2 ms |
  | New (0.45.8), fast-lane model downloaded | 0.0 ms | 0.7 ms |
  | New (0.45.8), no fast-lane model | 0.0 ms | 0.8 ms |
  | New (0.45.8), first Send after app start (token decrypt) | 0.0 ms | 26.0 ms |

  Working was already set at once on the old typed path, so the gain is the request leaving: about 0.6 s sooner with no
  fast-lane model and about 2.1 s sooner with it (with the faked 1.5 s model; a real cold start can be longer). Targets
  were working under 100 ms and on the wire under 300 ms. **Phone numbers come from the What-happened sheet**
  (Send → working, Send → on the wire) and have not been measured yet.

**Not in this release** (moved to 0.45.10): WhatsApp-style reactions, Terminal GIDs and rootfs persistence, the
avatar badge, and lazy init. Parallel tool calls are 0.45.9. These still keep their own grounds: BlazeOS (the Home tab
when the Classic board is off), the call screen, onboarding/setup and the lock screen.

**Tests**
- Full `testDebugUnitTest` (1500m, build machine, Linux aarch64): **1813 tests in 265 classes: 1714 passed, 0 failed,
  99 skipped.** The skips are the known ones: ShotsTest 84 (needs BLAZE_SHOTS=1; Robolectric native graphics don't
  run on aarch64) and native SQLite 15 (MindDb 5, MindLogs 4, MindUpgrade 4, PrivateChat 1, LongChatIndex 1).
- New: ThemeEverywhereTest 7:
  - one rule answers the same for every screen (desert gives the scene on all nine);
  - each screen's source reads the shared theme, and the old fixed grounds are gone;
  - every screen has the switcher, and all switchers write through one path;
  - a switch "from Music" and "from the Terminal" is seen by every reader (theme, light/dark, the Terminal palette);
  - every theme x light/dark Terminal palette keeps text at 7:1 and cursor, accent, dim and ANSI 1-15 at 4.5:1 or
    more, with text on ANSI 0, key caps, bars, the selected tab and sheet rows checked as well;
  - the Terminal follows the theme, and Classic stays near-black;
  - the ghost stack shows only strips.
- New: DesignNavTest 4: Design opens full screen from Chat, Projects, a project chat and Home; only Design is full
  screen; Back returns to the chat; it is wired into the chat screen with the Beta label and render check kept.
- New: SendPathTest 4: the old/new before/after bench above (new must be under 100 ms to working and 300 ms on the
  wire, old must wait at least the 0.6 s burst grace); typed messages route NOW and the fast-lane model is offline-only;
  the `X-Blaze-Reply-Lang` header (none for Auto); the four Send → rows survive the What-happened round trip.
- New: UpdatePath0458Test 4: a 0.45.5 phone reading versions.json sees 0.45.8 as newer, with the download only from
  android.url and no GitHub host; versionCode 156 and the package id; the 0.45.5 install checks (SHA-256, same package,
  higher versionCode, same signer, PackageInstaller, the unknown-sources prompt) are unchanged; prefs from 0.45.5 survive,
  and the PrefCoerce migration still fixes a phone that never ran it. RapidMessagesTest now expects the new routing.
- Not covered: rendered pixels (no ShotsTest on aarch64), and two activities on screen at once.

**Needs a phone (not checked here)**
- The theme matches on every screen: Chat, Projects, Home (Swipe and Classic tabs), Settings, sheets, Music (and its
  player), Video, Design and the Terminal.
- The switcher works from Music and from the Terminal (⋮ › Theme), and the change shows on the other screens.
- Design opens full screen, and both the back arrow and the system Back return to the chat.
- The Home strip matches Home, and the news stack shows clean edges.
- Send shows working instantly: the orb and work card appear on the frame after Send, and a second message sent right
  after joins the same reply.
- The timing rows: What happened shows Send → working, on the wire, headers and first token, and `speed stats` shows
  the median line. These are the real phone numbers for the latency targets.
- In-app update from 0.45.5: the banner or `update` downloads 0.45.8, Android asks "Allow from this source" once if it
  was never allowed, then asks to confirm the update; chats, theme, sign-in and settings are kept.

## v0.45.7: local languages, readable on every theme, password eyes, honest Cloud sync switch (versionCode 155)

**Local languages (priority 1)**
- **Settings › Language** (new top-level row): Auto (match me), English, Kiswahili, ChiBemba, ChiNyanja, Hausa,
  Yorùbá, አማርኛ, Français, Português, العربية. The choice adds one stable `LANGUAGE:` line to the second system
  message (the app context) and to the chat lane's prompt. The shared persona (first system message) stays
  byte-identical. Auto tells the model to answer in the language of your latest message, with its own spelling, tone
  marks and script. A picked language means "always reply in X", with names, code and numbers left as they are.
  Before this, no prompt said anything about the reply language.
- **Fonts:** the bundled Inter is a 228-character Latin subset. It has no Hausa ɓ ɗ ƙ ƴ, no precomposed Yorùbá ẹ ọ ṣ,
  no Chichewa ŵ, and no Ge'ez or Arabic. Android already drew missing letters from a system font (so no tofu), but
  letter by letter, in a mix of two fonts. Now:
  - any bubble whose text needs a letter Inter lacks is drawn wholly in the system font (Roboto / Noto);
  - picking Hausa, Yorùbá, ChiNyanja, አማርኛ or العربية switches the whole UI to the system font;
  - emoji don't count, since the emoji font always draws them.
- **Nothing cuts a letter in half.** The streaming fade-in split the last 28 characters into fixed 4-char steps,
  which could split a Yorùbá tone-mark sequence or an emoji between two alphas. Its spans now snap to grapheme
  clusters. The same cluster-safe cut is now used by quote-chip snippets, group-chat summaries and long-chat search
  snippets (`Scripts.clip`).
- Markdown, ParseMemo, ReplyClean (streaming and final), ToolLeak, the chat file and the request JSON keep every
  language byte for byte. Tests below.

**Readability (priority 4)**
- **Contrast sized per scene.** The veil over a theme's picture was a fixed 0.55 (dark) / 0.62 (light). Measured
  against each bundled scene's darkest and brightest 1 %, that left body or secondary text below WCAG AA on several
  scenes: lagoon, mono ink, rose dawn, prism and liquid mercury in dark; aurora, firefly, ember, neon rain, prism and
  solar flare in light. Each scene now gets the least veil that keeps body text at 4.5:1 and secondary text and icons
  at 3:1, on both extremes, for the palette in use.
- **Look & feel › Background strength** slider: 1 = that AA minimum (picture strongest), 0 = a 0.92 veil (picture
  faint). The default 0.7 dims a little more than the minimum. No position goes below AA.
- **No hard grey band.** The veil over the chat while the Halo arc / + sheet is open (the y≈185–1150 band that ended
  above the Halo dial) is lighter (0.10) and fades out over its bottom 30 %.
- **Solid chips:** suggestion chips and pills are opaque (fill on the page colour) with a 1 dp border at 3:1 or more.
  They used to be see-through glass.

**Also**
- **Show/hide eye on every password and passphrase field:** sign-in, create account, change password, Cloud sync
  passphrase and "Type it again", the AI key field, and Saved logins while editing. It has a 40 dp target and
  "Show password" / "Hide password" for TalkBack. The secure form card and the browser vault already had one.
  Two-step codes are not masked, so they need none.
- **Cloud sync switch matches its label.** Before a passphrase was set, the switch showed ON (`on || setup`) while
  the label said "Off. Nothing leaves this phone.". It now stays off until "Turn on cloud sync" succeeds, and says
  "Off until you set a passphrase below and tap “Turn on cloud sync”".
- **Home weather in °C and km/h outside the US** (user bug 07:28: °F and mph in Ndola). The Home weather card now
  reads gateway /v1/weather when you're signed in, the same source and units as chat's get_weather, and shows the
  source and time. Units are metric unless you picked imperial, or the phone's locale is US and the place is in the
  US. A phone set to en-US in Ndola gets °C and km/h. Chat's get_weather uses the same rule (it used to go imperial
  on any US/LR/MM locale). The 4-day lines still come from Open-Meteo, in the same units. Signed out, or if the
  gateway fails, the card uses Open-Meteo directly, also with the new units rule.
- **What happened** sheet: a new "Longest pause" row (the longest gap between streamed chunks, the same number
  `speed stats` shows).

**Tests**
- Full `testDebugUnitTest` (1500m, build machine, Linux aarch64): **1794 tests in 261 classes: 1695 passed, 0 failed,
  99 skipped.** The skips are the known ones: ShotsTest 84 (needs BLAZE_SHOTS=1; Robolectric native graphics don't
  run on aarch64) and native SQLite 15 (MindDb 5, MindLogs 4, MindUpgrade 4, PrivateChat 1, LongChatIndex 1).
- New: LocalLanguagesTest 10, which runs a sample per language (Kiswahili, ChiBemba, ChiNyanja with ŵ, Hausa with
  ɓ ɗ ƙ ƴ, Yorùbá with decomposed tone marks, Amharic, French, Portuguese, Arabic, plus emoji ZWJ, skin tone and
  flag):
  - ReplyClean (final and streaming), ToolLeak, Md.render, Md.plain and every streamed prefix through ParseMemo come
    back byte for byte with the same number of combining marks;
  - the chat file and the request JSON keep the text, and the persona stays first and byte-stable;
  - the language lines, the font-coverage rule, cluster-safe cuts, clips and fade spans, chip snippets, and the
    "Longest pause" round trip.
- New: ContrastTest 9: WCAG math; every theme scene x light/dark x 11 slider steps gives body/ink 4.5:1 and
  secondary 3:1 on the scene's darkest and brightest 1 %; the slider only adds veil; the old fixed veil failed lagoon
  in dark; chips are opaque with a 3:1 border; the sheet veil fades to 0; eye toggles and the sync switch; a source
  scan that every masked field has a reveal toggle.
- New: HomeWeatherTest 3 (metric for Ndola on an en-US phone, imperial for a US place/locale or when picked, the
  request asks units=metric, the card shows °C and km/h with the source and time). PromptCacheBenchTest carries the
  new "lang" part.
- Not covered: rendered pixels (no ShotsTest on aarch64), and a Robolectric Amharic text-measure test (not feasible
  for the same reason).

**Needs a phone (not checked here)**
- In each language (Kiswahili, ChiBemba, ChiNyanja, Hausa, Yorùbá, Amharic, French, Portuguese, Arabic): type a
  message in Auto and check the reply comes back in that language. Then pick the language in Settings and write in
  English: the reply should be in the picked language. Check the letters render (no boxes, no mixed fonts) in the
  bubble, the composer, the quote chip and the notification. Check Arabic lines read right-to-left.
- Amharic glyph width: a Robolectric text-measure test isn't possible here (Robolectric's native graphics don't run
  on Linux aarch64, the same reason ShotsTest skips), so on the phone: Amharic must not render as tofu or collapse.
- Background strength: drag it on a bright scene (rose dawn, lagoon) in light and dark and check messages stay
  readable at both ends. Open the Halo arc: no hard band.
- Eye toggles on sign-in, create account, change password and both Cloud sync fields. With TalkBack it should say
  "Show password".
- Cloud sync before a passphrase: the switch stays off until "Turn on cloud sync" succeeds.
- Home weather in Ndola: °C and km/h, with the "Open-Meteo · HH:MM" source line (signed in).

**Deferred to 0.45.8** (written into PLAN.md): the Home bottom strip and the news card's ghost stack, theme
everywhere and full-screen Design, WhatsApp-style reactions, Terminal GIDs and rootfs persistence, the avatar
badge, lazy init, phone approvals. The veil for Classic backgrounds and user photos is unchanged; only theme scenes
are sized per scene.

## v0.45.6: music plays when asked, 3D designs draw (or say why), smoother scrolling and background (versionCode 154)

**Music (P1, phone 21:27–21:30)**
- **"play music" plays Blaze Music.** No approval card, no screen control. Music requests ("play music", "pause the
  music", "next song", "previous track", "what's playing", "resume the music") now run in the app before anything else
  reaches the model. Requests that name another app ("play music on Spotify") still go to the normal turn.
- `music_ops` has transport actions (play, pause, resume, next, previous, stop, now). In Normal mode they run without a
  card. A model that types `music_ops play` as a shell command gets the real tool, not the terminal.
- **Nothing queued?** "Play music" asks what to play and offers your last playlist and/or a shuffle of your local
  music (or says there's no music yet). It never guesses.
- **Opening apps:** an outside app opens only on an exact package or exact label match, never a near match ("Blaze*"
  opened BlazeFX). Blaze's own screens ("Blaze Music", "Blaze settings") are sent to open_view. After a launch the
  foreground app is checked, and a different app in front is reported as a failure.
- **Every turn ends with a message.** If a turn stops, pauses, times out or loses a tool and nothing was written, the
  chat gets one plain line saying what happened (with the last step).

**Design (P1, user 21:35–21:40)** · Design is now labelled **Beta** (studio header and the view chip)
- **A blank 3D view gets fixed or explained.** Any three.js page, not only the 3D starter, now has to prove it drew
  something: the check samples the frame for non-background pixels, and draw calls alone no longer count. If three
  checks come back blank, the view steps in once:
  - it gives a zero-height canvas a size;
  - it adds default lights (hemisphere, key and ambient) and a neutral environment when the scene has none;
  - it sets a background;
  - it frames the camera on the model's bounds, with near/far planes scaled to the model, orbiting its centre.
  
  If the scene is still blank, an error card names the problem, and parts-based models fall back to the simple
  drawing. The pane is never left empty. Until a 3D page has drawn, it shows "Drawing the 3D view…". The 3D starter's
  camera planes now follow the model's size, so a model in the wrong units is no longer clipped (the old far plane
  was fixed at 200).
- **No "Done" unless it rendered.** After the design tool runs, Blaze waits up to 15 s for the Design view's
  load/draw report on the new version. That result is added to the tool result as a RENDER CHECK line, and the reply
  is grounded in it. A "Done… is open / is ready" opening is replaced with what's true: still opening, or "It didn't
  render: <reason>".
- **Motion tab:** with a design open, it shows one honest status: "The Motion timeline isn't built yet; your intro is
  on the Page tab", with a button to go there. Chat replies to Motion requests say the same.
- The Page tab's live preview now sits below the Phone/Desktop and version/undo pills, so a page's own overlay text
  ("Drag to orbit • Scroll to zoom") no longer runs under them. The WebView is opaque and hardware-drawn, so a page
  that draws nothing is never see-through.
- Suggestion chips fit what's open: 3D models get lighting, colour, gloss and stand ideas (not "Make the headline
  bigger", and not the old orb/halo chips), three.js pages get camera, lighting and background ideas, and web pages
  get copy ideas.

**Grouped chats (P1, item 11)**: speaker attribution in group notifications (the "Cyp home" case) is fixed. Each
message keeps its sender ("Debi" is "Deborah"). A question meant for someone else is never put to you. Blaze never
auto-sends into a group, and the summary names the group and each speaker.

**Smoothness**
- The animated background read its clock during composition, so it recomposed about 30 times a second while any
  theme moved, and it was capped at 30 fps, which looked like jitter on 60–120 Hz screens. The clock is now read only
  while drawing (no recomposition per frame) and ticks at up to 60 fps.
- Chat rows that scroll back into view no longer re-parse their reply. Cleaning, card parsing, rich blocks and styled
  text are kept in process-wide LRU caches keyed by the text. Before/after (JVM, build machine, ParseMemoTest):
  - Scroll re-entry, 60 replies (861 chars on average): **14.13 ms → 0.242 ms per pass (58.3×)**, per row 0.235 ms → 0.0040 ms (first run); rerun 11.66 ms → 0.253 ms (46.1×), per row 0.194 ms → 0.0042 ms.
  - Streaming a 3,381-char reply over 845 frames, full re-parse per frame: p50 0.301 ms, p95 0.507 ms (rerun; first run p50 0.341 ms, p95 0.705 ms).
  - These are JVM timings on the build machine, not phone frame times.
  Streaming is not changed in this release: every frame still re-parses the whole live reply (numbers above).
  Incremental parsing is queued for 0.45.7.
- Scenes were already downscaled and cached (0.45.3), and saves already run off the main thread (0.30.5), so neither
  changed here.

**Also from the parked queue (written for 0.45.5, compiled and tested now)**: a wrong-reply guard (a reply that
repeats Blaze's own status block is caught, nudged once, then replaced by an honest line); a per-reply "What
happened" sheet (info icon: model and provider, route, tools, timings, errors, a copyable request id); a warm-up on
app open and on typing (at most every 25 s), with streaming kept on retries; the shared persona as the first system
message (bundled v1, refreshed daily); and the get_weather tool on the gateway endpoint (one source, day/night aware,
says when it's unavailable).

**Not in this release (queued as 0.45.7):** startup lazy init and a Baseline Profile (debug APKs don't package one),
incremental streaming parse, JankStats logging, phone approvals, the Home weather card on the gateway endpoint,
password eye toggles, the Cloud sync switch state, contrast work, reactions, Terminal GIDs/rootfs, and the avatar badge.

Tests: new InAppRouteTest (7), MusicCtlTest (4), TurnEndTest (3), DesignTruthTest (4), Design3dViewTest (7),
ChatThreadsTest (5), ParseMemoTest (2), Wip0456Test (5). Full `testDebugUnitTest` on ARM Linux, JDK 21:
**1772 tests in 258 classes: 1673 passed, 0 failed, 99 skipped** (ShotsTest 84, plus 15 native-SQLite tests that can't load on ARM Linux: MindDb 5, MindLogs 4, MindUpgrade 4, PrivateChat 1, LongChatIndex 1). The first full run had 3 failures, all fixed before this run: an empty catch in Agent.init (now logged), the prompt benchmark missing the weather part, and the group summary naming Deborah by her sender name "Debi" instead of the name the question used. CI not run. No phone tests yet (see below).

**Needs a phone:**
- "play music" → Blaze Music plays, no approval, no screen control. Also try "pause the music" and "next song", and
  "play music" with an empty library (it asks what to play).
- "open Blaze Music" opens the Music view. Asking for an app by a near name ("open Blaze") opens nothing outside and
  asks which app you mean.
- Design → 3D tab on a sample model shows the model lit and centred (the "3d-rocket" design). The chat reply says
  "Done" only once it has drawn. With WebGL off, an error card shows instead of a blank pane.
- Design header and view chip say Beta. The Motion tab shows the honest status. The Page preview's own text no longer
  runs under the Phone/Desktop and v2/undo pills.
- Grouped WhatsApp: in "Cyp home", Stefano asks Deborah, Debi says "yes", Elena says "Pronto" → no question to you.
  The summary names each speaker.
- Smoothness: scroll a long chat up and down, and watch a moving theme. Frame times (JankStats) and cold start need
  the phone; no on-device numbers are claimed here.

# Changelog

Newest first. Builds before v0.8.0 were numbered 1.0–1.17.0; `versionCode` has always counted up.

## v0.45.5: hotfix, every message failed with "String cannot be cast to Boolean" (versionCode 153)

- **Fixed: a plain message ("hey how's it going") failed at once** with a red ERROR "java.lang.String cannot be cast to
  java.lang.Boolean" and a "Continue where you stopped" chip, on every turn. Cause: two features shared one setting
  name. The "Suggested follow-ups" switch is a yes/no setting called `followups`; the Follow-ups tracker (since 0.38.0)
  saved its list of plans it watches in chats under the same name. Once a chat notification carried a plan, the list
  replaced the switch, and every turn crashed reading the switch while building its prompt. Cloud sync carries that
  setting, so it could reach your other phones too.
- The tracker now keeps its list under its own name. On the first start of 0.45.5 the list is moved there (nothing
  tracked is lost) and the switch goes back to on. Other settings stay as they were.
- Every yes/no and text setting is now read safely whatever type is stored ("true", "1", "false", "0" strings count,
  anything else falls back to the default), so a setting can never stop a turn again. Stored values are fixed to
  their proper type once at start.
- Cloud sync writes settings with the type this phone uses ("true" from another device becomes a real yes/no) and
  never sends or accepts a list or text in place of a yes/no setting.
- Tests: new PrefCoerceTest (6) and SettingsTypesTest (3, Robolectric: an upgraded phone with the old list and mistyped
  values starts a plain turn and keeps its data). Full `testDebugUnitTest` on ARM Linux, JDK 21:
  **1736 tests in 250 classes: 1637 passed, 0 failed, 99 skipped** (the known skips: ShotsTest 84, needs BLAZE_SHOTS=1, and 15 that open Robolectric's native SQLite).

**Needs a phone:** install 0.45.5 over 0.45.4 on the phone that showed the error (settings and cloud sync left on),
send one plain message, and check that it answers; check that Settings › Suggested follow-ups is on and that a plan
in a chat still gets its "Are you going?" follow-up.

## v0.45.4: Google and GitHub sign-in finish in the app, updates come from the Blaze site (versionCode 152)

- **Sign-in with Google or GitHub comes back to Blaze.** On 0.45.3 the browser said "You're signed in" but the first-run
  screen stayed on "Continue with Google". The app listened on 127.0.0.1 and redeemed the one-time code while the
  browser was still in front, so the outcome (a failed redeem, or a frozen/closed app) never reached the screen, and an
  error, when there was one, was drawn below the email form, out of sight. Now the browser returns through
  `blaze://auth/cb` (blaze-auth 0.9.5 accepts it and says so in /v1/providers `features.app_redirect`); MainActivity
  takes the link, checks its state, and redeems with the PKCE verifier with Blaze in front. The verifier and state are
  kept encrypted on disk for 10 minutes, so the sign-in still finishes when Android closed Blaze while the browser was
  open. A server without `app_redirect` keeps the 127.0.0.1 path, whose page now has an **Open Blaze** button.
- **Two-step accounts go straight to the code screen** after Google or GitHub, from first run too (redeem answering
  401 `mfa_required` opens the code step; the emailed code is sent by itself when email is the only way).
- **The first-run sign-in shows what happened** (signed in, or why not) at the top of the form, and moves on by itself
  once the session lands. Expired or reused sign-in links say so in plain words.
- **Signed out in chat:** a message sent while signed out of Blaze Cloud, with no other model to answer, shows a
  **Sign in** card instead of an error. The message stays and is sent by itself once you're signed in.
- **In-app updates come from the Blaze site.** /update, Settings › Updates and the update_blaze tool read
  https://blazeinchue.pages.dev/versions.json (no cache; schema 1, the android entry), compare versions semver-style
  (a leading v is ignored), download android.url inside the app (redirects followed, resumable, on mobile data too),
  check the file against android.sha256 (a mismatch deletes it and says so in plain words), check the signing key, and
  install through Android's installer. The "Allow from this source" step now says why it's needed. Once a day on app
  open a small "Blaze X.Y.Z is available" banner shows a newer version (Update or dismiss; silent when offline). Nothing
  in the update path uses GitHub any more, so updates work with GitHub unreachable. Phones on 0.45.2 or 0.45.3 still
  look on GitHub, so they need one manual install from https://blazeinchue.pages.dev.
- **Blaze's own source links point to GitLab** (gitlab.com/blazeinchue: Blaze-agent, Blaze-dekstop, Blaze-cli) in the
  built-in docs and the repo shortcuts; the store sources and the about screen name https://blazeinchue.pages.dev.
  GitHub sign-in, the GitHub connector and the git and terminal tools are unchanged.
- **My voice engine downloads from the Blaze release registry** (voice-runtime 1.13.8, the same files byte for byte),
  and the download is now SHA-256 checked before unpacking as well as after.
- Tests: new AuthReturnTest (14), SignInGateTest (7), VersionFeedTest (7); UpdateDownloadTest (11) checks SHA-256 and
  resume without a known size; MfaTest expects the app_redirect flag. Full `testDebugUnitTest` on ARM Linux, JDK 21:
  **1727 tests in 248 classes: 1628 passed, 0 failed, 99 skipped** (the known skips: ShotsTest 84, needs BLAZE_SHOTS=1,
  and 15 that open Robolectric's native SQLite).

**Needs a phone:** Google and GitHub sign-in from first run (step 01/03), with Blaze in the background and with Blaze
closed by Android while the browser is open; the same with a two-step account (app code, and email-only); the Sign in
card in chat and the message going out after sign-in; this build updating in-app to a newer one with GitHub unreachable; the
update banner once a day; My voice download on a fresh install.

## v0.45.3: faster first word, no Halo stalls, Autopilot knows when it's done, fewer command cards, theme pictures, yearly plans, two-step sign-in (versionCode 151)

- **Halo stream stall fixed** (agent/LlmClient.readSse). The stream reader skipped handing on a text chunk that came
  less than 16 ms after the previous one; it was only shown with the NEXT chunk. A burst followed by a pause in the
  model's stream therefore sat invisible on screen (a reply froze mid-list, then jumped). Every chunk now goes straight
  to the screen (StreamGate still keeps it to one update per frame). The streaming filters (tool-call text, [re:]
  markers, follow-up and Autopilot tags) were checked chunk by chunk on the screenshot's reply: they hold back at most a
  short ambiguous suffix, never paragraphs (StreamPromptTest). A list line that is only "- " so far waits for its words
  (Md.dropLoneMarker). The speed line now names the longest pause inside a stream ("longest pause in the stream 3.1 s
  (provider · model)") so a model-side pause can be told apart on a phone.
- **Halo memory clean-up loop fixed.** "@halo yes, clean them up" ran 9 minutes until Stop: there was no way to delete a
  memory by its #id, and `forget "#12"` answered "Nothing matched", so the model kept trying. New `memory_delete` tool
  (ids from memory_search, up to 60 at once, ONE confirm card for the whole list, never repeated in a turn, refused in
  private chats); `forget` with an id points to it. New LoopGuard: the same call that got the same answer twice isn't
  run a third time, and a tool that changed nothing three times in a row is stopped for the turn (screen / desktop /
  wait tools exempt).
- **Autopilot stops when the answer is done** (agent/AutoContinue.kt). It used to send "decide yourself" after any reply
  ending in a question, so a finished answer that offered something ("Want me to run the unused-apps check early…?")
  got a second, unasked turn. Now Autopilot continues only on the model's hidden `<<continue>>` marker, a stated plan
  with steps left ("Step 2 of 5") or a failed check after tool work; it stops on a complete answer, any question or
  offer to the user, chit-chat, or `<<done>>`; never after a question; at most 3 automatic turns per message. The
  markers are never shown or spoken. Approval rules for deletes, money, messages and calls are unchanged.
- **Normal mode no longer asks for every command** (agent/CommandPolicy.kt; shell, git and Linux-desktop typing / keys /
  opening apps). Safe and read-only commands (ls, cat, grep, git status/diff/log, local git add/commit, builds and tests
  in the workspace: gradle, make, cargo, go, npm run/test, pytest, tsc…) run without a card. Risky ones always ask and
  can't be pre-allowed: rm / deletes, sudo / su, installs from the network (apt, apk, pip, npm install, curl | sh…),
  writes outside the workspace, force pushes / hard resets, disk tools, anything that texts, calls or pays. Everything
  in between asks with two new choices on the card: "Allow for this chat" and "Always allow this command" (by program +
  sub-command, e.g. "git push"; kept as `cmd_allow_always`). Allowances don't apply in a turn that read outside content
  (web, messages, the screen). Several commands in one reply get ONE card ("Run these 3 commands?"). Autopilot keeps
  its old behaviour exactly.
- **Faster first word.** Optional look-ups before the first model call get a time budget (screen read for orientation
  400 ms, specialists and remembered plans 250 ms; agent/PreBudget.kt) instead of holding the request; late ones are
  named in the speed line ("late: orient"). Autopilot (the default) no longer marks every turn as an action turn, so a
  plain question isn't sent with the action lineup's slow-route preferences. The connection is warmed as soon as the
  message box is tapped (not only on the first key). Short / plain turns send `blaze_reasoning: low` (ignored by the
  current gateway, which strips blaze_* fields).
- **speed_stats** adds "First words on screen after Send" (median and slow ones) and the gateway's time to answer
  headers; each turn's speed line has `headers` and (when reasoning came first) `first text`.
- **Theme pictures.** The 13 scene pictures of the Blaze Site (assets/theme-scenes, full size 1376x768 WebP, 893 KB;
  no thumbnails) are each theme's background: on the picker cards (centre-cropped to the portrait card, particles and
  the droplet on top) and full-screen behind Chat / Home (cropped to cover, with a dark or light scrim in the page
  colour for readable text; the living particles on top; still with reduced motion, as before). Decoded once at screen
  size (inSampleSize, RGB_565) into a small cache; the carousel decodes the front card and two either side ahead. The
  Weather theme picks a scene from the weather: clear → Solar Flare, heatwave → Desert Dune, cloudy → Liquid Mercury,
  night → Nebula, rain / storm → Neon Rain, snow → Arctic Aurora, fog → Liquid Mercury, wind → Golden Ember, sunset →
  Lagoon. Classic stays without a picture.
- **Monthly or yearly Blaze Cloud plans** (connect/Billing.kt, ui/CheckoutPage.kt; Settings › Account › Plan). Checkout
  shows a Monthly / Yearly switch when the gateway offers yearly prices (0.6.2 and later): the price per period, the
  saving ("2 months free"), the local amount for the chosen period and "365 days, not auto-renewing". The payment is
  sent with `period: "month" | "year"`; an older gateway gets no switch and monthly as before. Terms never renew by
  themselves; payments are still confirmed by hand.
- **Sign-in with two-step verification** (connect/Mfa.kt, BlazeAccount, Settings › Account; blaze-auth 0.9.0 / 0.9.1).
  When an account has 2FA on, a correct password or a Google / GitHub sign-in now leads to a code step instead of
  failing: the 6-digit code from the authenticator app, a code by email, or a recovery code. The step shows the time
  and tries left ("4:05 left · 3 tries left"); a wrong code says how many tries remain, and after 5 minutes or 5 wrong
  codes it goes back to sign-in. **Codes by email** (0.9.1): "Email me a code instead" sends a 6-digit code (valid 10
  minutes); "Send a new code" waits 30 s between sends and shows how many of the 3 sends per sign-in are left. An
  account without an authenticator app gets the email straight away ("We sent a code to l**e@example.com").
  Settings › Account also gets: Add / Change password (changing it signs out your other devices), "Forgot password?" on
  the sign-in form (a reset link by email), Authenticator app on / off (set-up key + "Open in authenticator app", the
  first code, then the 10 recovery codes shown once) and Email codes on / off (confirmed with a code we email you; off
  also takes an app code). Each part only appears when the account service says it's switched on (`password_reset`,
  `two_factor`, `two_factor_methods` in /v1/providers), so an older service shows nothing new. No SMS codes.
- **Tests:** full unit suite on ARM: 1699 tests, 1600 pass, 0 fail, 99 skipped (84 `ShotsTest` screenshots, 15 SQLite tests that skip on ARM). New: `MfaTest`, `BillingTest`, `CommandPolicyTest`, `LoopGuardTest`, `AutoContinueTest`, `PreBudgetTest`, `StreamPromptTest`, `ThemeScenesTest`.
- **Needs a phone:** first-word feel after Send (and "First words on screen" in speed_stats); Halo streaming with no frozen replies (and the "longest pause" in the speed line); Autopilot stopping after a complete answer or a question and continuing only when there are steps left; command cards in Normal mode (safe commands run without a card, "Allow for this chat", "Always allow", one card for several commands); theme picture quality on the picker cards and behind Chat / Home (crop, scrim, memory on a low-end phone, Weather picking a scene); checkout with the Monthly / Yearly switch against the live gateway; 2FA sign-in (password and Google / GitHub accounts with an authenticator app, recovery codes, the 5-minute / 5-try state, back to sign-in when it runs out); emailed 2FA codes (auto-send for accounts without an app, "Email me a code instead", "Send a new code" wait and the 3-send limit, Email codes on / off); Add / Change password and "Forgot password?"; memory_delete with one confirm card ("@halo yes, clean them up").

## v0.45.2: icon actions, theme carousel, keep the desktop alive, desktop live view fix, faster checks (versionCode 150)

- **Message actions are icons.** The row under each message (Copy, Quote, Retry, What I did, React) is now a row of small
  icons (copy, quote, refresh, receipt, smile) to save space (ui/MessageActions.kt, Bubbles.MetaLine). Each icon keeps a
  40 dp touch target, a TalkBack name ("Copy message", "Quote message"…) and shows its name on a long-press. The time and
  the Halo x Blaze / run-status chips stay where they were. User messages get the same icons (Copy, Retry).
- **Keep desktop running** (desktop/DesktopAlive.kt; switch on the Desktop (Linux) page and in Settings › Device &
  diagnostics › Linux desktop; off by default, saved). When on, the Linux desktop runs under a foreground service with an ongoing
  notification (foregroundServiceType `specialUse` with its subtype property, permission FOREGROUND_SERVICE_SPECIAL_USE),
  holds a partial wake lock while it runs (renewed hourly, released when it stops), asks once for the battery
  optimisation exemption (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) when first turned on, checks the desktop every
  15 s and repairs it when it was killed (same cap as Restart by itself: 3 times in 10 minutes), and starts it again if
  Android killed all of Blaze while it was on (sticky service). The copy says plainly that Android 12 and newer can still
  close it in rare cases; then the page says so and shows Restart.
- **Removed device-model mentions** from UI strings, prompts, comments, tests and docs. The Alpine card now says
  "Recommended for most phones". Generic device logic (memory tiers, low-RAM checks) is unchanged. A source scan test
  (NoDeviceModelTest) keeps it that way.
- **Desktop live view fix.** Asking to do something on the running Linux desktop ("open the browser in it") made Blaze
  run shell commands, and once it told the user to connect a VNC client. Cause: the `desktop` and `desktop_task` tools
  were in no tool group, so since the tool diet they were only sent on request, while the words "browser" / "install"
  turned on the browser and terminal groups. Now: a new tool group "desktop" holds both; requests that name the desktop,
  or a GUI action aimed at "it / there" while the desktop runs or was just discussed, turn it on (agent/DesktopRoute.kt),
  the turn skips the phone fast path, both tools are kept past the tool cap, and a short turn hint steers the model to
  desktop_task (which shows the live, watch-only view card in chat). A reply that still sends the user to a VNC client
  or localhost port gets one re-prompt, then a plain line pointing at the live view. After Blaze works in the desktop a
  "View desktop" chip appears in the chat; "view desktop" / "show desktop" also open it. Regression test:
  DesktopRouteTest.
- **Themes.** Profile menu › Themes (and Settings › Look & feel › Themes) opens a full-screen picker: a looping 3D
  carousel of phone-shaped previews on a cylinder (graphicsLayer rotationY / cameraDistance / scale / alpha). The front
  card faces you, its neighbours turn away; swipe either way; the round arrows beside the front card are tinted with the
  accent of the theme they lead to and update as you scroll. Tap the front card to use it, tap a side card to bring it
  to the front, back closes. Only the front preview animates. Themes: Golden Ember, Arctic Aurora, Deep Sea, Solar
  Flare, Nebula, Mono Ink, Rose Dawn, Firefly Forest, Liquid Mercury, Prism, Desert Dune, Neon Rain, plus Weather
  (temperature sets the colour, the weather sets the motion, the local time sets the light: heatwave, snow night, rain,
  thunderstorm, fog dawn, windy autumn, starry night, tropical sunset, clear day; it reads the Home weather widget when
  there is one and falls back to the time of day offline) and Classic (the look as before, with the Look & feel
  background). Each theme is a living background (ground, particle colours, motion style) drawn by the v0.39 living
  background, tints the Blaze droplet and its halo, and sets the app's accent. Saved as `look_theme`. Rose Dawn and
  Desert Dune switch to light when picked.
- **Light / dark:** the "Light theme" row left the profile menu. Long-press the profile icon (top right) to switch light
  and dark, with a haptic tick and a short "Light theme" / "Dark theme" note. Light / Dark / Auto stays in Settings ›
  Look & feel.
- **Faster checks that stay real** (agent/VerifyPolicy.kt). (a) A plain answer turn (no action, no action claim) has no
  verification step; the local final-claim rule (no "done" claim without a done receipt) still runs. In 0.45.1 such
  turns already made no separate model check, so this mostly makes the rule explicit and measured. (b) An action is
  checked on the phone first (app in front, setting read back, file there, next alarm, desktop port / process); a
  conclusive result is final and the model is told no screenshot or extra look is needed; only an inconclusive one leaves
  room for one model-side check. (c) Probed actions get 0.4 s for the local check; if it hasn't finished, the turn goes on
  with the action marked "still checking" (never done), the check finishes in the background (up to 8 s) and the
  receipt under the reply flips to done / failed / couldn't confirm when it ends, with the reason in "What I did".
  (d) The specialist self-review runs on the quick route with a 12 s limit; a timeout or failure now says "Not
  self-checked" instead of passing silently. Risky one-shot actions (send, call, pay, delete, install) always wait for
  their check, as before. (e) Settings › Speed › Strict checks brings back the old path. The speed log line (speed_stats)
  now has "verify N ms (local n/ms, model n/ms, background n, saved ~ms)".
- **Tests** (full `testDebugUnitTest` on ARM Linux, JDK 21): **1644 tests in 237 classes: 1545 passed, 0 failed,
  99 skipped.** The skips are the known ones: ShotsTest 84 (needs BLAZE_SHOTS=1) and 15 that open Robolectric's native
  SQLite, which has no ARM Linux build (MindDb 5, MindLogs 4, MindUpgrade 4, PrivateChat 1, LongChatIndex 1). New in this
  release: DesktopRouteTest 11, VerifyPolicyTest 12, DesktopAliveTest 8, LivingThemesTest 9, NoDeviceModelTest 1, all
  passing. LivingThemesTest.weatherWordsParse failed in the first full run (75°F gave 23 instead of 24); WeatherLook.tempOf
  now rounds and it passes. assembleDebug OK. GitLab CI was not run for this release.
- **Needs a phone** (not checked on a device): the icon row (touch targets, long-press names, TalkBack names); Keep
  desktop running (the foreground service and its notification, the wake lock, the battery-optimisation prompt, repair
  after a kill, the sticky restart, behaviour on Android 12+ and 14+); the desktop live view fix against a real model
  ("open the browser in it" uses desktop_task and shows the live view card, no VNC / localhost advice, the "View desktop"
  chip); the themes carousel (3D look, swipe, arrow tints, smoothness on a low-end phone, light switch for Rose Dawn /
  Desert Dune) and the Weather theme with a real Home weather widget; long-press on the profile icon for light / dark;
  faster checks (receipts flipping from "still checking" to done / failed, "Not self-checked", Strict checks, the
  verify numbers in speed_stats).

## v0.45.1: faster first word and faster screen reading (versionCode 149)

Speed only. Every reliability rule from v0.45.0 (proof for every action, "What I did" receipts, the pinned goal,
context kept when the chat is shortened) works exactly as before.

- **The system prompt is ordered for prompt caching** (agent/PromptParts.kt, Agent.systemPrompt). AI services reuse
  the work for the longest identical start of a request, so a reused start answers sooner. The prompt is now built
  from named parts in four tiers: always the same, rarely changes (connected accounts, about Blaze, project), changes
  with the kind of request (core skills, views, terminal, skills lookup), and live (clock, memory, phone line,
  screen awareness, setup, goals). Parts keep their exact wording; only their order changed, stable first.
  The one wording change: "KNOW BEFORE YOU ASK" now points at "the phone line near the end of this prompt" instead of
  "the line above". Settings key `prompt_stable_order` (default on) switches back to the old order.
- **The live part is kept for one turn.** During a phone task each step used to rebuild the clock, phone line and
  screen awareness, so the system message changed at every step and the whole history after it had to be read again.
  Now the live part is built once per turn and reused by its steps (rebuilt when the memory block, full/summary
  awareness or the request changes, and after 5 minutes). Tool results still carry the fresh screen, and proof of an
  action still comes only from tool results, so nothing is decided from the kept part.
- **Tools keep their place across a turn's steps** (ToolPick.keepOrder): a tool that joins mid-turn goes after the
  ones already sent instead of in the middle; the same tools are sent, nothing added or dropped.
- **Measured** (PromptCacheBenchTest, from the real prompt parts and core skills with sized live data and history;
  tokens ≈ chars/4): system prompt 27.2k chars for a phone task (static 17.6k, slow 0.5k, per request kind 6.6k,
  live 2.5k; biggest parts: core skills 5.1k, visual answers 4.5k, phone control 3.5k, memory 1.5k). Shared start
  between turns of different kinds: about 3.7k → 5.8k tokens. Inside a 5-step phone task, the part of each step that
  repeats the step before: 76% → 96% (about 9.6k → 12.1k tokens per step); the live part is built once instead of
  5 times. How much this speeds up the first word depends on the AI service's caching; the speed log now shows the
  service's cache hit ("cache 12.3k/15.1k") when it reports one.
- **Work before the first request runs side by side** (Agent.runLoop): the memory block is prepared and the phone
  screen is read for orientation while the tool list, skills and specialists are still being looked up, instead of
  after them (the screen is still read on the main thread, where the element numbers live).
- **The connection to Blaze Cloud is opened early**: on the first key press in the composer and when Blaze comes to the
  front (LlmClient.prewarm, at most once a minute), so DNS and TLS are done before Send. The warm-up now always closes
  its reply so the connection goes back to the pool instead of being dropped.
- **Screen reading reuses an unchanged tree** (BlazeAccessibilityService.readScreen, agent/ScreenReadCache.kt): a read
  within 1 s of the last one, on the same window, with no accessibility event from that app and no action by Blaze in
  between (taps, typing, scrolls, gestures, any phone tool, apps it opened) reuses the last walk instead of asking
  Android for every node again. Waiting for a button (every 150 ms), finding a target right after a read, the text
  field lookup and look_at_screen after an action no longer walk the tree several times in a row. Blaze's own orb and
  overlay redraws don't count as changes to another app. Element numbers, the "Same screen… changed" diff and the
  output are built exactly as before. Each element's role is worked out once per read instead of up to three times.
- **Measuring on the phone**: the speed log line (blaze_self speed_stats) now adds the system prompt size by tier and
  its biggest parts, and the AI service's prompt-cache hit; speed_stats adds screen reads (tree walks with their average
  time, and reads reused). "Where do my tokens go" (token_ledger) now ranks the system prompt's parts (#16).
- **Blaze Cloud (prepared, not deployed)**: gateway 0.6.1 on branch feat/gateway-0.6.1-turn-pin reads the
  `X-Blaze-Prefer` header the app already sends (#17), so all steps of a turn stay on the route that answered the first
  one while it is healthy, which also keeps that service's prompt cache warm. Until it is deployed the app works as
  in 0.45.0 for routing.
- Not changed on purpose: the screenshot path (720 px, JPEG 60, text first, one model call per picture, 12 s cap) was
  already tuned in v0.33.3; screen reads stay on the main thread (moving them needs the element list made
  thread-safe); no prompt text was trimmed (no wording was removed for speed); first-token model choice stays with
  the existing chat lane and ModelRoute.
- Tests: new PromptPartsTest (10), PromptCacheBenchTest (2); ToolPickSizeTest shares its tool list. Needs a phone:
  real first-word times, cache hits per AI service, screen-read times.

## v0.45.0: one release with everything since v0.39.0 (versionCode 148)

This release brings together every feature that was waiting in its own branch. Nothing was left out; the details of each part are in its own entry below.

- **v0.43.0**: the Linux desktop really starts (or says plainly why not) and has Repair; Bank mode steps Blaze back while banking apps are open; @all asks Blaze and Halo at the same time and never shows text-form tool calls; long-press a suggestion chip and drop it on another to combine them; pick Blaze, Halo or All from the orb's arc.
- **v0.42.0**: Cloud runs: each message goes to Blaze Cloud once as a run, so a dropped connection or a closed app never loses or doubles a reply; idempotent retries for plain cloud requests; a smaller "what's going on" block for plain chat.
- **v0.41.0**: My voice accepts a normal recording; Blaze speaks only its answer; a quieter Simple chat; faster turns (timed phases, parallel prep, memory block cached per change); the Gemini voice.
- **v0.40.0**: optional, end-to-end encrypted live cloud sync of chats, memory, settings and designs between your phones (off by default).
- **v0.36.1**: 3D designs draw again instead of falling back to the simple drawing.
- **v0.36.0**: BlazeOS native apps, phase 1: a WebAssembly runtime and Snake Native.
- Merging notes: a memory change now both refreshes the cached prompt block (v0.41.0) and tells cloud sync (v0.40.0). There is no separate v0.44.0.

### Agent reliability (part of v0.45.0)

Blaze does a lot of its work out of sight, especially in Simple mode, so it must never say something worked when it didn't. This part makes every action prove itself, shows what was really done, and keeps the task's context.

- **Proof for every action** (agent/ToolVerify.kt, VerifyProbes.kt, ClaimCheck.kt, ActionProof.kt): each tool result is now typed as done (verified), unverified or failed, with the evidence, and the model sees `[verified=…]` on every result.
  - Checks after the action: a setting the system can report (Wi-Fi, Bluetooth, airplane mode, Do not disturb, mobile data) is read back; the next system alarm must match a new alarm; an opened app must really be in front; the Linux desktop must answer on its port; a written or downloaded file must exist and not be empty.
  - Sending (WhatsApp, SMS, email, Slack) only counts when the tool says "Sent". Opening the Wi-Fi page no longer counts as turning Wi-Fi on, and reading a web page no longer counts as opening an app.
  - Before the reply is shown, every claim in it is checked against these results. A claim backed only by an unconfirmed result becomes "I tried to … but couldn't confirm it worked (why)"; a failed one becomes "… but it didn't work: why". No extra model call is needed for that.
  - The check works on intent, not only English words: "let Mum know I'm late" counts as a send, and claims in French, Swahili, Nyanja and Bemba ("j'ai envoyé", "nimetuma", "ndatumiza") are checked too.
- **What I did** (agent/Receipt.kt, ui/ChatScreen.kt): every reply keeps a log of what Blaze actually did for it (each tool, a short summary of its inputs with secrets hidden, what came back, and what was checked). It is saved with the message and opens from "What I did" under the reply. A quiet line under action replies sums it up: "✓ Sent to Mum · ✓ Alarm 07:00" or "⚠ Couldn't confirm: Wi-Fi on". These receipt chips always show, also in Simple mode with technical details hidden.
- **Recovery** (agent/Recovery.kt, OneShot.kt): a safe tool that fails for a passing reason (no connection, a timeout, a busy service) is tried again after 1 s and 3 s. Messages, calls, payments and deletes are never tried again and never run twice in one task, even when the model asks again: the earlier result is given back instead. A tool that takes too long is stopped and reported honestly; for a message or payment that means "it may or may not have gone through", never "done". Stop still ends everything at once.
- **Context kept, not dropped** (agent/GoalPin.kt, LlmClient.kt, ToolPick.kt): the task's goal and the limits the user set ("under K150", "don't book anything", "only WhatsApp") are pinned into every step, so they survive summaries of earlier steps, long-chat windows and Cloud runs. "Continue" or "yes" keeps the goal of the request before it. When a request is too large and old messages must be cut, they are replaced by a short digest of what was asked and done instead of disappearing. When the model asks for a tool that wasn't in this turn's smaller tool list, the rest of the turn gets every tool.
- **Simple mode talks simply** (agent/PromptProfile.kt): Simple users' prompt no longer contains the terminal and coding instructions, file paths or commands, and asks for everyday words with no paths, commands, tool or model names. The terminal, PC and MCP tools still exist but load only when the request clearly asks for them.
- **A shorter prompt** (PromptProfile, CoreSkills): the full terminal and "make things" guidance, the coding skill and the full views list go only into turns that need them (code, files, making content, a routed view); other turns get one line each.
- **One model for the whole turn** (agent/TurnModel.kt): every step of a turn sends the same turn id and the route that answered its first step (`X-Blaze-Turn`, `X-Blaze-Prefer`), so Blaze Cloud can keep a phone task on one model. The speed log notes when the model changed mid-turn.
- **Saved phone plans only from turns that worked** (agent/PlanGate.kt): steps are kept for replay only when every action was verified and nothing had to be corrected; a "no, that's wrong" (also "hapana", "awe", "ayi", "non") right after drops the saved plan. The learned tool router doesn't learn from failed turns.
- **Fewer wasted re-prompts** (agent/NudgePolicy.kt): a claim with nothing behind it gets one re-prompt only when that can help (the tool wasn't loaded, or nothing ran at all); otherwise the honest line comes straight away with a "Try again" chip. Re-prompts per turn are counted in the speed log.
- **Requests in any language get the action tools** (ToolRouter): when no English cue matches and the request isn't English, the phone, messages and screen tools go along.
- **Cloud runs keep going through phone steps** (agent/CloudRuns.kt): a screenshot or a note added after a phone step no longer cancels the run and uploads the whole conversation again. Notes ride along with the tool results; a screenshot is put into words by one quick vision call and sent with them. The speed log shows how many steps kept the run.
- Also from the agent-reliability batch: action turns go to the strongest model lineup (ModelRoute), fewer tools are sent per turn (ToolPick), tool calls written as text are run or hidden (TextCalls), and Advanced › AI has a Cloud runs switch.
- Tests: ReliabilityP2Test (each item above), AgentLoopScriptTest (scripted model and tools: a false success claim, a tool failing mid-task, an unconfirmed action, a missing tool, a repeated message, Stop mid-step, a Cloud-run phone step with a screenshot, a long chat cut down with the goal kept), plus ToolVerifyTest, ClaimCheckTest, ModelRouteTest, TextCallsTest and ToolPickTest from the batch.

### Speed and correctness (part of v0.45.0)

Blaze should feel instant and never lose or mix up anything, also when several things run at once.

- **Saving a chat no longer re-reads every chat** (agent/ChatIndex.kt, ChatStore.kt): the chat list comes from a small index with each chat's title, time and preview. A save updates one row; the list only re-reads a chat file that something else changed (cloud sync, an older build), so it can never hide a chat. A cold start no longer builds the chat list just to check that the old-chat import already ran.
- **Faster start** (Agent.preload, BlazeApp.kt): the main conversation is read and parsed on a background thread from the first moment of start, while the rest of the app starts; it is checked against the file before use, so it is never an older copy. Pictures and recordings in old messages are checked only when their row is drawn, not one by one for every message at start. Background scheduling (Life Log, Money radar, time capsules, the idle memory review) left the main thread, and the start time is in the speed log.
- **Saves never fail silently** (ChatIndex.write, Agent.persist): a save that can't be written (storage full, a phone that refuses the rename) falls back to a second way of moving the file, then says once "Couldn't save this chat: phone storage is full" instead of losing it. A save that met a history change mid-way is retried instead of skipped. Each message is turned into text once and reused by later saves (ChatJson), so saving the long main chat after every turn is much cheaper; the file format is unchanged.
- **Chats working at once no longer share model state** (agent/CallState.kt, LlmClient.kt, TurnModel.kt): background agents, Halo's side lane, @all's two streams and the visible chat each have their own call state (chat-lane or action hint, quick-thinking hint, route pin, usage, timings, last streamed chunk). A hung call is now cut off even while another chat is streaming, usage and timings belong to the call that made them, and a background step is never tagged as a chat-lane request.
- **Stop really stops** (LlmClient.cancellable, Agent.idleGuard): tapping Stop closes the connection of the model call in flight (including the 12-second screenshot read), so nothing more arrives, no more tokens are spent and nothing is added after "Stopped.".
- **Stop and pick up again** (agent/StopResume.kt): after Stop the chat says "Stopped." with a "Continue where you stopped" chip (also under errors). The history is tidied: a tool call that was cut off gets an honest "interrupted, may or may not have happened: check before doing it again" (so a message or payment is never repeated blindly), and the unread screenshot is dropped so the resumed call is small. Continuing (the chip, or "continue", "resume", "carry on", "endelea") pins the stopped task's goal and limits again and tells the model what was already done and checked. Anything else starts something new. The stopped task is saved with the chat, so it survives closing the app.
- **Streaming is lighter on long chats** (ui/ItemBuilder.kt): each streamed frame only rebuilds the rows after the last unchanged message instead of the whole conversation; the result is always the same as a full rebuild.
- **Idle chats leave memory** (agent/SessionTrim.kt): chats that were opened, background agents' chats and notification replies no longer stay in memory forever; idle ones (not on screen, not working, nothing waiting, newest save on disk) are let go after a turn and reload from disk when opened.
- **Bank mode polls less** (bank/FrontPoll.kt): it checks the app in front every 1.5 s only right after a change, then every 3 s, and reads only the new usage events.
- Tests: ChatIndexTest, ChatJsonTest, ChatLoadTest, CallStateTest, StopResumeTest, SessionTrimTest, ItemBuilderTest, FrontPollTest.


### Security, safety and health (part of v0.45.0)

Closing doors other apps and other devices could use, and making quiet failures traceable.

- **Dashboard hardened** (dashboard/DashHttp.kt, Dashboard.kt): the link's token is checked before anything of a request body is read; bodies are capped (1 MB, about 16 MB for the 12 MB file upload) and read as bytes, then decoded as UTF-8, so a message with accents or emoji no longer hangs for 5 minutes and a huge Content-Length can't run the phone out of memory. Header lines and counts are capped, tokens are compared in constant time and new tokens are 128-bit (older 72-bit ones are replaced once). "Make a new link" rotates it, and a Wi-Fi link expires when the server stops or sharing is switched. A message typed on another device counts as outside content: risky or pre-approved actions it leads to always show the confirm card.
- **Other apps can't start Blaze listening or calling** (IntentKey.kt, MainActivity.kt): "listen now", "start a Blaze call", "open this chat" and "open the secure form" only work when Blaze's own notification, tile, shortcut, floating button or wake word sent them. The assistant button, voice command, launcher and share sheet work as before.
- **Browser tabs only read files on saved pages** (browser/BrowserEngine.kt, SavedPages.kt): file access now follows the page: on for Blaze's own saved-page archives, off again as soon as the tab goes to a website (link taps, redirects, back and forward).
- **BlazeOS apps can't wear out storage or spam** (blazeos/OsBridgeLimits.kt, OsWebApp.kt): an app's storage is kept in memory and written once per burst instead of on every setItem, and notification requests are limited to 5 a minute per app.
- **Error messages point to something you can do** (PlainError, LlmClient, ToolCalls, Agent): no message sends you to "Settings → AI" (hidden in Simple and no longer used); they say "Tap Retry" or "send a shorter message". Simple mode's prompt no longer mentions run_shell.
- **Bank mode explains phone control** (bank/BankMode.kt, BankModeLogic.kt, ui/BankModePage.kt): when the "turn phone control back on" reminder is ignored, the next one explains the trade-off in everyday words; after 5 ignored reminders Blaze stops asking and Bank mode's page says so, with "Remind me again". Simple mode explains what happens without the computer command.
- **Quiet failures are logged** (device/CrashLog.quiet): 89 empty catch blocks in the agent, app start, memory, cloud sync, browser, dashboard and BlazeOS now write what failed to the crash log (once a minute per place; cancellation is never logged).
- **Dead code removed** (docs/removed.md): the uncalled old @all lanes in Agent.kt and the never-shown free-model chain settings.
- **Tests and CI** (app/build.gradle.kts, .gitlab-ci.yml): Robolectric tests now run on ARM Linux machines (Conscrypt off there); the 15 that open Robolectric's native SQLite, which has no ARM Linux build, are skipped there with that reason and still run on x86 CI; GitLab runs the unit tests on every merge request and a lint job with a baseline (only new lint errors fail; it may fail until a baseline is committed).

### Reactions and lighter cloud sync (part of v0.45.0)

React to Blaze's replies, and your phones only send what changed.

- **Reactions** (agent/Gid.kt Reaction, ui/Reactions.kt, ChatScreen.kt): double-tap one of Blaze's replies for 👍, or tap "React" under it for 👍 👎 ❤️ 😂 😮 🙏. Tap your own reaction to take it back. Long-press (copy or quote words) and swipe (quote) work as before. Reactions are saved with the chat and sync to your other phones. Halo meeting-room messages keep their reactions when saved, but you can't add a reaction in a room yet. In a private chat they are not saved (Blaze says so once).
- **👍 and 👎 teach Blaze** (agent/TurnFeedback.kt): 👍 on a reply that worked your phone counts as one more good use of the saved steps. 👎 deletes the steps Blaze saved for that request and takes back what that turn taught it about which tools to use. The memory review also skips that turn. Take the 👎 back and all of it is restored. Blaze hears about the reaction once, at the start of your next message. A reaction never starts a reply.
- **Every message has a global id** (Gid): each bubble gets its own id that stays the same on every phone. A reply split into several bubbles gives each bubble its own id. Older chats get matching ids when they load, the same on every phone, so nothing has to be converted.
- **Cloud sync sends single messages, not whole chats** (connect/ChatItems.kt, SyncEngine.kt): a new message uploads only itself plus a small chat summary, and a reaction uploads one tiny item. Each item is still encrypted on its own (AES-GCM), and the gateway API is unchanged. A message that is missing on one phone is never deleted on the others. Whole chats from an older Blaze still merge, and an older Blaze still gets the 20 newest messages of each chat.
- **Synced changes merge into the open chat** (agent/SyncMergeIn.kt): sync no longer reloads an open chat from disk. That reload could lose a message that hadn't been saved yet, or get overwritten by a chat that was busy. Missing messages are added in time order, and nothing you have open is dropped. A reply in progress is never touched.

### Tests, known gaps and what to check on a phone (v0.45.0)

- **Unit tests** (full `testDebugUnitTest`, ARM Linux build machine): 1588 tests in 229 classes: 1489 passed, 0 failed, 99 skipped. The skips: 84 ShotsTest screenshots (they only run with BLAZE_SHOTS=1; they are now skipped before Robolectric's native graphics start, which has no ARM Linux build) and 15 tests that need Robolectric's native SQLite, which has no ARM Linux build either (MindDbTest 5, MindLogsTest 4, MindUpgradeTest 4, PrivateChatTest 1, LongChatIndexTest 1). Those 15 are not verified on ARM; they run on x86 CI.
- **Not tested on a real phone yet**: everything in this release was built and unit-tested only. Worth checking by hand: Stop in the middle of a reply or a screenshot and "Continue where you stopped"; a cold start with a long main chat; the "Couldn't save this chat" note; the dashboard from a laptop (upload, a message with emoji, "Make a new link"); the notification, tile, wake word and "Call Blaze" shortcut still listen or call; a saved page, then a website, then Back in the browser; the Bank mode reminder wording; double-tap and "React" next to link taps, word selection and swipe-to-quote; two phones syncing messages and reactions; the first sync after updating on a large chat (every message is uploaded once as its own item); "What I did" and the receipt line after real phone actions.
- **Partly done**:
  - Prompt size (#16): heavy blocks only go into turns that need them, but the size saving hasn't been measured yet.
  - One model per turn (#17): the phone sends the turn headers, but Blaze Cloud doesn't use them yet (the gateway needs a change).
  - Faster start (#2): the main chat is read in the background, but long chats still load whole (no paging yet), and other chats still open on the calling thread.
  - Streaming (#10): only changed rows are rebuilt, but the streamed text still updates the message list every frame.
  - Chat storage (#11): messages aren't rows in the database yet.
  - App start (#14): most start-up work is still on the main thread.
  - BlazeOS bridge (#33): no origin-checked message channel yet.
  - Bank mode (#24): no buttons on the reminder notification.
  - Quiet failures (#29): about 474 empty catch blocks remain outside the cleaned files.
  - Dead code (#27): only the clearly unused parts were removed.
  - Reactions: can't be added inside a meeting room yet; a reaction made on another phone doesn't teach this phone; an older Blaze joining fresh only gets the 20 newest messages per chat; the memory review skips by time window, so a turn sent within 60 s after a 👎'd one in the same chat can be skipped too.
- **Not done in this release**: the split of the agent loop into testable parts (#26, only the chat parser moved) and replay tests on the real loop (#35); one SQLite stack (#31); a shared app scope (#30); Blaze/Halo reacting instead of filler chips (R5); model inventory via the gateway admin token. Plain http stays allowed (#32) so http sites, radio/podcast streams and home-network gateways keep working. The foreground service type stays dataSync while targetSdk is 34.
- **CI**: the new unit-test and lint jobs haven't run on GitLab yet; lint may fail until a lint baseline is committed.
- **Signing**: the committed signing key is unchanged in this release; replacing it is deferred.

## v0.43.0: The Linux desktop really starts (or says why not), Bank mode, a faster, honest @all, combined suggestions and the orb picker (versionCode 146)

- **The Linux desktop starts for real, or says plainly that it didn't** (desktop/DesktopPlan.kt, DesktopHealth.kt, LinuxDesktop.kt):
  - Why it never worked: the "is it up?" check ran `pgrep -f 'Xvnc|Xtigervnc'` inside the Linux, and pgrep found the shell running that very check (its own command line says "Xvnc"). Together with a leftover `/tmp/.X11-unix/X1` socket file it always answered "up". So Blaze reported "The desktop is running" while nothing listened on port 5901, the viewer failed with ECONNREFUSED, and every later Start returned "already running" without starting anything.
  - Xvnc was started in the background of a shell that then became the XFCE session; with proot's `--kill-on-exit`, Xvnc died whenever the session ended, leaving that stale socket behind. Apps opened by Blaze (`setsid dillo &`) died the moment the short command that started them ended, so "Dillo has been started" was never true either.
  - Now: "running" means the app connected to 127.0.0.1:5901 and got a VNC greeting. Start waits up to 45 s for that (and stops early if the start job dies). The job's main shell waits on Xvnc itself; XFCE runs beside it and is restarted up to 3 times. Apps go through a small launcher inside the desktop (`/tmp/blaze-run`), so they stay open. A missing password file, a missing Xvnc or an Xvnc that exits ends the start with the last lines of `/tmp/xvnc.log`.
  - Stop no longer kills itself: every `pkill -f` pattern is written like `[X]vnc`, which can't match its own text.
  - Fewer background processes (power manager, screensaver and similar autostarts are hidden; no accessibility bus), which matters on Android 12+.
- **Repair** (Desktop page, the viewer, `desktop action=repair`): stops everything, clears stale locks and sockets, re-links Xvnc / vncpasswd and starts again with the port check.
- **The viewer waits instead of failing** (desktop/Rfb.kt, ui/LinuxDesktopPage.kt): it keeps retrying a refused connection for 30 s while the desktop starts, then shows "The desktop isn't answering…" with Retry and Repair, never the raw ECONNREFUSED text. Advanced mode shows the X server's last log lines under a failed start.
- **Blaze never claims it's running without proof** (agent/Agent.kt, LinuxDesktop.kt): the desktop tool answers "VERIFIED: …" only after the port check and "FAILED: … Do not tell the user it is running" otherwise (with the log lines); status says "VERIFIED running" or "NOT running". Opening an app says it isn't confirmed until a screenshot shows it. A new prompt rule: say something is running, open, sent or done only when a tool result this turn confirms it.
- **Bank mode** (bank/BankMode.kt, BankModeLogic.kt, ui/BankModePage.kt; Settings › Bank mode, `/bank [on|off]`, the `bank_mode` tool): banking apps refuse to run while they see an outside accessibility service or apps drawing over them. With Bank mode on, Blaze steps back: its phone control switches itself off (AccessibilityService.disableSelf), the floating orb, Magic Hand and the pointer go away, notification reading pauses, and Jarvis stops screen glances and camera looks. A notification says "Bank mode on — Blaze is stepping back" with "Turn Blaze back on".
  - Turn on by itself (on by default): when an app on your banking list opens, Bank mode turns on, and off again 4 s after you leave it (with usage access; otherwise use the notification). The list is filled from the apps on your phone that look like banking, payment or wallet apps, and you can switch any off or add your own.
  - Turning it off puts back exactly what was on. Phone control comes back by itself when Blaze holds WRITE_SECURE_SETTINGS (one optional adb command, shown in Advanced); otherwise one tap goes straight to Blaze's switch.
  - Blaze never reads, taps or types inside a banking app, with or without Bank mode (read_screen, screenshots and taps refuse; screen memory, triggers and habit watching skip banking apps).
  - Blaze has no system VPN (the browser's VPN is a proxy inside Blaze only) and doesn't record the screen, so there was nothing else to switch off. Some banks also refuse phones with USB debugging, developer options or root on; Blaze can't change those and says so.
- **@all is faster and never shows code** (agent/Duo.kt, DuoText.kt, Agent.kt):
  - Why it was slow: an @all chat raced both companions, cancelled the slower one, then ran it again after the first finished, and often a third turn, each a full model call with up to 100 s of idle allowance shared between both streams. Now both answer at the same time, each streaming into its own bubble, each capped at 45 s; one that runs late gets a plain line and the other's answer stands. Timed with TurnTimer (logcat BlazeSpeed, the speed log, route "duo-all").
  - Why Halo answered "browser_show()": the @all chat gives the models no tools, so a model that had seen tools earlier "called" one by writing it, and the text went straight to the bubble. Text-form calls (`name()`, `name(key=…)`, `name {json}`, JSON, `[/tool …]`, `<tool_call>`) are now spotted: in the meeting room an allowed research tool runs for real; anything else is dropped, Blaze asks the model once for a plain answer, then falls back to a plain line. Raw function syntax never shows, not even while streaming, and old saved replies that were only a call show "That step didn't run…".
  - When the talk is about the Linux desktop, both get a live check from the app ("running" only if the port answered), and a new rule: never say something works unless the app confirmed it.
- **Combine suggestions** (ui/Bubbles.kt FollowupRow, ui/ChipMerge.kt): long-press a suggestion chip under a reply and drop it on another to ask for both in one message ("set gentle wake tone and schedule bedtime reminder"). The combined chip can be tapped to send or dropped on a third ("a, b and c"). A haptic tick on pickup and when you're over a chip, which glows. Let go anywhere else and the chip goes back (a combined one splits into the chips it was made of). A plain tap still sends one chip.
- **Pick who you talk to from the orb** (ui/TalkArc.kt, TalkArcMath.kt, Shell.kt, ChatScreen.kt): the "Talk to @blaze @halo @all" pills are gone. Tap the orb at the top left: a lit semicircle opens above the text box (above the big mic in voice mode) with the glass orb (Blaze), the halo (Halo) and the orb in its halo (All). Drag around the arc or swipe sideways to turn it; the focused one is larger and lit, with its name underneath. Tap one to pick it; tap outside or Back to close. The header orb then shows who you're talking to. Same @blaze / @halo / @all routing as before, now in Advanced mode too (it was Simple mode only). Each icon is announced to TalkBack ("Talk to Halo").
- Tests: DesktopHealthTest (port probe on real sockets, retries, plain errors, log diagnosis, scripts that can't match themselves), BankModeTest (bank app matching, on / off / auto / restore), DuoTextTest (text-form tool calls, streaming, decisions); ChipMergeTest (join text, row order, split, drop target), TalkArcMathTest (arc layout, rotation, swipe, snap, hit); DesktopPlanTest, DesktopDistroTest, DesktopExtrasTest updated.

## v0.42.0: Cloud runs: your reply is safe when the connection drops (versionCode 145)

- **Cloud runs** (agent/CloudRuns.kt, Agent.kt; needs gateway 0.5.0): each message now goes to Blaze Cloud once, as a run with its own message id. Blaze Cloud works on the answer and keeps it, and the phone follows it as it streams. If the connection drops, Blaze reconnects on its own and carries on from the last piece it got (nothing is repeated or lost). If Blaze is closed, or the phone was offline for a long time, the reply lands in its chat when Blaze opens or the phone is back online, with the usual notification when Blaze isn't on screen. When Blaze needs your phone for something (a text, a setting, the screen), the cloud pauses, Blaze does it on the phone with the same approval rules as before, and sends the result back to the same run. Stop stops the run in the cloud too. A piece of an answer that the cloud had to start over is taken off the screen, and voice still reads only the final reply.
- **Plain words while it works**: "Sending…", "Sent. Waiting for Blaze…", "Blaze is thinking…", "Busy right now, trying again in 5s…", "Doing that on your phone…" and "Waiting for internet — your reply is safe…" in the quiet working line (Simple mode) or the step card (Advanced). After a long time offline: "Connection lost. Your reply is safe and will show up here when you're back online."
- **Falls back by itself**: an older Blaze Cloud (no runs) means the old way for 6 hours; no connection before the message got through, a message too big, or a cloud-side error means the old way for that message. Private chats, background agents, specialists, routines and Halo turns keep the old way. Settings key `cloud_runs` (on by default) switches it off.
- **No double answers on a bad connection** (agent/Cloud.kt, LlmClient.kt): plain requests to Blaze Cloud carry an Idempotency-Key. When a request may not have arrived (no answer at all), the retry uses the same key, so Blaze Cloud hands back the answer it already made instead of making (and counting) a second one. "Still being answered" (409 in_progress) now waits and asks again.
- **Smaller prompt for plain chat** (agent/Awareness.kt, ChatLane.kt, Agent.kt): the full "what's going on" block (what's playing, on screen and just happened) is sent only when the turn can use it: phone, screen, media, read-aloud or health tools are on, or the words point at it ("this song", "what's on my screen"). Other turns get a two or three line summary, and the chat lane now gets that summary too.
- Tests: CloudRunsTest (the run client against a fake Blaze Cloud: create → stream → done, resume after a cut with Last-Event-ID, reset, the phone-tool round trip, a new run when the conversation moved on, idempotent resend, older-gateway and offline fallbacks, the pick-up after a long time offline or a restart, failed runs; SSE frames, event order and repeats, saved runs, Idempotency-Key reuse, the short Awareness summary).

## v0.41.0: My voice works, Blaze only speaks its answer, a quieter Simple chat, faster replies and the Gemini voice (versionCode 144)

- **My voice accepts a normal recording** (voice/myvoice/AudioClip.kt, MyVoiceSetup.kt, ui/MyVoiceSettings.kt): the "too short" check measured speech on the clip after it was cut to 10 s and its pauses removed, so the room's quiet was gone and only the loudest ~40 % of frames counted. A full 15 s read scored about 3.5-4 s against a 5 s minimum and was always refused, which left step 3 saying "Download the model and record first" with the model downloaded. Speech is now timed on the whole recording against the room's real noise floor, with the short gaps between words counted (AudioClip.activeSpeechSeconds), so a 10-15 s read passes. Steady noise with no talking gets "There's a lot of background noise". Step 3 says exactly what is missing ("Record again first (step 2). The last one was too short to use.", "Download the voice model first (step 1).", "Waiting for the voice model to finish downloading."). A good recording saved before you left Settings still counts.
- **Blaze speaks only its answer** (voice/SpokenText.kt, voice/Voice.kt, agent/Agent.kt): voice turns no longer read the work steps aloud ("Looking at your series") or say "On it.". A soft tick plays while Blaze works instead. Text the model writes before a tool call becomes a step, so it is no longer spoken while it streams: streamed speech starts early only on the chat lane or once the reply is clearly longer than a one-line "Let me check…", and anything already said is stopped when it turns out to be a step. Spoken text drops markdown symbols, links and URLs, code blocks, table pipes, thinking, [re:m7] markers and status / tool lines.
- **Quieter Simple chat** (agent/SimpleMode.kt, ui/ChatScreen.kt, ui/Shell.kt): in Simple mode the big "Done · Details" step card is gone. While Blaze works there is one small line ("Looking at your series…") next to the usual working drop, and once it is done only the answer shows. Advanced mode, or Settings › Show every step, keeps the full step card.
- **Faster replies** (agent/TurnTimer.kt, PromptMemo.kt, Agent.kt, Cloud.kt, Memory.kt, Mind.kt, SpeedLog.kt):
  - Every turn is timed by phase (route, tools, prep, orient, prompt, first word, model, tool work). The line goes to logcat (tag BlazeSpeed) and the speed log, and `speed_stats` shows the last turn's breakdown.
  - The lookups before the first model call (tool list, current design, skills, specialists, saved plans) now run at the same time instead of one after another.
  - The chat lane no longer builds the full system prompt (memory, awareness, skills) just to throw it away.
  - The memory block is read from the database once per change (or every 20 s), not once per model call: a 6-step task read it 6 times before.
  - The gateway task hint counts message text the way the gateway does, without turning the whole request into JSON on every call. JSON escaping alone had been tagging normal-size tool turns as "long".
- **Gemini voice** (voice/gemini/GeminiTts.kt, GeminiVoice.kt; Settings › Voice › Gemini voice): Blaze's replies are spoken by Gemini text-to-speech (gemini-3.8-flash-lite-tts, falling back to gemini-3.8-flash-tts) through the Interactions API, using the Gemini key saved in Blaze (it also arrives through your Blaze account). You can paste a key on that screen if there isn't one. Pick from eight voices (Achird, Sulafat, Kore, Puck, Aoede, Charon, Leda, Vindemiatrix) and hear a sample. The reply is made sentence by sentence and plays while the next one is made. With no key, no connection or no quota left, Blaze tells you once in plain words and uses the phone voice.
- Tests: MyVoiceSetupTest, SpokenTextTest, SimpleWorkTest, TurnTimerTest, PromptMemoTest, SpeedBenchTest, GeminiTtsTest; AudioClipTest still passes.

## v0.40.0: Cloud sync between your phones, end-to-end encrypted (versionCode 143)

- **Cloud sync** (connect/CloudSync.kt; Settings › Account › Cloud sync, `/sync`, `open_view view=sync`): optional and **off by default**. Phones signed in to the same Blaze account keep their chats, memory, settings and designs the same, live. Needs Blaze sign-in; signed out, the page says so and offers Sign in.
  - **End-to-end encrypted** (connect/SyncCrypto.kt): you pick a sync passphrase. The key is derived on the phone (PBKDF2-HMAC-SHA256, 600 000 iterations, random salt kept with your account) and stored Keystore-encrypted on this phone; the passphrase is never stored or sent. Each item is gzipped and sealed with AES-256-GCM, its AAD binding kind + id. Blaze's service keeps only the scrambled copy. A second phone checks the passphrase against an encrypted check value before it syncs anything.
  - **What syncs** (connect/SyncAdapters.kt, one small adapter each, switch any off): chats (the chat files), memory (the memories table, one item per line), settings (an allow-list of your own choices: Simple / Advanced, navigation, Look & feel, persona, reply and voice options, approvals, your commands) and designs (each design's page, not its undo history). **Never synced:** the Blaze session, connector tokens, API keys and MCP tokens, the PC pairing, permissions and per-phone switches (app lock, orb position, your photo background, wake word), private chats.
  - **Live**: a push about 5 s after a change here, a round when Blaze comes to the front and every 60 s while it's open, and a WorkManager job every 15 min with a network.
  - **Conflicts** (connect/SyncMerge.kt): last writer wins per item by its change time; chats are merged instead: messages are a union by who said it and when, so two phones never lose a message (a colliding local id gets a fresh one), and a /clear on one phone hides the older messages on both. Deletes travel as tombstones. A chat too big for one item (512 KB) sends its newest messages first (model history first to go); the other phone keeps what it already had. A design too big is kept on this phone and listed (Advanced).
  - **The page**: the switch, passphrase set-up (twice on the first phone) or entry (other phones), the status in plain words ("Synced just now", "Waiting for internet", "Your cloud copy is full (25 MB)…"), What syncs, Sync now, Turn off (everything here stays) and Delete my cloud copy (asks first; stops sync on every phone). Signing in to another account turns sync off. Blaze knows the view and the `/sync` command (Awareness, CommandRegistry).
  - Needs Blaze Gateway 0.4.0 (`/v1/sync`, migration 0005). Until it's deployed the page says cloud sync isn't switched on in Blaze's service yet.
- Tests: SyncCryptoTest, SyncMergeTest, SyncEngineTest (two phones against a fake gateway: union, LWW, tombstones, paging, busy retry, size skips, resets, swapped blobs), SyncAdaptersTest.

## v0.39.0: Simple mode, swipe navigation, living backgrounds and a hands-free computer (versionCode 142)

- **Simple / Advanced** (agent/SimpleMode.kt; Settings, top; `/simple`, `/advanced`): Simple is the default and only hides things. Terminal and Jobs, MCP servers, BlazeRouter and the brain / model pages, usage meters, the dashboard, prompt commands, the skill library and sub-agents leave the lists (Views, the + menu, Settings › Advanced). Every view and / command still works, and Blaze can still open any of them. Advanced shows everything, technical pages included.
- **Navigation** (agent/SwipeNav.kt; Settings › Navigation: Swipe (default) or Classic tabs, separate from Simple / Advanced, applies at once): with Swipe, Blaze opens on Home (the Home cards: weather, news, heads-ups). A grab handle sits above the system gesture area (navigation bar inset + 32 dp) with "Swipe up to chat". Drag it up, or tap it, and Chat slides up over Home and follows your finger. A fast flick or a third of the screen finishes the move; anything less springs back. In Chat, pull down from the top bar to bring Home back, or use Back. The pill becomes Chat | Projects. Classic tabs keeps the old Chat | Projects | Home pill and opens on Chat. Chat comes up by itself when Blaze asks a question, needs your OK, shows a fix card or starts using the computer. `open_view view=home` and `/home` show Home, and Awareness reports whether Home or Chat is in front.
- **Simple chat** (agent/SimpleHome.kt, ui/SimpleUi.kt): a greeting by time of day ("Good afternoon, Sam", "Still up?") and two starters that fit the hour. A question from Blaze gets Yes / No / Later reply chips, plus Do it for me when it's about doing something. A "Talk to @blaze / @halo / @all" row keeps Halo x Blaze and team phases in reach. A big hold-to-talk mic uses the phone's own speech input: let go to send, Type for the keyboard, Photo for the + menu. / commands still work in the text field.
- **Plain working line**: in Simple mode the work card's title is the step in plain words ("Sending Jack the location…"), "Done" afterwards, with "Details" to see each step.
- **Undo bar** (agent/UndoCenter.kt): when Blaze makes or deletes a routine or reminder on Autopilot, a 10-second countdown bar offers Undo. Undo deletes the new routine, or brings the deleted one back (Routines.restore). Actions with no way back just get their plain line.
- **Errors as fixes** (agent/FixCards.kt): when a tool finds notification access, phone control, usage access, the overlay or an app permission switched off, the chat shows a card with "Turn it on". That button opens the right system screen. No error codes.
- **Things Blaze is doing** (Settings, `/doing`): routines and reminders with friendly names and when they run ("Every weekday at 07:00", "Tomorrow at 14:30"), each with a switch, then "Today I…", a diary of today's Awareness events.
- **Look & feel** (ui/LivingLogic.kt, LivingBackground.kt; Settings, `/look`): living backgrounds drawn with Compose Canvas behind the glass UI:
  - Your sky follows the local time (night stars, dawn, day, sunset, dusk) and the weather from a Home weather card if you have one.
  - Aurora, Ocean, Calm paper (still; the default, so nothing changes until you pick something), Mosaic, and Your photo (slow pan).
  - A soft wash over the background keeps text readable.
  - Light / Dark / Auto (Auto is dark after sunset) and an accent colour.
  - Move with music pulses the background while Blaze Music plays.
  - Save battery: keep it still. The background also stops by itself in power saver, at 20 % battery or less (not charging), and with system animations off.
- **Home-button sheet** (ui/AssistPanel.kt, ui/ConfirmCard.kt): Blaze was already the device assistant, so the sheet over the current app now has a "Blaze & Halo" strip with its status, "Sees: <app>", the live transcript and chips (What is this? / Reply for me / @halo / Type instead). A message waiting for your tap reads "Draft · not sent yet" with Not now / Send, here and in the chat.
- **Hands-free computer** (desktop/DesktopAgent.kt, DesktopAgentLogic.kt; `desktop_task` tool): Blaze works the Linux desktop by itself. It takes a screenshot, the vision model picks one step (click, double or right click, type, keys, scroll, open an app, wait), xdotool does it, and the loop repeats until the job is done, Blaze needs you, or 25 steps pass (40 at most). The chat shows a watch-only live PC view: frames from the desktop's own VNC, and nothing you touch reaches it. Around it are the "Watching · you can't touch the screen" badge, "Step n of m" with the plain step, and Watch full screen / Pause / Stop. Scrolled away, it shrinks to a "Working on the computer…" pill. Paying, sending or posting, and deleting still ask, even on Autopilot.
- **Blaze knows every view and command** (agent/CommandRegistry.kt; `blaze_command` tool): blaze_now lists every / command and every view, Home, Things Blaze is doing and Look & feel included. Blaze can run a command itself: views open, prompt commands give it their prompt, and app commands (/autopilot, /voice, /orb…) run. /stop, /new, /clear and /retry stay with you.
- **GitLab CI** (.gitlab-ci.yml): builds the debug-signed APK (keystore/blaze.keystore) on shared runners for pushes to feat/simple-mode, merge requests and manual runs, and keeps it as a job artifact.
- Tests: SimpleModeTest, SimpleHomeTest, FixCardsTest, UndoCenterTest, CommandRegistryTest, DoingLogicTest, SwipeNavTest, LivingLogicTest, DesktopAgentLogicTest.

## v0.38.0: Jarvis mode, follow-ups, and Autopilot by default (versionCode 141)

- **Autopilot is the default** (agent/Modes.kt, `ApprovalDefault`): Blaze runs commands and ordinary steps without approval cards. Someone who picks Auto in the + menu keeps the cards (a saved "auto" stays). Deleting things, spending money and messaging or calling people still need one tap. Questions wait two minutes (was one) before Blaze decides by itself, and the prompt no longer says the user is away.
- **Jarvis mode** (proactive/Jarvis.kt, JarvisLogic.kt; Settings › Proactive › Jarvis mode; `jarvis_mode` tool): levels Off / Gentle / Eager (default) / Jarvis.
  - Getting-to-know-you questions as heads-ups ("Quick question: what do you do for fun?"), up to 1 / 2 / 4 a day, 9:00-21:00, never in quiet hours; topics already in memory are skipped, Blaze may reword them to fit what's going on, and the reply (in the notification) is saved to memory.
  - Screen glances (switch, off by default): the screen as text through phone control every 60 / 25 / 12 minutes, never in banks, wallets, mobile money, password managers, authenticators, Settings, or on screens showing a password, code or card. Help only when it clearly fits; a one-line note of what you were doing goes into Awareness. Never while Blaze is working a task.
  - Camera looks (switch, off by default): one small front-camera frame only while Blaze is open on the Chat screen (Android shows its camera dot), never saved.
  - Usage: today's top apps (Usage access) feed its thinking.
  - Its own routines: every 14 / 7 / 3 days Blaze may propose one (never messages, money or deletes); at Jarvis it sets it up itself as "Blaze · …" and says so with a tap to remove.
  - Call offers: in the early evening every 3 days (Eager) or daily (Jarvis), a "Got a minute?" heads-up with a Call me button (Blaze's voice call).
- **Follow-ups** (proactive/FollowUps.kt, FollowUpLogic.kt; `follow_ups` tool): a chat message that makes a plan becomes a follow-up (who, app, what, when). "Jack Banda texted on WhatsApp about a meeting about the budget, tomorrow at 15:00. Are you going?" with Yes / No / I replied. Yes sets a reminder 30 minutes before. Opening that app while you haven't replied: "I see you're in WhatsApp. Jack wanted to talk about… Maybe get back to Jack?" (at most every 2 hours). Your own message in the chat's notification, Send tapped with that chat open, or a reply through Blaze closes it. Open follow-ups show in Awareness and in the heads-up check.
- **Personal heads-ups** (proactive/Proactive.kt): name the person, app, time and topic, never "you have new messages", and no nudges about chats you already answered.
- Tests: JarvisLogicTest, FollowUpLogicTest, ModesDefaultTest.

## v0.37.0: Blaze knows what's going on: the song playing, the video you were watching, the screen in front (versionCode 140)

- **Awareness** (agent/Awareness.kt, AwarenessHooks.kt): a WHAT'S GOING ON line in every prompt (in the changing tail, so the cached prefix stays the same). "Skip this song", "what was the movie I was just watching", "turn this up", "what am I looking at" work without asking.
- **Live**: the song in Blaze Music (title, artist, position, playing or paused), the video in Blaze Video (where it is now), other apps' media (Spotify, YouTube, podcasts…) from their media sessions through the notification access Blaze already has, the Blaze screen in front, the view open over the chat (with the design's title in Design), and the BlazeOS app in front. Positions are worked out when the prompt is built, so nothing ticks in the background.
- **Recently**: what you played, watched (and where you stopped), and opened, kept 3 days on the phone (filesDir/awareness.json), so "the movie I was watching" still works after Blaze restarts.
- **blaze_now** tool: everything live, the last 30 events and every Blaze app and view with the tool that drives it.
- Tests: AwarenessTest (8).

## v0.36.1: 3D designs draw again instead of falling back to the simple drawing (versionCode 139)

- **The "started but drew nothing" error is gone** (ui/DesignServe.kt, assets/design/three-safe.js). The check that decides whether a 3D scene drew anything sampled the WebGL canvas from a timer, after the browser had already cleared it (pages don't set preserveDrawingBuffer), so every scene looked blank: r170 "failed", r162 "failed", and the Design view fell back to the native sketch.
- Now every frame records its draw calls (three-safe.js wraps render), and the pixel sample is taken right after a render while the frame is still in the buffer. A scene with draw calls is ready; only a scene that renders frames with no draw calls (or never renders) fails. Pages that don't load three.js through the safe build are never failed on a check that can't see them.
- Tests: DesignRenderTest (17).

## v0.36.0: BlazeOS native apps, phase 1: a WebAssembly runtime (versionCode 138)

- **Native apps** (blazeos/OsSyscalls.kt, OsWasmRuntime.kt, OsWasmApp.kt): a new manifest entry `{ "type": "wasm", "path": "app.wasm" }`. The app is a WebAssembly module that draws straight to a Compose Canvas and gets touches, swipes, keys and Back; no web page in between.
- **Runtime**: Chicory 1.7.5 (pure JVM interpreter, no NDK). Each app runs on its own thread at up to 60 fps; pause when it leaves the screen or BlazeOS pauses, resume when it's back, kill when it's closed. 16 MiB memory cap (8 MiB on low tier phones); memories use ByteBufferMemory so they don't need VarHandle (Android 13+ on ART).
- **Syscalls** (`blazeos` module): canvas (clear, rect, circle, line, text, image, size), storage (the same per-app store as web apps), notify, theme, time / random, log. Every call goes through a permission gate: not in the manifest = refused, turned off in Settings = refused, notifications ask the first time. Refusals are return codes, not crashes.
- **Snake Native** in the App Store next to the web Snake: Snake written in WebAssembly text, 2.4 KB (blazeos-sdk/samples/snake, with its build script). SDK notes in blazeos-sdk/README.md; phases 2-4 in docs/plans/plan-blazeos-native.md.
- Tests: OsSyscallsTest (gate, imports, keys), OsWasmRuntimeTest (runs the real Snake module), OsManifestTest (wasm entries, catalog).

## v0.35.0: the Design studio, phase 1 (versionCode 137)

- **A real design studio** (ui/DesignView.kt, ui/DesignStudio.kt): header with the design's name, a light / dark switch and Open; a mode switcher (Page · 3D · Motion · Audio); the stage with floating tools (format, version and Undo on top, a select / colour / fit rail, the design check and zoom at the bottom); a layers dock; prompt chips and an ask box that is scoped to the selected element.
- **Studio pages** (agent/DesignPage.kt, new kind `canvas`): a page is a small spec (format, background, elements: text, button, rect, ellipse) embedded in a self-contained HTML file, so it still opens anywhere. Formats: Post 4:5, Story 9:16, Slide 16:9, Icon 1:1, App screen, Poster A-ratio; switching keeps everything on the page.
- **Edit by hand, natively**: one finger pans the board, pinch or double tap zooms; tap to select, drag to move, eight handles to resize (circles keep their shape, nothing collapses or is lost off the page); Front, Remove, edit text.
- **Colour wheel with hex** (ui/ColorWheel.kt): hue / saturation wheel, brightness and opacity, hex input (#RGB, #RGBA, #RRGGBB, #RRGGBBAA), brand swatches; text or fill.
- **Design check**: WCAG text contrast against what is under it and elements off the page, a 0-100 score; tap it to select the weakest element.
- Hand edits save as a new version after a pause; Undo steps back; Blaze's `design` tool can create pages from elements and edit them (`set_elements`, `remove_elements`, `format`, `background`).
- Other pages, sites and 3D scenes keep the live preview (Phone / Desktop, 3D orbit).
- Still to come: Motion timeline and Audio tracks, versions sheet, compare with equal zoom, brand kit, per-mode export.
- Tests: DesignPageTest.

## v0.34.1: a browser toolbar you can move, one Library tab with a pull-down player (versionCode 136)

- **Move the browser's floating toolbar** (browser/BrowserActivity.kt, `ToolbarDock`): drag it by its grip to the bottom, top, left or right of the page; on release it snaps (animated) to the nearest edge, never resting in between, and the edge is remembered (`browser_bar_dock`). Left / right turn it into a column. Only the grip drags, so taps on the buttons work as before; it still slides away on scroll.
- **Library and Player are one tab** in Blaze Video and Blaze Music (pill: Library | Store). Tapping a video or song opens the full player as a sheet with a grab handle; swipe it down (or tap the handle, or Back) and it collapses into a mini-player at the bottom with play / pause and close (Music keeps Next) while playback carries on. Tap the mini-player to open it again. The player is never recreated for this (mediaapps/MediaUi.kt `PlayerSheet`, `MiniBar`).
- **No blank space under the player**: Video lists your other library videos under the playing one, Music shows a "From your library" row under the controls; one tap switches.
- Tests: ToolbarDockTest.

## v0.34.0: subtitles that work, smarter Video / Music / Gallery, Camera Night + HDR, a fuller Linux desktop (versionCode 135)

- **Subtitles reach the player** (mediaapps/Subs.kt, SubsLoader.kt): Blaze Video now reads YouTube's caption tracks (manual and auto-generated), converts json3 / srv1 / WebVTT / SubRip into one clean WebVTT track and side-loads it into Media3, so the subtitle menu has real choices. A live subtitle menu while playing, and `.srt` / `.vtt` files next to a video are picked up.
- **Video**: data cost before streaming, series / episodes with skip intro and autoplay next, smart downloads, AI subtitle translation, "previously on" recap; agent tool `video_ops`.
- **Music**: smart playlists, daily mixes, Wrapped, data saver, equaliser, fades and sleep fade, playlist import, Blaze DJ, tag fixer, karaoke (lyrics), Android Auto; agent tool `music_ops`.
- **Gallery**: Clean up (duplicates, blurry shots), Trash, paging for big libraries, On this day, a photo editor, pinch-to-resize grid, a backup plan, location-free sharing; agent tool `gallery_ops`.
- **Camera**: Night (4-frame aligned burst) and HDR (about -2 / 0 / +2 EV, fused) in Photo mode at up to 1600 px (1280 on low-RAM phones); Document mode lets you drag 4 corners onto the page and flattens it (no Google scanner needed); Text mode works out prices (with totals per currency) and sums it read; product barcodes (EAN / UPC) are looked up on Open Food Facts; agent tool `camera_math` (exposure, hand-held shutter, depth of field / hyperfocal, ND, reading receipts).
- **Linux desktop**:
  - **App drawer**: every app the desktop's .desktop files list, one tap opens it.
  - **App store**: a curated list (Firefox, LibreOffice, VS Code via code-server, GIMP, Inkscape, VLC, Geany, Thonny, htop, Git) with the real package names for Alpine / Debian / Ubuntu / Arch / Fedora, installed with that Linux's own package manager with live progress; apps a Linux can't run say why (e.g. Ubuntu's Firefox is a snap).
  - **Two-way clipboard** in the viewer: what you copy in the desktop lands on the phone's clipboard; the new clipboard button sends the phone's clipboard to the desktop (paste with Ctrl+V).
  - **Snapshots**: save the Linux as it is now (one .tar.gz in Blaze's own phone folder, space checked first) and restore it later; a restore unpacks next to the current Linux and swaps it in, so a failed restore changes nothing.
  - **Restart by itself** (on by default): if Android or a crash closes the desktop, Blaze starts it again, at most 3 times in 10 minutes.
  - **Screen size**: 1024x576 (default, lightest), 1280x720, 1600x900, 1920x1080, 1280x720 24-bit or portrait 720x1280, used from the next start; the agent's clicks are scaled to the real screen.
  - **Careful-command check**: the `desktop` tool asks a second time, saying plainly that it could delete files or break the Linux, before typing or running anything like `rm -rf /`, `mkfs`, `dd of=`, package removal, `curl | sh` or a force push.
  - The `desktop` tool gains apps, launch, store, install, snapshot and snapshots.
  - Every other-Linux download (Ubuntu, Debian, Arch, Fedora, Manjaro) is SHA-256 checked, and the Terminal shows exact sizes.
- Tests: SubsTest, VideoSmartTest, MusicSmartTest, GalleryToolsTest, CameraSmartTest, DesktopExtrasTest.

## v0.33.5: Stop the desktop download, pick your Linux (versionCode 134)

- **Stop download, keeping progress**: the Desktop page's greyed-out "Working…" is now **Stop download**, and the download notification has a **Stop** action. Stop closes the download in flight (the `.part` file stays), kills the running install process (PRoot `--kill-on-exit` takes apk / apt / pacman / dnf with it) and wakes any retry wait, for the Alpine and other-Linux base downloads, the desktop packages Blaze prefetches, and the package installs. The page shows a **Download paused** card ("41.2 MB of Ubuntu is kept on the phone") with **Start** (carries on: HTTP Range resume, the package manager skips what's installed, `dpkg --configure -a` finishes a half-done apt run, a stale pacman lock is cleared) and **Remove downloaded files** (partial archives, the package caches, and a Linux whose base never finished; an installed Alpine always stays). A "Download paused" notification with **Resume** is posted too, and the paused state survives a restart.
- **Pick the desktop's Linux**: Alpine (default, "Recommended for most phones"; an existing Alpine desktop is untouched, nothing is reinstalled), Ubuntu 24.04, Debian 12, Arch Linux ARM or Fedora (64-bit only), as cards on the Desktop page, saved as a preference. Each installs, starts and stops with its own package manager and real arm64 package names (checked against bookworm / noble arm64, Arch Linux ARM aarch64 and Fedora 41 aarch64; Fedora uses `tigervnc-server-minimal`). Small shims give every Linux the same `Xvnc` / `vncpasswd` / `import` names (Debian ships `Xtigervnc` / `tigervncpasswd`). The agent's `desktop` tool works in the chosen Linux and says so.
- **Terminal in the chosen Linux**: `run_shell linux=true` (and background jobs), new Terminal tabs and the Settings terminal run in the chosen Linux once it's installed, else Alpine; hints name its package manager. run_code, git, BlazeRouter and routines stay on Alpine (they use apk).
- **BlazeOS App Store Linux apps** install with the chosen Linux's package manager (Alpine names mapped, e.g. xeyes → x11-apps / xorg-xeyes / xeyes; manifests can give `distro_packages`).
- Tests: DesktopDistroTest + DesktopFlowTest (package mapping, scripts per manager, Stop / pause / resume transitions, removal plan, terminal tab choice), FetchTest (Stop keeps the .part and resumes), OsManifestTest (`distro_packages`).

## v0.33.4: the Linux desktop install gets past the browser step (versionCode 133)

- **Desktop install no longer stalls on "The package list changed. Retrying (n of 12)"**: the last batch asked for `netsurf`, which isn't in Alpine 3.20 (main or community), so every retry failed the same way. The desktop's small browser is now **Dillo** (in community for aarch64, armv7 and x86_64), every other package was checked against the 3.20 indexes, and the extras (ImageMagick, the browser) are optional: if one is missing from the mirror it's skipped after one fresh-list retry instead of failing the install. A missing required package stops after 3 tries with a plain message, instead of spending all 12.

## v0.33.3: links open their apps, a full-screen browser, a fast screen look, a desktop install that finishes, your apps found (versionCode 132)

Fixes from a low-end phone.

- **Links open the app they belong to** (browser/AppLinks.kt, `RedirectRules`): the Blaze browser, the BlazeOS Browser and installed site apps now hand off `intent://` links (the package first, then `browser_fallback_url`, then its Play page), other apps' schemes (`market:`, `mailto:`, `tel:`, `whatsapp:`…; ACTION_VIEW with a safe chooser fallback) and https App Links: a cross-site link tap, server redirect or JS / meta-refresh redirect ("You are being redirected to the app to continue installation") opens the installed app that verifiably handles it (FLAG_ACTIVITY_REQUIRE_NON_BROWSER on Android 11+; on older phones only an app that handles that host and isn't a browser or Blaze). Same-site browsing, typed addresses, iframes and background (agent) tabs never jump to an app; the same URL is never handed off twice within 4 s (no loops); with no app for it, the page just loads.
- **More room in the browser**: the row of tab pills and the monospace "Slow network · still waiting on…" line are gone (the tabs button with the count still opens every tab). Loading shows as a 2 dp brand line laid over the bottom edge of the address bar, taking no space.
- **Floating toolbar**: back, forward, home, the mascot, tabs and more sit in a rounded glass pill floating over the page, inset from the edges and the navigation bar; the page fills the screen to the bottom. It slides away when you scroll down and back when you scroll up (always shown on the start page, with a panel open or the pointer out). Pages get an 84 px spacer at the end so nothing stays hidden under it; sheets leave room for it.
- **"Looking at the screen" is fast** (agent/VisionFast.kt): look_at_screen reads the screen as text first and answers from it when it says enough (pixels=true forces a picture for images, games, video and maps); screenshots are 720 px / JPEG 60 (was 1280 / 70), scaled and compressed off the main thread; a screenshot is seen by one model call only (later steps no longer re-send it, so they stop going to the slow vision lineup); a picture call gets 12 s, then the picture is swapped for the screen as text on the gateway's faster text route. Gateway-only routing as before.
- **The Linux desktop install finishes on a flaky connection**: Blaze downloads the desktop's packages itself (desktop/DesktopPrefetch.kt): `apk add --simulate` lists what's missing, each package is fetched with HTTP Range resume from its .part file across mirrors (main, then community), many retries with backoff up to 60 s, 90 s read timeouts, then apk installs the local files (signatures still checked) and records the names. apk's own cache is on too, and a prefetch that can't run falls back to plain apk add. 12 tries per step (was 5). The Alpine base download lives in filesDir (not cacheDir), resumes, shows "Resuming… 12.3 of 48.0 MB"; a verified archive is kept if unpacking fails, and an unpacked marker means a later failure never downloads or unpacks again. The foreground service (notification with progress, partial wake lock, Wi-Fi lock) now holds for up to 4 h.
- **Your apps, found and shown** (phone/AppFinder.kt, InstalledApps.kt): list_apps matches loosely (words of the label and the package, partial words, case-insensitive) and by Android category ("car rally game" finds Rally Horizon; "my games", "music"), lists every launchable app, and runs off the main thread. "List my apps" no longer pulls in a shopping skill (product-listing). The chat draws the list as an app card (icons and labels; tap opens, long-press quotes the tile with its real package), and a reply that says "here's your app list" always carries it. "Open / play X" resolves X against installed apps first (a quoted tile's package wins) and launches it; a Play Store link for an installed app opens the app instead, a guessed Play id that doesn't exist is refused, and the agent is told never to offer Install for an installed app.

## v0.33.2: BlazeOS, Blaze's own virtual phone (versionCode 131)

Home is now **BlazeOS**: a clean, bare virtual phone of Blaze's own (not your phone, not an Android VM, so it works on every device), with an iPhone-like look (design B), nothing signed in and only Blaze's own apps.

- **The machine** (blazeos/BlazeOs.kt, OsMachine.kt): Off → Booting (the mascot boot animation) → On ⇄ Paused, Power off, Restart. At boot it reads the host (ActivityManager.MemoryInfo, isLowRamDevice, memoryClass, StatFs) and sets its budget: disk quota min(8 GB, 25 % of free space), picture cache, disk cache, icon size, blur and animation level, apps open at once (a low-end phone gets the low tier: 2 apps, 8 MB pictures, drawn glass, reduced motion). Pause frees memory (pictures, background apps, web views, music) and keeps the app in front; Resume brings it back with its state. Factory reset wipes the BlazeOS disk only, after a confirm.
- **Its own disk** at filesDir/blazeos/ (OsDisk.kt): apps/, data/<app>/ (each app's own folder), system/ (registry.json, permissions.json, wallpaper), cache/. Per-app BlazeOS permission prompts (camera, microphone, files, notifications: Allow / Only this time / Don't allow), then the phone's permission when one is behind it.
- **Fullscreen** (BlazeOsActivity): tapping Home (or /phone, or Views › Phone) opens it immersive, with no Blaze chrome or chat; the screen stays on while it runs; the volume keys change BlazeOS's own volume; Back is BlazeOS's back; the home indicator goes home (long-press: app switcher). The real power button can't be caught by an app, so BlazeOS has its own power menu (long-press the status pill, or the control centre's Power): Pause & leave, Restart, Power off, Exit to Blaze. Leaving Blaze or pressing the real power button pauses it.
- **Control centre** (swipe down or tap the status row): BlazeOS brightness and volume, Wi-Fi status (read-only from the phone), dark mode, the power menu, recent BlazeOS notifications.
- **Home screen** (design B): status row, frosted widgets (calendar with the next BlazeOS event, Ask Blaze with the real mascot, now playing, battery / disk / memory rings), squircle app grid over pages, the App Library (search, A to Z), a frosted dock and the orange-to-blue wallpaper. Long-press to edit: drag to rearrange, − to take an icon or widget off, + Widget.
- **Built-in apps**: Blaze (the assistant), Camera, Photos, Music (Blaze Music inside the BlazeOS frame), Video, Browser (BlazeOS's own, its own WebView profile, nothing signed in), Calendar, Clock (timer, stopwatch), Weather (Open-Meteo, free, a city you pick; no location), Files (the BlazeOS disk), Terminal, Desktop (Linux), App Store, Settings. Each has its own icon.
- **App Store** (OsManifest.kt, OsRegistry.kt, assets/blazeos/catalog.json): manifest JSON (id, name, icon, version, entry). Entry types: built-in Kotlin app, web app (an HTML/JS zip or an https URL, run in a sandboxed WebView with the window.BlazeOS bridge for storage, notifications and the theme), Linux app (run in the proot Linux desktop). Ships Calculator and Notes (preinstalled), Snake, X Eyes and X Calc. Bundled catalog now, a remote catalog url later. Uninstall frees the space (package, data folder, permissions, web storage).
- **Settings app**: wallpaper (presets, a photo, a colour), icon shape (squircle, circle, rounded) and tint, grid 4×5 / 4×6 / 5×6, labels, dock apps, widgets, glass intensity, accent, light / dark, text size, per-app permissions, Storage (used vs quota), Memory, About BlazeOS, Factory reset. All saved in new bos_* prefs; a one-time migration carries the old board's photo, accent and light theme. The old board stays as **Classic board** (Blaze settings › Look & feel).
- **Install sites as apps** (siteapps/): Blaze browser › menu › "Add to Home screen / Install app", and the BlazeOS Browser's add button. The site's real icon, in this order: the web app manifest's icons (largest, maskable first), apple-touch-icon, the largest rel=icon, favicon.ico, and only then a letter icon. Outside BlazeOS it pins a real launcher shortcut (ShortcutManagerCompat.requestPinShortcut) that opens it as its own app (SiteAppActivity): no address bar, bars in its theme_color, a splash with its icon and name on background_color, its own Recents entry (setTaskDescription), its own WebView profile where the phone supports it, Back inside the app, off-scope links in the browser. Browser › Installed apps opens or uninstalls them. Inside BlazeOS it becomes a home-screen app in the registry with its own data folder.
- **Views**: Phone (BlazeOS) in /views and the Views list; /phone opens it fullscreen.
- **Low-RAM phones**: apps are composed only while open, icon and picture decoding happens off the main thread with downscaling, real blur only on API 31+ and not on the low tier, boot / installs / disk scans on IO.
- Tests: OsLayoutTest (grid, pages, home order, dock, icons, prefs + migration), OsMachineTest (state machine, tasks, resource budget), OsManifestTest (manifests, catalog, install registry, permissions), SiteLogicTest (head and manifest parsing, icon order, scope, colours). Screenshot 57-blazeos-home.

## v0.33.1: Fewer round trips, measured tokens

The owner's 33-idea speed / intelligence list, checked against the code. Seven were already in Blaze in some form, four are built here, and the rest are recorded with why not (docs/ideas-0.33.1.md).

- **Content-hash tool cache** (idea 29, agent/ToolCache.kt): identical web_search / fetch_page lookups are answered from a 200-entry on-phone cache keyed by a SHA-256 of the tool and its normalised arguments. Time-sensitive searches (news, today, live, prices, rates, weather, scores) keep 5 minutes, other searches 30, pages 2 hours (news / live / API pages 10 minutes). Errors, bot blocks and empty results are never kept; "refresh" / "check again" skips the cache; a cached answer tells the model its age. Private chats never read or write it. Toggle: set_setting tool_cache.
- **Speculative fetch** (idea 2): after a web search on Wi-Fi (never on metered data or in Low-data mode) the top two result pages are fetched in the background into that cache, so the fetch_page that usually follows comes back at once. A fetch already in flight is awaited, never started twice. Toggle: speculative_fetch.
- **Learned tool router** (idea 16, agent/LearnedRouter.kt): learns on the phone, from your own turns, which words go with which tool groups, and preloads a group when a word has been seen in at least 3 turns and the group ran in at least 60 % of them. It only adds (at most 2 groups a turn) on top of the word lists, so nothing becomes unreachable; it saves the find_tool / load_tools round trip on requests the word lists miss. Not used in private chats, routines or background agents. Toggle: learned_router.
- **Adaptive thinking budget** (idea 9, agent/ThinkBudget.kt): "think briefly" (and the gateway's quick lineup for plain chat) was decided by length < 80 characters. Now an on-phone classifier reads hard cues (why, prove, solve, compare, plan, debug, maths, code, several questions) and easy cues (greetings, open / call / set an alarm, fix typos, translate, summarise). Short-but-hard thinks properly; long-but-easy answers fast. Toggle: adaptive_think.
- **Token ledger per feature** (idea 33, agent/TokenLedger.kt): every model call's input is split into its parts (system prompt, each tool group's schemas, your messages, replies, tool calls, each tool's results, screen readouts, images) and kept for 7 days. Ask "where do my tokens go?" (blaze_self token_ledger) for the biggest parts with their share, tokens per call and a concrete fix each, plus tool cache and learned router stats. Reports only; nothing changes itself. Toggle: token_ledger.
- Tests: ToolCacheTest, LearnedRouterTest, ThinkBudgetTest, TokenLedgerTest.

## v0.33.0: Life Log 2, YouTube back in the Store, batch downloads, Scam Shield

- **Life Log 2: record, then review.** Life logs record only the moments people speak (sound gate with pre-roll and hangover) to a session file. After Stop a background job (foreground notification with progress, survives restarts) transcribes it on the phone with Vosk, tells voices apart with Vosk's speaker model (x-vectors, average-linkage clustering; ~13 MB, downloaded once) and recognises voices it already knows. Blaze drafts the review (scenes and settings from what's said, room noise and time; who each voice is, "You" for the owner; memories; things you said you'd do) and Halo checks it against the transcript, fixes names, adds relationships and feelings, and returns the final version. Memories are saved marked inferred, the labelled transcript goes into the journal, the write-up posts to the chat, and names given to voices are remembered for next time. Audio is deleted after the review unless "Keep recordings" is on. Settings › Life logs: Record-then-transcribe (off = old live text mode), Keep recordings, Review my last recording again, known voices + Forget voices. life_logs action review. life/LifeAudio.kt, Diarize.kt, LifeReview.kt, LifeLogWorker.kt. LifeLog2Test.
- **YouTube in the Music and Video Stores again**, built in (mediaapps/store/YouTubeStoreSource.kt): YouTube's own search (videos only, live streams skipped, more pages), and play / download through the working link clients with the quality picker. Replaces the plug-in, whose streams YouTube refused mid-download; old queued YouTube downloads refresh through it (same id "youtube"). Pasted links now show the real title and channel (oEmbed) instead of "YouTube video".
- **Batch downloads from the chat** (tool media_download, mediaapps/MediaFetch.kt): "download these 10 songs" searches each one, scores the matches (words, covers/live/remix/loops/long mixes penalised unless asked, official/Topic/VEVO preferred), queues clear matches at once at the asked quality (best / high / small, or 720 / 480 / 1080 for video; m4a unless opus asked), and returns the unsure ones with three candidates so Blaze asks you, then queues your picks. MediaFetchTest.
- **Shuffle and loop buttons in the music notification**, lock screen and quick-settings player (media3 custom layout): dim when off, loop cycles off / all / one. MusicService, MusicControlsTest.
- **Money Radar** (Receipt Vault + Bill Radar + Bundle Guardian; opt-in in Settings › Notification replies, or ask Blaze): mobile-money and bank SMS become a searchable receipt ledger (amount, sent / received / paid / withdrawn, who, balance, reference; never PINs or codes); payees paid two months running become bills with a nudge two days before; data / airtime bundle SMS are tracked with a warning under 100 MB / 10 % or 24 h before expiry. Tool money (summary, receipts, bills, bundles, add_bill). notify/MoneyRadar.kt, MoneyRadarTest.
- **Safe Walk** ("walk me home", "check on me in this taxi"): pick a trusted contact and a time; an ongoing notification has I'm safe and +15 min. Miss the time and Blaze buzzes you, then after 2 minutes texts the contact your last location (map link) and destination, with updates every 10 min until you check in (and a "I'm safe" text after). Starting it shows a confirmation card describing exactly that. Survives restarts. phone/SafeWalk.kt, SafeWalkTest.
- **Phone Health Check** ("why is my phone slow / hot?"): a score out of 100 from battery, heat, storage, memory, uptime and app use, fixes in order of payoff, apps unused for 30+ days with sizes, and the week's most-used apps. Read-only. Tool phone_health, device/PhoneHealth.kt, PhoneHealthTest.
- **Time Capsule**: seal a note for future you ("open in a year", "on 2027-01-01"); it stays locked (even from Blaze) until then, opens as a notification and in the chat. Tool time_capsule, agent/TimeCapsule.kt, TimeCapsuleTest.
- **Autopilot Routines**: triggers now fire on arriving at / leaving a place (saved by name and coordinates, or "here"; checked every 15 min from the last fix, with hysteresis), a Bluetooth device connecting (by name) and joining a Wi-Fi network (by name), so "when I get to campus go silent, open my timetable and turn on Wi-Fi" is one sentence. proactive/Autopilot.kt, AutopilotTest.
- **Paperwork Mode** and **Price Scout** skills: step-by-step help with NRC, passport, TPIN, licence, bursary and other forms (documents to bring, fees and deadlines from the official site, field-by-field filling, never submitting without a yes), and live local price comparison with unit prices and the cheapest trustworthy seller flagged.
- **Receipt Vault photos and Bill forecast**: send a receipt photo and Blaze files it (money add_receipt), with warranty end dates reminded 2 weeks before (warranties); money forecast shows bills in the next 30 days and your usual monthly spending.
- **Halo Persona Studio** (Settings › Personality, or ask Halo): sliders for warmth, humour, energy, formality and length, a Bemba / Nyanja mix, boundaries, and saved personas to switch between. Applied to every Halo turn and duo chat. agent/HaloStudio.kt, ui/HaloStudioSection.kt, HaloStudioTest.
- **Group Brain**: ask a group chat a question ("when's the stats test?" in the class group), catch up on hundreds of unread messages, or see who owes what, from the chat logs Blaze keeps. Tool group_brain, notify/GroupBrain.kt, GroupBrainTest.
- **Desktop Bridge**: the dashboard (Settings › Dashboard, Share on Wi-Fi) is now Blaze on a big screen: chats that still drive the phone, the phone's clipboard both ways, Blaze's files to download, and drag-in uploads to Download/Blaze. A QR code with the token link opens it from a laptop or tablet. dashboard/Bridge.kt, BridgeTest.
- **Agent Marketplace**: browse skill packs other Blaze users publish (GitHub repos tagged blaze-skill), get a safety review of any pack (PINs/passwords, moving money, skipping approvals, overriding rules, piped scripts, sending data out), install with a confirm card showing the verdict (unsafe packs are refused), and share your own skills as a .blazepack.zip. Tool skill_market, agent/Marketplace.kt, MarketplaceTest.
- **Duo Workspaces**: long projects over days with a goal, a shared folder (~/workspaces/<name>), a todo / doing / done board with cards owned by Blaze, Halo or you, a log, and an optional daily standup where the duo reports and does its next card. Tool duo_workspace, agent/DuoWorkspace.kt, DuoWorkspaceTest.
- **Call Copilot** (opt-in, Settings › Notification replies): live offline captions in a notification during calls (both sides on speaker), a "Halo, help" button for a suggested answer plus a translation if the caller switches language, and a summary with to-dos after you hang up. No audio kept. phone/CallCopilot.kt, CallCopilotTest.
- **Beat Lab**: hum a melody (or give a WAV) and Blaze makes a beat on the phone: pitch tracking, key detection, notes snapped to the key, chords chosen bar by bar, drums / bass / pad / your melody as lead, pop, trap or amapiano feel, at your tempo; saved to Music, with lyrics from Halo on the chords as the next step. Tool beat_lab, mediaapps/BeatLab.kt, BeatLabTest.
- **Clip Studio**: pick videos ("latest 4" or by title) and get a 9:16 reel (default 30 s) with the best slice of each, a caption per clip and an optional music bed, made on the phone with Media3 Transformer and saved to Videos. Tool clip_studio, mediaapps/ClipStudio.kt, ClipStudioTest.
- **Offline Brain** (Settings › Brain): download a small on-device model once (Qwen2.5 0.5B, about 550 MB, LiteRT / MediaPipe) and Blaze keeps chatting with no internet or no data: it answers, drafts and plans on the phone, and says what it will do with tools once you're back online; the next message online goes to Blaze Cloud with the whole chat. agent/OfflineBrain.kt, OfflineBrainTest.
- **Scam Shield.** SMS and chat notifications are checked on the phone for "sent by mistake" mobile-money scams, fake prizes, account-blocked threats, requests for PINs or codes, look-alike or hidden links and fee-first offers; a likely scam gets a warning notification with the reasons. On by default; toggle in Settings › Notification replies. notify/ScamShield.kt, ScamShieldTest.

## v0.32.2: Link downloads that finish

- **Fix: YouTube link downloads stuck on "Retrying soon · The server refused the download".** The app clients Blaze used (ANDROID_VR, ANDROID, IOS) now hand out stream urls that answer the first bytes and then refuse (YouTube wants a PO token). Blaze now asks the VISIONOS client first (plain streams, no PO token), updates the ANDROID / IOS versions, and the stream probe reads 1 KB at ~1.5 MB in instead of the first byte, so a client whose urls would stall is skipped. A stuck download picks up the new path on its next retry.
- **A link pasted in the Store search box** now opens it like "Download by link" instead of searching for the url.

## v0.32.1: Download by link

- **Download by link (Music and Video).** A "Download by link" button in both Stores: paste a YouTube link (watch, youtu.be, Shorts, YouTube Music, embed, live; prefilled from the clipboard) or a direct link to a music / video file (or a page with an og:video / og:audio file). It opens the usual preview with qualities, Play / Watch and Download. YouTube links try the YouTube plug-in first (when the build has it), then Blaze's own InnerTube player client (ANDROID_VR, ANDROID, IOS; visitor id; stream probe). Downloads refresh expired links the same way. `mediaapps/store/LinkSource.kt`; tests: LinkSourceTest.

## 0.32.0 — Halo x Blaze: open conversation + Meeting room

No more "both answer, react once, stop". Blaze and Halo now really talk.

- **No turn cap.** Every duo message ends in a hidden control tag: pass to the partner, hand the floor to you, agree (and name who writes the final answer), or done. They keep going until one of those ends it. Only a high safety stop remains (`duo_max_turns`, default 24 messages per round); when it hits they're told to land it.
- **First to speak takes the floor.** Both get your message at once; whoever produces the first character is shown, the other is restarted with your message AND that reply, so the second message actually answers the first.
- **They involve you.** When something depends on you (taste, a missing detail, a decision), they ask and stop. Your next untagged message goes straight back to them, in the chat or in the room. A plain phone command ("open WhatsApp") still goes to Blaze; any @tag starts fresh.
- **Chat mode** (@all on casual things): short texting-style messages in the main chat, banter welcome. When you asked for something that needs an answer and they agree, the one they pick writes a polished **Consensus**.
- **Meeting room** (tasks: untagged creative/multi-step jobs, @all on real work like code, plans, comparisons, "should I…"): a separate window opened from a live card in the chat. They propose, critique, write code, say which tools/skills they'll use and actually run research tools (web search, pages, memory, skills, calendar/drive/gmail search; read-only), with tool steps shown in the room. Agreement only counts when both agree back to back, and not before a proposal, a critique and a revision. The chosen one delivers the **Verdict** in the main chat. You can drop in from the room any time.
- **Anti-yes-man rules** in every prompt: don't entertain weak/generic ideas, offer better, faster, nicer ways; agreeing early is a failure; say why when you agree.
- **Split: Halo is free while Blaze works.** When Blaze is busy with a task (its tool loop), your new messages go to Halo instead of waiting: the glass drop detaches and stays on Blaze's work (its halo fades off the working orb), and Halo's gold ring rides her own replies. She chats, answers and researches (web, pages, memory, skills; no screen control). If your message is really for Blaze's task (a correction, "stop", "also do X") or needs the phone, she passes it on and it joins Blaze's run at its next step. "@blaze …" still steers Blaze directly. Side talk joins the history only once neither is mid tool-call. Setting `duo_split` (on).
- **New Blaze icon everywhere in the app**: the old flame face is replaced by the glass drop inside its gold halo (top-left avatar, setup/settings orb, assist panel, Magic Hand, browser, work banners). The launcher icon is unchanged.
- **Fix: web search said "no results" for things that exist** (e.g. REA, morluto/rea). DuckDuckGo's HTML endpoint now often answers with a bot check, which the parser read as empty. Search now detects the block and falls back to Bing (RSS) and, for code-ish queries or when blocked, GitHub's repo search. A total failure is reported as "blocked, this does NOT mean nothing exists" with a direct URL to try. Tests: WebSearchTest.
- Fix: "[Blaze]" / "[Halo]" tags no longer leak into work-card steps or replies.
- Rooms are saved with the chat. New: `ui/MeetingRoom.kt` (RoomCard, MeetingRoomScreen). Core rules in `Duo.kt` (signals, consensus, kindFor, prompts), runner in `Agent.runConversation`. Tests in DuoTest.

## v0.31.3: The pair rests off the words

- **Fix: where the orb and halo finish.** The waiting half used to sit at the start of the reply, on top of the first word. Now a small
  rest slot sits after Retry in the Time · Copy · Quote · Retry row. The waiting half waits there while the other rides the text. When
  the reply ends, the traveller leaves the last word, glides down into the slot, joins its half, and the pair dims and waits there.
  A reply with no actions row never parks on the text: the pair just fades.
- The docked pair stops animating (no wobble clock while it waits), saving battery.

## v0.31.2: Halo x Blaze

- **Two minds in one orb.** Blaze (blue: fast, sharp, structured) and Halo (gold: warm, curious, imaginative) now share the chat.
  `@blaze` = Blaze alone, `@halo` = Halo alone, `@all` (or both / duo / everyone) = both answer at once and react to each other.
- **Team jobs.** With no tag, creative or multi-phase work (stories, plans, scripts, campaigns…) becomes a team task: a quick
  kickoff where they pick roles, then phases (same-step phases run in parallel), handoffs, and a final piece. Phone tasks stay with Blaze.
- **The look.** The glassy drop now sits in a golden halo. While Blaze writes, the drop rides the text and the newest words shimmer
  blue; while Halo writes, the halo leaves the drop and rides ahead, the words shimmering gold. When a reply ends, one band sweeps the
  words, the traveller glides home, and the pair dim and wait. Halo's (and duo) replies carry a small coloured name.
- **Stream end fix.** A reply settles when words stop arriving: the last word is always fully solid and the orb never stays parked on it.
- **Theme fixes.** Glass boxes (composer, preview cards) no longer show a pale rectangle inside (an elevation shadow seen through
  the glass); the avatar menu and "/" suggestions follow the theme instead of dark-on-dark in light mode; camera sheet text fixed.
- Tests: `DuoTest` (tags, team detection, plan parsing, reply cleanup, context).

## v0.30.7: Faster phone actions

The v0.30.4 trace of "stop GitHub opening links in the GitHub app" took 18 steps and 4 min 25 s (≈ 7 s a step, one model round trip per tap). v0.30.4 cut what each call carries; this release cuts the number of calls. Target for a task like that: 2 model calls (3 at most when a label differs) and ≈ 12–18 s, down from 18 calls and 265 s (estimates from the trace: ≈ 5.5 s per model call, ≈ 3.5 s on the phone; not yet re-measured on the test phone).

- **The prompt stopped asking for a reply per tap.** The orient rule said "check that each step worked before the next" and the first-call note said "Re-check the screen after each action before the next one", which told the model to send one action per reply, against run_plan. Both now say: plan the whole task, send it in ONE reply (run_plan or several calls), the phone checks each step and you get the screen once at the end.
- **Whole settings tasks in one call.** run_plan steps can open a settings page by intent (`{do:open_settings, page:open_by_default, app:GitHub}` then `{do:tap, target:"Open supported links", want:"off"}`), so the trace's task is one run_plan + the one-line answer.
- **Switches are never flipped the wrong way.** `tap` and run_plan taps take `want: on|off`: Blaze reads the switch (on the element or inside its settings row) and only taps when it isn't already that way ("Already off: … Not tapped"). This is what makes batching and replaying a toggle safe.
- **Plan memory.** The phone steps of a task that ended well (open_app, open_settings, taps by their words, typing, scrolls, back, run_plan steps; a switch tap records which way it ended up) are kept per request on the phone (`plan_memory.json`, 120 requests). The same request next time ("stop GitHub from opening links" matches; "turn off wifi" never matches "turn on wifi", other apps or numbers don't match) gets them with the first call as one run_plan to send, so it's 2 calls with the right labels for this phone. Never kept: private chats, background agents, routines, a task that ended on a failure or needed a "did you really do it" re-prompt, and any plan with a send / pay / delete / remove / uninstall step (`TapLoop.Recorder`, `TapLoop.Plans`).
- **run_plan waits for the next target, not a fixed settle.** After a step whose next step looks for a label, the settle is 0.45 s max (was 1.2 s) and the next step's own wait (2 s, LabelMatch on the new screen) does the checking; after open_app / open_settings 0.6 s (was 1.5 s). Apps that never go quiet (animations, video) hit the old cap every step: up to ≈ 0.75 s saved per step, ≈ 0.9 s per app launch.
- **No summarise call in the middle of a phone task.** Auto-compaction ran before every model call; once the turn has touched the screen it now waits for the next turn (a "too long" error still folds and retries as before). When it fired, that was a blocking extra model call of several seconds mid-task.
- **A run_plan that opens an app no longer pays for a glance.** `Orient.startsByOpening` read `action`, but run_plan steps say `do`, so every plan starting with open_app did an extra full screen read and carried a ~900-character "[Before acting]" block. Fixed (open_settings counts too).
- **Shorter screen readouts.** Unlabelled clickable rows (`[12] item`) are left out of read_screen and the after-action diff (their labelled children are listed, a tap on a child climbs to the row, and the numbers stay valid): ≈ 10–20 % fewer characters on Settings-style lists.
- **Skill replays (run_skill) find their targets like run_plan:** LabelMatch with a short wait and scroll-to-find when the old substring match misses, and a 0.6 s settle instead of 1.5 s before each step. open_settings is recorded in the trace now, so a learned settings skill replays it.
- Unchanged: risky-tap confirmations (run_plan, tap, run_skill), ConfirmGate and SkillGuard. The model lanes are unchanged: every call goes to the Blaze gateway's "agent" task, which has no separate low-latency tool lane yet (that would be a gateway change). Tests: `TapLoopTest`.

## v0.30.6: Rapid messages (send while Blaze works; replies say which message they answer)

Luke: "Let's make blaze like [another assistant] where I can send messages after messages with no pause and blaze highlights the one it's responding to and such (or replies to rapid succession questions that are related with one message)".

- **The composer never blocks.** Before, a message sent while Blaze worked was silently dropped (`Session.send` returned when a turn was running, and the chat screen ignored Send while busy). Now it shows at once as your bubble and joins the running turn at its next step, between tool calls ("sent while you were working"); if the turn already gave its answer, it starts the next turn right after (never two at once). With text in the box the round button is Send even while Blaze works; Stop shows when the box is empty. Stop keeps what you sent in the conversation but starts nothing.
- **Bursts are one turn.** Typed messages a moment apart while Blaze is idle are batched: the turn starts 0.6 s after the last send, or, if you're typing again, once you pause for 1.5 s (never more than 6 s after the last send). Your bubble, the tick and the working card show at once. Voice, Magic Hand, routines, notification replies and background agents start immediately, as before. Several messages in one turn are routed (skills, tools, the chat lane) as one request.
- **The model knows which message is which.** Every message reaches it as `[m7] …`; the prompt says to read them all, that a later correction wins, to answer related ones together and unrelated ones separately, and to start each reply with `[re:m7]` / `[re:m7,m8]`. The marker is never shown or spoken (held back while streaming); separate answers become separate bubbles.
- **Quote chips and highlights.** A reply that answers a message that isn't right above it, or several, gets a small quote chip (a thin accent bar + the first 60 characters); tap it to scroll to that message, which glows for 1.2 s. The messages Blaze is working on get a faint accent wash until it's done. Saved chats keep the link (`reply_to`, optional; older chats load as before).
- ask_user questions are still answered by what you type; on-device answers (time, battery, "hide the orb") still come instantly, and while a turn runs they reach the model as a note at its next step instead of landing in the middle of a tool call. Code: `agent/RapidMessages.kt` (pure; RapidMessagesTest), `Agent.Session` inbox / burst / `startTurn`, `ui/ReplyQuote.kt`.

## v0.30.5: Fixes batch (secure form, YouTube streams, Home glass, chat glass, taste, skill guard, send hitch)

- **Secure sign-in form closes when the task stops.** A stopped or killed task used to leave its secure form on screen. The wait is now in try/finally: the card and its notification go away on stop, cancel or force-quit, an answered form disappears at once, and the task is told not to ask that site again.
- **YouTube Music / Video play and download again** ("The stream link expired or was refused"). The YouTube plug-in (Blaze-mobile) probes each client's stream urls before handing them out, skips a refused client, sends a visitor id, and uses ANDROID 20.10.38 (19.44.38 now gets 400). The music and video players retry once on a refused stream with a fresh link and resume where they were; downloads refresh the link the same way.
- **Store suggestions and taste.** YouTube and other search-only stores open on a scrollable row of ideas instead of a blank search. Ideas come from what you play and download (kept on the phone, `Taste`), and Blaze saves a short "music and video taste" memory.
- **Jamendo.** The client id ships in the APK when the `JAMENDO_CLIENT_ID` secret is set in CI; otherwise the setup card offers "Get a free ID" and "Ask Blaze to set it up" (the `jamendo_setup` tool checks the id with Jamendo before saving).
- Home web window is fixed again (no moving or resizing, like v0.29.3). The page only scrolls; slide on the glass or around it for a slight tilt that springs back. The page no longer goes black, and the title bar is opaque enough to read in light and dark.
- New live browser preview on Home: while Blaze works in its browser, a fixed glass window shows the page and the current step. Preview only: tap or Watch opens the full browser, × hides it until the next task.
- Chat bubbles and the "Worked · N steps" card use theme glass: white-tinted glass with a light hairline in dark, frosted white with a soft dark hairline in light. The blue outline is gone.
- Sent pictures stand on their own (rounded, right-aligned, no bubble around them); your words get their own bubble underneath.
- **Readable tables.** A table too wide for the card slid its columns under a sticky first column and cut text off on both edges. Columns now shrink to fit the card (no sideways scrolling); a table with more columns than fit at their minimum width shows each row as a small block instead (first cell bold, then "Header  value").
- The Magic Hand pause bar's "Resume" no longer wraps mid-word: buttons keep their width, the hint wraps on words.
- **No hitch when you send.** Every send turned the whole chat (all messages and the full model history) into JSON on the main thread, which grows with the one long main chat. Only a cheap snapshot is taken now; the JSON is built and written in the background, newest snapshot wins.
- **Skill guard.** `use_skill` / `run_skill` only load a skill that shares real words with what you asked (your last few messages). One shared word is refused once and needs a stated reason; no overlap is always refused; naming the skill always works. The per-turn suggestions drop skills matched by a single stray word (`SkillGuard`, SkillGuardTest).
- **Heads-ups for new things on Home.** After the background Home refresh, a new story, video, score, release or deadline you haven't seen, or a price move of 5 % or more, becomes one short heads-up ("New on your Home: …"). Opening Home marks everything on it as seen; the first pass only learns the board. At most one every 4 hours and 3 a day, and quiet hours and digest mode apply. Say "turn off heads-ups about new things on Home" to stop them (`HomeNews`, HomeNewsTest).
- **Often-done tasks get skills first.** Every real task (2+ tool calls) is counted by its fingerprint. A task done 3 times with no skill covering it triggers the learning review with skill-writing first, and the prompt lists up to three such tasks so Blaze saves a workflow next time.

## v0.30.4: Fast phone actions, true storage figures

The test phone trace of "stop GitHub opening links in the GitHub app" took 18 steps and 4 min 25 s before it was stopped (≈ 7 s a step, one model round trip per tap). Causes found in the code, and the fixes:

- **Old screens were never dropped from the prompt.** The check for a screen readout looked for "Foreground app:", a header `readScreen` stopped writing in v1.9.7, so every step re-sent every earlier screen (and, with long chats on, big ones were cut to head + tail lines, hiding the middle elements). The history grew by a whole screen per tap, which could also trip the auto-compaction check and add a blocking summarise call in the middle of the task. Now `FastActions.isScreen` recognises every readout; only the newest (plus the full one a diff refers to) is kept, in the history itself. On a replay of the trace's shape the last call carries 22,018 → 9,349 characters and the task as a whole sends 44 % less (`FastActionsTest`).
- **Skills by word overlap.** "github" + "app" + "open" pulled in android-app-builder, git-pr-workflow and three workflows (≈ 6,000 characters in full) before the first call, the "Operator" specialist matched "in the app", and "github" switched on the terminal and connector tool groups. A plain device / settings action (`FastActions.plainDevice`) now gets no skill packs, no skills lookup, no specialists and none of those groups, only a learned, replayable skill of the user's own on a strong match; other requests need score ≥ 5 from ≥ 2 distinct words for a skill in full (`SkillGateTest`).
- **Settings by intent: `open_settings(page, app)`.** One call lands on the exact page: an app's Open by default (`ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` + `package:` on Android 12+, App info + the "Open by default" / "Set as default" row on Android 11), notifications (`APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE`), permissions, battery, storage, data, App info, unknown sources, overlay, all-files, language, alarms, and phone-wide pages (Wi-Fi, Bluetooth, location, display, sound, battery, default apps, …), with fallbacks in order. The app name resolves to its package itself (no `list_apps` step). The prompt now says to use it instead of tapping through Settings (`SettingsRouteTest`).
- **Taps that fail on Samsung.** Targets matched plain substrings of visible labels only, then one blind scroll. `LabelMatch` normalises words and knows OEM names (Samsung "Apps" / Pixel "Apps & notifications", "Set as default" / "Opening links" / "Open by default" / "App links", "Choose default apps"…), allows small spelling differences, never matches "Allow" to "Don't allow" (or back), and never matches a destructive row ("Uninstall", "Force stop", "Clear storage") unless the target says so. `reveal` searches the whole tree, brings off-screen rows in with ACTION_SHOW_ON_SCREEN, else scrolls the main list down then up until the row shows or the list stops moving, so `tap text=…` and run_plan taps need no separate scroll turns (`LabelMatchTest`).
- **Macros restarted from scratch.** A run_plan stopped at step k now says steps before k are done and to resend only k onwards, and the same script sent again in the same app skips the steps already done (`FastActions.skipDone`). Its waits went from 3 s of polling + one scroll to 1.2 s + `reveal`.
- **"Looking at the screen first" round trips.** A result that already carries the screen (open_settings, a stopped run_plan) now counts as a look, so a numbered tap after it isn't bounced back for another model call.
- **Batching.** Several screen actions in one reply run back to back with a 0.7 s settle; the screen is read once after the last (was: settle up to 1.5 s and a full read after each). The prompt says multiple calls per reply are fine. After-action settle is 1.2 s max.
- **Storage.** "What's my storage space" said "5.9 GB free of 118 GB" on a 128 GB low-end phone that Settings shows as 61.98 GB used (48 %). `device_status` read `StatFs(/data)`: its total is the /data partition only (system partitions excluded, so 118 GB) and its `availableBytes` is only what an ordinary app can allocate right now, without the space Android frees on demand (clearable caches) or reserved blocks. It now uses `StorageStatsManager.getTotalBytes/getFreeBytes(UUID_DEFAULT)` like Settings, falls back to `StatFs(Environment.getDataDirectory())` with the total rounded to the advertised size, and reports decimal GB: used, total, used %, free, plus any mounted SD card (`StorageMath`, `StorageMathTest`). The low-storage proactive check uses the same figure.
- The step trace stays honest: open_settings shows "Opening GitHub's link settings", the run_plan board marks skipped steps "(already done)", and a tap by text says how many scrolls it took. Risky-tap confirmations are unchanged.

## v0.30.3: Blaze Camera Pro (zoom dial, digital zoom, focus, exposure, grid, timer)

- **Zoom presets** above the shutter: **1x · 2 · 5 · 10**, plus **0.5** when the phone exposes an ultrawide lens (CameraX reports a zoom below 1). Only presets the camera can reach are shown (the front camera gets 1x and 2). The lit pill is the largest preset at or below the live zoom and shows the live value between presets ("3.4x"). Tapping a preset glides there.
- **Half-circle zoom dial** (a protractor): tap the lit preset or long-press any to open it. Dark glass, minor ticks every 5°, labelled stops (1x, 2x, 5x, 10x), an accent arc up to the thumb and the live value in the middle. Drag along the arc to turn it (relative, so touching never jumps), tap a spot to go there; a light haptic tick at each labelled stop; it settles on a stop within 3° when let go and hides after 3.5 s idle. Log scale, so 1x→2x gets as much arc as 5x→10x.
- **Digital zoom past the lens's maximum**, up to 10x on the back camera (the lens's own max when it goes further; 4x on the front): beyond `maxZoomRatio` the preview is scaled about its centre and the saved photo gets the same centred crop, decoded straight from the JPEG (BitmapRegionDecoder, never the full frame), upscaled and lightly sharpened (unsharp mask, stronger the further you zoom), off the main thread, EXIF orientation kept. Pinch and the dial run straight across the hardware / digital boundary; the dial tints the digital stretch and shows "DIGITAL".
- **Tap to focus and meter** (FocusMeteringAction, AF + AE + AWB, back to continuous after 5 s) with a focus ring that turns blue when focus locks, and an **exposure slider** beside it (sun on a short track, the camera's own exposure-compensation range and step, shown in EV). Works on the zoomed preview (the tap is mapped back through the digital scale).
- **Grid** (rule of thirds) and **timer** (off / 3 s / 10 s with a big countdown, a tick each second; pressing the shutter again cancels) in the top bar. Both are remembered.
- Scan, Text, Ask, Photo, Translate, Document and the + → Camera fallback work as before. Code: `device/CameraZoom.kt` (zoom maths, digital crop, exposure, `CameraTimer`, `Sharpen`; CameraZoomTest), `ui/CameraPro.kt` (dial, presets, focus ring, grid, countdown), `ui/InAppCamera.kt`, `CameraSupport.digitalZoom`. Screenshots 84 and 85 in ShotsTest.

## v0.30.2: Terminal that starts, and cd that works in Linux

- **The phone tab never opens dead.** Before opening, Blaze checks BusyBox once per build (`busybox true`, then `busybox sh -c 'echo ok'`, each with a timeout); if either fails for any reason the tab starts straight on Android's own shell. If a BusyBox tab still dies (signal 31 at any time, or any error in its first 5 seconds) it is swapped at once, in place, for one on Android's shell with a short line saying why: no "[Process completed (signal 31) - press Enter]", nothing to do. The decision is remembered per build (versionCode + install time) and checked again after an update.
- **32-bit phones:** the 32-bit BusyBox reaches chmod, ln, rmdir, rm -r, cp -a, tar x and mkfifo through old syscalls Android's 32-bit sandbox kills. A second start-up probe catches that and leaves those commands to Android's toybox. Every BusyBox syscall on both ABIs was checked against Android 11 and 12's real allowlists (`tools/busybox/seccomp_allowlist.py`).
- **Alpine (and every Linux): `cd phone/`, `cd /sdcard` and every other `cd` work again.** They said "can't cd to phone/: Function not implemented" and `pwd` printed nothing: the bundled PRoot answered chdir/getcwd with ENOSYS under Android's app sandbox. Blaze now ships Termux's current PRoot 5.1.107.96 (with talloc 2.5.0 and libandroid-shmem), reproduced and verified off-phone under Android 11/12's seccomp filter (`tools/proot/README.md`). ~/phone and /sdcard are real mount points in the rootfs now.
- **`blaze-doctor`** in every Linux checks cd into ~/phone and /sdcard, getcwd, stat, the file tools and fork, and says what fails. Blaze runs it once per build in the background and turns PRoot's seccomp acceleration off on phones where only that works (`LinuxDoctor`).
- Tests: `PhoneShellTest` (per-build verdict, first-death fallback, note width on the test phone, 32-bit applets), `LinuxDoctorTest` (doctor script runs, parser, seccomp decision, pinned PRoot binaries).

## v0.30.1: Blaze Music and Blaze Video

- Two new apps inside Blaze, each full screen with its own **Player | Library | Store** pill: **Music** (/music, Views) and **Video** (/video, Views).
- **Music player:** cover art that tints the screen, seek, play/pause/next/previous, shuffle, repeat one/all, queue (drag to reorder, remove), speed, sleep timer (minutes or end of song), synced lyrics (LRCLIB, saved for offline), liked songs. Keeps playing with Blaze closed, with the media notification, lock-screen and headset controls; pauses for calls and when headphones are unplugged; gapless. Picks up the last queue where you left it.
- **Music library:** songs, liked, artists, albums, playlists (create, rename, delete, add, remove, reorder), recently played, search. Includes downloads and the audio already in Blaze's Gallery; everything plays offline. Mini-player on Library and Store.
- **Video player:** swipe to seek, left side for brightness, right side for volume, double-tap ±10 s, full screen with rotation, picture-in-picture, speed, subtitles and audio track choice, resumes where you stopped; Continue watching row.
- **Stores** (never a web page; a grid of pictures, titles, authors, lengths and the licence of each item): Music = Jamendo (Creative Commons; needs a Jamendo client_id, paste it in the Store) and Internet Archive audio (Creative Commons / public domain only); Video = Internet Archive public-domain feature films and the Blender open movies. Tap for details and quality, then Download or Play / Watch.
- **YouTube** (Music and Video Stores) is a separate plug-in module (`youtube/`): included in this repo's builds, gone entirely when the folder is removed.
- **Downloads:** a queue that survives network drops, closing Blaze and restarts (resumes from where it stopped), retries with backoff, pause / resume / cancel, progress notification, free-space check. Songs go to Music/Blaze/Music, videos to Movies/Blaze/Videos, visible to the Gallery and other apps. Separate YouTube video and audio streams are merged on the phone without re-encoding.

## v0.30.0: Gallery albums, music and videos

- Gallery has a pill switcher like the rest of Blaze: **All · Albums · Images · Music · Videos**.
- Songs and videos Blaze downloads to shared storage (Music/Blaze, Movies/Blaze, Blaze/Music, Blaze/Videos, Download/Blaze) show up next to what Blaze made, with real cover art or a video frame and the length on the tile. Songs with no cover get a coloured tile with a note instead of a grey box. New downloads appear while the Gallery is open.
- **Albums**: automatic ones (Generated images, Music, Videos, Downloads, plus Screenshots and Camera after you tap "Show Screenshots and Camera") and your own: create, rename, delete (the files stay), add items, move items between albums, remove from an album. Saved on the phone (`gallery_albums.json`).
- **Viewer**: swipe between items, pinch or double-tap to zoom, tap to hide the controls. Songs play with cover art, a seek bar and pause; videos play inline with the system controls. Share, Rename (keeps the extension), Info (size, date, resolution, length, folder), Set as wallpaper (home, lock or both) and Delete (asks first; Android asks again for files Blaze didn't save).
- **Select**: long-press a tile, tap more, then Share, Album, Move, Remove or Delete; Select all in the header.
- **Timeline**: sorted by date, the grid is grouped by day ("Today", "Yesterday", "Mon, 12 Oct", "16 Oct 2025"); while selecting, tap a day's tick to select the whole day.
- **Fast on low-end phones**: at most 3 thumbnails decode at once, tiles that scroll away cancel theirs, thumbnails are kept in memory (LRU, ~1/10 of the app's heap) and Blaze's own files and song covers on disk (cache/gallery_thumbs); shared-storage pictures use Android's thumbnail cache. Thumbnails fade in.
- Opening an item grows it out of its tile and closing shrinks it back into the tile of the item you ended on (the grid scrolls to it if needed).
- **Sort** by date, name (natural order: "track 2" before "track 10") or size, either way, and **search** by name.
- Pictures Blaze saved to Pictures/Blaze aren't shown twice. New permissions: READ_MEDIA_IMAGES / READ_MEDIA_VIDEO (READ_EXTERNAL_STORAGE up to Android 12), only asked from Albums, and SET_WALLPAPER. Code: `media/GalleryModel.kt` (GalleryModelTest), `media/GalleryRepo.kt`, `ui/GalleryScreen.kt`.

## v0.29.9: Page window grips, page selection pill, clearer bubbles

- **The glass page window on Home moves only by its rim or title bar.** Hold the glass edge (an 18 dp rim) or the title bar to carry it around; it leans a little as it moves and springs back. The page in the middle is never a handle any more, so scrolling, selecting and copying there never drags the window. The two bottom corners resize it (small ticks show where; never smaller than 220 × 260 dp, never off-screen). Sliding on the dim area outside no longer tilts anything (`ui/GlassPaneGrip.kt`, GlassPaneGripTest).
- **Selecting words on that page shows the chat's selection pill**: Copy, Quote, Select all, Search, Ask Blaze, Translate, Define (1–3 words), Read aloud, Share, swipeable like in chat. Quote drops the words into the chat box, Ask / Translate / Define go to Blaze (answer in Chat), Search opens in the window itself (Back returns), Copy uses the page's own copy. Back or a tap outside clears the selection (`ui/PageSelection.kt`: the WebView's action mode is routed to the pill; PageSelectionTextTest).
- **Clearer chat glass:** no opaque backing inside bubbles any more (code and table cards are see-through too), and message text carries a soft page-coloured halo so it stays readable with particles drifting across it.
- The particles that drift over the thread stay off Home, so they no longer float across the glass page window or the board.

## v0.29.8: Terminal that explains itself, phone shell fixed

- **Phone shell starts again.** On some low-end phones the phone tab opened straight to "[Process completed (signal 31) - press Enter]". Signal 31 is SIGSYS: Android's app sandbox (seccomp) kills any app process that calls setgid/setuid, and the bundled BusyBox called both at the start of every tool (its `check_suid` privilege drop, a no-op for an app). Both BusyBox binaries now skip that drop (one instruction each, `tools/busybox/patch_setid.py`, README there).
- BusyBox's tools are really on PATH now: setup ran `libbusybox.so --list`, which BusyBox answers with "applet not found", so nothing was ever linked. It now runs through a `busybox` link and is probed first; if a phone still refuses it, the tab opens Android's own shell (toybox) instead of a dead tab and says why. ping stays Android's (it works for apps).
- The phone tab explains itself: a welcome that says what it is (this phone's command line, nothing to install, not a full Linux) and how to get a Linux, a short tour, tappable example commands above the keys (help, ls, cd storage, top, ping, space…) and a `help` command listing the tools with plain descriptions. Renamed "Phone shell · built-in tools, no install" (tab chip "phone sh").
- ⋮ and + open a Blaze bottom sheet (rounded, dark, Blaze blue, an icon and a hint per row, the app's motion timing) instead of the white Android pop-up. The extra keys are rounded Blaze key caps, CTRL/ALT light up blue, tabs use Blaze blue; the remaining confirmations are dark dialogs.

## v0.29.7: Browser remembers: saved passwords, tabs that come back, offline pages

- **Saved passwords.** Sign in on a site in Blaze Browser and a Blaze card asks "Save password?" (or "Update password?" when the password changed), with Save, Not now and Never for this site. Passwords are encrypted with the phone's Android Keystore (AES-256-GCM, the key never leaves the phone) in `browser_logins.json`, one entry per site and username, several accounts per site. Sites are matched by registrable domain (accounts.google.com and www.google.com are one site; bob.github.io and alice.github.io are not). Next time a sign-in form appears, a "Use your saved password" bar offers the accounts for that site; one tap fills them (it never presses Sign in for you). https pages (and the LAN) only. Two-step sign-ins (email first, password next) keep the email.
- **Private tabs never save passwords**: they don't get the sign-in watcher at all.
- **⋯ › Saved passwords**: every site and account. Show, copy or edit a password after your fingerprint, face or PIN (unlocked for 2 minutes); delete with a second tap; undo "Never for this site".
- **Sign-ins stay signed in**: cookies are flushed to disk after each page and when you leave the browser, so a killed app keeps you logged in.
- **Tabs come back.** All normal tabs (address, title, order, the tab you were on, and each tab's back/forward history) are saved and reopen after closing the browser or Android killing Blaze. Only the tab you were on loads straight away; the others load when you open them. Private tabs are never restored.
- **⋯ › Save page for offline** saves the page as a web archive (.mht with its pictures and styles) plus a readable text copy. **⋯ › Saved pages** lists them (newest first, size and date); tap to open offline in a new tab, the text icon for the text copy, delete with a second tap.
- Code: `browser/SiteDomain.kt`, `BrowserLogins.kt`, `TabStore.kt`, `SavedPages.kt`, `BrowserVaultUi.kt`; tests `BrowserPersistenceTest`.

## v0.29.5: Truly see-through chat

- Chat bubbles are nearly clear now (a faint tint, no grey slab) with a crisp outline; the work card and follow-up chips are clear with a hairline too.
- The blue particles drift over the thread, so they pass through the bubbles and text instead of hiding behind them.

## v0.29.4: Blue particles, glass chat bubbles

- Background particles are blue in both themes (deep sky blue on light, periwinkle on dark), slightly bigger and much easier to see.
- Chat bubbles are see-through: yours has a light blue tint, Blaze's a soft neutral one, and each has a clear outline so you can always tell where the message box is. Your text is now dark ink instead of white.

## v0.29.3: Glass Home
- Home's new default background is a light daytime sky (drawn, nothing to download): blue to a pale horizon, a soft sun glow and clouds.
- Over the sky or your own photo (Customise → Background → Image), cards, stacks, buttons and chips turn to frosted glass with a bright edge. Text uses dark ink on the sky in both app themes.
- Card icons are real rounded icons on a soft accent tile (the old ones looked like keyboard emoji).
- News and video cards show preview pictures: a wide picture for the top story, small ones beside the rest (feed pictures, Bing News pictures matched to Google News stories, YouTube thumbnails). Cached and downscaled for low-RAM phones.
- Tapping a story or picture opens it in a glass window on Home that looks like a small screen: the page scrolls and its links open inside. × (or Back) closes it, tapping outside does nothing, and sliding on the glass tilts it a little in that direction before it springs back. ↗ opens the page in the browser.
- Your photo now shows in full behind the glass instead of faded. Solid and Gradient backgrounds keep the old solid cards. Anyone who already picked a background keeps it.

## v0.29.2: Blaze Terminal
- New **Terminal** view (/terminal, Views, "open terminal"): a full-screen, Termux-style terminal with a real PTY (Termux's Apache-2.0 terminal-emulator + terminal-view). vim, nano, htop, colours, Ctrl-C and resizing work.
- More Linux: + → "Install a Linux…" offers Alpine, Ubuntu 24.04, Debian 12, Arch Linux ARM, Fedora and Manjaro (proot-distro rootfs, .tar.xz with pax headers; resumable download). Remove one from the same menu.
- Tabs run any installed distro (bash when present) or the phone shell (BusyBox ash). The same distro can be open in several tabs. Long-press a tab to close it. Shells keep running when you leave the screen.
- apt works as root under PRoot (APT::Sandbox::User root).
- Termux's two extra-keys rows (ESC / ― HOME ↑ END PGUP · ⇥ CTRL ALT ← ↓ → PGDN) with key repeat; pinch to zoom; long-press to select and copy; ⋮ menu for paste, copy all, text size, restart and close.
- The old Jobs page stays (Views → Jobs); "terminal" now opens the new one.

## v0.29.1: browser load feed
- While a page loads, Blaze Browser shows what it's pulling under the progress bar: "Connecting to bbc.com", then "Pulling 23 files · fonts.gstatic.com › inter.woff2 · 4s". After 6 s with nothing new it says "Slow network · still waiting on bbc.com", so a slow connection looks like work, not a freeze (`browser/LoadFeed.kt`, LoadFeedTest).

## v0.29.0: Home board
- Home board: a widget store, the board itself, a Customise sheet, an interest picker, widget tools Blaze can use (add / change / remove / list widgets) and a Home pill in the top bar. Sources include crypto (CoinGecko), currency rates and more (`home/`, `ui/HomeBoardUi.kt`).

## v0.28.9: "open it" fix + empty-reply fix
- "open it for me" (about a gallery picture) opened the game "Juno: New Origins Complete Edition": the fast lane sent open_app "it", and "Ed-it-ion" contains "it". Commands that point back ("it", "that", "the picture", "again") now skip the fast lane and its cache, so the full agent uses the chat context. App names match on whole words only (`phone/AppMatch.kt`), and open_app refuses pronouns.
- An empty provider reply showed the raw "End of input at character 0 of" error. It now counts as a provider hiccup (503) and goes through the normal retry / next-model path.
- AppMatchTest covers the real failure, pronouns, real app names and mid-word matches.

## v0.28.8: proof for every action
- **ActionProof** (`agent/ActionProof.kt`): a final reply that says Blaze sent, called, set an alarm/timer/reminder/event, changed a setting, saved to memory, wrote/exported a file, opened an app or page, installed, downloaded, changed a contact or played music must have a tool of that kind SUCCEED this turn. If not, Blaze is re-prompted once to really do it ("it has NOT happened"), and if it still hasn't, the claim is replaced with "I didn't actually …". Questions about earlier work, offers, questions back and honest failures are left alone.
- **Stricter success test:** tool results starting "Not …", "No …", "Need …", "Can't", "I couldn't", "Permission denied" or "The user didn't …" no longer count as done. message_contact's failure lines all start "Couldn't send".
- **Harder tests (ActionProofTest):** 41 real and paraphrased replies (incl. the Gabriel cat failure) with the exact verdict each must get; every proof tool must exist in the code; a source check fails the build if message_contact ever returns "Sent to" outside the background reply or before the on-screen SENT check; failure results must never read as success.

## v0.28.7: honest sends + phone workflows (the "send Gabriel a cat" fix)
- **What went wrong:** asked to find Luke's older brother Gabriel and send him a cat picture made with Pollinations, Blaze said there was no Gabriel, then opened WhatsApp and claimed "Message sent" with nothing sent, and finally sent the words "orange cat" instead of the picture.
- **message_contact sends pictures:** new `image` argument ("last" = newest Gallery image, or a file name). WhatsApp gets the picture through a FileProvider share aimed at the contact's chat (`jid`), with `text` as the caption.
- **Contacts that actually match:** names with emoji ("Gabriele❤️"), words in another order and small typos match (`phone/SendCheck.kt`, falls back to scoring every contact). Several matches come back as options for ask_user instead of "couldn't find".
- **Verified sends:** Send is pressed only in that person's chat, and "Sent to …" is returned only when the draft or picture preview is gone afterwards (one retry). Otherwise the result says "Couldn't confirm it was sent" or "Not sent yet" and the model is told never to say it was sent.
- **Fake-send guard:** a reply claiming "message sent" with no send tool succeeding this turn is replaced with an honest line (ClaimGuard.unbackedSend).
- **Built-in workflows:** wf-send-to-person, wf-find-a-person, wf-call-someone, wf-reply-to-latest, wf-make-and-share, wf-do-it-on-screen, each a step list with checks that the skill lookup finds by trigger.
- ToolGuide: send_text is SMS; WhatsApp and pictures go through message_contact. Tests: SendCheckTest.

## v0.28.6: the camera works on every phone (Galaxy A07 fix) + Blaze Camera (Scan, Text, Ask, Photo, Translate, Document)
- **Root cause of "No camera app found." on the Galaxy A07 (SM-A075F):** `zxing-android-embedded` (the Desktop pairing QR scanner) declares `android.permission.CAMERA` in its manifest, so CAMERA is in Blaze's merged manifest. When an app declares CAMERA and hasn't been granted it, Android refuses `ACTION_IMAGE_CAPTURE` with a `SecurityException` (even though the camera app does the shooting). A fresh install had never granted it (Luke's phone had, through QR pairing), and the old `openCamera()` caught every exception and said "No camera app found." The permission is kept on purpose (QR pairing and Blaze Camera need it) and is now requested properly.
- **+ → Camera, robust on every phone** (`device/CameraFlow.kt` decides, `ui/CameraCapture.kt` runs it): Camera permission first (asked; explained if refused once; "Open Settings" if refused for good; "Pick a photo" always offered) → the phone's camera app with explicit URI grants (EXTRA_OUTPUT + ClipData + read/write flags) → **Blaze's own CameraX camera** when there is no camera app, the launch throws (ActivityNotFound, SecurityException, a work profile / Secure Folder / policy), or the app bounces straight back or ignores EXTRA_OUTPUT (remembered per phone) → the photo picker. The pending photo path survives Blaze being killed behind the camera app (low-RAM phones). Every failure logs the real exception class and message to logcat (`BlazeCamera` tag) and to Blaze's diagnostics log (Settings → Device & diagnostics → crash report) with the phone model and Android version, and the toast says what actually happened.
- **Manifest:** `<queries>` for IMAGE_CAPTURE, STILL_IMAGE_CAMERA, VIDEO_CAMERA and VIDEO_CAPTURE (package visibility on Android 11+); CAMERA declared explicitly (it was only there through zxing) with every camera feature `required="false"`; the camera entries in + are hidden only when the phone has no camera at all (`FEATURE_CAMERA_ANY`).
- **Photos are upright and small:** captures are rotated by their EXIF orientation and scaled to ~1600 px (1280 on low-RAM phones) before they're attached; picked photos are now rotated by EXIF too (BitmapFactory ignores it, so portrait photos from many phones reached the model sideways).
- **Blaze Camera** (`ui/InAppCamera.kt`, CameraX 1.3.4): preview, shutter, front/back flip only when both lenses exist, flash only when the lens has one (off / auto / on), pinch-zoom, tap-to-focus, its own permission screen, insets-aware (gesture and 3-button nav, cut-outs, landscape without a restart). A mode bar along the bottom (TalkBack tabs, remembers the last mode, motion follows Remove animations):
  - **Scan:** live QR / barcode detection with ML Kit barcode scanning (bundled model, offline) and action chips: Open link, Join Wi-Fi (Android 11+ add-network sheet; older: password copied + Wi-Fi settings), Add contact, Call, Email, Copy, Ask Blaze.
  - **Text:** on-device OCR with Magic Hand's warm ML Kit recogniser; a selectable card with Copy, Translate, Search (Blaze's browser), Save (Downloads/Blaze), Ask Blaze.
  - **Ask:** snap, then the chat opens with the photo attached and "What is this?" ready to send, edit or speak over.
  - **Photo**, **Translate** (OCR on the phone, Blaze translates into the phone's language; ML Kit translation isn't bundled), **Document** (Google's ML Kit document scanner with edge detection, crop and clean-up when Play services has it, as a PDF; otherwise a photo framed as a page).
- **Reachable from:** chat + (Camera = a photo to attach; Blaze Camera = the modes), Magic Hand's new camera button, the `blaze_camera(mode)` tool, and **plain requests with no model guess** (`agent/CameraIntent.kt`, checked in `Agent.send` before the fast lane): "scan this document for me" → Document, "look at this" / "can you see this" → Ask, "read this sign" → Text, "translate this menu" → Translate, "scan this QR" → Scan, "take a photo" → Photo; typed or spoken ("Hey Blaze, scan this document"); French, Spanish and Portuguese too ("scanne ce document", "escanea este documento"). Short single-clause requests only; anything about the screen, a page, a link or the chat is left alone. **The capture goes straight back into the chat that asked** (`Agent.fromCamera`) with the user's own words, so Blaze answers about it; text read on the phone and scanned codes ride along with the photo.
- **open_camera tool:** photo = the camera app, else Blaze Camera (saved to Pictures/Blaze); video = VIDEO_CAMERA, else VIDEO_CAPTURE when allowed, with a precise reason when neither works; qr = Blaze Camera's ML Kit scanner (it can act on the code). No more "No camera app found." when the real problem was something else.
- **Device audit (cheap fixes):** Magic Hand's screen-size fallback no longer assumes 1080×2340. The rest is listed in STATUS.md.
- Dependencies: `androidx.camera:camera-core/camera2/lifecycle/view:1.3.4`, `com.google.mlkit:barcode-scanning:17.3.0`, `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`. Prefs: `camera_perm_asked`, `camera_prefer_inapp`, `camera_mode`.
- Tests: `CameraFlowTest` (the A07 case, rationale / settings, every fallback step, the camera app's answers, precise messages, lenses / flash, sizing, EXIF), `CameraModesTest` (mode routing for every origin, start mode and memory, prompts, scan chips, Wi-Fi QR parsing), `CameraIntentTest` (English, French, Spanish and Portuguese phrases, and requests that must not open the camera). Screenshots `80-blaze-camera-modes`, `81-blaze-camera-scan` (single camera, no flash), `82-blaze-camera-plain`.

## v0.28.5: project briefs capped for real; the full brief and instructions are looked up, not pinned
- **Why:** every project turn pinned the whole brief (only loosely held to 2,400 characters, and shown only with long chats on) and up to 1,200 characters of instructions into the system prompt; the "0 / 600 TOKENS" counter in the editor was characters ÷ 4 and nothing enforced it for Blaze's own edits.
- **Hard 600-token cap on every save** (`Projects.BRIEF_TOKENS`, counted like everything else in blaze.db: UTF-8 bytes ÷ 4). The project page's counter is the real count; typing or pasting past the cap is cut at the cap with a note under the field ("put longer details in Instructions"). Blaze's brief-updater (`update_brief` → `Projects.updateBrief`) squeezes whitespace first, then keeps the top part (whole lines first, so purpose and rules written first survive), saves the whole text it was given as a note, and tells the model the brief was trimmed so it can write a tighter one. The brief it replaces is kept as a note too ("Earlier brief", up to 30 notes per project).
- **The prompt pins at most ~600 tokens per project** (`Projects.pinned`): the project name, the top of the instructions (a third of the budget when there's a brief, more if the brief is short) and the top of the brief, plus one line saying the full instructions, full brief and older notes are searchable. This is the only project text in the prompt now; the separate long-chat brief block is gone.
- **Full text in blaze.db:** new table `project_docs` + FTS5 `project_docs_fts` (porter unicode61; prefixed names like `blaze_docs`, schema v1 and `user_version` untouched), chunked by heading (~150–600 tokens, the user's own text, not scrubbed), keyed by project, reindexed only when a project's texts change (signature in the shared meta table as `project_docs:sig:<project>`), on start and lazily before each lookup. `projects_json` in prefs stays the source of truth (it now also holds `notes`), so if blaze.db can't open nothing is lost and the tools fall back to plain matching (`agent/ProjectDocs.kt`).
- **Two tools, project chats only:** `project_docs_search(query, limit ≤ 8)` (bm25 snippets with ids like `instructions#3`, `brief#1`, `note2#1`; a blank query lists the sections) and `project_docs_read(id)`. Core, read-only (plan-mode safe, run in parallel); stripped from the tool list of any chat that isn't in a project. Deleting a project drops its chunks.
- **Migration, no data loss:** on the first start a brief over the cap is copied whole into the project's notes ("Full brief (kept when the 600-token brief cap came in …)") and the pinned brief becomes its top part. Instructions are never cut; they are all searchable.
- Database: new tables `project_docs`, `project_docs_fts`; meta keys `project_docs:sig:<id>`. Prefs: `projects_json` entries may carry `notes` (older builds ignore it).
- Tests: `ProjectDocsTest` (cap on fit / editor / update_brief / migration, multi-byte and one-line text, notes keep the old and the full text, JSON round trip incl. v0.28.4 JSON, chunk / search / read per project, reindex only on change, tools and the no-database fallback, project-only tool list), `PromptBudgetTest` (a 20k-char brief + 20k-char instructions add ≤ 700 tokens, none of the tail, and the tail is found by search).

## v0.28.4: blaze-docs: Blaze knows itself by looking it up (exact repo URLs, versions, features, settings); the work card's time is the real run time
- **Luke's screenshot:** asked "take me to blaze-agent repo in github", Blaze guessed the owner (`LacksonKapapa` instead of `lacksonkapapahue`), decided the repo doesn't exist and sent him to a stranger's profile. The real repo is https://github.com/lacksonkapapahue/Blaze-agent.
- **Built-in docs, generated from this repo at build time** (`BlazeDocsTask` in `app/build.gradle.kts`, one per variant, wired as a generated assets folder): `assets/blaze-docs/` holds README, CHANGELOG split into one file per version (+ an index), STATUS, `docs/`, and the hand-written `app/blaze-docs/about.md` (what Blaze is; owner and the three repos with exact URLs: Blaze-agent, Blaze-dekstop, Blaze-cli; releases / apk branch / raw APK URLs; how to update with /update; tech stack incl. blaze.db FTS5, WorkManager, AccessibilityService, the Cloudflare Workers gateway at https://ai.blaze-app.workers.dev; feature map; settings map; privacy and security; known limits), filled in with this build's version, versionCode, commit, date and min / target / compile SDK, plus `manifest.json` (files + hash). E-mail addresses, tokens and keys, Google Cloud project IDs, long hex IDs and store passwords are scrubbed at build time and again when indexing.
- **Stored in blaze.db, not the prompt:** on the first launch after an update (manifest hash, version or tool list changed) the docs are chunked by heading (~300–800 tokens; long sections split with their heading, small neighbours merged with every heading kept in the section path; each chunk has id `doc#n`, doc, section path, version) into the new table `blaze_docs` + FTS5 `blaze_docs_fts` (porter unicode61), replaced whole in one transaction (idempotent), signature in the shared meta table as `blaze_docs:sig`. Prefixed names; schema v1 and `user_version` untouched. Runs on an IO coroutine 2.5 s after start; while blaze.db isn't the memory store yet (the one-time mind.db copy) the docs live in memory for that run (`agent/BlazeDocs.kt`).
- **A tool reference from the real tool list:** `Agent.toolCatalog()` (the exact list the model gets) is indexed as the doc `tools` (name + one line, grouped like ToolRouter); a changed list (a connector, a setting) reindexes off-thread, so it never drifts.
- **Two tools:** `blaze_docs_search(query, limit ≤ 5)` returns bm25-ranked snippets with ids (section ×6, doc ×2, text ×1, the about doc preferred; "source code" / "what version" / "0.28.1" expanded to the words the docs use; a repo question always gets the exact repo URLs on top) and `blaze_docs_read(id)` returns one chunk. Both are core tools (always sent; plan-mode safe; run in parallel with other lookups). A question about Blaze itself adds a one-line per-turn hint and skips skill packs and specialists (Luke's question had pulled in five coding skills and the Coder specialist because of "repo" and "github").
- **System prompt:** one static line (`BlazeDocs.PROMPT_LINE`: use blaze_docs_search first, never guess URLs) with the repo URL as the single pinned fact; the ABOUT YOU line now points at blaze_docs_search for docs and about_blaze for live setup.
- **GitHub near-misses open the real repo:** `ToolLeak.fixBlazeUrl` turns a github.com URL to a Blaze repo under a guessed owner or the wrong case (`LacksonKapapa/Blaze-Agent`, `lacksonkapapahue/blaze-agent`, `Lackson-Kapapa/Blaze_Agent/releases/latest`, `…/Blaze-Desktop`, `…/blaze-cli.git`) into the real one, keeping the rest of the path; used by browser_open (and its step line), fetch_page and open_url. Someone else's `octocat/blaze-agent` is left alone. The leaked `github_open` alias maps any owner (or none) for all three repos.
- **Work card timer fix** (same screenshot: "Worked · 4 steps · 97m 45s" over steps of 0–13 s): the card timed a run from the message before it, so a Retry (or a background agent's wake) 97 minutes after the prompt counted the whole gap. Each turn now stamps its real start on its first step (`ChatMessage.startAt`, saved as `start_at`; set by send / retry / wake), a finished card ends at the reply that closed it or its last step (never at a later prompt) and never reads the clock, so the total is frozen at finish however long the app sits in the background; the live card before its first step also uses the turn's start. Saved chats without the stamp count the previous message only when it is within 2 minutes of the first step (`ui/WorkTime.kt`).
- **Audit (see STATUS.md):** memories and the journal are rows in blaze.db (FTS5), nothing writes memories to .md or JSON-in-prompt; the prefs `memory` list is a write-only mirror / fallback; the prompt carries only the pinned core + subject index + lookup tools; skills are matched per turn (top 3 within 6,000 chars) and never all read into the prompt.
- Database: new tables `blaze_docs`, `blaze_docs_fts` (+ index `blaze_docs_doc`) in blaze.db, meta key `blaze_docs:sig`. Chats: new optional message key `start_at`. No prefs changes.
- Tests: `BlazeDocsTest` (chunking by heading and size, real docs within budget, idempotent reindex next to the memories with schema v1 untouched, "repo" / "source code" / "blaze-agent repo in github" → the exact URL, update / language / tools / 0.28.1 questions, tool reference from the list, no secrets in the generated files or chunks, the prompt line, GitHub near-misses), `PromptBudgetTest` (500 memories + every doc indexed: the memory/docs part of the prompt grows by < 1,500 tokens (budget 6k) and holds no docs text and only pinned-core memories; skills injected within budget), `WorkTimeTest` (Luke's 97-minute case is 33 s, frozen at finish, stopped turns, old chats). Screenshot `71-blaze-docs-repo`.
## v0.28.3: project chats are their own place; one round back button everywhere
- **Project chats open inside Projects, never in main Chat** (Luke's phone: tapping "+ New chat in this project" or a project chat switched the pill to Chat and loaded that thread there, so the One Mind main chat looked wiped, and Chat kept coming back as the project chat). Now the **Chat pill always shows the one main conversation**; a project chat opens as its own screen in Projects: round back button, the project's name over the chat's title, ⋯ (New chat in this project / Rename / Delete), and the same message list, cards and composer as main Chat. Back: project chat → project page → projects list. Switching the pill to Chat and back to Projects puts you where you were (list, project page or chat). Navigation is one pure value, `agent/ChatNav.kt` (tab, project, chat; saved across rotation and process death).
- **Any number of chats per project:** listed on the project page (count = chats that exist), + New chat, and each row's ⋯ (or a long-press) to Rename or Delete it (confirm first; deleting removes that chat's messages and takes it out of the project, the project and its other chats stay). A new chat joins its project with its first message, so empty ones don't pile up.
- **Things that open a chat by themselves open it in Projects too:** the shell follows the agent (`ChatNav.follow`): a reply typed in a notification, the secure-form card's "Action required" notification, and tapping a reply notification from a project chat (new `Notifier.EXTRA_OPEN_CHAT` → `Agent.openFromLink`, main or project chats only) all land on that project chat screen, not in main Chat. A private chat stays on the Chat tab.
- **Repair on upgrade:** older builds saved the last project chat as the chat to reopen (`last_chat_id`); on start main Chat now always opens the main thread and that pref is reset to `main` (only the main thread is remembered from now on). Checked: project chats were never written into the main thread's file (each chat saves under its own id; the journal is keyed by chat id), so nothing is deleted or hidden there.
- **Project instructions and brief only in project chats:** the PROJECT line in the system prompt was read from the chat *on screen*, so a project turn running in the background could lose it (or the main chat get it). It now comes from the chat the turn belongs to (`systemPrompt(chatId)`), like the brief already did.
- **One back button for the whole app** (`ui/Kit.kt` `BackButton` + `BackTitle`): a 40 dp round tonal button (surface-variant fill, 1 dp outline) with Material's rounded `AutoMirrored.Rounded.ArrowBack` (mirrors for right-to-left), a 48 dp touch target, ripple and a slight press scale, content description "Back", title aligned next to it the same way on every page. Replaces the thin "←" text on Settings (every settings page), the project page, Search, Gallery and Learn's "← Decks"; the browser's page-back uses the auto-mirrored icon. `BackButtonTest` fails the build if a "←" text glyph or the old non-mirrored icon comes back. No square shapes (NoSquareTest).
- **Old leaked tool syntax hidden when drawn:** a reply saved before v0.28.1 could still show `[github_open repo="LacksonKapapa/Blaze-Agent"]` (Luke's 15:55 bubble, and the same text as a chat preview on the project page). Saved replies and chat previews now go through `ToolLeak.forDisplay` (text without a leak is untouched byte for byte); a reply that was only a leaked call shows "That step didn't run, so nothing was done. Tap Retry to try it again." Copy / Quote use the cleaned text. New replies were already cleaned by v0.28.1, which also maps that exact mixed-case string to opening https://github.com/lacksonkapapahue/Blaze-agent.
- No database changes. Prefs: `last_chat_id` reset to `main` once (above); nothing else.
- Tests: `ChatNavTest` (opening a project chat never changes the main chat id, new chat opens in Projects, back stack, tab switch restores, notification → Projects, deleted chat / project, save + restore), `BackButtonTest`, `ToolLeakTest` (stored leaks hidden at display time, mixed-case repo). Screenshots `71-project-page-back`, `72-project-chat`, `73-project-chat-new`, `74-main-chat-unchanged`, `75-back-buttons`.

## v0.28.2: security hardening (cleartext, Design view, backups, confirm cards, gateway CORS)
From an external review Luke checked and agreed with.
- **Plain http only where it's needed, enforced in code.** Android's network config can't say "LAN only", so it still allows cleartext, and Blaze now checks every model, gateway, MCP, connector and Blaze account call itself (`connect/UrlSafety.kt`: `check` / `open`, `Http.req(strict)`, `LlmClient`, `ModelCatalog`, `ProviderCatalog`, `Quota`, `Mcp`, `BlazeAccount`, `KeySync`, `SkillSync`). `http://` is refused unless the host is this phone (localhost, 127/8, ::1, the emulator's 10.0.2.2), the LAN (10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA), CGNAT / Tailscale (100.64/10), `*.local`, `*.lan`, `*.home.arpa`, `*.ts.net` or a single-label LAN name ("desktop"), with a plain error: "Blocked an unencrypted connection to api.example.com … Use https://api.example.com/v1 instead (or a local, LAN or Tailscale address)." A credentialed http request to the LAN doesn't follow redirects. Reading a public http page (web fetch) still works, but **no request ever sends an Authorization header, API key or token over http to a public host** (`UrlSafety.carriesSecret` / `checkSecret`, also the `http_request` tool). On-device runners (Needle fast lane, BlazeRouter, Ollama / OmniRoute on the PC) are unchanged.
- **Blaze browser:** an `http://` page shows an orange "Not secure" in the address bar, and secure forms / saved logins (`browser_secure_input`, `{{secret:…}}` typing, `fillSecret`) refuse to fill it unless it's on this phone or the LAN (your router's page still works). A browser click on a button that pays, sends, posts or deletes now always asks first.
- **Design view WebView:** `allowFileAccess` and `allowContentAccess` are off. Every page comes from the virtual origin through `shouldInterceptRequest`, including a file outside Blaze's home (`/ext/<name>`, served only from that file's own folder; files in the app's private data aren't served) (`DesignServe.servedUrl` / `extRoot`). The top frame can't leave the design's origin: a link the user taps shows "Link to … · Open in browser" (opens Blaze's browser on the tap), anything else is dropped (`DesignServe.allowNavigation`). The `BlazeDesign` bridge (gl / error / ready / fail) still only updates the view; strings are capped (500 / 16 chars) and calls after the view is gone are dropped.
- **Backups: chats no longer go to Google's cloud backup.** Cloud backup and the older full backup no longer include `files/chats/`, and memory (`files/blaze.db`, `databases/mind.db`, `files/backup/`) was never included; the `blaze` prefs file (memory mirror plus the Keystore-sealed Blaze account / gateway session tokens and keys) is excluded too, so Google's cloud backup now holds no Blaze data. Phone-to-phone transfer is unchanged (everything except `vault.json`). To keep a copy yourself: Settings › Advanced › Backup › **Export** (settings, keys and memory; chats are not in the file).
- **Confirm cards that can't be skipped** (`agent/ConfirmGate.kt`, used by `Agent.confirm`). Actions in your name always show the card, whatever standing OKs or Autopilot say: SMS (`send_sms`), calls (`call_number`), messages in other apps (`send_text`, WhatsApp / Telegram, notification replies), email (`gmail_send`), posts (Slack, GitHub issues, new GitHub repos), money (USSD mobile-money codes, risky taps and browser clicks), deleting files / gallery items / calendar events, installing an APK (`github_install_apk` now asks before downloading) and changing phone settings (`device_setting` toggles, flashlight, volume; a command you typed or said yourself in the fast lane doesn't ask, reading the battery doesn't).
- **Prompt-injection guard:** each turn tracks whether it read outside content: a web page (browser), a fetched link / `http_request`, web search results, notifications, SMS / Slack messages, email, the screen (`read_screen`, Magic Hand), a shared file, an MCP tool's answer, a background agent's report. After that, any destructive or pre-approved action needs the card too, and the card starts with "Requested after reading a web page." (or whichever came first). A new message from you starts the turn clean. Notification auto-replies you switched on for a conversation are unchanged.
- **Gateway CORS:** no more `Access-Control-Allow-Origin: *`. The native apps don't need CORS and the admin page is same-origin, so by default no website can read the gateway's answers (no `Access-Control-*` headers at all, preflights included). Optional `ALLOWED_ORIGINS` (comma-separated exact origins; `*` ignored) opts origins back in, with `Vary: Origin` (`gateway/src/util.ts` `withCors`). Gateway tests: 76 (new CORS test).
- **Docs:** the Cloudflare account ID and the admin email are gone from the gateway docs ("the Cloudflare account"); `wrangler.toml` keeps `account_id` (deploy config, not a secret).
- No database changes. No prefs changes (same keys, same files).
- Tests: `UrlSafetyTest` (host classifier, clear error, credentials, browser rules), `ConfirmGateTest` (full decision table, card line, sources, taint), `DesignSafetyTest` (served URLs, ext root, private files, navigation, bridge caps), `BackupRulesTest`, gateway `cors`.

## v0.28.1: batch 1 fixes: instant grab card, no leaked tool calls, updates that survive slow networks, Markdown everywhere, picture stack + 3D viewer, table cards
- **Magic Hand grab card opens at once.** Was: after a grab the dock said "Reading what you grabbed…" while it snapped the box (up to 0.7 s), hid every Magic Hand window for 160 ms, took a screenshot, made a new ML Kit recogniser and ran OCR on the full-size crop, and only then showed the card. Now the card (Grabbed · app, shimmer lines, Copy / Translate / Search / Save / Carry, Undo / Grab again) is set in the same frame the finger lifts, with no fade or resize animation (`MagicHandState.reading`). Snapping and the screenshot run in parallel (one screenshot for the drawn box plus room to snap, cropped again for the snapped box), only the Magic Hand windows over the area are hidden (the dock stays up) for two frames, and OCR runs on `Dispatchers.Default` on **one warm recogniser** (created and run once on a 32 px bitmap when Magic Hand starts, closed when it stops), on a crop **downscaled** to 1280 px max. Actions tapped while it's still reading are **queued** ("Copy when ready") and run the moment the text lands; an empty read drops the queue. Remove animations still respected.
- **No junk lines from text cut by the box** (Luke's "ervAnood lot H… handlA"): the OCR crop now has a 16 dp margin so cut lines are read whole, then every line whose bounding box is less than 60 % inside the selected box is dropped; lines are ordered by row, then left to right (`phone/MagicHandOcr.kt`).
- **Leaked tool calls are run, not shown.** Models sometimes answered with the raw text of a call (`[/find_tool need="stock tracking"]`, `[github_open repo="LacksonKapapa/Blaze-Agent"]`, a reply starting `[/blazer_save kind=features text="## App ...`). `agent/ToolLeak.kt` parses bracketed `[/name k="v" …]` / `[name k=v …]` (also unclosed, multi-line values), `<tool_call>name k="v"</tool_call>` / `<tool_call>{json}`, `<function=name>{json}</function>` and `[TOOL_CALLS][…]`. A call that maps to a real tool becomes a real tool call and the turn continues (same step limits): exact / near names first, then aliases: `*_save` / `remember*` → `memory_save` (unknown kinds → project, subject from `kind`), `github_open` / `github_view` → `browser_open` with the repo URL (a misspelt Blaze-agent repo → https://github.com/lacksonkapapahue/Blaze-agent), tool search → `find_tool`. Anything else is stripped and the model is asked **once** to use native tool calling or plain words; if that fails too the reply is one honest line ("I meant to run a step for that but it didn't go through, so nothing was done yet. Could you ask me again?"). Tool syntax is stripped from every displayed / spoken / stored reply (`ReplyParts.split`), including a half-streamed one; specialists get the same recovery. New static system-prompt rule `ToolLeak.PROMPT_RULE` (native tool calling only, never tool syntax in text).
- **Updates survive slow and flaky networks** (Luke's "UPDATE DIDN'T WORK: The update didn't work: unexpected end of stream"). `update/ApkDownload.kt`: resumable download into `<name>.part` with `Range: bytes=<have>-` (206 appends, 200 starts over, 416 = complete), automatic retries with backoff (2, 4, 8, 16, 30 s; a try that made progress resets the count, 10 failures in a row with no progress = stop and keep the partial file for Retry), 90 s read timeout, 16 KB buffer with an fsync every 512 KB, **mirrors** (the GitHub release asset, then the `apk` branch raw file, then the contents API; switch after 2 failures in a row on one), waits for the network to come back instead of failing (polls every 2 s; mobile data is fine), and checks the exact size and the **git blob SHA-1** from the GitHub listing before the existing signature check. The GitHub token only goes to api.github.com.
- **Update card:** sentence case ("The update didn't finish", no shouty uppercase), plain lines ("Connection dropped, I'll keep trying in 8 s.", "No connection right now. I'll carry on when you're back online."), progress bar kept while retrying, and **any finished or failed card goes away by itself after about a minute** (also a card left over from before a restart); "Up to date" after 8 s.
- **Markdown renders everywhere, never raw symbols.** Your own sent bubbles (Luke's "## App", "1. **Offline mode**:"), the text under sent pictures, the assistant panel, "Ask Blaze about this page", work-card steps, specialist card titles and progress lines, and notification text (heads-ups, questions, approvals, `notify`) all render or strip Markdown. While a reply streams, an unclosed `**` / `__` / `~~` / `` ` `` is hidden instead of shown (`Md.tidy`), and `##Heading` without a space is a heading. Copy still copies plain text (your own messages too).
- **Several pictures = a fanned card stack** (sent bubbles): the top picture centred and full size, the next two behind it tilted left and right, smaller and dimmed; swipe left / right flips through them with a spring (instant with Remove animations); dots (or "2/6" from six up); a round ⤢ button and a tap open the full-screen viewer. One picture stays one rounded thumbnail. In the composer the thumbnail row stays (each needs its own ×), and tapping one opens the viewer at it.
- **Full-screen viewer with a 3D flip:** dragging tilts the current picture on the Y axis (up to 30°, with perspective) as it slides out while the next swings in from the other side, over a dark blurred copy of the picture (blur on Android 12+, a dark wash before); spring settle; Remove animations = a plain slide. Round close (X) top left, round save top right (Pictures/Blaze), "2/5" at the bottom, pinch to zoom and pan (no flipping while zoomed), rubber band at the ends. Used for single pictures too. Code `ui/ImageStack.kt` (`StackMath`, `FlipMath` are pure).
- **Table cards:** a new ```table block Blaze picks on its own for rows of facts with several columns (in the visual-answers prompt), and every Markdown pipe table is drawn the same way: a dark rounded card (24 dp), bold title and a thin divider, muted grey headers, wrapping white cells with generous row spacing and no grid lines; columns stretch to fill when they fit and scroll sideways inside the card when they don't (edge fades), with the first column kept in view (3+ columns); 8 rows then "Show all N rows". **Copy** = aligned plain text, **TSV** = tab-separated for a spreadsheet; the message's Copy turns a ```table into plain text too (`ui/TableCard.kt`, `RichKit.table`).
- **Enter never sends** (Luke): in the chat composer, the assist panel, "Ask Blaze about this page", a specialist's Steer / Follow-up box and Design's change box, the keyboard's Enter key (and a hardware keyboard's Enter) inserts a new line; only the send button sends. The boxes are multi-line with the IME's normal Enter (`ComposerKeys.Message`, `ImeAction.Default`) and no Send action or Enter key handler; page chat's Find mode keeps its Search key (it never sends). Magic Hand's dock has no message box (its only field, the routine name, stays one line).
- No database changes. No prefs changes (the update's partial file lives in `cache/update/`).
- Tests: `MagicHandOcrTest`, `ToolLeakTest` (Luke's three strings exactly), `UpdateDownloadTest` (resume after "unexpected end of stream", server ignoring Range, mirror switch keeping the partial file, waiting for the network, giving up but keeping the file, damaged download, cancel, git blob SHA-1, backoff, mirrors, card words, auto-dismiss), `MdTest` (Luke's sample), `ImageStackTest`, `TableCardTest`, `ComposerKeysTest`. Screenshots `65-magic-hand-grab-reading`, `66-user-bubble-markdown`, `67-update-card`, `68-image-stack`, `69-image-viewer-flip`, `70-table-card`.

## v0.28.0: My voice: Blaze can speak in your own voice, cloned on the phone
- **Joey's ask:** "I'm tired of my Android robotic voice, let's use one small voice cloning model that we can download then record our voice for a little bit and then we use that as the voice." New **Settings → Voice → Blaze voice: System voice (default) / My voice.**
- **Setup:** (1) download the voice model, one time, ~107 MB (Wi-Fi recommended; says so when on mobile data), resumable (Pause / Download continues where it stopped, HTTP Range), size + SHA-256 checked, free-space check, progress for download and unpacking; (2) record ~15–20 s reading a short script on screen, with a timer, level meter, auto-stop at 20 s, Play back and Re-record; the clip is trimmed, long pauses shortened, capped at the 10 s the model uses and normalised, and Blaze says plainly when it was too short / quiet / loud / silent; (3) **Try it**: a test sentence in your voice plus how fast this phone makes it ("Made 4.4 s of speech in 6.2 s. A bit slow on this phone…"); (4) **Use this voice**. Manage: quality (Auto / Fast / Balanced / Best = 3 / 2 / 3 / 5 flow steps; measured on a desktop ARM CPU the steps barely change speed, the main model dominates), "Keep my voice when it's slow", Re-record, Delete my voice, Delete model (frees ~220 MB). Mic permission asked when you tap Record, with an Allow link to app settings if refused.
- **Engine:** sherpa-onnx 1.13.8 offline TTS with **PocketTTS** (Kyutai Pocket TTS, int8 export `sherpa-onnx-pocket-tts-int8-2026-01-26`): zero-shot cloning from the reference audio only, no transcript. Runs fully on the phone (4 threads on 8 cores, 2 on 4+). **Nothing is uploaded; the recording stays in app-private storage** (`files/myvoice/voice/`).
- **All speech follows the choice** (`voice/Voice.kt` → one `out()` route): voice-mode replies (streamed sentences), calls (greeting, "Talk soon"), short progress words, proactive spoken notices and **Read aloud** from the selection pill. My voice makes speech piece by piece (`SpeechChunker`: short first piece so the first audio starts fast, then a sentence each) on its own thread while the previous piece plays (AudioTrack, float PCM 24 kHz); stop / tap / the mic opening interrupts it at once. The model is loaded when the mic opens (so it's ready by the reply) and freed after 4 idle minutes.
- **Automatic fallback, told once:** if this phone makes speech slower than it plays (`SpeedWatch`: real-time factor, first piece allowed to warm up), the rest of the reply goes to the phone voice and My voice rests for 6 hours; if the engine or model fails or is missing, the phone voice speaks. Each reason is told once in plain words (a toast), e.g. "Your voice is too slow on this phone right now, so I'm using the phone voice."
- **APK size:** the native runtime is **not** in the APK. `libsherpa-onnx-jni.so` (onnxruntime linked in) for the phone's ABI (~9 MB gz) is downloaded with the model from this repo's `voice-runtime-1.13.8` release (copied unchanged from the official AAR, `tools/voice-runtime.sh`; SHA-256 of the .so pinned in the app) and loaded with `System.load`. The Kotlin API is vendored (`com.k2fsa.sherpa.onnx`, Apache-2.0, kept from R8). Only commons-compress 1.21's bzip2 reader is added (for the model's .tar.bz2).
- Prefs: new `blaze_voice` (system / mine), `myvoice_quality`, `myvoice_keep_slow`, `myvoice_slow_at`, `myvoice_told`. Files: `files/myvoice/{runtime,model,voice,dl}`. No database changes.
- Tests: `SpeechChunkerTest`, `AudioClipTest` (trim, pauses, normalise cap, verdicts, WAV round trip, meter), `VoiceRouteTest` (route, 6 h slow cool-down, tell once, low-end phone defaults, speed watch), `VoicePackTest` (resume after a dropped connection, server ignoring Range, damaged download, cancel, tar reader incl. GNU long names, unpack keeps only model files and checks sizes). Screenshots `63-my-voice-setup`, `64-my-voice-tested`.

## v0.27.3: "show me your memories" just shows them; retries stay on your request; no fake 3D; Blaze offers to do it
- **Root-cause fix: "show me your memories" opened an old solar-panel diagram and called it 3D** (Joey's screenshots: "Checking memory" 11 s → "Doing it properly this time" 26 s → "Doing it for real in Design" 36 s → "Opening the design" 7 s, 1 m 40 s, then "Opened the Solar Panel Working diagram in Design view. You can orbit the model with one finger and zoom with two. If the 3D doesn't load, it falls back to a simple native drawing", over a flat 2D flowchart). Causes, all fixed:
  - **The lookup didn't count as work.** `recall_memory` / `memory_search` are read-only, so `actedThisTurn` stayed false and FakeCall treated "I checked my memory…" as a fake claim and re-prompted ("Doing it properly this time"). Now any tool that ran backs "checked / searched / looked" (`FakeCall.looksFake(…, looked)`); action claims ("I installed…") still need a real action. ClaimGuard also counts a memory lookup as backing "here are your memories".
  - **A stray claim was "fixed" by doing unrelated work.** The next answer mentioned the old diagram; ClaimGuard re-prompted "do it for real in Design" and handed the model the stale current design, so it opened it. Now a claim about a view is only re-prompted when the user's request is about that view (`ClaimGuard.relevant`); otherwise the stray sentence is dropped (`ClaimGuard.drop`), or the model is told once to get back to the question. The stale current design is only offered when a design was touched in the last 30 min. Sentence-initial claims ("Opened the … diagram in Design.") are now recognised too.
  - **Retries wandered.** Every corrective re-prompt (fake call, claim, refusal, verify) now carries the user's latest message and says to stay on it, not to reopen earlier work or open a view it didn't ask for, and that a plain text answer is fine (`RetryGuard.anchor`). At most 2 corrective re-prompts per turn across all kinds (`RetryGuard.MAX`).
  - **"show me" after a design was read as a design edit.** The follow-on matcher only excluded "memory", not "memories"; it now excludes memories / remember / "know about me", and the Memory view's cue covers "show / list my memories" and "your memories".
  - **Friendlier labels:** "Double-checking my answer", "Making sure it's really in Design", "Getting back to your question", "Looking through what I remember". "Doing it properly this time" / "Doing it for real" are gone.
- **Memories, fast** (`agent/MemoryIntent.kt`): "show me your memories", "list my memories", "what's in your memory", "what do you know / remember about me", "memories?" are answered straight from the memory table before any model call: grouped (About you, Preferences, People, Projects, Decisions, Other things), up to 60 lines, then "To change or delete one, say “forget …” with a few of its words, or type /memory to manage them all." Phone RAM / storage, photo "memories from…", and remember / forget / delete requests are not caught. When the model handles it, `memory_search` / `recall_memory` with `query=all` lists every line.
- **Design replies say what was really made** (`agent/Designs.kt`): create / open / update / undo results name the real kind ("a flat 2D diagram, not 3D" vs "3D model"); only 3D results mention orbit / zoom; the three.js render-error note (with its native-drawing fallback) only rides on 3D designs; opening a design from an earlier conversation says so. The final reply is grounded: when the designs shown this turn are all 2D, sentences about 3D, orbiting, zooming or the fallback drawing are dropped (`Designs.groundReply`). The Design hint asks for a reply that describes only what the tool result says.
- **Hands-on answers** (`agent/AnswerStyle.kt`, Joey: "maybe it could've ended with ‘want me to install it for you?’"): a new always-on rule: when asked how to get / install / set up / enable / open / download something Blaze can do itself, lead with that, keep steps short and end with one concrete offer ("Want me to install it for you?") with the action as the first follow-up chip; a clear command is just done. Joey's constraint: no generic "want me to help?" tails; facts, explanations, greetings and finished tasks end without a question. A per-turn hint adds the exact offer for how-to-get requests, and such requests skip the no-tools chat lane so Blaze can actually do it.
- No database or prefs changes.
- Tests: `IntentHonestyTest` (memory phrasing and what isn't, routing to Memory even right after a design, grouped list + manage hint, a lookup backs "I checked", relevance + drop of stray Design claims, anchored retries + friendly labels, a 2D diagram never described as 3D, hands-on offer verbs, no offer for fact questions / greetings / commands).

## v0.27.2: the text-selection pill swipes sideways for more (Search, Ask Blaze, Translate, Define, Read aloud, Share)
- **Joey's ask (screenshot of chat text selection):** the black "Copy · Quote · Select all" pill now scrolls sideways to more actions. Order: **Copy, Quote, Select all, Search, Ask Blaze, Translate, Define, Read aloud, Share.**
  - **Search** opens the words as a Google search in a new tab of Blaze's own browser; if the selection is a link (`https://…`, `www.…`, `github.com/…`) it **opens the link** there instead.
  - **Ask Blaze** puts "Explain: <text>" in the composer and sends it (if Blaze is busy it stays in the box with a short note). **Translate** asks Blaze to translate into the phone's language. **Define** (shown when the selection looks like 1–3 words) asks for the meaning, pronunciation and an example; on a longer selection it falls back to Ask. **Read aloud** uses the phone's text-to-speech (Blaze's voice settings). **Share** opens the system share sheet.
  - Every action ends the selection except Select all.
  - The selected words reach Search / Ask / Share **without touching the phone's clipboard** (`CaptureClipboard` catches Compose's copy); only Copy copies.
- **The pill:** same black rounded style, as wide as its actions up to the screen minus 12 dp each side, sits above the words (below them when there's no room), snapping fling (`rememberSnapFlingBehavior`, start-aligned), a haptic tick each time a swipe settles on a new action, a fade + chevron on the edge where more actions are hidden, and a one-time nudge that peeks the next actions and comes back (saved as `selection_pill_nudged`; never with Remove animations on, which also makes the snap instant via `MotionTokens`).
- **Where:** chat message bubbles and everything selectable in the thread (answers, cards, errors), and the answers in "Ask Blaze about this page" (no Quote there; Search / Open link opens a new tab and closes the sheet; Ask / Translate / Define ask inside the sheet). The Magic Hand dock keeps its own Copy / Select all.
- **Why the list is chosen from the selection's box:** Compose never hands the selected text to a custom toolbar (only a copy callback that also ends the selection), so Define shows when the selection box is one short line, and Search ↔ Open link / Define ↔ Ask are settled on tap from the real text (`SelectionActions.resolve`).
- Code: `ui/SelectionActions.kt` (pure: link / short / long, action lists, URLs, prompts, nudge rule), `ui/SelectionPill.kt` (`BlazeTextToolbar`, `CaptureClipboard`, `SelectionDo`, `SelectionPill`), `ui/ChatScreen.kt`, `browser/PageAskSheet.kt`.
- Prefs: new `selection_pill_nudged` (bool). No database changes.
- Tests: `SelectionActionsTest` (links with and without a scheme, in quotes / brackets; e-mails, file names, "e.g.", numbers aren't links; kinds; the order for long / short / link selections; Quote / Select all switches; the box rule; resolve on tap; search URLs and their cap; prompts and their cap; the nudge rule). Screenshots `61-selection-pill`, `62-selection-pill-dark`.

## v0.27.1: "Ask Blaze about this page" always answers (pasted links, Lens results, empty and one-word questions)
- **Root-cause fix: a pasted link got a reply about HTML** (Joey's screenshot, low-end phone: the user pasted a long Google Lens results link, `google.com/search?…&udm=26&lns_mode=un&source=lns.web…`, into the sheet over a Google page; Blaze answered "The page text you provided does not include the HTML markup of the `<body>` element…"). Two causes: the sheet sent whatever was typed as the question, so the model was asked to "answer" a 2,000-character URL; and it got the page as the agent's element dump (`Elements:` with `[12] link "…" → url` lines), which reads like markup and invited talk about it. Fixes:
  - **What the user means** (`browser/PageQuestion.kt`, pure). A message that is (mostly) one link is parsed: a Google / Bing / DuckDuckGo / Yahoo / Yandex / Baidu / Ecosia / Brave / Startpage / YouTube search link becomes its query (`q`, then `oq`, …) and the question "What does this search show? Answer “query” directly", with a web search for the query; a Google Lens link (`lens.google.com`, or a Google search with `udm=26`, `lns_*`, `source=lns.*`, `vsrid`, `tbs=sbi:`) becomes "Tell me what this Lens / image search shows: what the picture seems to be and the main matches"; the link to the open page itself becomes "What does this page show?"; any other link is read (`Web.fetch`, 4,000 characters) and summarised. Words typed next to the link are kept. The bubble shows a compact **link chip** ("Google Lens result", "Google search · best budget phones"), never the raw URL, and the raw URL never goes to the model as the question.
  - **The page as a person sees it** (`browser/PageAsk.kt`, pure; `BrowserEngine.capture`): title, a short address (host + path; no query for search and Lens pages), the words on the page, button / link / field labels (field values are never read; secrets still scrubbed), and how many pictures there are with their descriptions (alt text). No indices, no element kinds, no element dump.
  - **New rules** for the page chat: always give a useful answer and start with it; never mention HTML, markup, tags, code, the DOM, `<body>`, "page text", "the text you provided" or how the page was read; never say the user gave too little; when little could be read, say in plain words what is visible, use web results and own knowledge (marked), and only as a last resort add one short tip like scrolling to the results and asking again. Fixed at the prompt and input level; replies are not filtered.
- **Other edge cases in the same sheet:**
  - **Little or nothing readable** (Lens / image results, a page still loading, a canvas page): a plain note to the model plus, on a results page, a web search for the page's own query (from the address or a "… - Google Search" title). A thin read isn't kept, so the next question reads the page again once it has loaded. A page that can't be read at all still gets an answer ("Blaze couldn't read this page at all").
  - **Pages made of pictures:** picture count and descriptions are passed, with a note to use them and the title.
  - **Sign-in walls** (a password box, or a short page saying sign in with an email / phone / username box; a mere "Sign in" button doesn't count): the model says so in one line and answers from what's visible; never asks for passwords or codes.
  - **Very long pages:** the start (80% of the budget) and the end are kept, cut at line breaks, with a plain "a long middle part of the page is skipped" marker (page budget 9,000 characters; up to 30,000 are read).
  - **Other languages:** answers follow the language the user writes in; a link, a chip or an empty question is answered in the phone's language; a foreign page is answered in that language with quotes translated.
  - **Empty question:** the send button now works with an empty box and summarises the page (the hint says so); "?" alone does the same.
  - **One word:** "photosynthesis" asks what the word means here (on the page, or generally); "tldr" / "summary" / "explain" / "translate" map to the matching chip; "hi" / "thanks" get one friendly line and an offer.
  - An empty model reply now says "I couldn't get an answer for this page just now. Tap a chip below or ask again in a moment." instead of claiming the answer isn't on the page.
- **Also in this release (shipped in gateway PR #61 without an app line):** with Gateway 0.3.0's waiting list, a signed-in account that isn't approved yet sees "You're on the Blaze waiting list. You'll get access as soon as you're approved." and a rejected one sees "Your Blaze account doesn't have access to Blaze AI. If you think this is a mistake, contact the Blaze team." (from the gateway's 403 `waitlisted` / `access_rejected`), instead of being told their sign-in expired (`agent/Cloud.kt` `Cloud.access`, `LlmClient`, `PlainError`).
- No database or prefs changes. The agent's own `browser_read` output is unchanged.
- Tests: `PageQuestionTest` (Lens link with no query → Lens question + chip, Lens detection, Google `q` / `oq`, Bing, DuckDuckGo, Yahoo, YouTube, maps and watch links aren't searches, a link plus a question, a link inside a long question, other links fetched, this page's own link, empty / punctuation → summarise, one word, word commands, greetings, title queries, short addresses), `PageAskTest` (the no-HTML / no-blame / last-resort rules, language rule, the page block has no element dump or markup, thin and picture notes, empty capture, sign-in wall vs a Sign in button, long pages keep start and end within budget, when a web search is added, the message list).

## v0.27.0: offers only for apps you open on purpose + Magic Hand grab made obvious + one motion system
- **Habit offers never suggest a keyboard again.** "You open X around 19:00 most days. Want me to do it for you?" counted every window change, so a keyboard popping up (Gboard, SwiftKey, Samsung Keyboard), the home screen, the notification shade, a permission dialog or a chat head could turn into an offer. Now a visit only counts when the user opened the app on purpose (`proactive/HabitFilter.kt`, pure; `proactive/HabitApps.kt`, Android): it has a launcher icon (`getLaunchIntentForPackage`); it isn't an installed keyboard (`InputMethodManager.inputMethodList`), a home app (`CATEGORY_HOME`), the system UI, Settings, the installer, the permission controller, the share sheet or Blaze; its window was a normal app window covering at least half the screen (not a keyboard / system / accessibility-overlay window, a chat head or picture-in-picture); and the user stayed at least 4 s. A keyboard or the shade coming up over an app no longer ends or splits that app's visit, and a visit is counted at the hour it started. The offer uses the app's own label ("WhatsApp", never "com.whatsapp"). The manifest now declares the HOME and InputMethod intents in `<queries>` so the lookups work on Android 11+. Old offers already made stay as they are; nothing new is saved.
- **Magic Hand: Grab is a button now, not a hidden gesture.** The dock has a visible **Grab** toggle (blue while on). With Grab on, circle or drag over anything on the screen with your finger (a transparent layer above the app, never over the navigation bar; a tap turns it off). The first three times it says "Circle or drag over what you want", then just "Grab: circle or drag". Drawing a circle with the hand still works as before, and so do all the other gestures.
  - A dashed **live box** follows the drawn shape while you draw (with the hand it only shows once the drag is turning into a circle, so pointing never shows a box).
  - On release the box **snaps to the text under it**: the text blocks that sit mostly inside the drawing (or whose centre is inside), padded 6 dp; screen-sized containers are ignored and no side grows by more than half the drawing, so a long paragraph can't swallow the screen. No text under it = the box stays as drawn.
  - A haptic confirm on grab and a tick when the box snaps or goes back.
  - Bigger corner handles (each a 48 dp touch target; knobs drawn larger) and a short "Drag corners to adjust" coach mark on the first three grabs.
  - **Undo** (back to the drawn shape before the snap, or before a move / resize, step by step) and **Grab again** on the card.
  - A new read scrolls the grabbed text back to its first line; while the box is read again the card shows shimmer lines instead of "Reading…". Copy / Translate / Search / Save / Carry are bigger, with labels, and greyed while there's nothing to act on.
- **One motion system** (`ui/Motion.kt`, `MotionTokens`): durations 150 / 220 / 320 ms, standard easing (decelerate in, accelerate out) and a spring for drags. Used for: new messages fading in with a slight rise (only messages that arrive while the chat is open, once each; the history it opens with doesn't move), the work card and steps expanding / collapsing, the "working" card, follow-up chips fading up one after another, the + menu and /views sheets sliding open and closed (with the scrim fading in), Chat ↔ Projects cross-fading (and the tab and segmented-control highlight fading across), every Blaze button, pill and round icon button shrinking slightly while pressed, the Magic Hand dock cross-fading between panels and resizing smoothly, and the grab box fading in. Loading shimmer instead of a bare label for diagrams being drawn, pictures loading and the grab card.
  - **Remove animations is respected**: with Settings › Accessibility › Remove animations (animator duration scale 0) every spec is an instant snap, transitions are None, the thinking dot stands still and shimmer is a plain fill. Re-read on every resume and when Magic Hand starts.
- No behaviour changes beyond the above. No database or prefs format changes (two small counters for the first-time tips: `mh_grab_hints`, `mh_grab_tips`).
- Tests: `HabitFilterTest` (keyboards by list and by name, home, system, self, no launcher icon, overlays, brief visits, window kinds, passing windows, labels, visits across a keyboard and the shade), `MagicHandGrabTest` (live box, circling vs pointing, snap to text / partial blocks / no page containers / growth cap / no text, Undo history, 48 dp handles, Grab button and mode, Private, a grab ending Grab mode, Grab again), `MotionTokensTest` (the three durations, scale 0 = off, every spec snaps and every transition is None when off, only new messages animate and only once).

## v0.26.3: diagrams drawn on the phone + one memory store (blaze.db)
- **Diagrams work offline and stay on the phone.** Until now every Mermaid diagram was drawn by mermaid.ink (and Graphviz / PlantUML / D2 by kroki.io), so with no data you got nothing, and the diagram's text went to an outside server. Private chats couldn't show diagrams at all. Mermaid 11.17.2 is now bundled (`assets/diagram/mermaid.min.js`, MIT, listed in THIRD_PARTY_NOTICES). A hidden WebView that is never attached to the screen draws one diagram at a time off the UI thread (15 s timeout, `ui/DiagramRenderer.kt`), and the picture is cached as a PNG in `filesDir/diagrams` (sha-256 of version, kind, theme and code). Its page blocks the network (CSP `default-src 'none'` plus a script nonce, every request except the bundled script gets a 403), runs Mermaid in strict mode, and embeds the diagram text as an escaped string, so a diagram can't run script. Pictures use Blaze's light and dark colours and are labelled "· drawn on your phone". Order (`ui/DiagramRender.kt`): the phone first, then mermaid.ink only outside private chats; Graphviz / PlantUML / D2 use kroki outside private chats and show one plain line inside them. A failure (syntax mistake, too long over 20,000 characters, no connection, too big) is one plain line with a **Show code** link, never the raw source. The Design view draws `.mmd` files with the same bundled script (the jsDelivr fallback is gone).
- **Memory now lives in blaze.db, the same store as Blaze Desktop and CLI.** Before, the phone kept memory in its own `databases/mind.db` (FTS4), which Desktop and CLI couldn't read. It now uses Desktop's exact schema v1 with FTS5 (`store/BlazeDb.kt`, checked against Desktop's `store_v1.sql` in the tests) through a bundled modern SQLite (`androidx.sqlite:sqlite-bundled:2.5.2`, `store/AndroidSql.kt`), with Mind's extra fields in side tables (`mind_mem`, `mind_journal` + `mind_journal_fts`) that Desktop and CLI ignore. Search ranks by matched words, then bm25, then newest. The prompt still carries only the pinned core and the subject index (`agent/MindStore.kt`, `Mind.Store`).
- **The upgrade is safe.** On first launch Blaze keeps working on mind.db while a background thread backs it up to `filesDir/backup/mind-<ms>.db` (no backup, no copy), copies memories and the journal in batches of 500 keeping their IDs, checks the counts, and only then switches. Late writes are sent on to blaze.db. A crash means the copy simply runs again next launch. mind.db is never deleted. If blaze.db can't open, or was written by a newer Blaze, Blaze stays on mind.db and Settings › Memory shows one plain line.
- Tests: `DiagramRenderTest`, `BlazeDbTest`, `MindStoreTest`, `MindUpgradeTest`; `RichRepliesTest`, `RichKitTest`, `VisualRouteTest` updated. No changes to saved chats or prefs.
- CI fix (test-only): `MindUpgradeTest.bothStoresAnswerTheSame` failed with "No suitable driver found for jdbc:sqlite::memory:". `java.sql.DriverManager` only returns a driver registered from the caller's class loader; the plain JVM tests registered sqlite-jdbc first, so in Robolectric's sandbox class loader the lookup found nothing. The test helper `store/JdbcSql` now connects through `org.sqlite.JDBC` directly. The app itself (bundled SQLite) was never affected. Failing unit tests now print the full exception in the CI log.

## v0.26.2: specialists, side by side (parallel, playbooks, Blaze makes its own) + no more "Done." for an empty answer
- **Root-cause fix: "Done." instead of an answer** (Jack, 22:37–22:40 on v0.26.0: "what sound profile is my phone set to?" and "am I on mute in terms of device sound profile" both answered "Done.", work card "1 step", the reasoning pane only "The user asks:"). The real path: since v0.25.0 every model call goes through Blaze Cloud (`LlmClient.completeRaw`; the on-phone free chain with its 40 s NVIDIA read timeout in `LlmClient.call` / `FreeRouter` is no longer called, so that timeout wasn't it, and a timeout there would have thrown, not said "Done."). The gateway picks a free upstream and, once the upstream answers 200, pipes its stream through untouched (`gateway/src/chat.ts`). The upstream streamed a little reasoning and then ended the stream with no content and no tool call. `LlmClient.readStream` returned that as a normal message (content `""`), the agent loop took a message with no tool calls as the final answer (the "1 step" was only the "Skills: phone-task-speed" line; no tool ran), and `Session.addReply` turned the empty text into `"Done."`. Inferred from the code and the card, not from a log of that request. Fixes:
  - An empty reply (no visible text after reasoning tags and follow-up chips are stripped, and no tool calls) is a failed try, never an answer (`agent/EmptyReply.kt`). The tries now live in `Cloud.drive` (pure, tested): after the first empty reply the same route is asked again without streaming and with a nudge ("Answer the user now in one or two sentences from what you know and the tool results above"); after the second, the routes that came back empty are sent in a new `blaze_avoid` field so the gateway answers with its next model; after the third the user sees one plain line ("The AI came back without an answer… Tap Retry") instead of "Done.". A stream that breaks after it started (reasoning, then a timeout: code 0) also skips the route that served it on the next try. Sign-in, quota, too-large and bad-request handling are unchanged.
  - Gateway: `blaze_avoid` (at most 8 "provider/model" strings) puts those routes last for that request and is never sent upstream (it starts with `blaze_`); avoiding every route still answers. `avoidSet` in `gateway/src/chat.ts`, test in `gateway/test/worker.test.ts`. Deploys with the Gateway workflow on main (only when the Cloudflare secrets are set); an older gateway ignores the field and the phone's retries still apply.
  - The agent loop's last line of defence: if the final answer is still empty, it shows a specific line only when you asked for an action and a tool really succeeded ("Turned on Do Not Disturb.", from that tool's step line); otherwise it asks the model once more, then shows the plain error. `addReply` never writes a bare "Done." any more.
  - **Phone-state questions answer on the phone, instantly.** Ringer / sound profile / mute / silent / vibrate / Do Not Disturb, battery and network questions ("am I on mute?") are recognised (`agent/PhoneState.kt`) and answered by `SelfControl.quick` from the same readings as the "Phone now" line plus Android's Do Not Disturb filter ("You're not on mute: the ringer is on normal, so calls and notifications ring."), with no model call. Longer ones that still reach the model get a prompt rule (answer from the Phone now line, no tools) and no skill packs. `SkillMatch` now counts generic name words ("phone", "task", "app") once instead of three times, which is why "…my phone…" pulled in the `phone-task-speed` skill and its tool-loop instructions; that starter's description now says it's for actions in apps.
- **Specialists hand work out without blocking** (Jack: "side by side"). `delegate` no longer waits: each brief becomes a background agent running as that specialist (`BgAgents.Bg.specialist`, saved in `bg_agents.json` as an optional key) with its own card in the chat (label "B4 · SHOPPER", Steer / Stop / Open, reusing `AgentCard`). The turn carries on and the chat stays usable. Before two or more tasks a one-line scope note posts ("Sending Researcher and Shopper ×2 to work on this at the same time…", or the model's own `scope` line). Each result posts as soon as it lands: the finished card moves to the bottom of the chat showing the result, and the main Blaze is woken to write a short follow-up from it (and the combined pick when the last one is in). `message_agent` / `cancel_agent` and the card's Steer / Stop work on each running specialist. `delegate wait=true` (and private chats, whose work is never written to disk) keeps the old blocking run. Pure logic in `agent/Dispatch.kt`.
  - Concurrency: network-only specialists (everything without the phone group) run up to 3 at once even on 3–4 GB phones like the test phone, on their own pool; general background agents keep the 1 / 2 (6 GB+) RAM cap; phone-control specialists share that cap and never run two at once. A queued phone or general agent no longer holds up network specialists behind it. Blaze's single background browser is leased to the first agent that uses it; others are told to use web_search / fetch_page meanwhile, and the lease ends with that agent.
  - One specialist per target: briefs that are the same target twice in one call, or that a running specialist of the same kind already has, are rejected with the reason (steer it instead). At most 6 per call.
  - A specialist running in the background gets its own focused prompt (its playbook, skill packs, memory) and only its groups' tools, like the blocking run.
  - Prompt guidance (the per-turn hint and core skill `blaze-specialists` v2): delegate only when it helps (independent parallel parts, big research); small or sequential work stays with Blaze; never two specialists on the same target; follow up as results land.
- **A field playbook for every specialist** (Jack: weak free models gave "a blank screen with text and arrows for a design"). Each playbook has six sections: Principles of the field, Quality bar, Best practices, Common mistakes to avoid, Self-check (a checklist), Output format. The ten built-ins ship in `assets/specialists/<id>.md` (Researcher: primary sources, two-source rule, dates and units; Coder: clean code, language idioms, tests, run before you report; Designer: hierarchy, grid and spacing, a 2–3 colour palette with AA contrast, type scale, real content, open and look before returning, never raw text and arrows, Mermaid only as a rendered picture; and so on for Phone operator, Writer, Planner, Inbox manager, Shopper, Booker, Tutor). The playbook goes into the specialist's prompt in both the blocking run and background runs (`Playbooks.specialistSystem`).
  - Self-check: after the specialist's draft, one review call checks it against the playbook's Self-check list and Quality bar; if it fails, the specialist gets the exact failed items and redoes it once. Bounded: one review, one redo; an unreadable review counts as a pass.
  - Designer, enforced: after `design` create / update the page is read back and checked (`agent/DesignLint.kt`): no styling, raw text arrows standing in for a diagram, Mermaid shown as text, an almost empty page, placeholder copy, no palette, a long unstructured wall of text. The notes go back to the model with the tool result ("[Design check] Not done yet, fix this first…").
  - A custom specialist without a playbook gets one written by the model on first use, validated like below (3 tries), and saved with it.
- **Blaze creates specialists itself**: new tool `create_specialist` (id, name, prompt, tool groups, playbook) for a field no existing specialist covers. The playbook must pass `Playbooks.validate` before anything is saved: all six sections, a minimum length for each, at least 4 self-check items, field-specific (uses the field's own terms from its name and prompt), not generic boilerplate (mostly "be accurate / make sure" lines or a tiny vocabulary), not a copy of another playbook (4-word phrase overlap), no credentials, under 9,000 characters. On failure the model is told exactly what to fix and rewrites it (3 tries per id, then it does the task itself). Built-in ids and your own specialists are never overwritten. Saved to the `specialists` pref with new optional `playbook` and `made_by` keys (old entries load as before; an older Blaze ignores the new keys). Settings › Specialists marks it "MADE BY BLAZE", shows whether a playbook exists, and lets you tap a custom specialist to edit its prompt and playbook (checked, with the problems shown) or delete it. It's matched by name in later turns like any custom specialist.
- **Polish (Jack approved):**
  - The "Fresh start. Earlier turns are out of my context…" / "Fresh start. Here's where we were" bubble is gone (`Session.freshContext` is silent; a carried note stays in the history only) and old ones in saved chats are hidden (`ReplyChips.isFreshStart`). The context-window logic is unchanged.
  - Follow-up suggestions sit in one horizontal swipe row of compact one-line chips under the reply (`ui/Bubbles.kt FollowupRow`, `LazyRow`; up to 5, long ones end in "…"), not a stack of full-width pills.
  - **Skills by lookup**: the per-turn prompt no longer lists up to 6 matched skills plus up to 40 other names (`SkillPacks.promptBlock`) and the top 3 learned skills with 400 characters of steps (`Learning.promptBlock`). One index of every enabled skill (bundled, your own, learned) ranked like Blaze Desktop's store (bm25 over name / description / when-to-use, weights 10 / 5 / 1; `agent/SkillIndex.kt`) puts only the 2–3 best matches in the prompt with a short description, and one line: "use_skill / find_skill to search for more". New tool `find_skill` (core, read-only). The index is rebuilt when the skill set changes (install, toggle, learn, delete; keyed on a signature of the skills). Skills attached in full by the per-turn match aren't listed twice. `find_tool` now ranks tools the same way (name / description / group, 10 / 3 / 2) with the old word score as fallback; which tools are sent each turn is unchanged (ToolRouter already sends core + matched groups, not every schema).
  - Chart x-axis labels longer than 10 characters end in an ellipsis ("Livingsto…") instead of being cut silently ("Livingston") (`RichKit.axisLabel`).
- Judgment call: the untracked `store/BlazeDb.kt` (a port of Desktop's internal/store schema v1 with FTS5) has its `Sql` interface but no `AndroidSql`, no bundled SQLite and no sqlite-jdbc dependency, and none can be verified without a local Android toolchain, so it stays out of this release (memory isn't migrated here either). The skills index uses the same ranking in plain Kotlin and can move onto blaze.db with the memory release.
- Tests: `DispatchTest` (low-end phone slots: 3 network specialists beside a general agent, phone ones one at a time, queue passing; one per target; scope line; immediate return; result message; card to the bottom; browser lease), `PlaybooksTest` (all ten built-ins pass and have six sections; Designer and Coder carry Jack's asks; rejections for a missing section, thin, generic, copied, secret and off-field playbooks with exact feedback; injection into the specialist prompt; review parsing; generation retry), `SpecialistStoreTest` (old prefs load, round trip with playbook / made_by, create_specialist rejects then accepts, built-in and user ids protected, the new one matches in later turns), `SkillIndexTest` (name outranks description, only top matches, no name list, find_skill, generic name words), `EmptyReplyTest` (a reasoning-only reply is retried without streaming plus nudge, then sent to the next model, never "Done."; three empties fail plainly; a mid-stream timeout skips that route; tool calls are an answer; sign-in / quota unchanged; an action plus a successful tool gives "Turned on Do Not Disturb."), `PhoneStateTest` (Jack's two questions, actions excluded, answers), `PolishTest` (fresh-start hidden, chip row, axis ellipsis, design check). ShotsTest `60-specialists-chip-row`. Gateway test for `blaze_avoid`. No database changes; prefs, saved chats and `bg_agents.json` backward compatible.

## v0.26.1: visual answers, picked automatically + "Thinking it through" instead of raw reasoning
- **Blaze picks the format by itself** (like ChatGPT's Intelligent UI). Jack asked "how do solar panels work?" and got plain text: the v0.24.9 prompt told the model to use rich blocks "only when it really helps", so it almost never did. `RichReplies.PROMPT` is rewritten (still one static string, so the provider's cached prefix holds; left out only when Visual answers is off): how something works → a Mermaid flowchart plus 2–3 sentences; comparisons → a compare card; numbers and trends → a chart; anything with an amount you could change → a mini tool; a choice to make next → tappable buttons; several missing details → a small form; simple facts and chit-chat stay plain text; at most two visuals per answer. It carries worked examples (solar panels → Sunlight → Solar cells → DC power → Inverter → AC power → Home; "split a K450 bill between 3 with 10% tip" → K165 each plus a bill splitter; "compare iPhone 16 vs Galaxy S25" → a compare card).
- **New interactive blocks**, written by the model as fenced code blocks with a JSON body, parsed in the pure layer (`ui/RichKit.kt`) and drawn natively in Compose (`ui/RichInteractive.kt`; rounded cards, solid classic Blaze blue, no gradients, no WebView, no new dependencies):
  - ```` ```chart ````: bar (grouped series), line, pie (biggest 7 slices + "Other") and scatter, drawn with Compose Canvas, rounded axis ticks (1.2K / 3.5M); tap a bar, point or slice (or a pie legend row) to read its value in a blue pill.
  - ```` ```choices ```` / ```` ```buttons ````: up to 6 tappable buttons; a tap sends that text (or the option's own `send`) as your next message through the same path as the follow-up chips (only while Blaze is idle; the picked one turns solid blue).
  - ```` ```form ````: up to 8 fields (text, number, select pills, toggle) and a submit button; submit sends one compact message ("Trip plan: City: Livingstone · Days: 3 · Budget: High · With kids: yes"), blank fields left out.
  - ```` ```tool ````: a live mini calculator: number fields, sliders and selects as inputs, outputs recomputed on the phone as you move them (the last output is the big headline). Formulas go through `ui/Expr.kt`, a tiny pure-Kotlin evaluator: numbers, the tool's own input names, + - * / % ^ ( ), < <= > >= == !=, min, max, round(x, digits), ceil, floor, abs, sqrt, pow, if(cond, a, b). Nothing else: no scripting engine, no reflection, no JS. Divide by zero or a half-typed number shows a blank (—), never a wrong number; unknown names drop that output; formulas are capped at 400 characters and 40 levels deep.
  - ```` ```compare ````: 2–3 columns side by side (rows of label + values, optional "Pick" column in blue and a one-line verdict).
- **Never a crash on bad JSON**: a block that can't be used shows as the plain code block once it's closed; while it's still streaming in (unclosed fence, half the JSON) a quiet "Drawing a chart…" placeholder shows instead of raw JSON. Mermaid / Graphviz diagrams now also wait for their closing fence instead of fetching a half diagram on every streamed word. Lenient bits models get wrong (trailing commas, smart quotes) are accepted. In Private chat the new blocks still draw (they never leave the phone); diagrams still don't go to a renderer, as before.
- **Copy** on a reply turns visual blocks into readable text (a chart's values, a compare table, a tool's results with its starting numbers, the choices) instead of JSON (`RichKit.readable`).
- **Settings › Brain › Visual answers** (the existing `rich_replies` pref, default on) switches all of it, the prompt included.
- **"Thinking it through…" instead of the reasoning pane.** The "REASONING ⌄" header with the dotted brain that expanded to the model's raw reasoning is gone from the chat. While a reasoning model thinks, a calm "Thinking it through…" line with a softly pulsing blue dot shows; afterwards the reply has a small "Thought it through · 4 s" (seconds measured from the first to the last reasoning chunk; just "Thought it through" when it wasn't measured, e.g. older chats). It isn't tappable and never shows the reasoning text; the answer bubble, Copy and Quote use only the answer (reasoning was already split out by `ReplyParts` / `ReplyClean`). The reasoning is still saved with the chat as before (backward compatible); `think_ms` is a new optional field in saved chats. The Settings toggle is now "Show thinking" (same `show_reasoning` pref). Pure logic `agent/ThinkingLabel.kt`.
- **Diagrams asked for in chat are drawn in the chat** (Jack's report: "draw a flowchart of how solar panels work" took minutes, wrote a Mermaid file and opened the Design preview, which showed the raw `flowchart TD / Sun[Sunlight] -->|Photons| …` source on a white page; "simply drawing the design and pasting it in chat would have done"). Root causes: `ViewRouter`'s Design cue matched "diagram / flowchart / chart / graph of" and injected "REQUIRED VIEW THIS TURN: Design"; the bundled `diagrams` skill (description "Use for any diagram, flowchart…", finish line "open in the preview") was matched in full by `SkillMatch` and listed for `use_skill`; and the Design view served `.mmd` as a raw download/text. Now `agent/VisualRoute.kt` decides: a visual ask with no file words (save, export, download, file, PNG/SVG/PDF, poster, slides, website, 3D, edit the file…) is in-chat, so `ViewRouter.route` returns no view (unless something else in the ask is clearly Design work), `SkillMatch.forTurn` and `SkillPacks.promptBlock` leave out the `diagrams` skill (and `data-analysis` unless there's data to analyse), and the turn gets a one-line hint: answer with a ```` ```mermaid ```` / ```` ```chart ```` block, no files, preview, Design or use_skill. The same rule is in the static `RichReplies.PROMPT` and in ViewRouter's VIEWS block. Bundled skills: `diagrams` (v2) now opens with "In chat: just reply with a ```mermaid block" and its description says it's for saving / exporting files only; `data-analysis` (v2) says a chart in chat is a ```chart block; core `blaze-views` (v2) pins the same rule. `assets/skills/index.json` versions and sha256 updated, so untouched installs upgrade on the next start.
- **The Design view never shows raw Mermaid**: a `.mmd` / `.mermaid` file, a `.md` that is one ```mermaid block, or a `.txt` / extension-less file that starts like Mermaid (flowchart, graph TD, sequenceDiagram, mindmap, timeline, pie title…) is served as a small page that draws it as a picture: the mermaid.ink image (like the chat's diagrams), falling back to mermaid.js from jsDelivr, and a plain line if neither can be reached. The source sits escaped in a hidden element and is never shown as text. Works for files outside Blaze's home too (`loadDataWithBaseURL`). `DesignServe.mermaidSource` / `looksMermaid` / `mermaidHtml`, `mermaidFile` in `ui/DesignView.kt`.
- Tests: `ExprTest` (precedence, right-associative ^, unary minus, functions, comparisons, if, loan payment, divide by zero → blank, no code execution, length / depth limits, formatting), `RichKitTest` (each block, pie folding, scatter, axis maths, tap hit-testing, choice sends, form message, tool outputs incl. ÷0, unsafe formulas dropped, compare both shapes, malformed JSON → code, partial JSON → placeholder at every cut, diagrams wait for their fence, Private, readable Copy, prompt contents), `ThinkingLabelTest` (label states, durations, the clock, reasoning never in the answer or Copy, ChatScreen has no reasoning pane), `VisualRouteTest` (in-chat asks route to no view, file asks still go to Design, the diagrams skill isn't matched, the skills' new first rule, `.mmd` detection, the Mermaid page shows a picture and never the source); `RichRepliesTest` and `DesignEverythingTest` updated. ShotsTest `58-visual-answers`, `59-visual-tools`. No database changes; prefs and saved chats backward compatible.

## v0.26.0: Magic Hand box you can fix + long chats that just keep going
- **Move and resize the grab box**: after you circle text with the Magic Hand, the blue box now has corner knobs and edge bars. Drag inside it to move it, drag a corner or an edge to resize it (it stays on screen, off the status and navigation bars, and never gets smaller than 40 dp). When you let go, Blaze reads the text in the new box again (350 ms after release; a quick second drag cancels the first read) and the "Grabbed" card updates in place ("Reading…" while it works; "No text in this area" keeps the card open). The box is its own small overlay window just around the rectangle (`NOT_FOCUSABLE | NOT_TOUCH_MODAL`, added under the dock and the hand so both stay on top, untouchable when there's no grab), and it's hidden for the screenshot so it never ends up in the text. It goes away with the card. Copy on the card now keeps the card and the box open (Close still closes). Pure logic in `phone/MagicHandBox.kt` (hit-testing, move/resize clamping, minimum size).
- **Selecting part of the grabbed text no longer closes Magic Hand.** Root cause: the dock's window has a "long-press anywhere closes Magic Hand" escape hatch (650 ms timer in `DockFrame`), and long-pressing the text to start a selection hit it, so the box and the card vanished. That hatch is now off while the dock shows selectable text (grabbed text, an answer) or the teach review (`MagicHandState.longPressCloses`); Close, Back twice and the notification's Stop still work. Also, Android's floating Copy toolbar can't open in a bare overlay window (no Activity), so the dock now draws its own **Copy · Select all** bar above the text while something is selected.
- **No more "This chat is getting long · Fresh chat →".** Long chats compact themselves in the same chat, silently: when the un-summarised history is over the long-chat budget, or the whole request (system prompt + tools + history) reaches 75% of the model's context window, older turns fold into the chat's rolling summary and only the summary plus the recent turns are sent. Everything stays on screen. The summary is saved with the chat (`lc_summary` / `lc_upto`, as before) and only updated with new turns, never redone each turn. If a provider still says the chat is too long, Blaze folds harder (4 messages kept word for word; a clipped transcript stands in if the summariser fails) and retries once, instead of "Start a new chat". The token meter only shows with Show technical details. Pure logic in `agent/AutoCompact.kt`; `Agent.freshChat` and `FreshChatOffer` are gone.
- Tests: `MagicHandBoxTest` (handles, move/resize clamping, min size, window flags, re-read state, long-press rule), `AutoCompactTest` (75% trigger, which messages fold vs stay, forced fold, fallback note, context errors, no "new chat" line), `MindTest.pressureUsesTheSummaryUnderBudget`. No database changes; saved chats are read as before.

## v0.25.5: Linux desktop live view + reliable download
- **Watch Blaze work in the desktop**: while the agent uses the `desktop` tool, a dark Glass card sits above the composer with a live mini view of the Linux desktop (its own view-only VNC connection to the local Xvnc, ~5 frames a second; the latest screenshot until it connects), a blue ring where Blaze last clicked, a solid Blaze-blue status pill in plain words ("Clicking Save", "Typing into Terminal", "Opening Files", "Pressing Ctrl+S", "Taking a look", "Deciding what to do next" between actions, "Done" at the end) and an **X** that stops Blaze's desktop work for the rest of that task (the next `desktop` call tells the agent to stop and say so). Tap the card for the full-screen Desktop view; the same pill shows there too. It appears only while Blaze acts on the desktop (after your yes), and hides 4 s after the task ends, or after 45 s with no desktop action. Typed text is never shown, only where it goes. The `desktop` tool gains an optional `label` ("Save", "Terminal") for the pill; chat step lines use the same words. Pure logic in `desktop/DesktopNarration.kt`; state in `desktop/DesktopLive.kt`; card `ui/DesktopLiveCard.kt`.
- **The desktop download no longer stops and fails.** Root causes in v0.25.1–v0.25.4: (1) the ~260 MB install was one `set -e; apk update; apk add <16 packages>` run with no retries (`DesktopPlan.installScript`), so one dropped connection anywhere failed everything and the next Start began again; (2) one mirror only (`dl-cdn.alpinelinux.org`, from the rootfs's own `/etc/apk/repositories`); (3) it ran in a plain coroutine with no foreground service or wake lock, and the Start button launched it in the Desktop page's own scope, so the screen turning off, leaving Blaze, or closing Settings let Android freeze/kill it (proot runs `--kill-on-exit`, so apk died with it); (4) the Alpine rootfs came over one plain `HttpURLConnection` with no resume, retry or checksum (`terminal/Linux.kt`), and a cut-short file went straight to the unpacker; (5) resolv.conf was hard-wired to 8.8.8.8 / 1.1.1.1, which some Wi-Fi networks block.
- Now: the install runs step by step (`desktop/DesktopInstall.kt`): refresh the package list, then 7 small batches (`DesktopPlan.BATCHES`), each retried up to 5 times with backoff (3, 6, 12, 24 s), moving to another official mirror after two failures on one (dl-cdn, eu/US kernel.org edges, MARWAN Morocco, RWTH Aachen, dotsrc; `terminal/AlpineNet.kt`, `/etc/apk/repositories` rewritten each step; the mirror that last worked is remembered), refreshing the list after any failure, and apk's network timeout raised to 120 s (`--timeout`, apk-tools 2.14+). Finished batches stay installed, so Start carries on where it stopped. Out of space stops at once with one plain line. Status in plain words: "Connection dropped. Retrying (2 of 5)…", "Connection dropped. Trying another server (eu.edge.kernel.org), 3 of 5…".
- **Stays alive with the screen off**: a `dataSync` foreground service (`desktop/DesktopKeeper.kt`, notification "Setting up the Linux desktop" with a progress bar and the same plain line) holds a partial wake lock and a Wi-Fi lock until the install ends; the install runs in Blaze's own scope (`LinuxDesktop.startAsync`), so leaving the page doesn't cancel it. New permission: `WAKE_LOCK`.
- **Alpine rootfs download** (`terminal/Fetch.kt`): resumes with HTTP Range from a `.part` file (starts over cleanly when a server ignores Range), 6 tries with backoff across the same mirrors, SHA-256 checked (aarch64 / armv7 / x86_64 sums from Alpine's `.sha256` files), plain errors; x86_64 phones now get the x86_64 rootfs. resolv.conf puts the phone's own DNS first, then 8.8.8.8 and 1.1.1.1.
- Tests: `DesktopNarrationTest`, `DesktopInstallTest` (drop → retry → mirror switch, give-up and out-of-space lines, progress), `AlpineNetTest`, `FetchTest` (a local server that cuts the connection mid-file, ignores Range, serves a damaged file or 404s); ShotsTest `57-desktop-live-card`. No database changes; prefs backward compatible (`linux_desktop_mirror` is new).
## v0.25.4: Magic Hand draws from its centre
- When you draw with the Magic Hand (circle to grab text, or any drag), the line now comes out of the middle of the Blaze mascot instead of a point above the flame tip, which confused people. The circled area matches the line you see. Pointing still reads what's under the flame tip. `MagicHandLogic.trailPoint`, test in `MagicHandTest`.

## v0.25.3: VPN that just works
- **Blaze Free (Cloudflare WARP)**, built in and free: the browser VPN works with one tap and no imported configs. On first use the phone generates a WireGuard key pair (pure-Kotlin X25519, RFC 7748 vectors in the tests; the private key never leaves the phone) and registers a free WARP device with Cloudflare's public client API (`POST https://api.cloudflareclient.com/v0a2158/reg`, falling back to `v0a5641`), reading the peer key, endpoint (`engage.cloudflareclient.com:2408`) and the interface IPv4/IPv6 addresses. The device is saved privately in `filesDir/blaze_free.json` (with its wg-quick text) and reused; if every route fails it signs up again once, in case Cloudflare dropped the device. It runs through the existing wgproxy path, and the exit is verified with the Cloudflare trace (`warp=on`).
- **One tap**: the VPN switch in ⋯ › VPN & privacy is always enabled; with nothing imported it connects Blaze Free (otherwise the last pick, else the first imported country). Blaze Free is always listed at the top (solid Blaze blue shield). `browser_vpn` connect with no country (or "free" / "warp") uses Blaze Free when nothing is imported. Imported per-country configs work exactly as before.
- **Reliability**: endpoint fallback when the handshake fails (last route that worked first, then the named endpoint, then the peer IP and 162.159.192.1 on ports 2408, 500, 1701, 4500, since some networks block UDP 2408); auto-reconnect with backoff (2, 4, 8, 16, 32, then 60 s, up to 8 tries, then a tap) when the tunnel drops or a connect fails; a health check every 45 s (two missed traces count as a drop); a plain-words status line ("Setting up Blaze Free (first time only)…", "That route is blocked here. Trying port 500…", "Reconnecting in 2 s…"). The kill switch is unchanged: while reconnecting, pages stay blocked.
- Pure logic in `browser/WarpFree.kt`; tests `WarpFreeTest` (keys, request body, response parsing, config build, endpoint order, backoff, trace). wgproxy unchanged. No database changes; prefs backward compatible (`browser_vpn.json` only gains `selected = "blaze-free"`, which older builds simply ignore).

## v0.25.2: classic Blaze colours back
- The v0.24.8 orange-to-blue gradient is gone everywhere: send button, primary buttons (Create, Allow, Continue, setup, Learn, lock screen…), project badges, progress bars, the "Blaze is working" overlay title, step-flow dots in rich replies, Magic Hand highlights and its notification. All use the classic solid Blaze blue (`BlazeBrush.primary` / `primarySoft`, `Glass.brand`). The flame mascot and app icon keep their own colours; warnings and danger buttons keep their warm warning colour.
- "VPN that just works" moves to v0.25.3.

## v0.25.1: Linux desktop on the phone
- **Settings › Advanced › Desktop (Linux)** (view `linux`, aliases linux desktop / xfce / vnc): a real XFCE desktop (terminal, Thunar, Mousepad, NetSurf) inside Blaze's Alpine Linux (proot) under TigerVNC bound to 127.0.0.1 with a random 8-character password (`desktop/DesktopPlan.kt`, `desktop/LinuxDesktop.kt`). Packages come free from Alpine on first Start (~260 MB download, ~750 MB on disk); low-memory-friendly defaults 1024×576, 16-bit; refuses to start below 350 MB free RAM; unsupported CPUs get one plain line.
- Blaze's own RFB viewer (`desktop/Rfb.kt`, `ui/LinuxDesktopPage.kt`): full screen, touch as a mouse, on-screen keyboard.
- `desktop` agent tool so Blaze can install / start / stop and use the desktop (screenshots, xdotool), with a confirm card first.
- Tests `DesktopPlanTest`, `RfbTest`; ShotsTest page. THIRD_PARTY_NOTICES lists the open-source packages. No database changes; prefs backward compatible.

## v0.25.0: Blaze Cloud only, Magic Hand can't lock the phone, swipeable Undo bar
Bug-fix release from Luke's phone reports. (The planned "VPN that just works" moves to v0.25.2; v0.25.1 is the Linux desktop.)
- **Magic Hand lock-up fixed** (had to reboot): in teach mode the dock's review step made its window focusable *without* `FLAG_NOT_TOUCH_MODAL`, so it captured every touch on the screen, and it sat over the navigation bar; meanwhile every accessibility event (System UI, Samsung capture…) was handled on the UI thread with binder calls, which froze Blaze into "isn't responding". Now (`phone/MagicHandSafety.kt`): the highlight layer is never touchable, the hand and dock are always `NOT_TOUCH_MODAL`, the dock sits above the nav bar / gesture area, the hand is clamped on screen; probing what's under the tip, teach events, screenshots, crops and OCR run off the UI thread with timeouts; key events are only observed, never consumed.
- **Escape hatches**: Back twice, a long-press on the dock, an ongoing **Stop Magic Hand** notification, and a watchdog that closes Magic Hand when the UI thread stalls for over 3 s (ending Blaze's process if it stays frozen) or after 10 idle minutes.
- **Teach mode**: a plain explainer with **Start** ("Teach Blaze a routine: tap Start, do the task once in any app, tap Done…"), then **Undo last step / Cancel / Done**, a 30-step limit; System UI, Android, launchers, screenshot tools, keyboards and Blaze are ignored; steps use button text or descriptions, never class names like `MainUiViewForStandAlone$7`.
- **Magic Hand pointer visible**: the hand window's root now carries the lifecycle owner Compose needs (only the inner view had it, so the mascot never composed), plus a native dark halo and light rim so it stands out on dark wallpapers; it starts near the centre and is draggable within the screen. Window failures are logged instead of swallowed.
- **Blaze Cloud is the only brain**: every call (chat, assist panel, Magic Hand, background, vision, code, agent) goes to the Blaze gateway (`https://ai.blaze-app.workers.dev/v1`, signed in with the Blaze account) with a task hint (`blaze_task`: chat / quick / code / vision / long / agent, `agent/Cloud.kt`); the gateway holds the keys and picks the model. No provider picker, key fields, "Get a key", key catcher, model picker or Plan & quota anywhere (Brain shows "Blaze Cloud" and a connection status; setup asks you to sign in). Old provider / model / key prefs stay on disk untouched but are never used or uploaded (key sync now carries only MCP servers).
- **"The AI doesn't offer that model any more" gone**: it came from `PlainError` turning a 404 for a stale saved model (a gemini-3 / pro-preview id the gateway and Google now refuse) into a Settings hint. Model-gone / refused-model / busy / network errors are now retried quietly with backoff (1 s, 3 s) and the gateway re-routes to its task lineup; only if all tries fail does one short line show, with no model or settings words.
- **No model under replies**: the "☁ Free (Google Gemini · gemini-3…)" route line is hidden (time, Copy, Quote, Retry stay); Settings › Brain › Show technical details shows it for debugging. The composer + menu has no Model row; `/model` and `/provider` are gone (typing them says Blaze picks automatically).
- **Undo bar swipes away** left, right or down (`ui/SwipeAway.kt`).
- Tests `CloudTest`, `MagicHandSafetyTest`, `SwipeAwayTest`, `ReplyChipsTest`; screenshot `55-magic-hand-teach-intro`. Accessibility config adds `canRequestFilterKeyEvents` (observe only). No database changes; prefs backward compatible.

## v0.24.9: rich replies
- **ChatGPT-style replies** (`ui/RichReplies.kt` rules, `ui/RichBlocks.kt` drawing, wired into `AnswerBody`): a numbered list whose items start with a **bold title** becomes a **step-flow card** (numbered orange → blue dots joined by a line); a picture on its own line right before a paragraph sits **beside that section's text**; **image groups**: up to 3 pictures side by side, 4 or more collapse into a **stacked, slightly tilted deck** you swipe through (or tap the arrows) with a **card-flip** animation, an instant swap when Android's *Remove animations* is on (animator duration scale 0); **inline diagrams**: ```mermaid blocks drawn by mermaid.ink, ```graphviz / ```dot / ```plantuml / ```d2 by kroki.io (free, keyless). In a private chat no diagram source leaves the phone (shown as code).
- Pictures load on a background thread (≤ 12 MB, downsampled to ≤ 1400 px, small memory cache) with a rounded placeholder and the alt text if they can't.
- The system prompt gets a short static RICH REPLIES block so models use the formats only when they help and only with real image URLs. Pref `rich_replies` (default on) turns it all off.
- Tests `RichRepliesTest`; screenshots `53-rich-steps`, `54-rich-deck`. No database changes; prefs backward compatible.

## v0.24.8: rounded everything (no square UI)
- **Shape tokens** (`ui/Theme.kt` `BlazeShapes`: xs 8, small 12, medium 16, large 24 dp, pill; Material's shapes mapped to them) and the brand gradient `BlazeBrush` for primary buttons; `BlazeButton` / `BlazeTextField` / `BlazeCard` and a rounded `ui/Kit.kt` (rows, tags, buttons as sentence-case pills, switches, search, segmented). `Modifier.hairline` now follows a rounded shape and clips to it.
- **Brand mark** is the mascot in a dark circle (was the square pixel "B"); Projects restyled.
- **Audit**: every screen rounded, Magic Hand included: selected rows, expanded detail panels, the Saved logins / Accounts notices, the model list, design preview, progress bars, onboarding dashes, the memory card's accent bar, the tap highlight over other apps (rounded rect), the edge particles and the orb bin (round dots), widget field and mic.
- **`ui/NoSquareTest`** fails the build if `RectangleShape`, `CutCornerShape` or `RoundedCornerShape(0…)` appears in app sources (comments ignored; empty allow-list).
- No database or pref changes.

## v0.24.7: Magic Hand (point at anything on screen), one Blaze task in Recents
- **Magic Hand**: the new hand button in the quick assistant panel (hold Home) summons Blaze's mascot as a pointer you move yourself over any app (`phone/MagicHand.kt`). Drag it and the element under the flame tip lights up; the dock at the bottom shows what it's on, the menu, Blaze's answer, the confirm card and Close (`phone/MagicHandUi.kt`, state in `phone/MagicHandState.kt`). **Closing the dock stops everything**: the windows, a question in flight, whatever the hand carries and a teach recording.
- **Point & ask**: double-tap the hand for the menu (the v0.24.6 browser pointer tiles and its element → action mapping, `BrowserMenuMapper`, plus Carry / Watch / Drop). Ask, Explain, Describe and the like send the element's words and a screenshot crop around it (Magic Hand's windows hidden while it captures) to Blaze; the answer shows in the dock.
- **Circle to grab**: draw a circle with the hand; the circled area is read with on-device OCR (ML Kit, bundled model, no network), falling back to the accessibility text, then Copy, Translate, Search (DuckDuckGo), Save (to memory) or Carry.
- **Carry & drop**: carry text across apps and double-tap a text box to drop it; it's always confirmed first, then typed with SET_TEXT (or the clipboard and Paste). "Drop in chat" hands it to Blaze.
- **Teach mode**: Teach in the dock records your own taps, typing and screen changes (accessibility click / text-changed events, newly added to the service config), then a review card (name, remove last, discard) saves it as a workflow, `tested: false`. Passwords are never recorded.
- **Watch this**: hold the hand on a price, score or status and confirm; Blaze re-reads it hourly and whenever its app comes to the front, and notifies when it changes. Settings › Advanced › **Watches** lists them, each removable.
- **Rules**: the hand never taps or types without a yes; in Private (private chat or a private browser tab in front) it captures nothing: no screenshot, OCR, teach, watch or question; copy, carry and drop still work.
- **Two Blaze tasks in Recents** fixed (`TaskDedupe`).
- Tests: `MagicHandTest`, `MagicHandStateTest`, `TaskDedupeTest`; screenshots 48–52 (dock, menu, grab, teach review, watches). No database changes; prefs unchanged.

## v0.24.5: Blaze's real mascot (pointer, launcher and notification icons), always-on pointer, Blaze-themed dialogs and work banner
Luke couldn't see the old arrow pointer at all, wanted it on screen the whole time Blaze drives the phone, and sent the real mascot: a flame / teardrop with its tip at the top right, orange at the top to bright blue at the bottom, two dark rounded eyes and a soft orange / blue glow.
- **The mascot, drawn as vectors** (`phone/Mascot.kt`: one shape in body units, traced from Luke's art; the gradient, cyan middle, eyes and glow colours). v0.24.4's invented "spark blob" is gone everywhere.
- **Launcher icon**: adaptive foreground = the mascot with its gradient, eyes and glow on a dark `#212226` background (`drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml`); **themed icon** = the silhouette with the eyes cut out (`ic_launcher_monochrome.xml`); **notification small icon** = the white silhouette with the eyes cut out (`ic_stat_blaze.xml`). The orb (pixel B) never used the blob and is unchanged.
- **Pointer = the mascot** (`phone/Pointer.kt`): 52 dp tall in a 64 dp window (easy to grab; was 44 dp), gradient body, eyes, glow, a thin white edge and a soft shadow so it reads on light, dark and busy apps. The flame tip is the click point (a tiny white dot marks it). It pulses lightly while gliding, squishes on click around the tip (the click point never moves) with a ring at the tip, and its edge turns orange while you hold it.
- **Always on while Blaze controls apps**: it no longer hides after 3 s; it stays on top of every app until the task ends. Agent taps always show it: the Settings › Phone control switch is now a one-line note, and the `show_pointer` pref is kept (old prefs load) but ignored. Every gesture path goes through it: tap and long-press, typing into a field (it glides to the field before focusing), scroll (to the list, or the swipe start), swipes (glide to the start, draw the path, end at the finish) and watcher taps (`tapNode`, e.g. Quick Settings tiles).
- **Manual double-tap is checked first**: Blaze reads the accessibility node under the tip (`BlazeAccessibilityService.labelAt`: the clickable element's text / description / child text; Blaze's own overlays skipped) and shows a one-line **"Click 'Add to cart'?"** bubble with Cancel for ~1.5 s, then clicks and resumes. When the label is unreadable, more than one thing is under the tip, or it looks risky (pay, send, delete, buy, order, transfer, confirm…), it asks with a confirm card instead (risky ones always ask, even on Autopilot). A cancel leaves everything as it was and the agent looks again.
- **Blaze-themed dialogs and menus** (`ui/Kit.kt`: `BlazeDialog` / `BlazeDialogCard`, `PillButton`, `BlazeMenu` / `BlazeMenuCard`, `DialogColors`): a rounded dark card (#16171A, subtle border, soft shadow), paper-white title, muted body, a primary pill in brand blue (or orange for a warm action) and a ghost secondary. The "Leave private chat?" dialog (was Material's lavender AlertDialog with purple Stay / Leave) and the browser's tab menu (was Material's DropdownMenu) use them; there are no other AlertDialog / Dialog / ModalBottomSheet / DropdownMenu uses left. `BlazeTheme` now also sets Material's colour scheme from Blaze's tokens (`blazeScheme`: blue primary, orange secondary, Blaze surfaces and every surfaceContainer level, light and dark), so no default purple can leak into anything Material draws.
- **The "Blaze is working" banner over other apps** (`ui/Overlay.kt` `TaskCard`): a fully rounded (26 dp) dark Blaze card with a subtle border and shadow, the mascot inside a spinning orange → blue ring, the status line in a brand-colour gradient, and Stop as a rounded orange pill. Swipe up, left or right: it slides away and minimises into the ongoing status notification (the banner stays away for that task); swipe down or tap: opens Blaze on the current chat. A short drag springs back. The overlay window is only as wide as the card (12 dp margins), so touches beside it reach the app below.
- **Tests:** `PointerTest` (always on, 64 dp target, the mascot geometry and colours, tip-anchored squish, pulse and squish ranges, click check rules and the bubble line). Screenshots `34-pointer` (redrawn), `44-pointer-mascot` (light, dark and busy backgrounds; gliding, clicking, paused; the check bubble) and `45-mascot-icons` (launcher, themed and notification icons rendered from res, plus the pointer), `46-private-leave-dialog` and `47-work-banner` (light and dark apps, plus the Blaze menu); `PointerTest.workBannerSwipes`.
- No database changes; prefs unchanged (`show_pointer` stays readable). applicationId and signing unchanged.

## v0.24.4: simpler day to day (icon, one status notification, background updates, private chat, seven-row Settings, setup gate)
Luke: one big "simplify-day" release (planned as v0.25.0, then v0.24.2; v0.24.3 landed first, so it ships as v0.24.4). Versioning rule from here (Luke 2026-10-13): bump the patch only (v0.24.N+1) per release.
- **Spark blob icon.** The launcher icon is a paper blob with Blaze's accent spark (`drawable/ic_launcher_foreground.xml`), with a themed **monochrome** layer (`ic_launcher_monochrome.xml`, spark cut out) for Android 13 themed icons. The status-bar icon (`ic_stat_blaze.xml`) is the monochrome blob instead of the pixel "B", so every notification changes with it. (No blob artwork existed in the repo or in Blaze Desktop, whose logo is still the pixel B; the blob was drawn to match the brand colours.)
- **One combined status notification** (`notify/StatusNote.kt`, `Notifier.working/doneWorking/needs/update/visible/stopEverything`). One ongoing line says what Blaze is doing: "Thinking…", "Browsing…", "Downloading update 42%" or "Needs you: …" (a question, a confirm, a secure form), with **Stop**, which cancels the agent work and any update download. Agent work isn't repeated in the shade while Blaze is on screen; downloads and needs still show. Channel renamed "Blaze status" (same id). Kept separate on purpose: the Life logs and wake word microphone foreground notifications (merging would break their foreground type) and the routine / terminal-jobs `dataSync` foreground notifications.
- **Background updates** (`update/Updater.kt`). The update runs in its own scope, not the chat turn: the `update_blaze` tool starts it and returns, `/update` and the card start it, and **Stop** (notification, card page) cancels between chunks and deletes the partial file. At most once a day on launch Blaze quietly checks; if a newer build is published the update card appears with Install (nothing downloads until you tap). There's only ever **one update card** (`Agent.hasCard`), and it disappears a moment after the update finishes or when you're already up to date (`Agent.removeCard`).
- **Private chat** (`agent/PrivateChat.kt`). The ghost button in the top bar opens a private chat: a "Private" pill replaces Chat | Projects, nothing is saved (no chat file, journal or long-chat index: `Session.persist`, `ChatStore.save`, `Mind.journal`, `LongChat.sync` all refuse `private-` ids), nothing is learned (no titles, memories, skills or phone-control traces), memory- and skill-writing tools are removed from the model's list and refused at dispatch, the widget isn't updated and a finished reply's notification says only "Blaze answered in your private chat". Leaving (ghost, pill or Back) asks "Leave private chat?" and throws it away; you return to where you were.
- **Settings cut to seven rows**: Brain, Voice, Permissions, Account, Updates, About, Advanced. Advanced lists every other page unchanged. New pages: **Permissions** (every dependency with its ring/check, tap to fix, plus phone control), **Updates** (the update card, Check now, Stop), **About**. The **BlazeRouter dashboard is removed** from Settings, the avatar menu and the top bar (the router dot is now the ghost); its prefs stay, `router` links open Brain.
- **Technical lines under replies hidden** (`agent/ReplyChips.kt`). Timings ("⚡ instant · 12 ms"), image timings, data used, model ids, "New context" and the token meter are hidden; human chips like "Memory updated" and "Heads-up · 14:02" stay. Brain › advanced › **Show technical details** (`show_tech_lines`, off) brings them back; stored messages are unchanged. A nearly-full context meter still shows.
- **Onboarding rings.** Setup steps show a ring that spins while a step is being set up and turns into a checkmark when it's done (`ui/SetupColumns.kt` `StepMark`), in first-run setup, the gate and Settings › Permissions.
- **Dependency setup gate for everyone, including upgrades** (`setup/SetupGate.kt`, `ui/SetupColumns.kt` `SetupGateScreen`). On launch Blaze checks what's missing: **required** Notifications and Terminal (Linux, counted done on CPUs it doesn't support); **optional** Phone control, Microphone, Show over other apps, Background battery and Pair with Blaze Desktop. Only the missing steps show, as columns; required ones block the app (Back doesn't skip them) and optional ones have **Later** (`setup_later`). When nothing is left `setup_deps_done` is set and the gate never runs again. Before it first runs, shared_prefs and Blaze's data files (chats, mind.db, longchat.db, vault, memories, skills; not Linux, voice models or caches) are copied to `filesDir/backup/pre-setup-v<versionCode>-<time>/` once per build (newest 3 kept, `setup_backup_v`).
- **Tests:** `PrivateChatTest` (no chat file, journal rows or index rows for a private chat; `Session.persist` writes nothing; tools filtered), `StatusNoteTest` (titles, priorities, update percent, Notifier state), `SetupGateTest` (an upgraded user with missing Linux / Desktop gets the gate; a complete user doesn't; Later only for optional steps; never again once done), `ReplyChipsTest`.
- Prefs: new keys only (`setup_deps_done`, `setup_later`, `setup_backup_v`, `show_tech_lines`, `update_last_check`); nothing removed or renamed. applicationId and signing unchanged.

## v0.24.3: core skills, specialists and workflows, every turn
Luke: make Blaze's answers and work better with strong built-in workflows and a character, and make it use them every turn.
- **Six core skills** (`assets/skills/blaze-*`, original text): `blaze-voice` (answer first, no filler, short texting-style messages, one pick with the reason, specifics over adjectives, match the mood, bad news first with a way forward, no faked feelings or facts, next offer only when the next step is yours, views over text walls, honest about being an AI), `blaze-task-workflow` (restate the goal, ask 1–2 short questions only when the answer changes the result, look it up first, cheapest tool first: memory → search/fetch → browser/phone, verify, retry differently and stop after two loops with options, confirm money/messages/deletions, deliver the thing, save preferences), `blaze-coding-workflow` (read first, minimal diffs, match the style, no invented APIs, build/test until green, protect data and secrets, logical commits, small runnable examples), `blaze-views` (the real views and their tools: Learn, Design, Schedule, Memory, Gallery, Projects, Skills, Browser, Archive), `blaze-specialists` and `blaze-workflow-builder`.
- **Always on** (`agent/CoreSkills.kt`): each core skill's "Pinned" rules go into the static part of the system prompt every turn (≈ 1,500 tokens, budget 6,400 chars, read from the shipped assets so an edit in ~/skills can't drop them). Core skills can't be switched off or deleted, come back if their folder is removed, and show first in Skills under **Core** (no switch; Restore original still resets your edits).
- **Specialists every turn** (`agent/SpecialistMatch.kt`): a keyword match (no model call) picks up to two specialists for the request; their skill packs are attached to the turn's skills, `delegate` is switched on, a hint says to keep small work and split big work into one tight brief per target, and the work card shows "Specialist: …". New built-in specialists **Shopper**, **Booker**, **Tutor** and **Designer** (never buy, pay or book; return options and a pick). Delegated specialists get their skill packs in their own prompt and can use browser / flashcards / design tools when their groups allow.
- **Workflows** (`save_workflow`, `SkillPacks.saveWorkflow`): a multi-step or recurring task is saved as a user skill with trigger, numbered steps, checks, tags and `tested:`; it's matched automatically next time. Skills view, ClaimGuard and the Learn rules know it; `write_skill` still works and core names can't be overwritten.
- **Tests:** `CoreSkillsTest` (core skills shipped, indexed and pinned within budget, per-turn matching, disabled exclusion, flashcards → Learn, workflow save + match, specialist matching and skill packs). Screenshot `18-skills` shows the Core group.
- No prefs or database changes (`skills_off` simply ignores core names). applicationId and signing unchanged.

## v0.24.1: a rounder composer with suggestions
Luke: the text box was still too square; he sent a reference (a dark rounded pill with + on the left, "Follow up" in light grey, a mic on the right).
- **Reference composer** (`ui/Shell.kt` `Composer`). A fully rounded pill, a fill one step off the background (lighter in dark mode, like the reference) with a thin 1 dp outline, a thin **+** on the left (attach and tools; turns into × with the menu open), and an outline **mic** on the right that becomes a round **send arrow** as soon as there's text (a stop square while Blaze works). Placeholder in light grey, regular weight, 16 sp. Long or multi-line messages keep the rounded ends.
- **Rotating placeholder** (`agent/ComposerHints.kt`, `ui/ComposerHintsSource.kt`). Every ~3 s the placeholder fades and slides to the next suggestion; it stops while the field is focused or has text, while listening, and when the phone's animations are off (reduced motion). Up to 8 suggestions, rebuilt each time Blaze comes to the front, only from what's on the phone: connected accounts ("Check my Gmail…", "Check my GitHub notifications…", "Play something on Spotify…"), launchable apps from the package manager ("Open WhatsApp…", "Play lo-fi on YouTube…"), birthdays in memory ("Wish Gloria happy birthday" when a person's remembered birthday is today), calendar events and reminders in the next 24 h ("Get me ready for NET103 quiz"; calendar only if access was already granted, never asked), and the newest project ("Pick up HomeGlow store…"). Generic fallbacks fill the rest. No network or model calls; nothing leaves the device.
- **Tests:** `ComposerHintsTest` (fallbacks, only accounts/apps you have, app matching, birthday parsing and today-only, next-24 h window, cap/dedupe/clean, recent topic). Screenshot `43-composer` (idle with hints, typing → send, working → stop, light and dark); every chat shot shows the new pill.
- No prefs or database changes. applicationId and signing unchanged.

## v0.24.0: messaging-style chat, /clear, no conversation switcher
Luke: "Let's add a /clear command, it clears the screen of all the messages… I want the conversations windows to not exist at all (the one that comes from holding the chat button at the top {chat|projects}) its only there to switch between chats and projects tab. Everything looks too uniform and boxed, I want it to look like a messaging platform just with our classic theme."
- **/clear** (`agent/ClearScreen.kt`, `Agent.clearScreen` / `undoClear`). Empties the visible thread so the chat starts empty (the greeting shows). Nothing is forgotten: the conversation's history stays in the chat file, the journal (`mind.db`, `search_chat`) and the long-chat index, and Blaze starts a new context window at that point (like Fresh start, without the bubble), so it looks older words up instead of answering from text you can't see. A floating **"Chat cleared · nothing is forgotten  UNDO"** bar puts everything back for ~12 s while nothing new was sent (no blocking dialog). Also in the slash list, `/help` and the + menu (**Clear screen**). Works in project threads too. While Blaze is working it says to stop first. The cleared state is saved, so messages don't come back on restart.
- **No conversation switcher.** The "Conversation" rail that opened from the Chat side of the Chat | Projects toggle, the Archive sheet (`ChatsSheet.kt`, deleted), the `chats` / Archive view, `/chats` and the chat list in Search are gone. The toggle only switches tabs. Search now finds views and offers **"Ask Blaze what you said about …"**, which searches every past conversation with `search_chat`. `/chats`, `/history` and `/archive` answer with a one-line note. Data is untouched: chat files, `mind.db`, `longchat.db`, projects and prefs load as before; the app opens the main conversation (or the project thread you were in); `/new` still gives a fresh context (a new thread in a project), and project threads open from Projects.
- **Messaging look, same classic theme** (`ui/Bubbles.kt`, new). Your messages: ink bubbles on the right. Blaze's: soft bubbles on the left (code and tables sit inset inside them). Consecutive messages from one side group 3 dp apart (16 dp between groups), with asymmetric corners: the sharp corner points at the sender on the first bubble, joined corners are tighter, the end rounds off. One quiet mono timestamp ("14:02", "Yesterday 09:05") with Copy · Quote · Retry closes each group instead of a row under every message. Errors are a Blaze bubble with an accent wash, follow-ups are rounded accent pills, the scroll-to-bottom button is round.
- **Composer**: a pill (+, the field, mic) with the round Send / Stop button attached at its end; mode chip, quote strip, attachments and slash suggestions are rounded and borderless.
- **Lighter cards, inline**: the work card (steps) is a soft rounded strip on Blaze's side (accent wash while live, no outline, round step marks); answer cards (code, tables, long lists, reports) are rounded insets with no border; the ask_user **QuestionCard** reads as Blaze's newest message (question bubble, answers as numbered pills, your own answer in a pill field with a round send) and still answers through `Agent.answerQuestion`; the confirm card matches. The Chat | Projects toggle, avatar, search and Exit chip are rounded; the brand mark stays square.
- Accessibility: Send / Stop / mic / + / search / avatar / jump-to-newest / undo have content descriptions; text keeps the theme's ink / paper contrast (accent text only on its soft wash or white on accent).
- **Tests:** `ClearScreenTest` (parsing, the new context window, undo rules, restore order, `/clear` listed and `/chats` gone), `BubblesTest` (grouping, pauses, corners, spacing, timestamps), `ViewsTest` / `ViewsSkillsTest` updated (no chat list view; Archive is search only). Screenshots `39-messaging`, `40-messaging-dark`, `41-clear-undo`, `42-question-inline`; chat shots 01–05, 08, 11, 25, 30–32 and 35 redrawn in the new look.
- No prefs or database changes. applicationId and signing unchanged.

## v0.23.6: BlazeRouter keeps your keys
- **Keys always save.** Adding a key in the BlazeRouter dashboard used to throw it away whenever the one-word test call failed. Now it's saved first, and the test result shows as a note ("Saved, but the test call failed: …").
- **Real reasons.** The catch-all "couldn't finish that one" message now includes the provider's actual reply (up to 160 characters), so a bad model, a plan limit or a region block is visible.

## v0.23.5: 3D designs survive bad objects
On Luke's phone a 3D design stopped with "Cannot read properties of null (reading 'morphAttributes')". The model had named a part's shape something the starter didn't know ("Cylinder", "nosecone", "CylinderGeometry"…), the starter's `geometry(p)` returned null, and three.js r170's `Mesh` constructor read `geometry.morphAttributes` and threw, so nothing drew; the native fallback drawing also skipped unknown shapes and showed an empty box.
- **Safety net for three.js** (`assets/design/three-safe.js`, new). Every served page's importmap now points `three` at `/three/three.safe.js` (or `/three/r162/three.safe.js`), which re-exports the bundled build and patches it: a Mesh / Line / Points made with a null geometry or material gets an empty one and is hidden instead of throwing, objects nulled later are fixed before each frame, `Box3` skips them, and a repeating render error is reported once. Files on disk are untouched.
- **Hardened 3D starter** (three-d-design `mockup.html`). Shape names are normalised (case, `THREE.` prefix, `Geometry` suffix, aliases like cube, tube, nosecone, dome, ring, pill), unknown names are guessed from the part's dimensions, numbers are checked, and one bad part can't stop the rest of the scene. A spec with no parts array still opens.
- **Same rules in Kotlin** (`Designs.normShape`, `normalizeSpec`, `shapeAliases`). The `design` tool, the served page (`DesignServe.prepareHtml` cleans the parts block) and the native drawing all agree on a part's shape, ids are unique and non-blank, and entries that aren't objects are dropped. Pages made from an older stock starter (no hand-written CUSTOM code) are served and updated from the current starter with the same parts (`Designs.upgradeStarter`).
- **Native drawing** (`ui/PartsSketch`). Unknown shapes are drawn as boxes, faces with NaN / infinite coordinates are skipped instead of breaking the fit, and when nothing can be drawn the view says "No drawable parts in this design yet" instead of showing an empty box.
- **CI** runs `node app/src/test/js/design-safe.test.mjs` before Gradle: it reproduces the test phone error with raw r170 and checks the safety net (r170 and r162) and the starter with a rocket full of bad parts.
- **Tests:** `DesignRenderTest` (shape aliases, `normalizeSpec`, upgrading stock starter pages, the sketch for a rocket with bad shape names and non-finite numbers, `prepareHtml` cleaning parts, `three.safe.js` served for both builds, importmaps pointing at the safety net).
- No prefs or database changes; existing `~/designs` files open unchanged. applicationId and signing unchanged.

## v0.23.4: Auto tries every free model, and remembers what failed
Luke: "auto routing is poor, it picks default models per provider and rotates among those; when they don't work or are rate limited I cannot talk anymore. Each provider has many models… Hopping between providers is fine, looking for working models, but make sure those that failed are not tried again until all models per provider have been tried." (NVIDIA NIM no longer lists the old default `meta/llama-3.3-70b-instruct`, so Auto's NIM entry just 404'd.)
- **Every model per provider** (`agent/ProviderCatalog.kt`). For each provider in the chain (Gemini, Groq, Cerebras, NVIDIA NIM, OpenRouter, Mistral, DeepSeek, Kimi, and keyless Pollinations) Blaze fetches the model list: OpenAI-compatible `/models`, Gemini's models API, OpenRouter's public list filtered to `:free` / zero price (expired ones dropped), Pollinations' public list. Only chat-capable text models are kept (embeddings, TTS, Whisper, guard / safety, image, rerank, reward, OCR/parse, translate, retired Groq models and deprecated Mistral models are dropped). Tool calling comes from the API where it says (OpenRouter `supported_parameters`, Mistral `capabilities`, Gemini vs Gemma, Pollinations `tools`), otherwise a small known list. Order: your chain pick, then the curated defaults, then the rest by family / size / "mini, coder, preview" penalties. A built-in default the provider no longer lists is dropped instead of 404ing every turn.
- **Cached for a day** in `filesDir/model_catalog.json` (new file), refreshed in the background when older than 24 h or when the key changes; offline or after a failed fetch the last list stays, then the defaults. A provider that was never listed is fetched once, mid-turn, when its defaults run out.
- **Failure memory** (`agent/ModelMemory.kt`, `filesDir/model_state.json`, new file; no prefs keys changed). Per model: ok, rate-limited until T (from retry-after / rate-limit headers via `Budget`), or failed with a reason. Cooldowns: retired / not found / decommissioned 24 h, 403 6 h, other 4xx 15 min, 5xx 2 min, timeouts 3 min. A rejected key (401) or an OpenRouter daily free cap rests the whole provider. "Can't use tools" is remembered for a day and only keeps the model out of tool turns.
- **Rotation** (`RoutePlan`). Providers take turns in chain order, each offering its next untried model, so no provider is hammered. A cooling model is never tried; a hard failure whose cooldown has passed goes to the back of its provider's queue until it works again; the model that last answered leads its provider. The tried set resets every turn; cooldowns carry over. Tool turns only get tool-capable models; chat turns can use any. Up to 10 calls (150 s) per turn; the next turn carries on with the next models. Two unreachable providers in a row stop the turn with "check your data or Wi-Fi" instead of marking every model failed.
- **Never stuck.** When every model is cooling, Auto waits once for the soonest (≤ 25 s, as before), then says plainly when the next one frees up ("All your free models are busy right now. The next one (Groq · …) frees up in 40s."). Keyless Pollinations stays the last resort for plain chat. The "Switch brain and continue" picker is unchanged.
- **Settings › Brains › Auto**: per provider, how many models Auto can use, how many are cooling, how many take tools and when the next frees up; tap a provider to see each model's state (worked, untried, limited / failed with time left and why). **Refresh models** re-lists every provider. The chain (provider order and starting model, ↑ / ×, "Use my free stack") still works as before.
- **Tests:** `AutoRouterTest` (catalogue filtering for NIM, Groq, OpenRouter, Gemini, Mistral, Pollinations; ordering and the "gemini isn't mini" case; defaults before the first fetch; cache surviving a failed refresh; 24 h staleness; round-robin across providers; tried-this-turn skip; failed-not-retried-until-exhausted and demotion after cooldown; cooldown expiry; provider-wide marks; tool-capable filtering; error classification; the friendly message). Screenshot `38-auto-pool`.
- applicationId and signing unchanged; keys and prefs untouched (the saved chain `free_chain` and `free_chain_removed` read as before).

## v0.23.3: 3D designs always render
On Luke's phone (Android 12, PowerVR GE8320) the design "simple-rocket" showed only its parts panel and, after 8 s, "The 3D engine didn't load, so the scene can't be drawn", on 4G. three.js never started (`window.__blazeScene` was never set). Three causes, all handled:
- **Designs load from a virtual https origin, not file://** (`ui/DesignServe.kt`, served by `ui/DesignView.serveDesign`). `https://appassets.androidplatform.net/designs/…` is `~/designs`, `/home/…` the rest of Blaze's home, `/three/…` the bundled three.js; the page's three.js importmap is rewritten to those same-origin URLs (with `text/javascript`), so module imports no longer cross from an opaque `null` origin to intercepted CDN responses. The CDN interception stays as a fallback (and for files outside Blaze's home, which still open as file://). **Open** still hands the real file to the browser.
- **WebGL fallback.** three.js r163+ needs WebGL 2, which Chromium's GPU blocklist often turns off on PowerVR Rogue GPUs. A small script injected first into every served page checks WebGL 2 and WebGL 1 before the renderer is made and picks the build: r170 with WebGL 2, **three.js r162** (newly bundled in `assets/design/three-r162`, the last release with WebGL 1, MIT) with only WebGL 1. If r170 fails anyway the view retries once with r162; if that fails or there's no WebGL at all, the page calls the `BlazeDesign` bridge and the Design view swaps to the native drawing of the same parts (`ui/PartsSketch`), which now turns with one finger and zooms with a pinch. Something always renders.
- **Ready, not a timer.** The page reports ready through the bridge once `window.__blazeScene` exists and a sample of the canvas shows it really drew (a blank canvas counts as a failure). The 8 s in-page timer is gone from the app; a 3D page now gets 20 s (the test phone parses three.js slowly), and an error before ready falls back after 2 s instead of waiting.
- **The real error, with Retry.** Errors are caught from `window.onerror` (including failed module and script loads), unhandled promise rejections and `WebChromeClient.onConsoleMessage` ("Failed to load module script", shader errors, WebGL context errors). The banner shows the actual text and a **Retry** button; the last errors are written to `~/designs/.last_error.txt` (removed when a design draws), and the `design` tool's create / update / open / read results mention that file when it's there, so Blaze can see what went wrong.
- New 3D pages carry a 20 s in-page message with the real error for browsers outside Blaze; v0.23.2 pages are upgraded on their next update and still open as they are.
- **Screenshot:** `37-design-3d-fallback` (the native drawing with the error banner and Retry).
- **Tests:** `DesignRenderTest` (virtual URL ↔ file mapping and path traversal, both three.js builds served and present, importmap rewriting for r170 / r162 and other libraries, the injected hook and the importmap chooser, hook syntax via node, engine choice and fallback order, retry URL, fatal console messages, the error file and the tool's note, upgrading v0.23.2 pages, sketch turn / zoom).
- No prefs or database changes; existing `~/designs` files open unchanged. applicationId and signing unchanged.

## v0.23.2: Design everything + no fake claims
Luke asked for a 3D rocket on his low-end phone and got "I'm on that tall modern 3D rocket… view it in the design section", then "I've updated the design to show the engine" and "I've opened the design view", with no design ever made, and replies from NVIDIA NIM's google/… models began with `<|channel>thought\n<channel|>`. His words: "Design is more than just for art, sites and that, like desktop it's also for 3d views and almost anything."
- **Design is for anything visual or interactive**, like Blaze Desktop's Design tab: 3D scenes and models, 2D art, sites and landing pages, UI mockups and app screens, diagrams, charts, posters, slides, floor plans, product mockups, simple games. A new **`design` tool** (`agent/Designs.kt`, always available) makes real files in `~/designs/<name>/index.html` and opens them in Design. **3D**: Blaze sends a parts list (box, cylinder, cone, sphere, torus, capsule, plane, group, with position, rotation, colour, material, parent, visible) and the page is generated from the three-d-design skill's starter (three.js, orbit controls, parts table, .glb export, `window.__blazeScene`, Desktop's `blaze-parts` contract), so the model never writes three.js by hand. Pages and everything 2D: `html` or `svg`.
- **Iterative edits change the same design.** The tool keeps a current design (`~/designs/current.json`); `update` merges 3D parts by id (`set_parts`, `remove_parts`, removing a group removes its children) or applies exact find/replace `edits` to pages; every update keeps the previous version (last 10) for `undo`; `read`, `open`, `list`. An update that changes nothing is an error.
- **Follow-on edits.** When the last turn made, changed or opened a design (or you're in the Design view, or a design was touched in the past 30 minutes), a short edit ("show engine", "add fins", "make it taller", "open it for me so I can see") routes to Design with a hint naming the current design and `design action=update` / `open`. Requests about other things (calendar, messages, weather…) are left alone.
- **Offline three.js.** three.js r170 (`three.module.min.js`, `OrbitControls`, `GLTFExporter`, MIT) is bundled in `assets/design/three`; the Design view serves CDN requests for them (jsDelivr, unpkg, cdnjs) from the APK, so 3D scenes open with no network. A page that still can't load the engine shows a short message instead of a blank screen.
- **Routing.** `ViewRouter` sends 3D, three.js, render, mockup, wireframe, prototype, diagram, flowchart, chart, infographic, floor plan, blueprint, UI/UX, app screen, scene and "make/build/show me a rocket, car, building, room, robot…" to Design (with picture words, "make a picture of a car" stays Gallery). The VIEWS prompt block, the Design hint, `open_view` and the Design view's empty state all say Design is for anything visual. `open_view view=design` with no target shows the current design instead of an empty preview.
- **No fake claims (every view).** `agent/ClaimGuard.kt`: a final reply that says it created, updated or opened something in a view ("I've updated the design", "view it in the design section", "Here's your updated 3D rocket", "I've made a deck", "I've set up a reminder"…) is checked against the tool calls that succeeded this turn (the view's tools, or `open_view` of that view). Without one, Blaze re-prompts once to do it for real; if it still isn't done, the claim sentences are replaced with an honest line ("I didn't manage to build or change it in Design this time… Say "try again""). Promises, questions, negations and past turns ("earlier", "yesterday") don't count.
- **Leaked reasoning tokens stripped** (`agent/ReplyClean.kt`, used by `ReplyParts.split`, so the streamed text, the stored message, speech and the history sent back all get it): Gemma-style `<|channel>thought…<channel|>`, gpt-oss harmony `<|channel|>analysis<|message|>…<|end|>` / `final`, `<think>…</think>` and a lone `</think>`, a bare `thought` line at the start, and template tokens (`<start_of_turn>model`, `<end_of_turn>`, `<|im_start|>assistant`, `<|im_end|>`, `<|eot_id|>`…), including with zero-width spaces inside. The reasoning goes to the thinking pane; half-typed tokens never flash while streaming.
- **Screenshot:** `36-design-3d` (a 3D rocket made by the design tool, drawn natively by `ui/PartsSketch` because Robolectric has no WebGL; the phone renders the same parts in three.js).
- **Tests:** `DesignEverythingTest` (3D / visual routing, Gallery and plain chat unchanged, follow-on edits, prompt and tool text, create → "show engine" → "make it taller" → remove group → undo on the same file, pages / svg / find-replace edits, unknown shapes, bundled three.js URLs, the native sketch, claim detection and backing per view, honest text that isn't a claim, nudge and honest line, Gemma / harmony / think / bare-lead / template-token stripping with the exact leaked strings, streaming).
- No prefs or database changes (designs are files in Blaze's home); applicationId and signing unchanged.

## v0.23.1: Linux works again after updates
- **Fix:** after an app update every Alpine/proot job failed with `CANNOT LINK EXECUTABLE ... libproot.so: library "libtalloc.so.2" not found` (seen on the test phone for the BlazeRouter job). `filesDir/linux/lib/libtalloc.so.2` is a symlink to the APK's native library folder, which Android moves on every update; the old link was left dangling and never replaced. `Linux.prepare()` now checks the link on every run and re-points it (copying the library if symlinks fail). No data, prefs or signing changes.

## v0.23.0: Views + skills
- **Blaze always uses its views.** A new always-present **VIEWS** block in the system prompt (`agent/ViewRouter.PROMPT`, in the cached prefix) lists every view (Learn, Schedule, Design, Gallery, Skills, Projects, Archive, Memory, Browser), what belongs there and the tool that writes to it, with a hard rule: output that fits a view is created there, the view is opened or named, and the chat gets one or two lines, never the whole thing as text. Fixes Blaze answering "make flashcards" with plain-text Q&A and saying it only uses views "when I use the specific tool to open them". SelfKnowledge's views section says the same.
- **Required view per turn.** A cheap keyword classifier (`ViewRouter.route`, no model call) reads each request ("flashcards", "quiz me", "study", "revise" → Learn; "remind me", "every morning" → Schedule; "landing page", "poster", "slides" → Design; "generate an image", "make a beat" → Gallery; "save this as a skill" → Skills; "new project" → Projects; "old chats" → Archive; "remember that" → Memory; "log in to <site>" → Browser). The turn gets a REQUIRED VIEW hint, that view's tool group is switched on, and the turn skips the tool-less chat lane.
- **Learn safety net.** On a Learn turn, a final reply with flashcard or quiz text (Q:/A:, Question/Answer, Front/Back, two-column tables, **Term** – definition) and no `flashcards` call is turned into a Learn deck (deck name taken from the request), the Learn view opens and the reply becomes one line. A "make flashcards" reply with nothing to convert gets one re-prompt to call `flashcards add`. Interactive "quiz me" (one question at a time) is left alone. The `flashcards` tool's description now says it creates Learn decks (deck + front/back cards).
- **Skills looked up on every turn.** Before each turn's first model call, `agent/SkillMatch` scores the request against bundled skills (assets/skills), the user's own (~/skills) and learned skills (skills.json) by name (×3), front-matter `tags:`/`keywords:` (×2, phrases ×3) and description (×1), with simple stemming. No extra model call; skills switched off in Settings › Skills never match. The top 1-3 are injected in full within ~6,000 characters (3,200 per skill), other matches by name; the work card shows **Skills: …** as its first step.
- **New bundled skill `study-flashcards`:** flashcards, quizzes and revision go to the Learn view (`flashcards add`, then `open_view view=learn`, a one-line reply).
- **Projects view.** `/views` and `open_view` now include **Projects** (opens the Projects tab).
- **Tests:** `ViewsSkillsTest` (view routing, plain chat has no view, every routed view exists and is in the prompt, Q/A / table / bold-term parsing, convert vs re-prompt vs nothing, deck names, converted cards land in a deck, skill matching by name / keywords / description, stemming, disabled-skill exclusion, top-3 within the budget, reading SKILL.md tags, the shipped skill wins for flashcards). Screenshot `35-views-skills`.
- No prefs, data or database changes; applicationId and signing unchanged.

## v0.22.0: Notification replies + Life logs
- **Notification replies.** The notification listener (`NotificationBrain`, reused) now reads every chat-app notification (WhatsApp, WhatsApp Business, SMS, Telegram, Messenger, Signal, Instagram, Discord… or anything posting a MessagingStyle / "msg" notification) with its MessagingStyle history: sender, conversation, group or not, and the user's own replies. Each conversation keeps a rolling log: the last 40 messages in memory and every message in the journal (`conv` `notif:<app>:<name>`, kind `notif`, role `<sender> (<app>)` or `You`), deduplicated because MessagingStyle repeats the history with every post. `notif_read` links senders to people memories and, when there are fewer than 4 messages, tells Blaze to ask (ask_user with draft options) or open the chat with phone control and read the screen before drafting. `notif_reply` sends through the notification's RemoteInput without opening the app; the confirm card always shows (even in Autopilot, `confirm(always = true)`) unless the user switched on auto-reply for that conversation in **Settings › Notification replies** (prefs `notif_auto`, a JSON list of conversation ids). *Keep chat logs* (prefs `notif_log`, on) and *Delete chat logs* live there too. Tools: `notif_conversations`, `notif_read`, `notif_reply` (the old `recent_notifications` / `reply_to_notification` stay).
- **Codes and bank messages are never kept** (`notify/Sensitive`): OTPs, verification / login / security codes, PINs, "don't share" messages, and bank or mobile-money alerts (debited, credited, balance, card ending, Airtel Money, MoMo, cash out…) are dropped before the log, the journal and memory, and show as "(a code or bank message, hidden)" in `recent_notifications`. A reply that contains one isn't sent.
- **Life logs.** "start logs", "live logs", "activate life logs", "stop logs", "pause logs" (instant quick rules, no model call), `/logs [on|off|pause|status]`, the `life_logs` tool, or **Settings › Life logs**. A microphone foreground service (`life/LifeLogService`) with a persistent notification (Pause / Resume, Stop) and Android's mic indicator; it never records silently and keeps no audio. Transcription is on the phone and free: Vosk with the small English model in `filesDir/wake-model` (the same model as "Hey Blaze", downloaded once with progress in the notification and Settings, never re-downloaded on updates), with a sound gate so Vosk only runs while there is voice-level sound; Android's SpeechRecognizer in a restart loop (with backoff) until the model is there. The wake word sleeps while logs run (one mic on a low-RAM phone); "stop logs" works by voice from the transcript.
- **Chunks, not audio.** `LogChunker` closes a chunk after 30 s of silence, on a topic cue ("anyway", "moving on", "next thing"…), or at 1,500 characters / 8 minutes, and drops recogniser noise ("the", "huh"). Each chunk goes into the journal as kind `life_log` (`conv` `life_log:<yyyy-MM-dd>`, role `heard`) with its time range and "may be other people, words may be wrong".
- **Learning from it.** The idle review now takes chat turns, chat-app messages and life logs in separate batches, each with a note on what it is reading; every memory learned from a life log is marked "(inferred)".
- **Brainstorm** ("brainstorm with me", `/brainstorm`, `life_logs brainstorm`): its own session (`brainstorm:<start ms>`); on stop one cheap free-chain call writes **Ideas / Decisions / Next steps** to the chat (and a notification when Blaze isn't open), a project memory and a decision memory per decision.
- **Stays out of the way:** pauses during phone calls, while another app takes the mic (Android 10+), while Blaze's own voice listens or speaks, and below 15% battery unless charging; resumes when the reason clears (a pause you chose waits for Resume). Settings › Life logs shows status, time logged today, saved chunks, the offline model and *Delete all life logs* (memories already learned stay in Memory). The first start shows a one-time explainer: it records nearby people too, so turn it off around anyone who hasn't agreed.
- **Journal `kind` column.** Added in place (`ALTER TABLE journal ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'`, no database version bump), so v0.21.0 and older still open `mind.db` after a downgrade. Desktop: same column and kinds if it adopts these logs.
- **Screenshots:** `26-life-logs`, `27-life-logs-paused` (dark), `28-replies`.
- **Tests:** `LifeLogsTest` (chunking by silence, topic and size, noise, spoken stop / pause, quick rules, brainstorm summary parsing), `ConversationsTest` (OTP and bank filtering, keys and titles, MessagingStyle dedupe, the rolling log), `MindLogsTest` (Robolectric: kind column, dedupe, delete, chat log in the journal without codes, people memories in `notif_read`, life logs reviewed separately and marked inferred).
## v0.21.0: Visible pointer for phone control
Luke's ask: see where Blaze is about to act, and take over in one touch.
- **The pointer** (`phone/Pointer.kt`): a small Blaze-blue arrow in an accessibility overlay (no extra permission). Before a tap Blaze glides it to the target (120–420 ms), re-checks that the target is still there and hasn't moved (the orient step: if it moved it aims again, if it went away nothing is tapped and Blaze looks again), shows a short ripple (held longer for a long press), then taps. Swipes are drawn as a path for a moment.
- **Touch to pause.** Touching the screen or the pointer pauses Blaze at once (manual mode, the pointer turns orange). Drag the pointer anywhere: dragging only moves it, it never clicks. **Double-tap the pointer** and Blaze clicks right there, looks at the screen and carries on with the task. A small bar at the top offers **Resume** (Blaze looks again first) and **Stop**. Blaze's own touches never count as yours.
- **Light on the test phone:** plain Views, no Compose in the overlay; it only animates while gliding or rippling, hides after 3 s idle and leaves the screen when the task ends.
- **Settings › Phone control › Show the pointer** (on by default).
- **Screenshot:** `34-pointer` (paused, with the bar, a ripple and a swipe path).
- **Tests:** `PointerTest` (glide and ripple timing, re-aim threshold, double-tap vs drag, own touches).

## v0.20.0: Orient loop + background browser with secure forms
Luke's ask after GitHub said "This account does not support password sign-in" while he typed into Blaze's browser, and phone control dropped on the way back to the chat.
- **Orient loop for phone control** (`agent/Orient.kt`): context → look → act → look, in code, not just the prompt. A phone-control turn starts with a compact screen summary (app, the fields and buttons that matter, any error line; ≤ 14 lines, never a full dump) sent with the first model call only. Before the first screen action Blaze glances (the compact summary goes in front of the result); a numbered tap or type on a screen Blaze hasn't looked at this turn, or after the app changed under it, is held back and the fresh screen returned instead (at most twice a turn, so it can't loop). After each action the diff re-read verifies the result as before. Accessibility tree first; screenshots only when the model asks.
- **Background browser that actually works offscreen.** Tabs that were never on screen used to lay out at 0×0, so browser_read saw no elements; they now get the phone's screen size offscreen. Page alerts and confirms in the background are answered and reported to Blaze instead of needing an Activity.
- **Secure forms in the chat** (`browser_secure_input`, `browser/SecureInput.kt`, `ui/SecureFormCard.kt`): when a page needs the user's email, phone, username + password, API key or one-time code, Blaze shows an *Action required* card with the right field types (passwords and keys masked with Show/Hide, email/phone/number keyboards). The browser fills the page from it and submits. The model only ever sees placeholders like `{{secret:password}}`; `browser_type` swaps them for the real value at the moment it types, page reads never show what's in a password or filled field, and any value that slips into tool output is scrubbed back to its placeholder. Card numbers are refused (use the live view).
- **Saved logins.** Values typed into the form are saved by default (untick to use once) in `filesDir/vault.json`, per site, each sealed with the Android Keystore key, and filled in next time on the same site without asking. One-time codes are never saved. **Settings › Saved logins** (also `/logins`, `/vault`) shows them masked and lets you edit or delete each one or a whole site. Excluded from device-to-device transfer (the key can't leave the phone).
- **"Action required" notification** (channel *Action required*, high importance) when Blaze isn't on screen; tapping it opens the chat waiting on the form. Android 13+ without notification permission: the card offers *Allow* (graceful; the low-end phone on Android 12 needs nothing). **Watch** on the card opens the live browser to watch or take over; background stays the default.
- **Sites that refuse passwords** (`SignInHints`): GitHub's "does not support password sign-in", passkey-only and SSO-only messages are spotted on every page read, click and submit. Blaze gets a note listing the page's other ways in (Continue with Google, passkey…) and is told to offer them with ask_user; the secure form won't ask for a password on such a page, and asks at most 3 times per site.
- **"Blaze lost phone control", fixed where it was ours.** A browser page whose renderer died (Android reclaims it on a 3 GB phone, or it crashes) took the whole app down, and Samsung then switches the accessibility service off; tabs are now reopened instead (`onRenderProcessGone`). The banner now says which way it dropped (*reconnecting* vs *off*), **Turn on** opens Blaze's own switch (Android 11+) instead of the whole list, with a one-line why and the battery tip. Connect/disconnect events go to the crash log. Phone-control-off results tell the model that browser tools don't need it.
- **Heads-ups no longer talk by themselves.** Reminders and heads-ups are read aloud only when *Speak up out loud* (`proactive_speak`) is on; urgent ones used to speak even with it off. Routine and reminder notifications don't call the voice anywhere else (routine turns are never voice turns).
- The ask_user question card is unchanged (now drawn by `QuestionView`, which screenshots reuse).
- **Screenshots:** `30-secure-form`, `31-secure-form-dark`, `32-no-password-choice` (with the phone-control banner), `33-saved-logins`.
- **Tests:** `OrientBrowserTest` (orient decisions and the no-loop guard, compact summary, field kinds, placeholders and scrubbing, vault round trip and masking, GitHub's no-password page, phone-control states, heads-ups speak only when on).
## v0.19.0: Visual parity
Blaze Desktop's look (`frontend/dist` in Blaze-dekstop: its Memory view, `style.css` pages, rows, cards and states), in the classic Blaze style: paper, ink, hairlines, one blue accent, square edges.
- **Memory graph.** Settings › Memory & learning (and `/memory`, /views › Memory) opens an animated graph of the memories table (`Mind.all()`), after Desktop's Memory view: square particle-dot nodes (a breathing 3×3 grid of dots when big enough, one square when small), hairline links between related memories, one cluster per kind with a `PERSON · 03` label and corner brackets, the newest memory sending out a slow blue pulse, a dot-grid stage, `+ − Fit` tools, mono labels for the most-linked memories without overlaps. Memories link when they share a subject (strongest), name each other in `related`, mention each other's subject, or came from the same turn; at most 8 links a node and the newest 400 nodes (the list has all).
- **Tap a dot** (or a row) for the detail card: kind, subject, the text (editable), kind chips, saved and updated dates, related, *Linked · 03* with why each link exists (tap to jump), **Save**, and **Forget** (asks for a second tap). Search highlights matches in the graph and filters the list; **Graph | List** remembers your choice (prefs `memory_view`). Edits go through `Mind.update` / `Mind.delete`, so the prefs mirror stays in step; on a phone without `mind.db` the old list still works. Two fingers pan and pinch, one finger scrolls the page, double tap fits.
- **Low CPU:** the force layout runs once up front (≤ 260 steps) and nothing is simulated per frame; the canvas only draws, at ~20 fps, and stops after 12 s without a touch, while the app isn't resumed (screen off, another app on top), and when the page closes. With *Background particles* off it stops after the reveal.
- **Desktop kit** (`ui/Kit.kt`): page header (accent eyebrow, 24sp title, lead), group heads, raised rows with numbers and tags, buttons (accent / ink / line / ghost), empty states with the particle-dot mark, search box with a focus ring, segmented control, square switch; Desktop's `--raised`, `--hair2` and `--accent-soft` as colour extensions.
- **Skills (Skill library):** header, search, *Yours / Bundled / Deleted* groups, numbered mono names with a one-line description, `UPDATE` / `EDITED` tags, a switch per skill, tap for the full description with Restore and Delete; an empty state.
- **Schedule (Routines):** header with **+ New**, numbered rows with the time as a tag and *Next Tue 14 Oct, 07:00 · Weekdays*, a switch, tap for what it does and Remove; the new-routine form in a card; an empty state with an action.
- **Settings:** Essentials and Advanced as grouped raised rows (Advanced opens in place).
- **Model picker** (Brain › Model): raised field with the current model in blue, search with a focus ring, one row per model with a `FREE` tag and a check on the current one.
- **Onboarding** (first-run setup): progress segments, step cards with a square check on the right that turn blue when done (no more "✓ " prefixes in titles).
- **Accounts:** header, one raised row per app with a `CONNECTED / CONNECT / SOON / OFFLINE` tag, Connect / Disconnect in place, an offline banner.
- **Empty states:** Memory, Skills, Schedule, Gallery, Learn decks, Projects and a project's chats use the same empty state.
- **Work card:** raised; a live card has a blue edge and a 3dp blue bar; open, each step has Desktop's mark (filled blue when done, a pulsing outline while running) and how long it took.
- **Screenshots:** `15-memory-graph`, `16-memory-detail` (dark), `17-memory-list`, `18-skills`, `19-schedule`, `20-settings`, `21-model-picker`, `22-onboarding`, `23-accounts`, `24-empty-states`, `25-work-card` (dark).
- **Tests:** `MemGraphTest` (links and their weights, the per-node cap, newest-400 clipping, the layout settles, stops and keeps kinds together, hit testing, empty and single graphs).

## v0.18.0: One mind
Mirrors Blaze Desktop's `one-mind` branch (`docs/plans/plan-one-mind.md` in Blaze-dekstop): same tables, same rules, same prompt text word for word.
- **One conversation.** Outside projects there are no separate chats: Blaze opens the main thread (`main`) every time. `+ › Fresh start` (and `/new`, `/clear`, the rail's button) starts a fresh context window on the same thread; nothing is deleted. Projects keep their own threads and *New chat* exactly as before.
- **Archive** (`/views › Archive`, the rail's *Archive →*, `/archive`): every chat from before, read-only (open one to read it; no edits, no delete). Search results that point at an old chat open it there.
- **Journal** (`filesDir/mind.db`, SQLite + FTS4): every turn of every conversation. On first launch every old chat is copied in once (`UNIQUE(conv, turn)`, so a re-run adds nothing; a meta flag is set only after it finishes, so a failure retries next launch). Chat files are never changed. `search_chat` now searches the whole journal (old chats included) and `read_turn` takes its ids (`j123`); the old per-chat ids still work.
- **Memories table** is the source of truth: id, kind (profile/preference/person/project/decision/fact), subject, text, related, source_turn, created, updated, with FTS. New tools `memory_search`, `memory_save` (same kind + subject and ≥ 0.5 word overlap rewrites the line: newest wins, no duplicates; identical text is a no-op; `inferred: true` adds "(inferred)"; secrets refused), `memory_update`. The old memory list is imported once and kept as a mirror (prefs `memory`), so backups and a downgrade still carry every fact; the Memory screen, `remember`, `recall_memory` and `forget` keep working on the table.
- **Prompt:** only the ONE MIND rule, the pinned core (profile lines, then the newest 12 preferences, ≤ 1,600 chars) and the memory index (`person: Gloria (3), Mutale (1)`). Everything else is looked up.
- **Save as you go:** after each real turn one cheap background call (shared extract prompt, last ~6,000 characters) writes or merges memories; the learning review now only looks for skills.
- **Idle review** (WorkManager `MindReviewWorker`): when the phone is idle and charging, or idle on Wi-Fi, Blaze works through unreviewed journal turns in chunks of ≤ 12,000 characters, writes or merges memories and marks them reviewed (a failed call leaves them queued). Free chain first, at most 3 chunks a run and once every 10 minutes, skipped while Blaze is working. Settings › Memory & learning › *Review past conversations when idle* (on by default) shows how many turns are waiting.
- **Context ladder:** (a) recent turns word for word while the history fits the budget (12k tokens or 60% of the context window, whichever is smaller); (b) the rolling summary + recent turns; (c) when the summary plus the tail would pass 90% of the window (or summarising failed): only the core, the index and the last 6 messages, plus "CONTEXT RESET … you don't remember; look it up".
- **Storage note:** Android's built-in SQLite (FTS4) through `SQLiteOpenHelper`, like `longchat.db`, rather than Room: no annotation processor in the build, same schema as Desktop.
- **Tests:** `MindTest` (dedupe, inferred marks, core and index, ladder thresholds, lookup tail, extraction parsing, chunking, shared prompt text), `MindDbTest` (Robolectric: migration idempotency, recall, dedupe and secrets in the database, retried turns, idle review marks turns and keeps them queued on failure).

## v0.17.0: Marketer & seller pack
Plan part D, for running and growing an online store (like a Shopify dropshipping store) and its social media. Blaze Desktop's `marketer-pack` branch didn't exist yet when this was built, so the six skills are written here in the shared SKILL.md format for Desktop to take as-is.
- **Six skills** (bundled, version-aware seeding; 41 in all): `content-calendar` (a week of posts in a mix of types, visuals in the brand kit, each post saved as a dated Schedule reminder to publish), `product-listing` (supplier URL from AliExpress, Zendrop, CJ and others → title, description, benefits, tags, SEO, variants, suggested price with margin, image prompts), `ad-lab` (5 angles, each scored on hook, clarity and CTA with a weighted total, best first, plus what to A/B test), `research-brief` (one-page competitor and pricing brief built with deep-research, only real prices with links), `whatsapp-replies` (customer replies in the brand voice, one approval per message), `weekly-digest` (orders and revenue from the store's order emails, top posts, next 3 posts).
- **Brand kit**: `brand_kit` tool (get / set only the given fields) and Settings › Seller (name, colours with swatches, fonts, voice). Stored in `~/.blaze/brand.json` with the same fields as Blaze Desktop's studio brand kit.
- **WhatsApp replies from the PC**: Blaze Link inbox requests with `kind: "message"` (`to`, `text`, `app`) are sent from the phone through `message_contact`, with no model call. The confirm card always shows ("Drafted on <PC>"), even in Autopilot or with a standing OK (`Agent.confirm(always = true)`); SMS isn't allowed from the PC. The PC gets back `Sent …` / `Not sent: …`. Protocol: [docs/blaze-link-messages.md](docs/blaze-link-messages.md).
- **Weekly store digest job**: Settings › Seller › *Weekly store digest* adds (or removes) the Schedule routine "Weekly store digest", Mondays 08:00, which runs the weekly-digest skill.
- **Seller view** (`/seller`, /views, aliases store, shop, marketing, brand). Plan mode allows `brand_kit get` and `flashcards list/due` (read-only).
- **Screenshots:** `14-seller`.
- **Tests:** `MarketerPackTest` (brand kit shape, merge and clearing; PC message parsing and outcomes; the always-confirm guard; skill hashes; digest schedule).

## v0.16.0: Student pack
Plan part C (Desktop's 1.0 plan, `docs/plans/plan-v1.0-redesign.md` in Blaze-dekstop). Desktop has no student-pack code yet, so these skills are written in the shared SKILL.md format for Desktop to reuse.
- **Google Classroom** (Google connector): `classroom_courses`, `classroom_coursework` (due dates in your time, turned in or not, overdue on request) and `classroom_sync` (every assignment that isn't turned in becomes a Schedule reminder 24 hours before it's due; safe to repeat). Read-only scopes `classroom.courses.readonly` + `classroom.coursework.me.readonly`, asked for only after you switch on **Settings › Learn › Google Classroom** and reconnect Google once, so nobody's existing Google connection changes. The Classroom API and those scopes must be enabled in Google Cloud project blaze-511220 (Testing mode).
- **Schedule items on a date.** Routines can now be a one-off on a date (`create_routine date=yyyy-MM-dd`; stored as `at`, older routines unchanged). Assignment and study-session reminders use it; Schedule shows the date.
- **Six skills** (bundled, seeded version-aware like the rest; 35 in all): `study-planner` (exam date + topics → day-by-day sessions in Schedule, re-plans a missed day), `tutor` (explain → quiz with score → flashcards), `lecture-notes` (free Whisper in Blaze's Linux → notes, summary, key terms, tasks), `essay-coach` (outline, scored feedback, APA 7 / Harvard citations; refuses to write graded essays and coaches instead), `group-project` (project brief, task list with owners, WhatsApp update text, sent only with your OK), `classroom-sync`.
- **Learn view** (`/learn`, /views, Settings › Learn; the old `learn` alias of Memory now opens it, `learning` still opens Memory): flashcard decks with due and new counts, review with Show answer and Again / Hard / Good / Easy (SM-2 spacing, the next interval shown on each button). Cards live in `~/learn/cards.json`. The `flashcards` tool lets Blaze add decks, list them and quiz you from due cards.
- **Low-data mode** (Settings › Learn): photos are described to text once instead of being re-sent with every step (text models only), screenshots are replaced by the text screen readout, the instant-picture shortcut is off (generate_image still works when asked), and each answer shows the data that task used (`↓↑ 0.42 MB`, Blaze's own traffic).
- **Screenshots:** `12-learn`, `13-flashcard` (dark).
- **Tests:** `StudentPackTest` (SM-2 intervals, deck store round trip, Classroom UTC dates and reminder times, coursework + submissions parsing, dated routines, skill hashes, data chip).

## v0.15.0: Long chats (Blaze never re-reads everything)
Port of Blaze Desktop's long-chat engine (`internal/longchat`, plan part B) to `agent/LongChat.kt`.
- **Rolling summary.** When the part of a chat that isn't summarised yet passes the budget (12k tokens on free models, or 60% of the model's context window when that is smaller), everything before the last 12 messages folds into a state note with one background call: Goal, Decisions (with why), Facts (names, numbers, paths exactly), Open threads, Files touched, at most ~800 tokens. The note is updated, never redone from scratch, and saved in the chat file (`lc_summary`, `lc_upto`; older chats simply start without one). If the summary call fails, the old sliding window still applies.
- **Project brief.** Each project has a pinned brief (purpose, rules, key files, in progress; up to ~600 tokens) sent with every turn of every chat in it. Edit it on the project page, or Blaze keeps it current with `update_brief`. Stored with the project in `projects_json` (old projects load with an empty brief).
- **search_chat / read_turn.** Every message goes into an SQLite FTS4 index (`filesDir/longchat.db`, Android's built-in SQLite; no Room annotation processor, same FTS engine). `search_chat {query, limit, all_chats}` returns exact snippets with turn ids, `read_turn {id}` returns one message in full. Chats that existed before this update are indexed the first time they're saved or searched. The prompt tells Blaze to search instead of asking you to repeat yourself.
- **Big outputs aside.** Tool results over 4,000 characters are saved to `~/.blaze/artifacts/<id>.txt` (same layout as Desktop); the model sees the first 40 and last 20 lines plus `artifact:<id>`, and `read_artifact {id, from, lines}` reads any part. Screen readouts, skills and the long-chat tools stay whole.
- **Token meter.** The work card shows `IN 4.2K · OUT 0.6K` for the last model call (provider usage when given, else an estimate). Past 80% of the model's context (32k when unknown), Blaze offers **Start a fresh chat with a summary**: a new chat in the same project that opens with this one's state note. Settings › Memory & learning › *Long chats* turns it all off.
- **Screenshot review fixes (v0.14.0 shots):** search results and chat lists no longer shift a pinned chat's title sideways (the pin mark moved to the right); the composer placeholder fits next to the mic on a 360dp phone (*Ask Blaze, or type /*, was cut to *Message Blaze, or t…*); the chat dims behind the + menu and /views so half-cut lines at the sheet's edge read as background (tap the dim area to close); the BlazeRouter page's *Classic editor* link no longer wraps its arrow onto a line of its own.
- **Screenshots:** new `09-router` (Settings › BlazeRouter), `10-project-brief`, `11-long-chat` (meter + fresh-chat offer, dark).
- **Tests:** `LongChatTest` (a 300-turn chat stays under budget, the summary keeps a seeded fact, tails never start on a tool result, artifacts read back, FTS queries are safe, brief round trip), `LongChatIndexTest` (search finds turn 5, re-index after a retry; Robolectric SQLite).

## v0.14.0: BlazeRouter on the phone
- **Blaze Desktop's router, on Android.** The same Go code (a port of OmniRoute, MIT; see THIRD_PARTY_NOTICES.md) is built by CI as `libblazerouter.so` and serves an OpenAI-compatible API plus its dashboard on 127.0.0.1:20128. It has every OmniRoute `auto/*` preset (auto/best-free, auto/fast, auto/best-chat…), category:tier ranking, chaos fan-out across providers, and last-known-good.
- **Runs where you choose:** *Auto* starts it inside Blaze's Alpine Linux (proot job), then Termux, then straight from Blaze. Data lives in `~/.blaze` (same layout as Desktop/CLI); your phone's model keys are handed to it at start, so nothing needs re-entering. Outside Alpine it uses public DNS (Android has no resolv.conf).
- **Settings › BlazeRouter** (also `/router`, the top-bar dot, avatar menu, + › Model): status, where to run it, Start/Stop, *Use as Blaze's brain* (auto/best-free), *Open dashboard* (in Blaze's browser, signed in), *Start with Blaze*, and a *Classic editor* link to the old Brain page (`/brain`), which is still there.
- **Blaze can drive it:** `blazerouter` tool (status, start, stop, use, dashboard), and about_blaze reports it.
- **CI:** vets and tests the router before building it.

## v0.13.0: Blaze knows itself
- **ABOUT YOU in every prompt:** what Blaze is (Android, Desktop and CLI, one Blaze account), this app's version, the paired PC and whether you're signed in, plus a rule: questions about Blaze, its apps or connecting phone and PC are answered, never bounced back with "what are you building?".
- **`about_blaze` tool:** a live report of every nook and cranny: the Blaze family and how to pair with Blaze Link, this app (mode, personality, projects), the phone, brains and which providers have keys (never the keys), Blaze account, connected apps, MCP servers, skills, memory and running things, files and folders, Alpine/Termux/dashboard, voice, every view and command, and the setup check. Ask for one section or everything. Allowed in Plan mode.
- **Tests:** `SelfKnowledgeTest`.

## v0.12.0: Calm shell like a messaging app (1.0 redesign, part A)
- **Top bar:** the B logo (back to the plain chat), a `Chat | Projects` switch, Search, the BlazeRouter dot (tap for models and keys) and your avatar. Tap *Chat* again for recent chats.
- **Avatar menu:** Account, Settings, BlazeRouter, All views, Phone control, Theme (tap flips light/dark, long-press follows the phone), Check for updates, Sign out.
- **Bubbles:** your messages on the right in an ink bubble; Blaze answers in plain text with no bubble, cards stay square hairline boxes.
- **One live work card per turn:** `● Working · 4 steps · 1m 12s ›`, tap to see each step; it settles to `Worked · 13 steps · 3m 11s` and the answer follows. Settings › *Show every step* adds the exact calls inside it.
- **Composer:** one bar with `+` and Send. The `+` menu holds Camera, Photos, Files, @ files, Paste image, Voice, Call Blaze, Plan/Build, Auto/Autopilot, Model, Views and New chat. The mode shows as a chip (`PLAN · AUTOPILOT`) only when it isn't the default. Typing `@` lists files in Blaze's home.
- **Nothing deleted: `/views`.** Every page that was in the sidebar (and Desktop's list) opens from one picker with a filter: Chats, Design, Memory, Jobs, Schedule, Agents, Accounts, BlazeRouter, Skills, Gallery, Browser, Doctor, Usage, Voice, Phone control, Proactive, Blaze Desktop, Commands, Dashboard, Blaze account, Settings. Shortcuts: `/design /jobs /schedule /accounts /doctor /router /skills /gallery /browser /search /projects` (plus the old `/memory /routines /settings /chats`). Search lists views too.
- **Blaze switches views itself:** new `open_view` tool. Visual work (a page, poster, design) opens **Design**, a live preview (phone or desktop width, reload, open in browser) with a box to ask for changes; other views ask first. A `DESIGN ✕ Exit` chip always returns to the chat.
- **Projects:** group chats around a goal with instructions Blaze follows in every chat inside it; `Working` and `Needs you` chips. Stored on the phone (`projects_json`), deleting a project keeps its chats.
- **Tests and screenshots:** `ViewsTest`; `ShotsTest` renders the shell on Robolectric (small-phone-sized) and CI pushes the PNGs to the `shots` branch.

## v0.11.0: Browser VPN by country, private tabs, Chrome-style tab grid
- **Browser VPN.** Import WireGuard configs (a `.conf`, several at once, or the `.zip` Proton/Mullvad download) in the browser's ⋯ › *VPN & privacy*, one per country, and pick a country. Only the browser goes through it: the tunnel runs inside Blaze (`wgproxy/`, user-space WireGuard on gVisor's netstack, shipped as `libwgproxy.so` and built by CI) as an HTTP proxy on 127.0.0.1 that the WebViews use via `ProxyController`. No Android VPN prompt; chats with your model don't use it. DNS goes through the tunnel too. After connecting, Blaze checks the real exit IP and country through the tunnel (Cloudflare trace) and fixes the country label if the file name guessed wrong. The flag shows next to the address bar.
- **Kill switch.** While the VPN is on, the browser never goes direct: if the tunnel fails to start or drops, pages fail to load (address bar shows !) until you reconnect or switch the VPN off. The VPN comes back on after a restart if it was on.
- **Private tabs.** Own WebView profile (separate cookies, storage, no cache), no history, wiped when the last private tab closes (and at start, in case Blaze was killed). Dark address bar. Phones with an old Android System WebView (no multi-profile) get history-free private tabs with a warning that cookies are shared.
- **Location masking** (on by default) in private tabs, and in every tab while the VPN is on: a script that runs before page scripts in every frame removes WebRTC (it would leak the real IP around the proxy), answers geolocation with the VPN country's capital (a few km of jitter) or denies it with no VPN, and reports that country's time zone (`Intl`, `Date` offsets, `toLocale*`) and language (`navigator.language(s)`). Not covered: the `Accept-Language` header, and time zone inside web workers.
- **Chrome-style tab switcher.** Full-screen two-column grid of rounded cards with favicon, title, close button and a page preview; normal / private switch at the top with counts; swipe a card sideways to close it; new tab, close all, Done.
- **Blaze can use it:** `browser_vpn` (status / connect to a country / disconnect) and `browser_open private=true`.
- **Tests:** `BrowserVpnTest` (config parsing, endpoint resolution, country guessing, mask script); Go tests run a real WireGuard handshake between two in-process peers and fetch through the proxy (plain HTTP and CONNECT). CI runs them before the APK build.

## v0.10.0: Builds Android apps, every Desktop skill, skills follow your account
- **Blaze builds Android apps** (skill `android-app-builder`, needs GitHub connected). It creates a private repo (`github_create_repo`), sets up a Kotlin + Compose project from the bundled template (`setup.sh`), writes the code, pushes, and lets GitHub Actions build it: every push builds a signed APK and publishes it as the repo's latest release. `github_ci` waits for the build and, when it fails, returns the failing step and the compiler errors so Blaze fixes and pushes again (up to 5 rounds). `github_install_apk` downloads the APK and opens Android's installer. Code: `connect/GitHubApps.kt`. CI now also builds an app from the template on every push (`app-template` job), so the template can't silently break.
- **GitHub `workflow` permission.** Pushing a new app's Actions file needs it; Blaze asks for it when connecting GitHub through blaze-auth 0.8.0 or newer. Already connected? Disconnect and reconnect GitHub once.
- **All of Desktop's skills.** Added browser-game, diagrams, floor-plan, motion-graphics, music-audio, paper-sketch, pixel-art, print-design, shell-sysadmin and three-d-design, adapted to the phone (previews through `preview_server`, tools installed in Alpine; 3D part picking and shell-sysadmin's host work stay on the PC). skill-creator gains Desktop's "combining skills" section (version 2). 29 bundled skills in all.
- **Skill sync.** Your own skills (learned by Blaze, written or installed by you; not the bundled library) follow your Blaze account to Desktop, CLI and other phones through blaze-auth `/v1/skills` (Worker 0.8.0): encrypted like model keys (`gzip` + AES-256-GCM, aad `blaze-skills-v1`, same per-account key), newest change per skill wins, deletions travel, `~/skills/.sync.json` tracks what was synced. Syncs at start (hourly), after sign-in, a few seconds after a skill is written, installed or deleted, and from Settings › Blaze account › *Sync skills now*. Code: `connect/SkillSync.kt`, `connect/SkillMerge.kt`. Until the Worker is updated it says sync isn't switched on yet.
- **Tests:** `SkillMergeTest`.

## v0.9.2: Plain words while working, no fake tool calls, swipe to reply
- **The status says what Blaze is doing.** "Thinking" and "Working" are gone from the chat, the top bar, the floating panel and calls. In their place is one plain line: "Reading your message", "Searching the web for …", "Going through the search results", "Writing the reply". New `Agent.status` flow, set from each step and between steps (`Friendly.between`).
- **Never a command on screen.** The model's narration is cleaned before it becomes a step (`Friendly.plain`): code blocks, `$ …` lines, inline code, tool names and links are removed, and a sentence that is mostly a command is dropped. Unknown tools read "Working on it" instead of their snake_case name. The folded steps view no longer shows the exact call under each step; that stays only with Settings › *Show every step*.
- **Fake tool calls are sent back.** A final reply that writes a tool call or shell command as text, uses agent syntax ("Action: …"), or claims "I ran / installed / sent …" when no tool ran this turn is returned to the model once with a nudge to call the tool for real or answer in plain language (`agent/FakeCall.kt`). Shell blocks stay allowed when the user asked for code or a command.
- **No flash of raw calls.** A reply that starts streaming as a tool call (`{"name": …`, `run_shell(`, `$ …`) is no longer shown live.
- **Swipe right to reply, like WhatsApp.** Grab any message (yours or Blaze's) and slide it right: a reply arrow fades in, the phone ticks past the threshold, and on release the message is quoted in the composer. Works while Blaze is busy too, so you can quote something to steer it. Tables and code blocks still scroll sideways first (`ui/SwipeToQuote.kt`).
- **Tests:** `NaturalStatusTest`.

## v0.9.1: Keys survive a phone move safely
- **Restored keys no longer fail silently.** API keys and sign-ins are encrypted with a Keystore key that never leaves the phone, so after an Android backup restore or device transfer they decrypted to nothing. At start Blaze now spots secrets from another phone, drops them along with their key-sync stamps (so the Blaze account's copy wins instead of being overwritten with nothing), and posts one heads-up: sign in to the Blaze account to sync model keys back, then reconnect accounts.
- **Encryption fallbacks are visible.** If the Keystore fails, `Secrets.encrypt` still keeps the key, but now logs it, and the self-check reports it under *Saved keys*.
- **Unencrypted links flagged.** Plain HTTP stays allowed for the on-device runner, OAuth loopback and gateways or MCP servers on your own network. The new self-check item *Unencrypted links* (`connect/UrlSafety.kt`) flags a custom model URL or MCP server on `http://` with a public host.
- **Fixes:** `key_rest` (resting marks) was treated as a secret and encrypted at every start, so the marks were lost on restart. `keys_extra_<pid>` (extra keys) wasn't treated as a secret, so Backup › Export wrote them as blobs no other phone could read.
- **Tests:** `UrlSafetyTest`, `SecretsPrefTest`.

## v0.9.0: MCP servers, the CLI/Desktop way
- **Same config as Blaze CLI and Desktop.** MCP servers are kept in the `~/.blaze/mcp.json` shape, `{"mcpServers": {"name": {"url": …, "headers": {…}}}}` (optional `"type": "http" | "sse"`, `"disabled": true`), stored encrypted on the phone. Paste an mcp.json in Settings › MCP servers to import it, or copy the phone's list out. Entries the phone can't run (local `command` / stdio servers) are kept and shown as needing a PC, so a list from your PC survives the round trip.
- **Synced through the Blaze account**: the list rides in the same encrypted `/v1/keys` blob as the model keys, as entry `mcp` (k = [mcp.json text], newest list wins). Older clients pass unknown entries through untouched.
- **Transports**: Streamable HTTP (sessions, SSE or JSON replies, batches, session-expiry retry) and the older HTTP+SSE transport (endpoint event, replies on the stream, server pings answered). Auto tries Streamable HTTP and falls back to SSE; URLs ending in `/sse` go straight to SSE. Headers carry bearer/OAuth access tokens or API keys. Tool lists are paged (`nextCursor`).
- **Tools named `mcp__<server>__<tool>`** (≤ 64 chars; a hash keeps long names unique). Legacy `mcp_…` names are still recognised.
- **Desktop's approval rules**: tools that declare `readOnlyHint` run without asking (and are the only MCP tools allowed in Plan mode); other tools show the confirm card; on their default they pass in Autopilot; `destructiveHint` tools always ask. Each tool can be set to Ask, Always or Off in Settings (tap a server).
- Servers saved by older builds (name + URL + bearer token) move into the new config automatically.
- **Unit tests** now run in CI (`testReleaseUnitTest`): config parsing and round trip, naming, approval, SSE parsing, JSON-RPC reply matching, result flattening and the sync merge.

## v0.8.0: Learns like Desktop, chats named by topic, heads-ups you can reply to
- **Proactive memory, the Desktop v0.7 way** (`agent/Learn.kt`, a port of Desktop's `internal/learn`). The prompt now carries the MEMORY & SKILLS rules: save lasting facts silently with their context, keep the user's certainty, replace a changed fact (`remember(fact, replaces=…)`) instead of piling up duplicates, forget every match on request, and write a skill when a method is taught or a workflow repeats.
- **Cheaper learning.** The background review used to run after nearly every answer. A local check with no model call (`Learn.decide`) now runs first: small talk, turns that already saved, and turns with nothing durable are skipped; a skill is only proposed when the user taught a method or a 5+ step workflow repeats (workflow signatures, last 40 kept). Only related memories go into the review prompt.
- **Safer memories.** Every auto-saved fact passes Desktop's filter (`Learn.accept`): no keys, passwords, PINs, codes or card/account numbers, nothing "only true today", no task logs. `remember` refuses secrets even when asked. Auto-written skills need a kebab name, a real description and at least 3 steps.
- **Chats named by topic** (`agent/Titles.kt`, port of Desktop `titles.go`). After the first exchange and every 4 turns after that, one small background call titles the chat by its dominant theme (≤ 6 words) and writes the one-line summary `search_past_chats` uses. Chats you rename are never retitled. Settings › Memory & learning › *Name chats by topic*.
- **Heads-ups you can reply to.** Tips, morning/evening briefs, routine and job alerts now land in one **Heads-ups** chat as they're posted. Replying from the notification (or tapping its suggestion) continues that chat, and the model is told every heads-up it sent since you last wrote plus the one you're answering, so "yes", "later" or "who's that?" make sense. Before, each reply opened a blank chat and the suggestion button ran in a separate hidden one. Notification replies skip the offline fast lane, which can't see that context.
- **5 more Desktop skills**: landing-page-builder, slide-deck, web-design-blaze-classic, web-design-teamofkeys and image-assets (adapted to the phone's Alpine Linux). They make HTML you can open with `preview_server`. Bundled skills can now ship extra files (templates, starters, design tokens) listed under `files` in `assets/skills/index.json`; read them with `skill_file`. shell-sysadmin stays desktop-only.
- **Version numbers match Desktop and CLI** (0.x); the updater compares build numbers (see Status).

## v1.17.0: Catching up with Blaze Desktop and CLI
- **Connectors are OAuth only** (like Desktop v0.5.0 / CLI v0.4.0): Google, GitHub, Spotify and Notion connect with one tap through blaze-auth. The browser signs in, the Worker swaps the code with its own client secret, and the tokens come back to a random 127.0.0.1 port through a one-time code bound to Blaze's PKCE verifier. Refreshes go through the Worker too (`/oauth/<id>/refresh`). The old manual setup (your own client ID, pasted tokens) is gone from the app; connections made that way keep working until you disconnect them. Providers the Worker hasn't switched on (Slack for now) show "coming soon".
- **Bundled skill library** (Desktop v0.7.0, CLI v0.5.0): 13 of Desktop's skills that fit a phone ship in the app (`assets/skills`) and are seeded into `~/skills`: deep research, email drafting, planning, documents, data analysis, API integrations, skill creator, code review, debugging, git/PR, tests, refactoring and web audits. Seeding is version-aware (`~/skills/.bundled.json`): upgrades only skills you haven't edited, never brings back one you deleted.
- **Settings › Skill library**: switch any skill off, delete it, or restore a bundled one. Switched-off skills leave the prompt and `use_skill` refuses them.
- **Leaner skill prompt** (like Desktop/CLI): with more than 12 skills, only the ones that match the request go in with their descriptions; the rest are listed by name.

## v1.16.0: Model keys follow your Blaze account
- API keys for the model providers (Gemini, Groq, Cerebras, OpenRouter, Mistral, NVIDIA, DeepSeek, Kimi, OpenAI, Claude, Grok), extra keys included, now sync with Blaze Desktop and Blaze CLI through the Blaze account: add a key on the PC and it works here, and the other way round.
- When: right after signing in, a few seconds after a key is added, changed or removed, and at start (at most hourly). Settings › Blaze account › **Sync keys now** shows the outcome.
- The keys are encrypted on the phone (AES-256-GCM, AAD `blaze-keys-v1`) before upload; blaze-auth (`GET/POST /v1/keys`, Worker 0.6.0) stores only the blob. Per provider the newest change wins; keys saved before syncing existed are merged with the account's, never dropped. Not end-to-end: the service hands the blob key to signed-in sessions.
- Connector tokens, the session and PC pairing never sync. Until the Worker with `/v1/keys` is deployed the app reports that sync isn't switched on yet and keeps keys local.
- Code: `connect/KeySync.kt` (same format and merge rules as `internal/keystore/sync.go` in Blaze-dekstop and Blaze-cli); change stamps `keysync_t_<provider>` in settings.

## v1.15.0: Blaze account (shared with Blaze Desktop)
- New Settings › Blaze account: sign in or create an account with email + password, or Continue with Google / GitHub, using the same blaze-auth service as Blaze Desktop (`https://blaze-auth.blaze-app.workers.dev`), so one account works on both.
- Google/GitHub sign-in runs in the browser with PKCE; the redirect comes back to a random `127.0.0.1` port and the one-time code is redeemed with the verifier. No client secret lives in the app.
- The session token is Keystore-encrypted (`conn_tok_blaze_session`); the user's name and email are cached for the screen. The session is re-checked at start and cleared if it was signed out or deleted elsewhere.
- Sign out (ends the session at the service too) and Delete account. Connected accounts, keys and chats on the phone stay.
- Code: `connect/BlazeAccount.kt`, `ui/AccountPage.kt`. Set `blaze_cloud_url` to point at a local Worker for testing.

## v1.14.4: Notification replies keep the conversation
- Replying from a Blaze notification now continues the chat that sent it, even if a different chat is open, instead of landing in whichever chat was on screen.
- Replying to a heads-up (proactive, routine or job alert) opens a new chat that starts with Blaze's message, and the model is told what it said, so the answer makes sense.
- If that chat is still working, the reply waits for it instead of being dropped.

## v1.14.3: First build on the rotated key
- The first build signed with the new key (v3 lineage from the old key). Install it once and from then on the phone only accepts Blaze builds signed with the new key.

## v1.14.2: Signing key rotation
- The original signing key is public (it was committed when the repo was private), so CI now signs with a new key kept only in GitHub Actions secrets (`BLAZE_SIGNING_P12`, `BLAZE_SIGNING_PASS`), using APK Signature Scheme v3 key rotation.
- Phones on older builds update normally. Once a phone is on a rotated build, Android refuses any APK signed only with the old key (the old key keeps installed-data but loses rollback, permission, shared-uid and auth).
- Android 8 (API 26–27) can't rotate keys, so it still verifies the old key.
- If the secrets are missing, CI stays on the old key and dry-runs the rotation with a throwaway key.

## v1.14.1: Hardening for the public repo
- Text and files shared into Blaze from other apps now wait in the message box for your Send instead of running on their own (any app can share, so it could carry instructions you didn't write).
- `/update` works without a GitHub connection (public repo; connecting only raises GitHub's rate limit). Before installing, the download is checked: it must be Blaze, newer than the installed build, and signed by the key the installed Blaze trusts (or a newer key in its lineage).
- CI: read-only token by default; publishing to the `apk` branch, releases and the failure issue only run on pushes to this repo's `main`, never on pull requests or forks; checkout doesn't keep the token.

## v1.14.0: Modes and background agents
- Composer modes (`agent/Modes.kt`, port of desktop modes.go). **Plan** = read and look only: the tool list is filtered and every other call is refused at dispatch (`Session.runTool`), the fast lane is skipped, and git is read-only (`planGit`). **Build** = full access (default). **Auto** = confirm cards (default). **Autopilot** = ordinary confirms pass, destructive ones (delete, wipe, uninstall, money, messages and calls in the user's name) keep the card; `ask_user` shows its card for 60 s, then Blaze decides itself; a reply that only asks a question gets "decide yourself" (max 8); runs capped at `autopilot_steps` (150) and `autopilot_minutes` (45). Prefs `work_mode`, `approval`. Switches: chips above the message box (`ModeBar`, pref `mode_bar`), `/plan [goal]`, `/build`, `/autopilot`, `/auto`.
- Background agents (`agent/BgAgents.kt`, port of desktop internal/agents + bgagents.go). Tools `start_agent`, `list_agents`, `check_agent`, `message_agent`, `cancel_agent` (core) and `/agent <task>`, `/agents`. Each agent runs in its own chat (`⚙ B1 · title`, IDs from Processes kind `agent` = B), 1 at a time (2 with 6 GB+ RAM), the rest queue. Live card in the chat (`ui/AgentCard.kt`): current step, time, steps, Steer / Stop / Open, Follow up when done. Confirms and questions from an agent are labelled with its ID. The result wakes the chat that started it (queued behind a turn in progress). State in `filesDir/bg_agents.json`; running agents resume after a restart. Agents can't start agents; an agent started in Plan stays Plan. Pref `bg_agents`.
- Also in this build (from the unmerged v1.12.1 branch): `call_control` (speaker, mute, end, answer, decline, hold, keypad, call volume for Blaze calls and phone calls), "let's get on a call" starts a Blaze call, clipboard image paste fix, background clipboard access (`phone/ClipAccess.kt`) with get_image / set_image / history.

## v1.13.0: Calm chat
- Plain steps: while working, one shimmering plain-English line ("Searching the web for …", `agent/Friendly.kt`); when done, "Worked for 12s · 5 steps", tap for each step and its exact call (`ui/CalmSteps.kt`). Setting "Show every step" (`show_steps`, off) brings back the full list.
- Answer cards: long tables, lists, code and reports fold into a clipped card with a fade; tap to expand, Copy gives the Markdown. Tables now render as grids. Setting "Fold long answers" (`fold_answers`, on). `ui/AnswerCards.kt`.
- Sun/moon button in the top bar: tap flips light/dark, long-press follows the phone again (`theme_mode`).
- `/update` and the `update_blaze` tool: newest `Blaze-v*.apk` from the `apk` branch (GitHub connector token), Checking → Downloading N% → Installing card, PackageInstaller install, plain errors with Retry (`update/Updater.kt`, `ui/UpdateCard.kt`).

## v1.12.0: Blaze Desktop pairing
- Settings › Blaze Desktop › Scan QR code (from Blaze Desktop › Phone), or paste the pairing link. Code in `link/DesktopLink.kt`, screen in `ui/DesktopPage.kt`.
- `ask_pc`: runs a Blaze turn on the paired computer (files, code, programs, installs); the PC's approval requests show as confirm cards here. Tool group `pc`.
- The other way: while Blaze runs, it long-polls the PC; Blaze Desktop's `phone_ask` requests run in a "From <PC>" chat and the answer goes back. Toggle: `pc_inbox`.
- Direct HTTPS to the PC (port 7377), pinned to the certificate fingerprint in the QR code; the token is stored encrypted (`conn_tok_pc`). Same Wi-Fi, or anywhere over Tailscale.

## v1.9.15: Batch 1, dev toolkit hardening
- `self_test` tool + Settings → Device & diagnostics → Run self-check: API key, internet, AI, terminal, Linux, git/GitHub, phone control, notifications, permissions, exact alarms, storage, voice, browser.
- `schedule_command`: cron for shell commands (time + days, or every N min ≥ 15), no AI involved, survives reboots, notifies always / on failure / never. Listed with routines.
- Terminal screen: session tabs (+ / ×), a read-only **Blaze** tab showing every command Blaze runs live, extra-keys row (ESC, CTRL, TAB completion, ↑↓ history, ←→, | ~ / - > & ; $ * "), Ctrl-C/D/L/Z/U.
- `preview_server`: opens http://127.0.0.1:PORT in the built-in browser; background jobs that print a port suggest it.
- In-app permission prompts (`device/Perms.kt` + transparent `PermissionActivity`) for location, contacts, calendar, SMS, call log.
- `add_contact`, `edit_contact`, `move_event`, `delete_event` (each asks for approval).
- Cleanup: `job_log` merged into `terminal_jobs id=N`; `agent/ToolGuide.kt` appends a "not for X, use Y" line to confusable tools; every tool call is guarded (crash → "Error in tool + Fix:", empty → explicit note).

## Tool map (v1.9.12–1.9.14)
- Terminal: `run_shell` (keeps cwd, `~/.blaze_env`, hints), `run_code` (python/node/sh, auto-installs), `file_read/write/edit/list/search/delete`, `job_input`, `terminal_jobs`, `terminal_stop`, `http_request`, `download_file`, `export_file`, `terminal_status`, `git` (connected GitHub), `termux_run`.
- Phone: `get_location`, Quick Settings tile toggles for Wi-Fi/Bluetooth/airplane/mobile data/DND.

## v1.9.6 (versionCode 17): process registry + steadier offline commands
- Process registry (`Processes.kt`): every alarm, timer, routine, trigger, calendar event (phone + Google) and background job gets a short ID (A3, R4, G2, E1, C1, J1). Tools: `processes` (one line per item) and `process(id, delete|pause|resume|rename|reschedule|details)`. Details are only read when asked. Alarms carry "(Blaze A3)" in their label so they can be found and deleted.
- Offline commands: politeness is stripped ("torch on thanks bro"), a looser one-setting rule catches "can u put the torch on" / "kill the wifi", and the fast lane's Needle 3 tools were redesigned per Cactus's guide (one tool per setting, on/off enum defaulting to on, regex triggers).
- "speed stats" and "what's running" work instantly and offline.

## v1.9.5 (versionCode 16): speed batch 1
- Tool diet: core tools + only the tool groups a request needs (`load_tools` pulls in more). Toggle: `tool_diet`.
- Cache-friendly system prompt: fixed text first, clock/memory/device last.
- Sliding history window (24 msgs, moves in blocks so the cached prefix stays stable). Setting: `history_window`.
- Free router: fastest healthy model first, Groq first on short turns, 8 s connect / 20 s first-chunk timeout.
- Connection pre-warm when a message is sent or the panel opens.
- Parallel lookups: several read-only tools in one step (search, fetch, mail, calendar…) run at once.
- Streaming voice: spoken replies start on the first sentence instead of after the whole answer. Toggle: `speak_streaming`.
- Speed readout: route chip shows "first word X s"; `blaze_self speed_stats` gives the median of the last 20 turns.

## v1.7 (versionCode 8)
- Auto (free routing): ordered chain of free models with cooldowns/health, keyless Pollinations chat fallback
- Fast lane: on-device Cactus Needle 3 (runner bundled as libneedle.so, weights downloaded to filesDir/needle) for direct commands, works offline
- Faster phone control: screen auto-returned after each action, settle-wait on accessibility events, do_actions batching, old screens pruned, optional steps model
- Connections: Google (Gmail/Calendar/Drive/Contacts, PKCE loopback), GitHub (device flow), Spotify (PKCE), Notion & Slack (token), remote MCP servers, share_to_app
- Learning: chat summaries + search_past_chats, auto memory, learned skills with task replay, approval mode, weekly curator
- Notification brain (read/reply via notification actions, memory-only) and opt-in screen memory
- Secrets encrypted with Android Keystore (AES-GCM)
