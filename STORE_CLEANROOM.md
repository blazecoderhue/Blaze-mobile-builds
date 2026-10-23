# Store tab: clean-room record

Blaze's Store tab (app/src/main/java/com/blaze/assistant/appstore/) is an original implementation. It was written from a
feature description only, without access to any other store client's source code.

## What was read (reference product: Orion Store, GPL-3.0)

Only these two documentation files, read on 2026-10-23 to learn the feature list, the UX and the flows:

- https://raw.githubusercontent.com/RookieEnough/Orion-Store/main/README.md
- https://raw.githubusercontent.com/RookieEnough/Orion-Store/main/ARCHITECTURE.md

Features taken from them as ideas (facts, not expression): a public JSON catalog; releases resolved from GitHub/GitLab;
ABI-aware APK choice; a download queue with progress notifications; batch updates; VirusTotal lookup before install;
install via the system installer, Shizuku, or wireless ADB; an installed/updates view.

## What was NOT read or used

- No Orion source file was opened, downloaded, grepped or copied: no .kt, .java, .xml, .gradle, .ts/.tsx, assets,
  screenshots' embedded code, workflows, or the Orion-Data catalog repository.
- No class names, identifiers, snippets, strings or resources from Orion are used. All code, names and strings were
  written for Blaze. The catalog schema (store/CATALOG_SCHEMA.md) is Blaze's own design; the seed catalog
  (store/catalog.json) was authored for Blaze from each app's own public GitHub/GitLab release metadata (asset names,
  sizes, SHA-256) and the APK signing certificates were computed by us with apksigner from the official release APKs.
- No ads, no XP/quests, no ad SDKs.

## Third-party code

- None. Installs use only Android's own PackageInstaller session API (user decision 2026-10-23 10:29): the user
  confirms every install in Android's dialog. No Shizuku, wireless ADB or root path exists in Blaze's code, and no
  GPL code is in the tree. The catalog (store/catalog.json) holds list facts only (names, packages, repos, licenses,
  asset-name rules) written by Blaze's own build script from each project's public release metadata.

Statement: no Orion Store source code was used in writing the Blaze Store tab.
