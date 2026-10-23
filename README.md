# Blaze for Android

AI assistant app for Android (Kotlin + Jetpack Compose), package `com.blaze.assistant`.
Chat, voice ("Hey Blaze"), floating orb, and phone control through an AccessibilityService.

## Build
1. Install Android Studio (or JDK 17 + Android SDK 34).
2. Open this folder, let Gradle sync.
3. Release APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
   Debug APK: `./gradlew assembleDebug`

Local and CI builds are signed with the Android SDK's own debug key. No signing key lives in this repo: releases are re-signed outside it with the private Blaze release key and a v3 lineage from the old key (rotated in 0.45.15; the old key is public and burned).

### YouTube plug-in (private, optional)
The YouTube Store plug-in (Gradle module `:youtube`) lives in the private repo
[blazeinchue/Blaze-mobile](https://github.com/blazeinchue/Blaze-mobile), not here. `youtube/` is in
`.gitignore`; Gradle includes `:youtube` only when `youtube/build.gradle.kts` exists, so without it Blaze builds as
normal with no YouTube Store.
- **Local dev with YouTube:** `git clone https://github.com/blazeinchue/Blaze-mobile.git youtube` in this folder
  (needs access), then `./gradlew :storeapi:test :youtube:test assembleRelease`. Commit plug-in changes inside `youtube/`
  (its own repo).
- **CI (historic, GitHub):** `build.yml` checked Blaze-mobile `main` out into `youtube/` with the Actions secret `BLAZE_MOBILE_TOKEN`
  (fine-grained PAT, Contents: read on Blaze-mobile). No secret (forks) or a failed checkout = the build carries on
  **without** YouTube and says so in a warning and the run summary. `build-without-youtube` keeps proving that build.
- **Daily check:** retired (0.45.18). Blaze-mobile keeps its own `sync.yml`.

## Layout
- `agent/` – Agent loop, LlmClient (OpenAI-compatible), Providers, Settings, Memory, ChatStore, Secrets (Keystore encryption), LongChat (rolling summary, FTS chat search, artifacts), Mind + MindReview (one mind: journal, memories, context ladder, idle review)
- `phone/` – BlazeAccessibilityService (read/tap/type), PhoneTools, Skills (SMS, calls, calendar, contacts)
- `ui/` – Compose screens (ChatScreen, SettingsSheet, Overlay orb, Theme, Kit: Desktop's page/rows/empty-state pieces, MemoryPage + MemGraph: the memory graph)
- `connect/` – Blaze account, OAuth connectors (incl. Google Classroom), key sync, MCP client
- `browser/` – built-in browser and its agent tools
- `terminal/` – shell, Alpine Linux (proot), git, background jobs
- `link/` – Blaze Desktop pairing
- `life/` – life logs: LifeLogService (on-device transcription, foreground, auto-pause), LogChunker, brainstorm summaries
- `voice/`, `routine/`, `widget/`, `notify/`, `assist/`, `proactive/`, `update/`

## Status
v0.34.0 (versionCode 135) is the current build on `main` (subtitles that reach the player, smarter Video / Music / Gallery, Camera Night + HDR and document crop, and a fuller Linux desktop: app drawer, curated app store, two-way clipboard, snapshots, restart by itself, screen sizes; see CHANGELOG.md). Before it, v0.30.4 (versionCode 120) (fast phone actions: settings pages opened straight by intent with `open_settings`, no skill packs for plain device actions, taps that match OEM labels and scroll to off-screen rows, failed scripts that resume instead of restarting, one screen read per batch of actions and only the newest screen in the prompt; storage figures that match Settings › Storage), on top of v0.30.3 (Blaze Camera Pro), v0.30.2 (terminal that always starts) and v0.30.1 (Blaze Music + Video); earlier, v0.28.6 (versionCode 102) (the camera works on every phone: + → Camera asks for the Camera permission and falls back from the camera app to Blaze's own camera to the photo picker, fixing "No camera app found." on the Galaxy A07; plus Blaze Camera with Scan, Text, Ask, Photo, Translate and Document modes, also opened by plain requests like "scan this document" or "escanea este documento"), on top of v0.28.5 (brief cap: a project's brief is capped at 600 tokens for real, and a project chat pins only the top of its instructions and brief (≤ ~600 tokens) while the full instructions, brief and older notes are searched in blaze.db with project_docs_search / project_docs_read), on top of v0.28.4 (blaze-docs: Blaze looks up its own docs (repos, versions, features, settings) in blaze.db instead of guessing; the work card shows the real run time), on top of v0.28.3 (project chats are their own place inside Projects, so main Chat is never replaced; one round back button across the app; old leaked tool syntax hidden), on top of v0.28.2 (security hardening: plain http only to this phone / the LAN / Tailscale, no file access in the Design view, chats and memory out of Google's cloud backup, confirm cards that can't be skipped, gateway CORS closed). Versioning rule (Blazeinchue 2026-10-13): bump patch only (v0.24.N+1) per release. CI (GitHub Actions) builds the signed release APK on every push and
publishes it as `Blaze-v<version>.apk` on the `apk` branch.

**Version numbers.** From v0.8.0 Blaze for Android uses the same 0.x numbering as Blaze Desktop and Blaze CLI. The builds
before it were numbered 1.0–1.17.0 (CHANGELOG.md keeps those numbers); `versionCode` keeps counting up, so phones
update normally. Because the name went down, CI also publishes the same APK as `Blaze-v1.999.<versionCode>.apk`:
1.x builds compare names and see it as newer, and v0.8.0+ reads the build number from it and compares `versionCode`.

## v0.30.4: Fast phone actions and true storage
Simple phone actions take a few steps instead of a long tap-by-tap loop. `open_settings(page, app)` jumps straight to a settings page by intent (an app's Open by default / notifications / permissions / battery / App info page, or Wi-Fi, Bluetooth, location, display, default apps…), so "stop GitHub opening links" is one call plus one tap. Plain device actions load no skill packs or specialists. A tap by text matches across skins (Samsung "Apps" vs Pixel "Apps & notifications", "Set as default" / "Opening links" / "Open by default"), never answers "Allow" with "Don't allow" or taps a destructive row by accident, and scrolls to rows that are off screen. A run_plan that stops at step k resumes from there. Several actions in one reply read the screen once, and only the newest screen goes to the model. "What's my storage" gives the Settings figures in decimal GB (used, free, used %, SD card). Code: `phone/SettingsRoute.kt`, `phone/SettingsJump.kt`, `phone/LabelMatch.kt`, `agent/FastActions.kt`, `device/StorageMath.kt`.

## Browser VPN that just works (v0.25.3)
The browser's VPN (⋯ › VPN & privacy, or the `browser_vpn` tool) works with one tap and nothing to import: **Blaze Free (Cloudflare WARP)**. The first time, the phone makes its own WireGuard keys and registers a free WARP device with Cloudflare (the way wgcf does), saves it privately and reuses it. Only the browser goes through it (wgproxy), it exits near you (hides your IP; can't pick a country), reconnects by itself when the tunnel drops, checks its health every 45 s, and tries WARP's other ports (2408, 500, 1701, 4500) when a network blocks one. The kill switch is unchanged. Imported per-country WireGuard configs work exactly as before. Logic and tests: `browser/WarpFree.kt`, `WarpFreeTest`.

## Terminal that always starts (v0.30.2)
The phone tab checks BusyBox once per build and starts on Android's own shell when BusyBox can't run, and a BusyBox tab that dies at once is swapped for one on Android's shell with a note, so it never opens as "[Process completed (signal 31)]". Linux tabs run Termux's PRoot 5.1.107.96, so `cd ~/phone`, `cd /sdcard` and every other cd work under Android's sandbox; `blaze-doctor` inside any Linux checks cd, getcwd, stat, the file tools and fork. Details: `tools/busybox/README.md`, `tools/proot/README.md`.

## Linux desktop (removed)
The on-phone XFCE/VNC Linux desktop (v0.25.1–v0.45.x: Desktop (Linux) page, `desktop` / `desktop_task` tools, BlazeOS Linux apps) was removed in wave B-4. Blaze's Alpine Linux terminal (run_shell linux=true, run_code, git) stays. After the update Blaze frees the desktop's packages, caches and settings once (terminal/LinuxDesktopCleanup.kt).

## Blaze docs (v0.28.4)
Blaze knows itself by looking it up, not from prompt text. At build time `BlazeDocsTask` (`app/build.gradle.kts`) writes `assets/blaze-docs/` from this repo: README, CHANGELOG (one file per version), STATUS, `docs/`, and `app/blaze-docs/about.md` (repos and exact URLs, releases, /update, tech stack, feature and settings maps, privacy, limits) filled in with the build's version, commit and SDK levels; secrets, account IDs and e-mail addresses are scrubbed. On the first launch after an update the app chunks it by heading into blaze.db (`blaze_docs` + FTS5 `blaze_docs_fts`, with a tool reference built from the real tool list) and the model uses `blaze_docs_search` / `blaze_docs_read`. The system prompt only gets one line and the repo URL. Edit `app/blaze-docs/about.md` when something it states changes.

## One mind
One continuous conversation instead of separate chats (projects keep their own threads; **Fresh start** clears the context, nothing is deleted). `filesDir/blaze.db` (the same schema as Desktop and CLI; before v0.26.3 it was `databases/mind.db`, kept as is after the upgrade) holds the **journal** (every turn, old chats imported once) and the **memories** table (profile, preference, person, project, decision, fact), both searchable. The prompt carries only a pinned core and a subject index; Blaze looks the rest up with `memory_search` / `search_chat`. Old chats stay on disk and in the journal (Blaze finds them with `search_chat`); since v0.24.0 there is no chat list or Archive screen, and `/clear` empties the screen without forgetting anything. Settings › Memory & learning › *Review past conversations when idle*. Shared schema and prompt text: `docs/plans/plan-one-mind.md` in Blaze-dekstop.

## Simpler day to day (v0.24.4)
- **Icon:** the spark blob (paper blob, accent spark) on the launcher, with a themed monochrome version; every notification uses a monochrome blob.
- **One status notification:** "Thinking…", "Browsing…", "Downloading update 42%" or "Needs you: …", with **Stop** (cancels the agent work and any update download). Life logs and the wake word keep their own microphone notifications; routines and terminal jobs keep their own foreground notifications.
- **Background updates:** a quiet daily check; downloads run in the background with Stop; one update card that disappears when the update finishes.
- **Private chat:** the ghost in the top bar opens a chat that's never saved or learned from ("Private" pill; leaving asks first and throws it away).
- **Settings:** Brain, Voice, Permissions, Account, Updates, About, Advanced (everything else). The BlazeRouter dashboard is gone; its prefs are kept.
- **Quieter replies:** timings, data used, model names and the token meter are hidden (Brain › advanced › *Show technical details*).
- **Setup gate:** on launch Blaze checks Notifications and the Terminal (Linux) (required) plus phone control, mic, overlay, battery and Blaze Desktop pairing (optional, *Later*); only missing ones show, as columns with rings that spin and turn into checkmarks. `setup_deps_done` stops it for good; prefs and data are backed up first to `filesDir/backup/pre-setup-v<code>-<time>/`.

## Messaging-style chat (v0.24.0)
The chat reads like a messaging app in Blaze's classic theme: your messages are ink bubbles on the right, Blaze's are soft bubbles on the left, runs from one side group tightly with asymmetric corners and one quiet timestamp, and the composer is a rounded pill (+, mic that turns into Send) whose placeholder rotates through on-device suggestions (your accounts, apps, birthdays, what's coming up). The top **Chat | Projects** toggle only switches tabs. `/clear` (or + › Clear screen) empties the screen, with Undo for a few seconds.

## Blaze Camera Pro (v0.30.3)
Blaze Camera has zoom presets (0.5 with an ultrawide, 1x, 2, 5, 10), a half-circle zoom dial you turn along the arc (log scale, haptic ticks at 1x / 2x / 5x / 10x), digital zoom past the lens's maximum up to 10x (preview scaled, photo cropped and sharpened), tap to focus with an exposure slider, a rule-of-thirds grid and a 3 s / 10 s timer (grid and timer remembered). Code: `device/CameraZoom.kt`, `ui/CameraPro.kt`, `ui/InAppCamera.kt`.

## Browser that remembers (v0.29.7)
Blaze Browser offers to save the passwords you sign in with (Keystore-encrypted, matched by site, never from private tabs) and offers them back on the next sign-in form; manage them under ⋯ › Saved passwords (fingerprint / PIN to show or edit). Normal tabs (with their back/forward history) come back after closing the browser or a restart, cookies persist, and ⋯ › Save page for offline keeps a page (web archive + text) under ⋯ › Saved pages. Code: `browser/BrowserLogins.kt`, `TabStore.kt`, `SavedPages.kt`, `SiteDomain.kt`.

## Background browser and phone control
Blaze's browser works in the background (offscreen tabs). When a page needs something only you have, an **Action required** card appears in the chat (and a notification if you're away); what you type goes straight into the page, the AI only sees `{{secret:…}}` placeholders, and it's saved per site in **Settings › Saved logins** (Keystore-encrypted; one-time codes never). Sites that refuse passwords get Google/passkey options instead. Phone control follows an orient loop: a compact look at the screen before acting and a check after each step. A visible pointer shows where Blaze is about to tap; touch the screen to pause, drag the pointer, double-tap it to click there (Settings › Phone control › Show the pointer).

## Memory graph
Settings › Memory & learning (or `/memory`): an animated graph of everything Blaze remembers, after Blaze Desktop's Memory view. Tap a dot to view, edit or forget a memory; Graph | List and search. It draws only while you look at it (≤ 20 fps, stops after 12 s idle or when the app is in the background).

## Notification replies
Settings › Notification replies: with notification access on, Blaze reads chat notifications (WhatsApp, SMS, Telegram, Messenger…) with their MessagingStyle history, keeps a rolling log per conversation in the journal (kind `notif`), links senders to people memories, and replies through the notification's own reply box. Every send shows a confirm card unless you switch on auto-reply for that conversation. Codes, PINs and bank / mobile-money messages are never stored. Tools: `notif_conversations`, `notif_read`, `notif_reply`.

## Life logs
Say "start logs" / "live logs" (or `/logs`, Settings › Life logs): a foreground service with a persistent notification (Pause, Stop) transcribes what the mic hears on the phone for free (Vosk small English model, shared with the wake word and downloaded once; Android's SpeechRecognizer until then). Only text is saved, as journal chunks (kind `life_log`) split by silence or topic; the idle review turns them into memories marked "(inferred)". "Brainstorm with me" (`/brainstorm`) writes ideas, decisions and next steps to the chat and memory when it stops. Pauses during calls, while another app records, while Blaze listens or speaks, and below 15% battery unless charging.

## Core skills and specialists
Six bundled **core skills** shape every turn and can't be switched off (they're listed first in Skills, marked "core"): `blaze-voice` (answer first, short and specific, one pick, warm and honest), `blaze-task-workflow` (goal → clarify only if it changes the result → look it up → plan with the cheapest tool → verify → deliver), `blaze-views` (output goes into its view), `blaze-specialists` (pick the specialist, split big work into parallel briefs, steer, merge), `blaze-workflow-builder` (save multi-step or recurring tasks as workflows with trigger, steps and checks via `save_workflow`) and `blaze-coding-workflow` (read first, small diffs, build and test, clean commits). Their "Pinned" rules go into the system prompt every turn (~1,500 tokens, from the shipped assets); the full skills are matched per turn like any other. Each request is also matched to a specialist (Shopper, Booker, Coder, Researcher, Tutor, Designer, Writer, Planner, Inbox, Phone operator or one you made): its skill packs are attached, `delegate` is offered, and "Specialist: …" shows on the work card. Code: `agent/CoreSkills.kt`, `agent/SpecialistMatch.kt`, `SkillPacks.saveWorkflow`.

Since v0.26.2 specialists work like a team you hand work to: `delegate` starts each brief as a background specialist with its own card (Steer / Stop) and returns at once, so the chat keeps going; each result drops into the chat as it lands and Blaze follows up on it. Up to three network specialists run at once even on 3–4 GB phones, while phone-control ones take turns. Every specialist works from a field playbook (principles, quality bar, best practices, common mistakes, a self-check it's reviewed against, output format; built-ins in `assets/specialists/`), and Blaze can add a new specialist itself with `create_specialist` when no one covers a field; its playbook has to pass a validator first (`agent/Playbooks.kt`, `agent/Dispatch.kt`).

## Views + skills
Blaze always uses its views: flashcards and quizzes go into a **Learn** deck, pages into **Design**, reminders into **Schedule**, pictures and beats into the **Gallery**, methods into **Skills**, facts into **Memory**; the view opens and the chat gets a short answer. Each request is matched to its view on the phone (no extra model call), and to the skills that fit it (bundled, your own and learned; switched-off skills are skipped); the best 1-3 are read in full and shown as "Skills: …" on the work card. Code: `agent/ViewRouter.kt`, `agent/SkillMatch.kt`. Blaze never claims it made, changed or opened something in a view unless that view's tool actually worked this turn (`agent/ClaimGuard.kt`), and leaked reasoning tokens from some free models are stripped from replies (`agent/ReplyClean.kt`).

## Design
**Visual answers** (v0.26.1, Settings › Visual answers, on by default): Blaze picks the format per question on its own, like ChatGPT's Intelligent UI. "How does X work" gets a Mermaid diagram plus a few sentences, comparisons a side-by-side compare card, numbers a native chart (bar, line, pie, scatter; tap for values), anything with an amount you can change a live mini tool (bill splitter, tip, loan, recipe scaler), choices tappable buttons and missing details a small form; simple facts stay plain text. Diagrams and charts asked for in chat are drawn inline in the reply, never as files or in Design unless you ask to save or export one. Code: `ui/RichReplies.kt`, `ui/RichKit.kt`, `ui/Expr.kt` (a tiny safe formula evaluator, no scripting), `ui/RichInteractive.kt`, `agent/VisualRoute.kt`. While a reasoning model thinks, the chat shows a calm "Thinking it through…" and then "Thought it through · 4 s"; the raw reasoning isn't shown.

Design is for anything visual or interactive, like Blaze Desktop's Design tab: 3D scenes and models (three.js with orbit controls, built from a parts list so you can say "show the engine" or "make it taller"), 2D art, sites, UI mockups, diagram and chart files, posters, slides, floor plans and product mockups (a `.mmd` Mermaid file previews as a picture, never as raw source). The `design` tool (`agent/Designs.kt`) writes `~/designs/<name>/index.html`, keeps the current design and its last 10 versions (undo), and opens the Design view; short follow-on edits change the same design. three.js is bundled in the APK, so 3D works offline. Designs load from a private https origin inside the app (`ui/DesignServe.kt`), so the page and three.js are same-origin; on phones whose WebView has WebGL 2 turned off (like some PowerVR GPUs) Blaze switches to three.js r162 on WebGL 1, and if WebGL is off altogether it draws the parts natively (drag to turn, pinch to zoom) with the real error and Retry, so a 3D design always shows something. The last render error is kept in `~/designs/.last_error.txt` for Blaze to read.

## Auto (free routing)
Settings › Brains › Auto spreads turns across every free model your providers offer, not one default per provider. Each provider's model list (OpenAI-compatible `/models`, Gemini's models API, OpenRouter's `:free` / zero-price models, Pollinations' public list) is cached in `filesDir/model_catalog.json` for 24 h and refreshed in the background; only chat-capable text models are kept, tool support is read from the API or a known list, and the curated defaults lead each provider. Providers take turns, each offering its next untried model; a model that failed or hit a limit is remembered in `filesDir/model_state.json` and isn't tried again until its cooldown passes (24 h for a retired model), and tool turns use tool-capable models only. When everything is cooling Auto waits once (≤ 25 s), then says when the next model frees up. The page shows per provider how many models are available and cooling, with **Refresh models**; the chain (provider order and starting model) is still yours to edit. Code: `agent/FreeRouter.kt`, `agent/ProviderCatalog.kt`, `agent/ModelMemory.kt`.

## Student pack
Settings › Learn (or `/learn`): flashcards with spaced repetition, Google Classroom (switch on, then reconnect Google once) and low-data mode. Skills: study-planner, tutor, lecture-notes, essay-coach, group-project, classroom-sync.

## Marketer & seller pack
Settings › Seller (or `/seller`): brand kit, the weekly store digest job, and WhatsApp replies drafted on Blaze Desktop and sent from the phone with your tap ([docs/blaze-link-messages.md](docs/blaze-link-messages.md)). Skills: content-calendar, product-listing, ad-lab, research-brief, whatsapp-replies, weekly-digest.

## Tests
`./gradlew testReleaseUnitTest` runs the JVM unit tests (`app/src/test`). CI runs them on every push and pull request.

## Changelog
What changed in each build: [CHANGELOG.md](CHANGELOG.md).

## Credits
BlazeRouter is a Go port of [OmniRoute](https://github.com/diegosouzapw/OmniRoute) (MIT); see THIRD_PARTY_NOTICES.md.

## License

Blaze is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE.md). You may read, run and modify it for noncommercial purposes; commercial use needs written permission from the author. Third-party components keep their own licences (THIRD_PARTY_NOTICES.md).
