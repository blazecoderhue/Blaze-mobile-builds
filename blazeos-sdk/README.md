# BlazeOS SDK

Native BlazeOS apps are WebAssembly modules that import BlazeOS's host calls from the `blazeos` module.

Package: a zip with `manifest.json` and the module:

```json
{ "id": "dev.you.game", "name": "Game", "version": "1.0.0", "versionCode": 1,
  "icon": { "glyph": "star", "from": "#5E5CE6", "to": "#2C2A8C" },
  "entry": { "type": "wasm", "path": "app.wasm" },
  "permissions": ["storage", "notifications"] }
```

Conventions: colours are ARGB in one i32 (0xFFRRGGBB); coordinates are f32 dp from the top left; strings are
(pointer, length) in the module's memory, UTF-8; text y is the baseline. Calls return 0 (or a length) on success and a
negative code on failure: -1 not found, -2 permission not in the manifest, -3 turned off by the user, -4 bad arguments,
-5 too big; 1 = the user is being asked.

## Imports (`blazeos`)

| Call | Signature | Permission |
| --- | --- | --- |
| clear | (argb i32) | |
| rect | (x f32, y f32, w f32, h f32, radius f32, argb i32) | |
| circle | (cx f32, cy f32, r f32, argb i32) | |
| line | (x1 f32, y1 f32, x2 f32, y2 f32, width f32, argb i32) | |
| text | (ptr i32, len i32, x f32, y f32, size f32, argb i32, align i32: 0 left, 1 centre, 2 right) | |
| image | (pathPtr i32, pathLen i32, x f32, y f32, w f32, h f32) -> i32 | |
| width, height | () -> f32 | |
| store_get | (keyPtr, keyLen, bufPtr, cap) -> length or code | storage |
| store_set | (keyPtr, keyLen, valPtr, valLen) -> code | storage |
| store_remove | (keyPtr, keyLen) -> code | storage |
| notify | (titlePtr, titleLen, bodyPtr, bodyLen) -> code | notifications |
| theme_dark | () -> i32 | |
| theme_accent | () -> i32 (argb) | |
| font_scale | () -> f32 | |
| now_ms, uptime_ms, random | () -> f64 | |
| log | (ptr, len) | |

## Exports (all optional but `memory`)

`memory`, `init(w f32, h f32)`, `resize(w, h)`, `frame(t_ms f64)` (draw the whole frame each time, up to 60 a second),
`on_touch(kind i32: 0 down, 1 move, 2 up, 3 cancel, x f32, y f32, pointer i32)`, `on_gesture(kind i32: 1 swipe, dx f32, dy f32)`,
`on_key(code i32, down i32)` (DOM-style codes: arrows 37-40, Enter 13, Space 32, Esc 27, A-Z 65-90, 0-9 48-57),
`on_back() -> i32` (1 = handled), `on_pause()`, `on_resume()`.

Limits: 16 MiB memory per app (8 MiB on low tier phones), 8 MB module, 2 MB store.

## Sample

`samples/snake`: Snake in WebAssembly text. `./build.sh` (needs `wat2wasm` from wabt) builds `app.wasm` and the package.
