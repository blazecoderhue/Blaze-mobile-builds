// Blaze Player (Apache-2.0). Added by Blazeinchue.
// One place that turns "play this" into navigation, used by screens and the agent bridge.
import {createNavigationContainerRef} from '@react-navigation/native';
import type {MediaItem} from './sources/types';
import {resolveItem} from './sources/registry';
import {castItem} from './cast/cast';

export type RootStackParamList = {
  Tabs: {screen?: string} | undefined;
  Player: {item: MediaItem};
};

export const navigationRef = createNavigationContainerRef<RootStackParamList>();

export async function playItem(input: MediaItem | string): Promise<MediaItem> {
  const item = await resolveItem(input);
  if (navigationRef.isReady()) {
    navigationRef.navigate('Player', {item});
  }
  return item;
}

export async function castResolved(input: MediaItem | string): Promise<MediaItem> {
  const item = await resolveItem(input);
  await castItem(item);
  return item;
}

export function openScreen(screen?: string) {
  const map: Record<string, string> = {
    home: 'Discover',
    search: 'Discover',
    library: 'Library',
    queue: 'Queue',
    settings: 'Settings',
  };
  if (navigationRef.isReady()) {
    navigationRef.navigate('Tabs', {screen: map[screen ?? 'home'] ?? 'Discover'});
  }
}
