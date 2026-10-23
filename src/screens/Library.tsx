// Blaze Player (Apache-2.0). Added by Blazeinchue.
// The user's own files and private locker folder (Storage Access Framework).
import React, {useState} from 'react';
import {FlatList, Pressable, StyleSheet, View} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import AppText from '../components/ui/Text';
import MediaRow from '../components/blaze/MediaRow';
import {BLAZE} from '../theme/blaze';
import {pickLocalFiles, pickLocker, scanLocker, useLibrary} from '../lib/sources/local';
import {playItem} from '../lib/playback';
import {useQueue} from '../lib/queue/queue';

function Action({icon, label, onPress}: {icon: any; label: string; onPress: () => void}) {
  return (
    <Pressable onPress={onPress} style={styles.action} accessibilityRole="button">
      <MaterialCommunityIcons name={icon} size={20} color={BLAZE.background} />
      <AppText role="labelLarge" style={{color: BLAZE.background}}>
        {label}
      </AppText>
    </Pressable>
  );
}

export default function Library() {
  const insets = useSafeAreaInsets();
  const files = useLibrary(s => s.files);
  const lockerFiles = useLibrary(s => s.lockerFiles);
  const lockerUri = useLibrary(s => s.lockerUri);
  const removeFile = useLibrary(s => s.removeFile);
  const addToQueue = useQueue(s => s.add);
  const [error, setError] = useState<string | null>(null);
  const wrap = (fn: () => Promise<unknown>) => () => {
    setError(null);
    fn().catch((e: any) => setError(e?.message ?? 'Something went wrong'));
  };
  const data = [...files, ...lockerFiles];
  return (
    <View style={[styles.root, {paddingTop: insets.top + 8}]}>
      <AppText role="headlineSmall" style={styles.title}>
        Your files
      </AppText>
      <View style={styles.actions}>
        <Action icon="file-plus-outline" label="Add files" onPress={wrap(pickLocalFiles)} />
        <Action
          icon="folder-lock-outline"
          label={lockerUri ? 'Rescan locker' : 'Choose locker folder'}
          onPress={wrap(() => (lockerUri ? scanLocker() : pickLocker()))}
        />
      </View>
      {error && (
        <AppText role="bodySmall" style={{color: BLAZE.danger, paddingHorizontal: 16}}>
          {error}
        </AppText>
      )}
      <FlatList
        data={data}
        keyExtractor={i => i.id}
        renderItem={({item}) => (
          <MediaRow
            item={item}
            onPress={wrap(() => playItem(item))}
            onQueue={() => addToQueue([item])}
            onRemove={files.some(f => f.id === item.id) ? () => removeFile(item.id) : undefined}
          />
        )}
        ListEmptyComponent={
          <AppText role="bodyMedium" style={styles.empty}>
            Add videos or music from your phone, or choose a folder as your private locker.
            Files stay on this device.
          </AppText>
        }
        contentContainerStyle={{paddingBottom: 96}}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: BLAZE.background},
  title: {color: BLAZE.onSurface, paddingHorizontal: 16, marginBottom: 12},
  actions: {flexDirection: 'row', gap: 8, paddingHorizontal: 16, marginBottom: 8, flexWrap: 'wrap'},
  action: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 16,
    height: 40,
    borderRadius: 20,
    backgroundColor: BLAZE.accent,
  },
  empty: {color: BLAZE.onSurfaceVariant, textAlign: 'center', marginTop: 32, paddingHorizontal: 32},
});
