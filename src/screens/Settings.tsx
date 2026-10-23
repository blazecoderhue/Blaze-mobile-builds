// Blaze Player (Apache-2.0). Added by Blazeinchue.
import React from 'react';
import {Linking, Pressable, ScrollView, StyleSheet, View} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import * as Application from 'expo-application';
import AppText from '../components/ui/Text';
import {BLAZE} from '../theme/blaze';
import {SOURCES} from '../lib/sources/registry';

function Card({title, children}: {title: string; children: React.ReactNode}) {
  return (
    <View style={styles.card}>
      <AppText role="titleSmall" style={{color: BLAZE.accent, marginBottom: 6}}>
        {title}
      </AppText>
      {children}
    </View>
  );
}

const Body = ({children}: {children: React.ReactNode}) => (
  <AppText role="bodyMedium" style={{color: BLAZE.onSurfaceVariant, marginBottom: 4}}>
    {children}
  </AppText>
);

export default function Settings() {
  const insets = useSafeAreaInsets();
  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={{paddingTop: insets.top + 8, paddingBottom: 96}}>
      <AppText role="headlineSmall" style={styles.title}>
        About
      </AppText>
      <Card title="Blaze Player">
        <Body>
          Version {Application.nativeApplicationVersion ?? '0.1.0'} ({Application.nativeBuildVersion ?? '1'})
        </Body>
        <Body>No ads. No tracking. Nothing leaves your phone except the searches you make.</Body>
      </Card>
      <Card title="Sources">
        {SOURCES.map(s => (
          <Body key={s.kind}>
            {s.name}: {s.terms}
          </Body>
        ))}
        <Body>Casting: Google Cast (Chromecast), for online items.</Body>
      </Card>
      <Card title="License">
        <Body>
          Blaze Player is a modified version of Vega (github.com/vega-org/vega-app) at commit
          9ccc0c8, licensed under the Apache License 2.0. Modified by Blazeinchue.
        </Body>
        <Pressable onPress={() => Linking.openURL('https://www.apache.org/licenses/LICENSE-2.0')}>
          <AppText role="labelLarge" style={{color: BLAZE.blue}}>
            Apache License 2.0
          </AppText>
        </Pressable>
        <Pressable onPress={() => Linking.openURL('https://github.com/vega-org/vega-app/tree/9ccc0c8')}>
          <AppText role="labelLarge" style={{color: BLAZE.blue, marginTop: 6}}>
            Upstream source (Vega @ 9ccc0c8)
          </AppText>
        </Pressable>
      </Card>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: BLAZE.background},
  title: {color: BLAZE.onSurface, paddingHorizontal: 16, marginBottom: 12},
  card: {
    marginHorizontal: 16,
    marginBottom: 12,
    padding: 16,
    borderRadius: 20,
    backgroundColor: BLAZE.surface,
  },
});
