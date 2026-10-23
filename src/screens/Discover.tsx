// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Internet Archive public-domain and CC feature films, plus search across licensed sources.
import React, {useCallback, useEffect, useState} from 'react';
import {ActivityIndicator, FlatList, StyleSheet, TextInput, View} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import AppText from '../components/ui/Text';
import MediaRow from '../components/blaze/MediaRow';
import {BLAZE} from '../theme/blaze';
import type {MediaItem} from '../lib/sources/types';
import {searchLicensed} from '../lib/sources/registry';
import {playItem} from '../lib/playback';
import {useQueue} from '../lib/queue/queue';

export default function Discover() {
  const insets = useSafeAreaInsets();
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<MediaItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const addToQueue = useQueue(s => s.add);

  const run = useCallback(async (q: string) => {
    setLoading(true);
    setError(null);
    try {
      setItems(await searchLicensed(q, q ? ['local', 'archive'] : ['archive']));
    } catch (e: any) {
      setError(e?.message ?? 'Could not reach the Internet Archive');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    run('');
  }, [run]);

  const onPlay = async (item: MediaItem) => {
    try {
      await playItem(item);
    } catch (e: any) {
      setError(e?.message ?? 'Could not play this item');
    }
  };

  return (
    <View style={[styles.root, {paddingTop: insets.top + 8}]}>
      <AppText role="headlineSmall" style={styles.title}>
        Blaze Player
      </AppText>
      <TextInput
        value={query}
        onChangeText={setQuery}
        onSubmitEditing={() => run(query)}
        placeholder="Search your files and public-domain films"
        placeholderTextColor={BLAZE.onSurfaceVariant}
        returnKeyType="search"
        style={styles.search}
        accessibilityLabel="Search"
      />
      {error && (
        <AppText role="bodySmall" style={styles.error}>
          {error}
        </AppText>
      )}
      {loading ? (
        <ActivityIndicator color={BLAZE.accent} style={{marginTop: 32}} />
      ) : (
        <FlatList
          data={items}
          keyExtractor={i => i.id}
          renderItem={({item}) => (
            <MediaRow
              item={item}
              onPress={() => onPlay(item)}
              onQueue={() => addToQueue([item])}
            />
          )}
          ListHeaderComponent={
            !query ? (
              <AppText role="labelLarge" style={styles.section}>
                Public-domain and Creative Commons films · Internet Archive
              </AppText>
            ) : null
          }
          ListEmptyComponent={
            <AppText role="bodyMedium" style={styles.empty}>
              Nothing found.
            </AppText>
          }
          contentContainerStyle={{paddingBottom: 96}}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: BLAZE.background},
  title: {color: BLAZE.onSurface, paddingHorizontal: 16, marginBottom: 12},
  search: {
    marginHorizontal: 16,
    marginBottom: 8,
    paddingHorizontal: 16,
    height: 48,
    borderRadius: 24,
    backgroundColor: BLAZE.surface,
    color: BLAZE.onSurface,
  },
  section: {color: BLAZE.accent, paddingHorizontal: 16, paddingVertical: 8},
  error: {color: BLAZE.danger, paddingHorizontal: 16},
  empty: {color: BLAZE.onSurfaceVariant, textAlign: 'center', marginTop: 32},
});
