// Blaze Player (Apache-2.0). Added by Blazeinchue.
jest.mock('react-native-google-cast', () => ({
  __esModule: true,
  default: {getSessionManager: () => ({getCurrentCastSession: async () => null}), showCastDialog: jest.fn()},
}));
jest.mock('react-native-mmkv-storage', () => ({
  MMKVLoader: class {
    withInstanceID() { return this; }
    withEncryption() { return this; }
    initialize() {
      const m = new Map();
      return {
        getString: (k: string) => m.get(k), setString: (k: string, v: string) => m.set(k, v),
        getBool: (k: string) => m.get(k), setBool: (k: string, v: boolean) => m.set(k, v),
        getInt: (k: string) => m.get(k), setInt: (k: string, v: number) => m.set(k, v),
        getArray: (k: string) => m.get(k), setArray: (k: string, v: unknown) => m.set(k, v),
        removeItem: (k: string) => m.delete(k), clearStore: () => m.clear(),
        getMap: (k: string) => m.get(k), setMap: (k: string, v: unknown) => m.set(k, v),
      };
    }
  },
}));

import {parseAgentCommand} from '../src/lib/agent/commands';
import {itemFromUri, fromIdOrUri} from '../src/lib/sources/registry';
import {queueOps, QUEUE_MAX} from '../src/lib/queue/queue';
import {canCast} from '../src/lib/cast/cast';

describe('agent commands', () => {
  it('parses open/play/cast and rejects everything else', () => {
    expect(parseAgentCommand('{"action":"open","screen":"library"}')).toEqual(
      expect.objectContaining({action: 'open', screen: 'library'}),
    );
    expect(parseAgentCommand('{"action":"play","sourceId":"archive:his_girl_friday"}')?.sourceId).toBe('archive:his_girl_friday');
    expect(parseAgentCommand('{"action":"play"}')).toBeNull();
    expect(parseAgentCommand('{"action":"install","uri":"x"}')).toBeNull();
    expect(parseAgentCommand('not json')).toBeNull();
    expect(parseAgentCommand('{"action":"open","screen":"evil"}')?.screen).toBeUndefined();
  });
});

describe('licensed-only uri allowlist', () => {
  it('accepts content:// and archive.org item uris', () => {
    expect(itemFromUri('content://com.android.providers.media.documents/document/video%3A12').source).toBe('local');
    expect(itemFromUri('https://archive.org/details/his_girl_friday').id).toBe('archive:his_girl_friday');
    expect(itemFromUri('https://ia800300.us.archive.org/download/his_girl_friday/his_girl_friday.mp4').id).toBe('archive:his_girl_friday');
  });

  it('rejects any other host, scheme or protocol', () => {
    for (const bad of [
      'https://example.com/movie.mp4',
      'http://archive.org/details/his_girl_friday',
      'https://archive.org.evil.com/details/x',
      'magnet:?xt=urn:btih:abc',
      'file:///sdcard/x.mp4',
      'ftp://archive.org/x',
    ]) {
      expect(() => itemFromUri(bad)).toThrow();
    }
  });

  it('resolves source ids', () => {
    expect(fromIdOrUri('archive:his_girl_friday').source).toBe('archive');
    expect(fromIdOrUri('local:content://x/y').uri).toBe('content://x/y');
  });
});

describe('queue', () => {
  const item = (n: number) => ({id: `archive:i${n}`, source: 'archive' as const, title: `T${n}`});
  it('adds, removes, clears and caps', () => {
    let q = queueOps.add([], [item(1), item(2)]);
    q = queueOps.remove(q, 0);
    expect(q.map(i => i.id)).toEqual(['archive:i2']);
    expect(queueOps.clear()).toEqual([]);
    const many = Array.from({length: QUEUE_MAX + 5}, (_, i) => item(i));
    expect(queueOps.add([], many)).toHaveLength(QUEUE_MAX);
  });
});

describe('cast', () => {
  it('casts only online items', () => {
    expect(canCast({id: 'a', source: 'archive', title: 'a', uri: 'https://archive.org/download/a/a.mp4'})).toBe(true);
    expect(canCast({id: 'b', source: 'local', title: 'b', uri: 'content://x/y'})).toBe(false);
  });
});
