// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Chromecast through react-native-google-cast (kept from Vega). Only http(s) items can be cast:
// a receiver cannot read a content:// file on the phone.
import GoogleCast from 'react-native-google-cast';
import {MediaItem, SourceError} from '../sources/types';

export function canCast(item: MediaItem): boolean {
  return !!item.uri && /^https?:\/\//i.test(item.uri);
}

export async function castItem(item: MediaItem, startTime = 0): Promise<void> {
  if (!canCast(item)) {
    throw new SourceError('E_CAST_UNSUPPORTED', 'Only online items can be cast');
  }
  let session = await GoogleCast.getSessionManager().getCurrentCastSession();
  if (!session) {
    await GoogleCast.showCastDialog();
    throw new SourceError('E_NO_CAST_SESSION', 'Pick a Cast device, then try again');
  }
  await session.client.loadMedia({
    mediaInfo: {
      contentUrl: item.uri!,
      contentType: item.mimeType ?? 'video/mp4',
      metadata: {
        type: 'movie',
        title: item.title,
        images: item.poster ? [{url: item.poster}] : [],
      } as any,
    },
    startTime,
    autoplay: true,
  });
}
