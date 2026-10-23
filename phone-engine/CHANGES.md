# phone-engine: changes made by Blaze

Apache-2.0 §4(b): files modified relative to NewBlackbox 89b59836 (see UPSTREAM.md). Every change carries a `Blaze:` comment.

| File | Change |
| --- | --- |
| `build.gradle` → `build.gradle.kts` | Rewritten as a Kotlin-DSL library module (compileSdk 34, minSdk 26 like Blaze, Java 17). Material and AppCompat dropped (unused by the engine), toml4j dropped (unused). NDK build kept (`-z max-page-size=16384`, flexible page sizes); `-PphoneEngine.native=false` skips it on hosts without an NDK. |
| `consumer-rules.pro` | Merged with upstream `proguard-rules.pro` (deleted) into the keep rules the app's R8 build needs; `black.**` kept. |
| `compiler/build.gradle.kts` | Project path `:phone-engine:black-reflection`. |
| `src/main/AndroidManifest.xml` | Rewritten: 4 guest slots (:p0..:p3) instead of 50; all ~60 `uses-permission` / `uses-feature` removed (the engine adds no permission to Blaze); no `usesCleartextTraffic`; DaemonService (foreground) and ProxyVpnService removed; every stub non-exported except the BIND_JOB_SERVICE job services; stub activities resizeable + excludeFromRecents (they open on Blaze's VirtualDisplay). |
| `proxy/ProxyManifest.java` | `FREE_COUNT` 50 → 4 to match the manifest. |
| `BlackBoxCore.java` | Removed the log upload (`sendLogs` now a no-op that reports "disabled") and `startLogcat()` (it copied the whole device logcat to public Downloads). Added `inHostMainProcess()`: the static crash "fixes" and `installSystemHooks()` (which replace the default uncaught-exception handler) run only in engine processes, never in Blaze's main process. |
| `utils/LogSender.java` | Deleted (uploaded logs to a third-party server, logs-sender-api.vercel.app). |
| `app/configuration/ClientConfiguration.java` | `getLogSenderChatId()` returns null (upstream: its author's chat id). |
| `core/system/am/ActivityStack.java` | Reads the launch display from the intent (`GuestDisplays`) and starts new guest tasks with `ActivityOptions.setLaunchDisplayId` on Blaze's own VirtualDisplay. |
| `core/system/am/GuestDisplays.java` | New (Blaze): guest package → display id map. |
| `res/layout/activity_launcher.xml`, `res/values/styles.xml` | ConstraintLayout / AppCompat theme replaced by framework ones (no extra dependencies). Blaze never shows this splash (`isEnableLauncherActivity()` = false). |
| `res/drawable/ic_launcher.png` | Deleted (unused). |

No analytics, ads or update checks were found besides the log uploader above (grep for URLs / HttpURLConnection over the
whole module: the only network endpoint was logs-sender-api.vercel.app).
