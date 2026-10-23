# Blaze Player agent API (v1)

Blaze Mobile controls Blaze Player **only over Android IPC**. No Player code is linked into Blaze.
Every entry point is exported and protected by:

```xml
<permission android:name="com.blaze.player.permission.AGENT"
            android:protectionLevel="signature" />
```

Only apps signed with the same key as Blaze Player (the Blaze release key, including its
rotation lineage) can hold it. The caller declares it:

```xml
<uses-permission android:name="com.blaze.player.permission.AGENT" />
<queries><package android:name="com.blaze.player" /></queries>
```

## Accepted targets (licensed sources only)

`play`, `cast`, `open` and `queue add` take **either** `uri` **or** `sourceId`:

| Form | Example | Notes |
|---|---|---|
| `sourceId = archive:<identifier>` | `archive:his_girl_friday` | Internet Archive feature film. The licence is checked again before playing |
| `sourceId = local:<content uri>` | `local:content://com.android.providers.media.documents/document/video%3A42` | A file the user already added to Blaze Player |
| `uri = content://…` | a SAF document uri | Must be passed as Intent data with `FLAG_GRANT_READ_URI_PERMISSION` (add `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` for SAF documents so the grant survives) |
| `uri = https://archive.org/details/<id>` (or `/download/<id>/…`, any `*.archive.org` host) | | Normalised to `archive:<id>` |

Anything else (other hosts, `http:`, `file:`, `magnet:`, …) is rejected with `E_SOURCE_NOT_ALLOWED`.

## 1. Intents

### open / play / cast: activity-alias `com.blaze.player/.AgentEntry`
The alias targets MainActivity and brings Blaze Player to the foreground.

| Action | Extras |
|---|---|
| `com.blaze.player.action.OPEN` | optional `screen` = `home`, `library`, `search`, `queue` or `settings`; or a target (uri/sourceId), which opens and plays it |
| `com.blaze.player.action.PLAY` | `uri` (as Intent data or extra) **or** `sourceId`; optional `title` |
| `com.blaze.player.action.CAST` | same as PLAY. Casts to the current Chromecast session; with no session the Cast device picker opens. https items only |

```kotlin
val i = Intent("com.blaze.player.action.PLAY")
    .setClassName("com.blaze.player", "com.blaze.player.AgentEntry")
    .putExtra("sourceId", "archive:his_girl_friday")
context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

// A user file:
Intent("com.blaze.player.action.PLAY")
    .setClassName("com.blaze.player", "com.blaze.player.AgentEntry")
    .setData(safUri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
```

MainActivity is also exported (for the launcher), but it acts only on intents that came through
the permission-protected `AgentEntry` alias.

### queue / search: activity `com.blaze.player/.AgentActivity` (invisible, `startActivityForResult`)

| Action | Extras | Result `json` on `RESULT_OK` |
|---|---|---|
| `com.blaze.player.action.QUEUE_ADD` | `uri` or `sourceId`, optional `title` | `{"size":N}` |
| `com.blaze.player.action.QUEUE_LIST` | none | `[MediaItem, …]` |
| `com.blaze.player.action.QUEUE_CLEAR` | none | `{"size":0}` |
| `com.blaze.player.action.SEARCH` | `query`, optional `sources` = `"local,archive"`, optional `limit` (1–100, default 20) | `[MediaItem, …]` |

On failure the result is `RESULT_CANCELED` with extras `errorCode` and `error`.

## 2. Bound service `com.blaze.player/.AgentService` (Messenger)

Bind with action `com.blaze.player.agent.BIND`:

```kotlin
val i = Intent("com.blaze.player.agent.BIND").setPackage("com.blaze.player")
context.bindService(i, conn, Context.BIND_AUTO_CREATE)   // add BIND_ALLOW_ACTIVITY_STARTS on API 34+
```

Send a `Message` with `what` set to one of the codes below, `replyTo` set to your Messenger, and
parameters in `msg.data` (Bundle). You get exactly one reply with the same `what`:
`arg1 = 0` (OK) and `data.getString("json")`, or `arg1 = 1` (ERR) and `data.getString("errorCode")` / `"error"`.

| what | Call | Bundle keys | Reply json |
|---|---|---|---|
| 0 | ping | none | `{"api":1,"app":"com.blaze.player"}` |
| 1 | open | `screen` or `uri`/`sourceId`, `title` | `{"accepted":true}` |
| 2 | play | `uri`/`sourceId`, `title` | `{"accepted":true}` |
| 3 | cast | `uri`/`sourceId`, `title` | `{"accepted":true}` |
| 4 | queue add | `uri`/`sourceId`, `title` | `{"size":N}` |
| 5 | queue list | none | `[MediaItem…]` |
| 6 | queue clear | none | `{"size":0}` |
| 7 | search | `query`, `sources`, `limit` | `[MediaItem…]` |

Notes:
* open, play and cast over the service park the command and bring Blaze Player to the front.
  Android may block activity starts from the background. On API 34+, bind with
  `BIND_ALLOW_ACTIVITY_STARTS` while Blaze is visible, or use the AgentEntry intent. A parked
  command runs the next time Blaze Player comes to the foreground.
* `content://` targets over the service need a grant Blaze Player has already persisted
  (`E_NO_URI_GRANT` otherwise). Send new user files through the AgentEntry intent with grant flags.

## MediaItem

```json
{"id":"archive:his_girl_friday","source":"archive","title":"His Girl Friday","year":"1940",
 "poster":"https://archive.org/services/img/his_girl_friday",
 "licenseUrl":"http://creativecommons.org/licenses/publicdomain/",
 "pageUrl":"https://archive.org/details/his_girl_friday"}
```
`source` is `local` or `archive`. Local items carry `uri` (content://).

## Error codes
`E_SOURCE_NOT_ALLOWED`, `E_BAD_URI`, `E_BAD_SOURCE_ID`, `E_MISSING_TARGET`, `E_NO_URI_GRANT`,
`E_UNKNOWN_ACTION`, `E_NETWORK`, `E_INTERNAL`. In-app (surfaced in the UI): `E_NOT_LICENSED`,
`E_NO_PLAYABLE_FILE`, `E_NO_CAST_SESSION`, `E_CAST_UNSUPPORTED`.

## Limits
Queue: 200 items, stored natively (SharedPreferences) and shared by the UI and the API.
Search: up to 100 results. Archive queries are reduced to plain keywords, so a query cannot
widen the licence filter.
