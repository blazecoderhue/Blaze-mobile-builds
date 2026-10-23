// Blaze Player (Apache-2.0). Added by Blazeinchue.
// The user's own files: individual files picked through the Storage Access Framework, and a
// "private locker" folder the user grants with the SAF folder picker. Nothing leaves the device.
import {NativeModules} from 'react-native';
import {create} from 'zustand';
import {persist, createJSONStorage} from 'zustand/middleware';
import {createZustandStorage} from '../storage/StorageService';
import {MediaItem} from './types';
import {agentNative} from '../agent/native';

const MEDIA_EXT = /\.(mp4|m4v|mkv|webm|mov|avi|ts|m3u8|mp3|m4a|aac|flac|ogg|opus|wav)$/i;

export function isMediaName(name: string): boolean {
  return MEDIA_EXT.test(name);
}

export function localItem(uri: string, name: string, mimeType?: string): MediaItem {
  return {
    id: `local:${uri}`,
    source: 'local',
    title: name.replace(/\.[^.]+$/, ''),
    subtitle: 'On this device',
    uri,
    mimeType,
  };
}

interface LibraryState {
  files: MediaItem[];
  lockerUri?: string;
  lockerFiles: MediaItem[];
  addFiles: (items: MediaItem[]) => void;
  removeFile: (id: string) => void;
  setLocker: (uri: string | undefined, files: MediaItem[]) => void;
}

export const useLibrary = create<LibraryState>()(
  persist(
    set => ({
      files: [],
      lockerFiles: [],
      addFiles: items =>
        set(state => {
          const seen = new Set(state.files.map(f => f.id));
          return {files: [...state.files, ...items.filter(i => !seen.has(i.id))]};
        }),
      removeFile: id => set(state => ({files: state.files.filter(f => f.id !== id)})),
      setLocker: (lockerUri, lockerFiles) => set({lockerUri, lockerFiles}),
    }),
    {name: 'blaze-library', storage: createJSONStorage(() => createZustandStorage())},
  ),
);

// Mirror titles to native so the agent's search(licensed sources) can include local files.
useLibrary.subscribe(state => {
  agentNative.setLibrary(JSON.stringify([...state.files, ...state.lockerFiles]));
});

export function allLocalItems(): MediaItem[] {
  const s = useLibrary.getState();
  return [...s.files, ...s.lockerFiles];
}

async function persistGrant(uri: string) {
  try {
    await NativeModules.UriPermissionModule?.takePersistableUriPermission(uri);
  } catch {
    // Non-fatal: some providers do not hand out persistable grants.
  }
}

/** Opens the system file picker (SAF) for one or more audio/video files. */
export async function pickLocalFiles(): Promise<MediaItem[]> {
  const DocumentPicker = require('expo-document-picker');
  const res = await DocumentPicker.getDocumentAsync({
    type: ['video/*', 'audio/*'],
    multiple: true,
    copyToCacheDirectory: false,
  });
  if (res.canceled || !res.assets) {
    return [];
  }
  const items: MediaItem[] = [];
  for (const a of res.assets) {
    await persistGrant(a.uri);
    items.push(localItem(a.uri, a.name ?? 'Untitled', a.mimeType ?? undefined));
  }
  useLibrary.getState().addFiles(items);
  return items;
}

/** Lists media in the user's locker folder (picked once through the SAF folder picker). */
export async function pickLocker(): Promise<MediaItem[]> {
  const {Directory} = require('expo-file-system');
  const dir = await Directory.pickDirectoryAsync();
  return scanLocker(dir.uri, dir);
}

export async function scanLocker(uri?: string, dirObj?: any): Promise<MediaItem[]> {
  const target = uri ?? useLibrary.getState().lockerUri;
  if (!target) {
    return [];
  }
  const {Directory, File} = require('expo-file-system');
  const dir = dirObj ?? new Directory(target);
  const out: MediaItem[] = [];
  const walk = (d: any, depth: number) => {
    for (const entry of d.list()) {
      if (entry instanceof File) {
        const name = entry.name ?? '';
        if (isMediaName(name)) {
          out.push(localItem(entry.uri, name, entry.type ?? undefined));
        }
      } else if (entry instanceof Directory && depth < 3) {
        walk(entry, depth + 1);
      }
    }
  };
  walk(dir, 0);
  useLibrary.getState().setLocker(target, out);
  return out;
}

export function searchLocal(query: string): MediaItem[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return allLocalItems();
  }
  return allLocalItems().filter(i => i.title.toLowerCase().includes(q));
}
