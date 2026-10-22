---
name: android-app-builder
description: Build a real Android app (Kotlin + Jetpack Compose) from the phone and install it - creates a GitHub repo, writes the code, lets GitHub Actions build the APK, fixes build errors from the log, and installs the result. Use when the user asks to make, build or create an Android app, or to change one Blaze built before. Needs GitHub connected.
tags: [android, app, kotlin, compose, github, apk, ci]
version: 1
---

# Build an Android app

The phone can't run Android's build tools, so GitHub Actions builds every push and publishes the
APK as the repo's latest release; you write the code here and read the result back.
Tools: `github_create_repo`, `git`, `run_shell` (Linux), `file_write` / `file_edit` / `file_read`,
`github_ci`, `github_install_apk`. Work in Autopilot for anything bigger than a screen or two.

## Before you start

- GitHub must be connected (Settings › Connections). If `github_create_repo` isn't available, say so and stop.
- Ask at most one question, only if the idea is unclear: what the app does, and its name. Pick everything
  else yourself (package `com.<github-login>.<appname>` in lowercase, Material 3, light/dark).
- Changing an app Blaze built before: skip to step 3 with `git` action `clone` (it pulls if already cloned).

## Steps

1. **Repo.** `github_create_repo` with a kebab-case name and a one-line description (private unless the
   user asked for public). If it notes that GitHub Actions files can't be pushed, tell the user to reconnect
   GitHub in Settings › Connections, then continue.
2. **Project.** `git` action `clone` with the new repo, then in Linux:
   `sh ~/skills/android-app-builder/setup.sh ~/code/<repo> <package> "<App Name>"`.
   It copies the template (Gradle, Compose, the build workflow, a starter `MainActivity.kt`) and renames it.
3. **Plan the code** (short, in your head or `goal`): screens, state, storage. Keep it to what was asked.
4. **Write the code** with `file_write` under `~/code/<repo>/app/src/main/java/<package path>/`:
   - One `MainActivity` with `setContent { MaterialTheme { … } }`; split screens into composables, use
     `rememberSaveable` / a `ViewModel` (add `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6`) for state.
   - Storage: `SharedPreferences` or a JSON file in `filesDir` for small data. Avoid Room/Hilt/KSP unless
     needed: annotation processors are the most common build failure.
   - Permissions go in `AndroidManifest.xml`; ask at runtime with `rememberLauncherForActivityResult`.
   - New dependencies go in `app/build.gradle.kts` with exact versions that match Kotlin 2.0.20 / AGP 8.5.2
     / compileSdk 34. Only use Material icons from `material-icons-core` (Icons.Default.Add, Delete, Edit,
     Settings, Favorite, Home, Search, Check, Close, ArrowBack, MoreVert, Star, Info, Person, Share…),
     or add `material-icons-extended` for others.
   - Imports: write every import explicitly; no wildcard guessing.
5. **Push.** `git` action `commit` (clear message), then action `push`. If the push is rejected because the
   remote has a new commit (CI adds the signing key on the first build), `git` action `pull`, then push again.
6. **Build.** `github_ci` with the repo. It waits up to ~2 minutes per call; a first build takes 3-6
   minutes, so call it again while it says *still in progress*.
7. **Fix.** On failure it returns the failing step and the compiler errors with file and line. Read those
   files, fix the cause (not just the symptom), commit, push, and go back to 6. After 5 failed rounds,
   stop and tell the user plainly what's still failing.
8. **Install.** On success: `github_install_apk` with the repo. Android shows its installer; the user taps
   Install. If Android asks to allow Blaze to install apps, it opens that setting: ask the user to allow it,
   then run `github_install_apk` again.
9. **Report** in two or three plain sentences: what the app does, that it's installed, and the repo link.
   Offer one next improvement.

## Pitfalls

- Never edit `.github/workflows/build.yml` unless the user asks: it builds, signs and releases the app.
- `versionCode` comes from the build number, so every build installs over the last one. The signing key is
  made on the first build (`keystore/release.jks`); never delete it, or updates stop installing.
- Compose: `@Composable` functions only call other composables inside composition; use
  `LaunchedEffect` for side effects; `mutableStateOf` needs `import androidx.compose.runtime.getValue/setValue`
  for `by` delegation.
- `Unresolved reference` usually means a missing import or dependency; `Manifest merger failed` usually
  means a missing `android:exported` on an activity with an intent filter.
- Don't build on the phone (`./gradlew` won't run here). All builds happen in GitHub Actions.

## Checklist

- [ ] Repo created, template set up, package and app name renamed.
- [ ] Code pushed; `github_ci` succeeded.
- [ ] APK installed with `github_install_apk` (or the user was told what to tap).
- [ ] User got the repo link and one suggested next step.
