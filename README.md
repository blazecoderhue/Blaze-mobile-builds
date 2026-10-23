# Blaze Player

Blaze Player is an Android media player for **licensed content only**:

* your own files and a private "locker" folder on your phone (Android Storage Access Framework),
* public-domain and Creative Commons feature films from the Internet Archive (official APIs),
* Chromecast casting for online items.

It has no ads, no analytics and no tracking, and it cannot install third-party sources.
Blaze (the assistant) can control it through a signature-protected agent interface, documented
in [AGENT_API.md](AGENT_API.md).

## Licence and origin

Blaze Player is a modified version of [Vega](https://github.com/vega-org/vega-app) at commit
`9ccc0c8`, licensed under the Apache License 2.0. Modified by Blazeinchue. See `LICENSE`,
`NOTICE` and `CHANGES.md`. Sources and their terms are in `SOURCES.md`.

## Build

```sh
npm ci
npm test
npx expo prebuild --platform android --no-install
cd android && ./gradlew :app:assembleRelease   # unsigned; signed outside the repo
```
