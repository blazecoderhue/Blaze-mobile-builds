// Blaze Player (Apache-2.0). Added by Blazeinchue.
/** Where a playable item comes from. Only licensed sources exist in Blaze Player. */
export type SourceKind = 'local' | 'archive';

export interface MediaItem {
  /** Stable source id, e.g. `archive:night_of_the_living_dead` or `local:<content uri>`. */
  id: string;
  source: SourceKind;
  title: string;
  subtitle?: string;
  year?: string;
  poster?: string;
  /** Directly playable uri (https or content://). Resolved lazily for archive items. */
  uri?: string;
  mimeType?: string;
  licenseUrl?: string;
  /** Archive item page, for attribution. */
  pageUrl?: string;
}

export class SourceError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}
