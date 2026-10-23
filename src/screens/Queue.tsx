// Blaze Player (Apache-2.0). Added by Blazeinchue.
import React from 'react';
import {FlatList, Pressable, StyleSheet, View} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import AppText from '../components/ui/Text';
import MediaRow from '../components/blaze/MediaRow';
import {BLAZE} from '../theme/blaze';
import {useQueue} from '../lib/queue/queue';
import {playItem} from '../lib/playback';

export default function Queue() {
  const insets = useSafeAreaInsets();
  const items = useQueue(s => s.items);
  const removeAt = useQueue(s => s.removeAt);
  const clear = useQueue(s => s.clear);
  return (
    <View style={[styles.root, {paddingTop: insets.top + 8}]}>
      <View style={styles.header}>
        <AppText role="headlineSmall" style={{color: BLAZE.onSurface}}>
          Up next
        </AppText>
        {items.length > 0 && (
          <Pressable onPress={clear} accessibilityRole="button">
            <AppText role="labelLarge" style={{color: BLAZE.accent}}>
              Clear
            </AppText>
          </Pressable>
        )}
      </View>
      <FlatList
        data={items}
        keyExtractor={(i, idx) => `${i.id}#${idx}`}
        renderItem={({item, index}) => (
          <MediaRow
            item={item}
            onPress={() => {
              removeAt(index);
              playItem(item).catch(() => {});
            }}
            onRemove={() => removeAt(index)}
          />
        )}
        ListEmptyComponent={
          <AppText role="bodyMedium" style={styles.empty}>
            Your queue is empty.
          </AppText>
        }
        contentContainerStyle={{paddingBottom: 96}}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: BLAZE.background},
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    marginBottom: 12,
  },
  empty: {color: BLAZE.onSurfaceVariant, textAlign: 'center', marginTop: 32},
});
