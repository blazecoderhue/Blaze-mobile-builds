// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Internet Archive public-domain and Creative Commons feature films, through the official
// advancedsearch and metadata APIs only (https://archive.org/developers/).
import {MediaItem, SourceError} from './types';

export const ARCHIVE_BASE = 'https://archive.org';

/** Collection + license filter applied to every query. NonCommercial licences are excluded. */
export const ARCHIVE_LICENSE_FILTER =
  'collection:(feature_films) AND mediatype:(movies) AND ' +
  '(licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*) AND NOT licenseurl:*-nc*';

const FIELDS = ['identifier', 'title', 'year', 'licenseurl', 'creator'];

/** True for public-domain marks, CC0 and CC licences without the NonCommercial term. */
export function isAllowedLicense(url?: string | null): boolean {
  if (!url) {
    return false;
  }
  const u = String(url).toLowerCase();
  if (!/creativecommons\.org|publicdomain/.test(u)) {
    return false;
  }
  if (/[/-]nc[/-]|-nc$|by-nc/.test(u)) {
    return false;
  }
  return true;
}

function sanitizeQuery(query: string): string {
  // Keep it a plain keyword search: drop Lucene operators so a query can never widen the filter.
  return query
    .replace(/[()[\]{}:"\\^~*?!+&|]/g, ' ')
    .replace(/\b(AND|OR|NOT|TO)\b/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 120);
}

export function buildArchiveSearchUrl(query: string, page = 1, rows = 30): string {
  const q = sanitizeQuery(query);
  const full = q ? `(${q}) AND ${ARCHIVE_LICENSE_FILTER}` : ARCHIVE_LICENSE_FILTER;
  const params: string[] = [`q=${encodeURIComponent(full)}`];
  for (const f of FIELDS) {
    params.push(`fl[]=${encodeURIComponent(f)}`);
  }
  params.push(`sort[]=${encodeURIComponent('downloads desc')}`);
  params.push(`rows=${Math.max(1, Math.min(rows, 100))}`);
  params.push(`page=${Math.max(1, page)}`);
  params.push('output=json');
  return `${ARCHIVE_BASE}/advancedsearch.php?${params.join('&')}`;
}

export function archiveThumb(identifier: string): string {
  return `${ARCHIVE_BASE}/services/img/${encodeURIComponent(identifier)}`;
}

export function parseArchiveSearch(json: any): MediaItem[] {
  const docs: any[] = json?.response?.docs ?? [];
  return docs
    .filter(d => d && typeof d.identifier === 'string' && isAllowedLicense(d.licenseurl))
    .map(d => ({
      id: `archive:${d.identifier}`,
      source: 'archive' as const,
      title: String(Array.isArray(d.title) ? d.title[0] : d.title ?? d.identifier),
      subtitle: d.creator ? String(Array.isArray(d.creator) ? d.creator[0] : d.creator) : undefined,
      year: d.year ? String(d.year) : undefined,
      poster: archiveThumb(d.identifier),
      licenseUrl: String(d.licenseurl),
      pageUrl: `${ARCHIVE_BASE}/details/${d.identifier}`,
    }));
}

const FORMAT_RANK = ['h.264', 'h.264 ia', 'mpeg4', '512kb mpeg4', 'h.264 hd'];

/** Picks the best progressive MP4 from a metadata response. */
export function pickArchiveVideoFile(files: any[]): {name: string; format: string} | null {
  const mp4 = (files ?? []).filter(
    f => f && typeof f.name === 'string' && /\.mp4$/i.test(f.name),
  );
  if (!mp4.length) {
    return null;
  }
  const rank = (f: any) => {
    const i = FORMAT_RANK.indexOf(String(f.format ?? '').toLowerCase());
    return i === -1 ? FORMAT_RANK.length : i;
  };
  mp4.sort((a, b) => rank(a) - rank(b) || Number(b.size ?? 0) - Number(a.size ?? 0));
  return {name: mp4[0].name, format: String(mp4[0].format ?? '')};
}

export function identifierFromId(id: string): string {
  const ident = id.startsWith('archive:') ? id.slice('archive:'.length) : id;
  if (!/^[A-Za-z0-9._-]{1,100}$/.test(ident)) {
    throw new SourceError('E_BAD_SOURCE_ID', `Not an Internet Archive identifier: ${id}`);
  }
  return ident;
}

/** Resolves an archive item to a playable https uri, re-checking the licence on the item itself. */
export function resolveArchiveMetadata(identifier: string, meta: any): MediaItem {
  const md = meta?.metadata ?? {};
  const licenseUrl = Array.isArray(md.licenseurl) ? md.licenseurl[0] : md.licenseurl;
  const collections: string[] = ([] as string[]).concat(md.collection ?? []);
  if (!collections.includes('feature_films') || !isAllowedLicense(licenseUrl)) {
    throw new SourceError(
      'E_NOT_LICENSED',
      `${identifier} is not a public-domain or Creative Commons feature film`,
    );
  }
  const file = pickArchiveVideoFile(meta?.files ?? []);
  if (!file) {
    throw new SourceError('E_NO_PLAYABLE_FILE', `${identifier} has no MP4 file`);
  }
  const path = file.name.split('/').map(encodeURIComponent).join('/');
  return {
    id: `archive:${identifier}`,
    source: 'archive',
    title: String(Array.isArray(md.title) ? md.title[0] : md.title ?? identifier),
    year: md.year ? String(md.year) : md.date ? String(md.date).slice(0, 4) : undefined,
    poster: archiveThumb(identifier),
    uri: `${ARCHIVE_BASE}/download/${encodeURIComponent(identifier)}/${path}`,
    mimeType: 'video/mp4',
    licenseUrl: String(licenseUrl),
    pageUrl: `${ARCHIVE_BASE}/details/${identifier}`,
  };
}

async function getJson(url: string): Promise<any> {
  const res = await fetch(url, {headers: {Accept: 'application/json'}});
  if (!res.ok) {
    throw new SourceError('E_NETWORK', `Internet Archive returned HTTP ${res.status}`);
  }
  return res.json();
}

export async function searchArchive(query: string, page = 1, rows = 30): Promise<MediaItem[]> {
  return parseArchiveSearch(await getJson(buildArchiveSearchUrl(query, page, rows)));
}

export async function resolveArchiveItem(id: string): Promise<MediaItem> {
  const identifier = identifierFromId(id);
  const meta = await getJson(`${ARCHIVE_BASE}/metadata/${encodeURIComponent(identifier)}`);
  return resolveArchiveMetadata(identifier, meta);
}

/** Hosts an archive play uri may point at. */
export function isArchiveHost(host: string): boolean {
  const h = host.toLowerCase();
  return h === 'archive.org' || h.endsWith('.archive.org');
}
