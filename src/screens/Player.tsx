// Blaze Player (Apache-2.0). Added by Blazeinchue. Uses Vega's media-console player chrome.
import React, {useCallback} from 'react';
import {Pressable, StyleSheet, View} from 'react-native';
import type {NativeStackScreenProps} from '@react-navigation/native-stack';
import {CastButton} from 'react-native-google-cast';
import VideoPlayer from '../components/media-console';
import {BLAZE} from '../theme/blaze';
import type {RootStackParamList} from '../lib/playback';
import {playItem} from '../lib/playback';
import {useQueue} from '../lib/queue/queue';
import {canCast} from '../lib/cast/cast';
import {settingsStorage} from '../lib/storage';

type Props = NativeStackScreenProps<RootStackParamList, 'Player'>;

export default function Player({route, navigation}: Props) {
  const {item} = route.params;
  const onEnd = useCallback(() => {
    const next = useQueue.getState().shift();
    if (next) {
      playItem(next).catch(() => navigation.goBack());
    } else {
      navigation.goBack();
    }
  }, [navigation]);

  return (
    <View style={styles.root}>
      <VideoPlayer
        source={{
          uri: item.uri ?? '',
          metadata: {title: item.title, imageUri: item.poster},
        }}
        title={{primary: item.title.slice(0, 70), secondary: item.year}}
        navigator={navigation as any}
        onBack={() => navigation.goBack()}
        onEnd={onEnd}
        showDuration
        seekColor={BLAZE.accent}
        toggleResizeModeOnFullscreen={false}
        disableGesture={!settingsStorage.isSwipeGestureEnabled()}
        rewindTime={10}
        playInBackground={false}
        subtitleStyle={{
          fontSize: settingsStorage.getSubtitleFontSize() ?? 16,
          opacity: settingsStorage.getSubtitleOpacity() ?? 1,
          paddingBottom: settingsStorage.getSubtitleBottomPadding() ?? 10,
          subtitlesFollowVideo: false,
        }}
      />
      {canCast(item) && (
        <Pressable style={styles.cast} accessibilityLabel="Cast">
          <CastButton style={{width: 28, height: 28, tintColor: 'white'}} />
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: 'black'},
  cast: {position: 'absolute', top: 16, right: 64, zIndex: 50},
});
