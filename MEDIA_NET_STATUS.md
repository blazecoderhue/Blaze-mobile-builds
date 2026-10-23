# Media that survives flaky networks (0.45.19 work, branch feat/media-net)

Base: 84984b3 (live 0.45.18 source). No version bump, no signing, no publish, no merge. gateway/ untouched.

## What exists today (discovery at 84984b3)

**Players.** media3 1.4.1 is already a dependency (`media3-exoplayer`, `-session`, `-ui`, `-transformer`, `-effect`;
`media3-datasource`/`-database` come in transitively). No `android.media.MediaPlayer` playback path.
- Music: `mediaapps/MusicService.kt`, a `MediaLibraryService` with one ExoPlayer, built with
  `StreamCache.factory(this, http)` + `StreamCache.loadControl(this)`, wake mode NETWORK. Store streams play through
  `MusicActivity.playStream` -> `MusicService.streamItem` (custom cache key = stable Track key). 403/410 is re-asked
  once (`retryStream`).
- Video: `mediaapps/VideoActivity.kt`, own ExoPlayer with the same factory/load control; `watch()` -> `load()`;
  split video+audio streams play as a `MergingMediaSource`. 403/410: refresh once, else replay from disk
  (`StreamCache.recall` + `hasStart`).
- `watch()` **blocks playback for up to 4 s** while it fetches the remembered subtitle language (`withTimeoutOrNull(4_000)`).

**Stream cache (Wave A 16)** `mediaapps/StreamCache.kt`: media3 `SimpleCache` (LRU, 256 MB / 128 MB on <=3.5 GB RAM,
<=10 % free disk, >=32 MB floor), `CacheDataSource` with `FLAG_IGNORE_CACHE_ON_ERROR`, buffer 20-50 s / 30-120 s by RAM,
`bufferForPlayback` 1.5 s, after rebuffer 3 s. Next song: first 1.5 MB prefetched (`prefetchNext()` on every item
transition) **on any network, under Data Saver too, with no quota cap**. Default load-error policy (a few retries,
then the player errors on a longer drop). No chunked range requests.

**Search (Wave A 17)** `mediaapps/MediaUi.kt` `StoreView` + `LiveSearch`: live search 350 ms after the last key, min
2 chars, links wait for Enter, a new search cancels the old (gen check), skeleton cards while the grid is empty,
sources searched in parallel with `async` **but results only land when every source has answered** (slowest source
sets the time to first result, up to the 15 s timeout). No recent-search memory, no per-card loading state (a card
with no thumbnail yet is a flat grey box). Preview sheet fetches `source.detail()` only after the tap.

**Entitlements** `connect/Entitlements.kt` (0.45.18): `GET /v1/entitlements` -> plan + monthly `downloads.song` /
`downloads.video` counters (`limit/used/remaining/unlimited`), cached in prefs, refreshed at app start, on the
Account page and after `POST /v1/downloads/consume`. Only Downloads uses it. Streaming/prefetch ignore it.

**Data Saver** `MediaPrefs.DATA_SAVER` (default on) + `MediaPrefs.saving(ctx)` = on AND metered. Used only to pick the
smallest stream (`VideoSmart.streamFor`) and in the preview sheet. Agent tools `music_ops` / `video_ops` can toggle it.
Agent media tools: `music_ops`, `video_ops`, `media_download`, `media_control` (ToolRouter).

**Tests touching this:** `WaveA4LiveSearchTest`, `WaveA5StreamCacheTest` (source-wiring greps + pure helpers),
`ShotsTest` renders `SkeletonTile`.

## What changed (feat/media-net, base 84984b3)

| commit | step |
|---|---|
| cd18997 | this file (discovery) |
| 811513a | 1/3 stream caching |
| 8d73e37 | 2/3 live search |
| 2686c86 | 3/3 faster first play |
| 72c8799 | device reads split into `MediaNetDevice` (MediaNet pure) + 2 test fixes from CI run 39917919875 |

**1. Stream caching** (`MediaNet.kt` pure rules, `MediaNetDevice.kt` phone/plan reads, `StreamCache.kt`)
- Read-ahead by link, re-read every 5 s (`ReadAheadControl` over DefaultLoadControl): Wi-Fi = RAM ceiling
  (50/120 s), good signal 60 s, weak 45 s, Data Saver 30 s, never under 30 s.
- Prefetch of the next queued song: Wi-Fi or good signal (>= 2 Mbps estimate) only; off under Data Saver (Blaze's,
  or Android's system Data Saver on a metered network); at most this month's song downloads left
  (`GET /v1/entitlements`, per-month ledger in prefs); a start already on disk isn't fetched or counted again.
  Prefetch does NOT call `/v1/downloads/consume` (it isn't a download) - judgement call.
- Cache cap = phone budget capped by the month's limits (songs x 10 MB + videos x 80 MB, floor 32 MB, uncapped or
  signed out = phone budget), applied live (`CappedEvictor`, `applyQuota` before each prefetch).
- Drops: `ResumingDataSource`/`NetReader` re-opens at the exact byte (Range) with back-off 0.25-2 s for up to 10 s
  inside the read; past that the player's `DropPolicy` retries every 1-5 s, 60 times, playing from buffer + disk
  cache. HTTP refusals (403/410) fail at once so the existing refresh path still runs.
- Video has no streamed queue (up-next is library files), so prefetch applies to the music queue only.

**2. Live search** (`MediaUi.kt`)
- Debounce 250 ms (was 350). Each source's answer lands as it arrives; cards on screen keep their place
  (0.45.18 waited for the slowest source, up to 15 s). One skeleton row under the results while sources answer.
- Per-card state: skeleton until it has a title, then text with a pulsing picture until the thumbnail lands
  (a failed thumbnail stops pulsing).
- Recent searches: last 8 per Store as chips while the box is empty (tap = search, Clear), saved on Enter/Search
  and on opening a result; result pages cached in memory 10 min (LRU 12) per query+chip+source -> instant.

**3. Faster fetch**
- Network ranges: 256 KB first, then 4 MB (range-ignoring servers fall back to one request). Start buffer 1.0 s
  (was 1.5 s; after a stall still 3 s).
- Video with a remembered subtitle: the stream's first chunk is fetched into the cache in parallel with the
  subtitle; the video starts after 700 ms at most (was up to 4 s) and a late subtitle is added when it lands
  (a short re-prepare from cache).
- Store: once results settle, the top results' details are fetched in parallel (Wi-Fi 2, good signal 1, never under
  Data Saver) into a 10-min cache that the preview sheet opens from.

## CI
Export `rel/exportmedia` (sync script `rel/sync-media-export.sh`: drops gateway/, .gitlab-ci.yml, HANDOFF*,
STATUS.md, docs/plans, replay tools, this file) -> blazecoderhue/Blaze-mobile-builds `app/0.45.19-media` c0a2bd7
(tree identical to 72c8799 minus the drops). Run **39918821177**: unit-tests green **2122 tests / 0 fail / 0 err /
179 skipped** (0.45.18: 2087); shots green; unsigned release APK (R8) green - all 3 jobs success. MediaNetTest 15/15, MediaNetCacheTest 5/5 (Robolectric, real media3
SimpleCache + CacheDataSource), MediaNetSearchTest 6/6, MediaNetFetchTest 9/9, WaveA4 3/3, WaveA5 3/3.
Run 39917919875 (first push): compiled, 2 test-only failures (non-void runBlocking test; wiring string) -> fixed.

## Drop mid-play (simulated, strict)
4-min 320 kbps song, 800 kbps link, 300 ms per request, read-ahead 45 s; 3-7 s drops at 5 different moments:
5/5 (one re-open each, 0 ms stall, bytes identical) with 0.45.18 single requests and 5/5 with the production
256 KB/4 MB ranges. 20 s drop: reader hands over after 10 s, player retries, 0 stall. 70 s drop with 30 s buffer:
one stall, recovers, bytes identical. Same drop with the reader off (0.45.18) reaches the player as an error.

## Time to first play (deterministic simulated network, from the Play tap)
| case | before (0.45.18) | after |
|---|---|---|
| audio stream: 320 kbps on 800 kbps, 300 ms RTT (real NetReader path) | 914 ms | 709 ms |
| video + remembered subtitle: 1 Mbps on 3 Mbps, 300 ms RTT, subtitle = 2 requests (link-sharing model) | 1516 ms | 700 ms |
Audio gain = start buffer 1.5 -> 1.0 s (the small first range doesn't change first-byte time on a server that
doesn't pace). Video gain = the subtitle no longer gates the start (worst case before: its 4 s timeout). These are
model numbers, not device measurements.

## Not proven yet (needs a real phone)
- Real network drops (airplane mode / Wi-Fi->LTE handover) with ExoPlayer + DefaultHttpDataSource: that a reset
  surfaces as an IOException the reader catches, and that Range re-opens work on YouTube/Archive/Jamendo CDNs.
- Whether 256 KB/4 MB ranges help or hurt on googlevideo (pacing / signed-url quirks) and battery/data overhead.
- `linkDownstreamBandwidthKbps` as the "good signal" test on real carriers; system Data Saver detection.
- Video subtitle added late: how visible the re-prepare blip is.
- Details warm-up: YouTube rate limits with 1-2 extra detail calls per settled search.
- Cache cap shrink/eviction and the prefetch ledger across a month boundary on device.
- Compose: recent-search chips, per-card skeletons and pending row on 360/375 dp (no new ShotsTest frames added).

## Plan (as started)
1. Stream caching: link-aware read-ahead, prefetch only on Wi-Fi / good signal, off under Data Saver, capped by the
   monthly quotas; short drops ride on the buffer + a resuming data source; long drops resume from cache.
2. Live search: 250 ms debounce, first results land per source, per-card skeleton until metadata+thumbnail arrive,
   recent searches cached.
3. Faster fetch: playback starts on the first chunk (small first range, low start buffer); metadata (subtitles,
   details) fetched in parallel, never in front of playback.
