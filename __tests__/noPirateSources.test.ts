// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Guards the strip: no torrent/magnet/provider-install/debrid/telemetry code may come back.
import fs from 'fs';
import path from 'path';

const ROOT = path.join(__dirname, '..');
const SCAN = ['src', 'native-src', 'plugins', 'app.config.js', 'index.js', 'package.json'];

function files(p: string): string[] {
  const full = path.join(ROOT, p);
  if (!fs.existsSync(full)) {
    return [];
  }
  if (fs.statSync(full).isFile()) {
    return [full];
  }
  return fs.readdirSync(full).flatMap(f => files(path.join(p, f)));
}

const BANNED = [
  /magnet:/i,
  /libtorrent/i,
  /\btorrent/i,
  /debrid/i,
  /firebase/i,
  /crashlytics/i,
  /analytics/i,
  /ExtensionManager|UpdateProviders|providerSandbox|manifest\.json["']?\s*\+/i,
  /cheerio/i,
  /byedpi|ciadpi|usque|\bwarp\b/i,
];

it('contains no torrent, scraper-install, debrid or telemetry code', () => {
  const hits: string[] = [];
  for (const f of SCAN.flatMap(files)) {
    if (!/\.(ts|tsx|js|kt|json)$/.test(f)) {
      continue;
    }
    // Comments may name what was removed (Apache §4(b) change notes); code may not.
    const body = fs
      .readFileSync(f, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/(^|[^:])\/\/.*$/gm, '$1');
    for (const re of BANNED) {
      if (re.test(body)) {
        hits.push(`${path.relative(ROOT, f)}: ${re}`);
      }
    }
  }
  expect(hits).toEqual([]);
});

it('ships no keystore', () => {
  expect(fs.existsSync(path.join(ROOT, 'debug.keystore'))).toBe(false);
});

it('does not pull the GPL-3.0 Jellyfin FFmpeg decoder through the react-native-video patch', () => {
  const dir = path.join(ROOT, 'patches');
  for (const f of fs.readdirSync(dir)) {
    const body = fs.readFileSync(path.join(dir, f), 'utf8');
    expect(body).not.toMatch(/org\.jellyfin|media3-ffmpeg-decoder|FfmpegLibrary\.isAvailable/);
  }
});
