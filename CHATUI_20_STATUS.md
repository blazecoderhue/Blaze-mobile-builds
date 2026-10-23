# 0.45.20 chat UI (worktree rel/20-chatui, branch feat/20-chatui, base rel/0.45.19 a82f7aa + fix/19-chat 386ad2b)

Not bumped, not signed, not published, not merged into a release branch. gateway/ untouched.
Export: rel/export20chatui (clone of export19chat), script rel/sync-20chatui-export.sh, pushed to
blazecoderhue/Blaze-mobile-builds branch app/0.45.20-chatui; workflow_dispatch of .github/workflows/mobile-build.yml
(the export's workflow also runs ChatUi20ShotsTest in the shots job).

## A. Bubbles (new: ui/ChatBubbles.kt, ui/BubbleMenu.kt; hooks in Bubbles.kt and ChatScreen.Entry)
- ChatBubble: yours right, Blaze left; rounded (Bubbles.corners), a small tail (TailBubbleShape) on the first bubble of a run,
  max 80 % width (92 % for tables / code), time inside bottom-right (LocalBubbleTime, last bubble of a run only).
- Day chips ("Today", "Yesterday", weekday, "2 Mar 2025") between days (BubbleDays, DayChip, heading for TalkBack).
- Grouping unchanged (3 dp inside a run, 16 dp between), Halo and Blaze never share a group (19-chat).
- Frost: BubblePalette.fill = theme-aware base (Blaze grey / accent tint for yours, never green) at the least opacity
  (>= 0.80) that keeps the text and the time at >= 4.5:1 over pure black, pure white and the page. No backdrop blur
  (RenderEffect behind every bubble is too heavy for a Galaxy A12): the frost is the fill + a soft sheen.
- Action row: only under the latest Blaze reply when idle; every bubble has a long-press menu (Copy, Quote, Retry, React)
  via a gesture that never consumes taps/swipes (bubbleLongPress). Over selectable text the system selection wins.
- The ack is a normal Blaze bubble (it is a Role.BLAZE message). Code blocks, tables, error bubbles render inside bubbles;
  the work (tool) card, SecureForm and media stay their own cards in the thread.

## B. Delivery status (new: agent/Delivery.kt, ui/DeliveryStatus.kt; hooks in CloudRuns + Agent)
- Label under the latest user bubble only: animated dots + "Sending", "Sent", "Read", "Not sent, tap to retry". No ticks.
- client_msg_id per bubble from its gid (Delivery.Tracker, pref delivery_v1); reused for every retry of that send, a new
  generation only when an already-delivered message is asked again (Retry on a reply).
- Send loop (Delivery.deliver in CloudRuns.create): network error = stays Sending, retries 1, 2, 4, 8, 16, 30, 30 … s,
  at once on reconnect (Agent network callback -> Delivery.reconnected); 408 backs off; 429 obeys Retry-After (capped 10 min);
  other 4xx = Not sent (no auto retry, no error bubble: Delivery.NotSent caught in the agent loop); 401/403/404/405/413/5xx
  keep their old handling. Same body + id every attempt, so the gateway keeps one message / one run.
- Offline no longer forces the local loop (unless the Offline Brain is ready); the step time limit starts after the 2xx
  (CloudRuns.timedAfterSent) and the turn's hard end moves by the time spent waiting.
- Sent on 2xx / message_received; Read on message_read / read != null / a real reply. Never from a timer. Stop or an app
  restart while still Sending shows Not sent. TalkBack: polite live region with spoken text.

## C. Step pills (new: agent/StepPill.kt, ui/StepPillView.kt; hook in the chat's working row)
- One pill under the pending reply with the newest `step`; replaced by each new step; specialty steps always
  "Using a specialty" / "Updating a specialty" (the contract amended at 11:08 CAT says "specialty", never "specialist";
  that replaced the brief's "Bringing in a specialist" wording; kind `specialist` from older drafts is still accepted). Hidden on done/failed/cancelled/expired, needs_device and on
  the first delta; not shown when the reply lands within 2 s. Text re-checked on the phone (StepPill.clean).
- StepPills.current is a StateFlow<Pill?> for the Blaze Phone PiP.
- TODO (PiP): the PiP lives on fix/19-phui, which is not merged here. After merging it, add one line in the PiP:
  `val pill by com.blaze.assistant.agent.StepPills.current.collectAsState()` and show `pill?.text`.

## D. Assistants (agent/DuoAll.kt; hooks in Agent)
- @all chat: Blaze (the doer) answers first, streamed; Halo then gets Blaze's answer and adds only what is missing in at
  most two sentences, or `[[quiet]]`; a Halo line that repeats Blaze is dropped (word overlap >= 0.6). Not streamed, so a
  dropped repeat never flashes. Tool-shaped @all still goes to Blaze's full lane; the meeting room is unchanged.
- Voice: VoiceFiller strips an opening "On it." / "Sure, one sec." from what is spoken when no tool ran this turn (the
  streamed first sentence and the final reply); prompt line s("voice", VoiceFiller.RULE).

## Tests (strict 5/5)
Delivery20Test (10), StepPill20Test (7), DuoAll20Test (5), ChatUi20Test (5, contrast 3 themes x light/dark), ChatUi20ShotsTest
(3: 6 screenshots + TalkBack semantics; shots job only). Updated: CloudRunsTest.unreachableBeforeARunFallsBackForThisTurn
(contract: offline retries instead of falling back), GlassBubbleTest (bubbles are back).

## CI
See the final report (run ids, counts, screenshots path).
