// Blaze Player (Apache-2.0). Added by Blazeinchue.
// The only sources Blaze Player knows. There is no plugin, extension or remote-install mechanism.
import {isArchiveHost, resolveArchiveItem, searchArchive} from './archive';
import {allLocalItems, localItem, searchLocal} from './local';
import {MediaItem, SourceError, SourceKind} from './types';

export const SOURCES: {kind: SourceKind; name: string; terms: string}[] = [
  {kind: 'local', name: 'This device', terms: "Your own files, read through Android's file picker"},
  {
    kind: 'archive',
    name: 'Internet Archive',
    terms: 'Public-domain and Creative Commons (non-NC) feature films, archive.org',
  },
];

/** Parses a uri handed in by the user or the agent; only licensed locations are accepted. */
export function itemFromUri(uri: string, title?: string): MediaItem {
  let parsed: URL | null = null;
  try {
    parsed = new URL(uri);
  } catch {
    throw new SourceError('E_BAD_URI', 'Not a valid uri');
  }
  const scheme = parsed.protocol.replace(':', '').toLowerCase();
  if (scheme === 'content') {
    return localItem(uri, title ?? decodeURIComponent(uri.split('/').pop() ?? 'File'));
  }
  if (scheme === 'https' && isArchiveHost(parsed.hostname)) {
    const m = parsed.pathname.match(/^\/(?:details|download|embed)\/([A-Za-z0-9._-]+)(\/.*)?$/);
    if (m && m[1]) {
      if (m[2] && /\.mp4$/i.test(m[2])) {
        // A direct file still has to belong to a licensed item: resolve the item to check.
        return {id: `archive:${m[1]}`, source: 'archive', title: title ?? m[1]};
      }
      return {id: `archive:${m[1]}`, source: 'archive', title: title ?? m[1]};
    }
  }
  throw new SourceError(
    'E_SOURCE_NOT_ALLOWED',
    'Blaze Player only plays your own files and Internet Archive public-domain/CC films',
  );
}

/** Makes an item playable (fills `uri`), re-checking the licence for archive items. */
export async function resolveItem(input: MediaItem | string): Promise<MediaItem> {
  const item = typeof input === 'string' ? fromIdOrUri(input) : input;
  if (item.source === 'archive') {
    return {...(await resolveArchiveItem(item.id)), ...(item.title && item.title !== item.id.slice(8) ? {title: item.title} : {})};
  }
  if (item.source === 'local' && item.uri) {
    return item;
  }
  throw new SourceError('E_NOT_FOUND', 'Nothing to play');
}

export function fromIdOrUri(idOrUri: string): MediaItem {
  if (idOrUri.startsWith('archive:')) {
    return {id: idOrUri, source: 'archive', title: idOrUri.slice('archive:'.length)};
  }
  if (idOrUri.startsWith('local:')) {
    const known = allLocalItems().find(i => i.id === idOrUri);
    return known ?? localItem(idOrUri.slice('local:'.length), 'File');
  }
  return itemFromUri(idOrUri);
}

export async function searchLicensed(
  query: string,
  sources: SourceKind[] = ['local', 'archive'],
): Promise<MediaItem[]> {
  const out: MediaItem[] = [];
  if (sources.includes('local')) {
    out.push(...searchLocal(query));
  }
  if (sources.includes('archive')) {
    out.push(...(await searchArchive(query)));
  }
  return out;
}
