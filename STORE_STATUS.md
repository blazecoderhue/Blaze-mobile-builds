# 0.45.19 App Store: status (feat/store-tab)

Install destination A (user decision 2026-10-23 10:29 CAT): the user's own phone through Android's PackageInstaller.

## Flow (appstore/InstallPipeline.kt)
1. ConfirmGate card (always = true) BEFORE any download: "Open-source, not made by Blazeinchue. Install at your own risk.",
   license, source repo, and that the file is checked with SHA-256, signing key and VirusTotal.
2. "Install unknown apps" for Blaze: if not granted, Android's ACTION_MANAGE_UNKNOWN_APP_SOURCES screen opens; the job
   waits up to 90 s for the grant, else ends NEEDS_PERMISSION (nothing downloaded). The Store page shows an
   "Allow installs" banner until it is granted.
3. Resolve the official release (GitHub/GitLab API); the file URL must be https on github.com / gitlab.com / f-droid.org.
4. Download (partial file deleted on any failure/cancel; 600 MB cap; connections always disconnected).
5. SHA-256 (catalog per-asset > catalog > GitHub digest) → package name → catalog signer pin → for an installed app the
   APK must share a signing certificate with the installed package (rotation history counts) and have a higher
   versionCode. Any mismatch: BLOCKED, file deleted, nothing installed.
6. VirusTotal hash lookup: malicious blocks; not scanned / suspicious, or no checksum and no pin, needs an explicit yes.
7. PackageInstaller session (MODE_FULL_INSTALL, USER_ACTION_REQUIRED on Android 12+, PACKAGE_SOURCE_STORE on 13+);
   StoreInstallReceiver (exported=false, one PendingIntent per session) maps the status: PENDING_USER_ACTION starts
   Android's dialog (kept so the page can reopen it: "Open installer"), SUCCESS → package-manager check → DONE,
   ABORTED → CANCELLED, CONFLICT/INCOMPATIBLE → BLOCKED, others → FAILED. Unanswered for 10 min → session abandoned.
No Shizuku, ADB, root or silent path. Blaze Phone sandbox and "open release page" are not install targets (release
page is a fallback link on the expanded row only).

## Tools
store_search, store_info, store_install, store_update (list or update one), store_open (installed app, else source
repo), store_status. One job per app shared by the page and the tools.

## Catalog
store/catalog.json = app/src/main/assets/store/catalog.json, 442 apps. store/merge_seed.py carries the seed's
Blaze-computed signer pins / release hashes / asset rules (store/seed.json, 10 apps) onto the 6 seed apps present in
the 442 file (NewPipe, Thunderbird, Aegis, KeePassDX, Fossify Gallery, LibreTube). The other 436 apps have no signer
pin: their identity check is the hash (when the host publishes one) + package name + Android's own signer rules for
updates. Site should republish THIS catalog.json (it changed: seed facts merged, Thunderbird asset rule fixed).

## Needs a real phone
- Android's install dialog appears and Install/Cancel reach the receiver (Android 8, 12+, 14).
- Starting the dialog from the background (Android 10+ may block it; "Open installer" on the row is the fallback).
- "Install unknown apps" grant noticed after returning from Settings.
- Update of an app Blaze installed still shows Android's dialog (USER_ACTION_REQUIRED).
- Package visibility: installed detection relies on the manifest's LAUNCHER query (apps without a launcher screen
  won't show as installed).
