# Blaze Store catalog: schema v1

Served at **https://blazeinchue.pages.dev/store/catalog.json** (Blaze Site hosts it; seed copy in `store/catalog.json`,
bundled into the app as `app/src/main/assets/store/catalog.json` for first run / offline).

The app fetches it with `If-None-Match` (ETag) and keeps the last good copy on the phone: `304` keeps the cache, a
network error or a bad body keeps the cache (or the bundled seed), a `200` with a valid body replaces it. A body that
fails to parse never replaces a good cache. Refresh: on opening the Store (if older than 6 h) and with the daily
update check.

**Hosting notes for Site:** serve as `application/json`, with a strong or weak `ETag`, `Cache-Control: max-age=300`,
CORS not needed. Keep the body under 1 MB (the app refuses bigger). Bump `updated` on every change.

## Top level

| field | type | required | meaning |
|---|---|---|---|
| `schema` | int | yes | `1`. The app ignores catalogs with a higher major schema (keeps its cache) |
| `name` | string | no | display name ("Blaze Store") |
| `updated` | string | no | ISO date of the last edit |
| `categories` | array of `{id, name}` | no | order = chip order. Apps with an unknown category go under "Other" |
| `apps` | array of App | yes | the catalog |

## App

| field | type | required | meaning |
|---|---|---|---|
| `id` | string `[a-z0-9-]{1,40}` | yes | stable id (agent tools accept it, e.g. `store_install app=newpipe`) |
| `name` | string | yes | display name |
| `package` | string | yes | Android package name; installs whose APK says otherwise are refused |
| `summary` | string | yes | one line (cards) |
| `description` | string | no | details page text (plain text) |
| `icon` | https url | no | square PNG/JPEG/WebP, <= 4 MB (decoded small) |
| `category` | string | yes | a `categories[].id` |
| `website` | https url | no | |
| `license` | string | no | SPDX id |
| `tags` | array of string | no | extra search words |
| `source` | Source | yes | where releases live |
| `asset` | Asset | yes | which release file is the APK |
| `signer_sha256` | array of hex | no | SHA-256 of the APK signing certificate(s) (`apksigner verify --print-certs`). When set, an APK signed by any other key is refused |
| `releases` | map tag -> Release | no | known-good hashes per release tag |
| `min_sdk` | int | no | hide Install on older Android |

Entries missing a required field, or with a bad id/package, are skipped (the rest of the catalog still loads).

### Source

`{ "type": "github" | "gitlab", "repo": "owner/name" }`

- github: `GET https://api.github.com/repos/{repo}/releases?per_page=10`; the first release that is not a draft, not a
  prerelease (unless `asset.prerelease` is true) and has an asset matching the pattern wins. Asset url =
  `browser_download_url`, size = `size`, and GitHub's own `digest` (`sha256:...`) is used as the expected hash when the
  catalog has none for that tag.
- gitlab: `GET https://gitlab.com/api/v4/projects/{url-encoded repo}/releases?per_page=10`; `upcoming_release` skipped;
  assets are `assets.links[]` (`name`, `direct_asset_url` or `url`). No size or digest from GitLab.

### Asset

| field | type | meaning |
|---|---|---|
| `pattern` | regex (Java syntax, full match on the asset file name) | the universal / default APK |
| `abis` | map abi -> regex | per-ABI APKs; the phone's `Build.SUPPORTED_ABIS` order picks the first that matches, else `pattern` |
| `prerelease` | bool | allow prereleases (default false) |

Files ending in `.asc`, `.sha256`, `.sha256sum`, `.idsig` never match.

### Release (per tag)

| field | type | meaning |
|---|---|---|
| `sha256` | hex | SHA-256 of the APK for that tag (single-asset apps) |
| `sha256_by_asset` | map file name -> hex | for multi-ABI releases |
| `size` | int | bytes |
| `version_code` | int | informational |

Hash priority: catalog `sha256_by_asset[name]` > catalog `sha256` > host digest (GitHub). A mismatch blocks the install
and deletes the file. With no expected hash at all the details page says "no published checksum" and the signer pin
(when set) is the only identity check.

## Versions

"Update available" compares the release tag with the installed `versionName` by their numeric parts
(`THUNDERBIRD_24_0` = `24.0`, `2026.09.29-32-android` = `2026.09.29-32-Web`, `v0.29.1` = `0.29.1`). After download the
APK's own `versionCode` must be higher than the installed one for an update (equal = reinstall is refused).
