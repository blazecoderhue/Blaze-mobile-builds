// Blaze Player (Apache-2.0). Added by Blazeinchue.
import React from 'react';
import {Image, Pressable, StyleSheet, View} from 'react-native';
import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import AppText from '../ui/Text';
import {BLAZE} from '../../theme/blaze';
import type {MediaItem} from '../../lib/sources/types';

export default function MediaRow({
  item,
  onPress,
  onQueue,
  onRemove,
}: {
  item: MediaItem;
  onPress: () => void;
  onQueue?: () => void;
  onRemove?: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`Play ${item.title}`}
      style={({pressed}) => [styles.row, pressed && {opacity: 0.7}]}>
      {item.poster ? (
        <Image source={{uri: item.poster}} style={styles.thumb} />
      ) : (
        <View style={[styles.thumb, styles.thumbIcon]}>
          <MaterialCommunityIcons name="file-video-outline" size={28} color={BLAZE.accent} />
        </View>
      )}
      <View style={{flex: 1}}>
        <AppText role="titleSmall" numberOfLines={2} style={{color: BLAZE.onSurface}}>
          {item.title}
        </AppText>
        <AppText role="bodySmall" numberOfLines={1} style={{color: BLAZE.onSurfaceVariant}}>
          {[item.year, item.subtitle, item.source === 'archive' ? 'Internet Archive' : null]
            .filter(Boolean)
            .join(' · ')}
        </AppText>
      </View>
      {onQueue && (
        <Pressable hitSlop={10} onPress={onQueue} accessibilityLabel="Add to queue">
          <MaterialCommunityIcons name="playlist-plus" size={24} color={BLAZE.onSurfaceVariant} />
        </Pressable>
      )}
      {onRemove && (
        <Pressable hitSlop={10} onPress={onRemove} accessibilityLabel="Remove">
          <MaterialCommunityIcons name="close" size={22} color={BLAZE.onSurfaceVariant} />
        </Pressable>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  thumb: {width: 64, height: 64, borderRadius: 12, backgroundColor: BLAZE.surface},
  thumbIcon: {alignItems: 'center', justifyContent: 'center'},
});
