# Blaze Phone: speed + link resolution + sweep (fix/19-speed, from rel/0.45.19 a82f7aa)

Updated 2026-10-23 (CAT). Not bumped, not signed, not merged, gateway/ untouched.

## CI (public blazecoderhue/Blaze-mobile-builds, export via /workspace/work/rel/sync-19speed-export.sh → /workspace/work/rel/export19speed)
| run | branch / commit | result |
|---|---|---|
| 39926424909 | app/0.45.19-speed 22de7ce (worktree ed4ac2f: engine extracted, no behaviour change, harness) | green, 2213 tests / 0 fail; baseline numbers invalid for 2 cases (fixture had two `e5` ids) |
| 39928223696 | app/0.45.19-speed-base 9ca39a7 (same code + fixture fix) | green, 2213 / 0 fail: **the BEFORE numbers** |
| 39929362537 | app/0.45.19-speed f59dc8a (all fixes) | compile + APK OK; 2236 tests, 2 failures (a simulator bug: a change during a bridge flush was dropped) |
| 39930476614 | app/0.45.19-speed d5def3d (worktree 81c8049) | **unit tests 2236, 0 failures, 0 errors, 179 skipped; shots OK; APK see report** |

## 5) Speed: the DOM bridge
- `PhoneScripts.bridge(token)`: injected at document start (androidx.webkit `addDocumentStartJavaScript`; old WebViews: injected at page start/finish), main frame only. Keeps a compact model of visible + actionable elements (role, name, value, state, stable `eN` ids, `inView`, value length) and posts diffs (set / del / order / text / headings / focus / net / scroll) 30 ms after the first change: MutationObserver + input/change/focus/scroll/history events + fetch/XHR in-flight count. Token kept in the closure; secrets masked page-side and again in Kotlin.
- Channel: `WebViewCompat.addWebMessageListener` (main-frame only, `view === web`), else a `@JavascriptInterface` holding the WebView weakly. Both removed on drop / renderer crash.
- `DomCache` (Kotlin): applies diffs in seq order, ignores other tokens and the previous document's late messages, detects gaps → one `BRIDGE_FULL` call resyncs. Reads come from it: no evaluateJavascript, no screenshot.
- `PhoneEngine` (pure, over `PageHost`): event waits only. After an action: up to 400 ms for any sign (DOM change, navigation, request) → the load → 120 ms with no DOM change and no request in flight (cap 1.5 s after load). Typing waits for the focus and the value via the cache. `phone_wait` re-checks on each page signal. Locating an element is one page call (find + rect). Pages without the bridge fall back to the direct DOM read and the old timed waits.
- Screenshots only when the DOM read fails (`phone_read` attaches one, marked NOT VERIFIED).
- On the phone: `phone_status` now shows "Page reading: live DOM bridge (n elements cached)" and "Speed on this phone: page read p50 / p95, act to verified p50 / p95 (n)" (`PhonePerf`, user's approval time excluded).

### p50 / p95 (same harness: PhonePerfTest on SimPage, virtual clock + phone cost model, 40 runs per case, engine CPU ×3)
| | before (39928223696) | after (39930476614) |
|---|---|---|
| page read | p50 28.1 ms / p95 73.4 ms (120/120) | p50 3.1 ms / p95 12.4 ms (120/120) |
| act to verified | p50 784.2 ms / p95 2401.1 ms, **182/200 verified** | p50 702.7 ms / p95 2130.1 ms, **200/200 verified** |

Per case after (before): read login 0.5 (14.3), booking 3.1 (28.1), shop 12.0 (70.0); link→next page 1656 p50 40/40 (1439, 28/40: read mid-navigation), in-page button that fetches 703 / 913 40/40 (784, 34/40: read before the fetch rendered), type 182 (419), select 243 (687), password + Enter sign-in 1522 (1844).
Cost model (SimPage): evaluateJavascript RTT 4-12 ms; DOM scan 0.003/node + 0.05/candidate + 2-6 layout + innerText; JSON 0.08 ms/KB; navigation starts 30-300 ms after a tap, 400-2000 ms long; fetch 100-600 ms then render 20-80 ms. These are modelled, not device numbers.

## 8) Link resolution
- `SiteResolver`: history first (Blaze Phone's own visits, Blaze browser history, saved logins), "a kind of site" names ("my bank", "the booking site") from history only, else it asks; else web search (Web.search) with ads, look-alikes ("github-login.net", "paypal-help-center.support") and SEO spam dropped, official domain preferred, then the sub-page (login / sign up / orders / account / cart / checkout / bookings) from a same-site result, or the page-level step (open the site, tap its own "Log in" link; a button that may submit is not tapped).
- `phone_open` takes a URL or what the user said; prompt guidance in the tool RULE ("never guess or invent a URL; if it can't tell, ask").
- Strict cases (SiteResolverTest, each 5/5: 5 wordings, results rotated with an ad on top), all 5/5 on 39930476614:
  herokuapp login page → the-internet.herokuapp.com/login; the-internet login → same; the booking site (history) → automationintesting.online; my bank (history) → online.zanaco.co.zm/login; my bank with none known → asks; github login (look-alike, wikiHow, ad) → github.com/login; booking.com sign in → account.booking.com/sign-in; netflix login (SEO spam, reddit, ad) → netflix.com/login; amazon orders → amazon.com/gp/css/order-history; reddit sign up → reddit.com/register/; the-internet dropdown page → /dropdown; paypal → paypal.com; an address → opened as is (no search); github login used before → from history (no search); acme sign in → home + tap "Log in".
  Plus phone_open flow sims 5/5: sub-page step, asks instead of guessing, an address skips the resolver.

## 9) Sweep: found and fixed
1. Element ids were read from the page's own `data-bzp` attribute and pasted raw into scripts (type / value check / focus): a page could inject script or make Blaze tap the wrong element. Now ids are owned by a WeakMap (page-set or cloned ids get a fresh `eN`), every script JSON-quotes its arguments, and the engine accepts only `^e\d+$` ids.
2. Cloned nodes kept their `data-bzp` → duplicate ids → taps on the wrong element. Fixed (same WeakMap).
3. Load race: the previous page's late onPageFinished completed the new load's wait, and the fixed 250 ms didn't cover slow navigation starts → reads of the old / half-loaded page (12 of 40 link clicks in the baseline). Now a load we start only finishes after its own start (progress-100 fallback), plus event waits.
4. Lifetime: bridge listener / document-start script / JS interface removed when a WebView is dropped or its renderer dies; messages from a stale WebView ignored; the renderer-crash path also drops the activity context wrapper.
5. Raw call text: CallScan's seed list lacked the phone_* tools (a `phone_open(…)` written as text could flash before the first request); chat step lines showed raw ids ("Tapping e12") or raw action strings. Fixed. Tool errors are scrubbed of secrets.
6. BrowserData (history/bookmarks) is now locked: the resolver reads it off the main thread while the browser writes it.
7. Typing was paced 6 ms/char even with nobody watching; now only while the live view is watched.
8. Duplicate cookie flush per page load removed.

Tests: PhoneBridgeTest (13: DomCache diffs/rejects, cache reads with no page call, every action verified with no fixed sleeps, no-op click returns after the react window, phone_wait wakes on the change, resync with one call, no-bridge fallback, pacing, perf excludes approval time, page ids never trusted + quoting, no raw call text, bridge script shape), all 5/5. Page-side JS: tools/phone-bridge (jsdom, 21 checks, all pass).

## Needs the real phone
- That the bridge injects on the user's WebView (WEB_MESSAGE_LISTENER + DOCUMENT_START_SCRIPT; older WebViews take the JS-interface + late-inject path): `phone_status` must say "live DOM bridge (n elements cached)".
- Real p50/p95: after the device steps below, read the "Speed on this phone" line in `phone_status` (and compare with the old build if wanted).
- Focus/typing via real key events (focusin reaches the cache), network idle on real sites (long polls ignored after 5 s), bfcache back navigation, the renderer-crash path, per-site profiles with the bridge.
- Live search for the resolver (DuckDuckGo/Bing can block phones: Blaze then asks rather than guessing) and the sub-page tap on real sites.

### Device steps (each 5/5)
1. "open the-internet login on Blaze Phone" (no URL): lands on https://the-internet.herokuapp.com/login; reply shows the resolution line; Username/Password listed.
2. "log into herokuapp login page with my saved login": login flow as before (PHONE_BROWSER_STATUS step 3), SIGNED IN.
3. "open the booking site on Blaze Phone" after step 4 of PHONE_BROWSER_STATUS has been done once: opens automationintesting.online from history (no search).
4. "open my bank on Blaze Phone" with no bank used before: Blaze asks which bank; with one used before: opens it, reads only.
5. "github login on Blaze Phone": github.com/login, never github-login.* or an ad.
6. Booking flow (PHONE_BROWSER_STATUS step 4): the tile shows letters typed only while watched; "Booking Successful!" read back VERIFIED.
7. "what is Blaze Phone doing?": "live DOM bridge" + "Speed on this phone: page read p50 … act to verified p50 …".
