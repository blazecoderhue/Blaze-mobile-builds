// Blaze Player: modified from Vega's App.tsx (Apache-2.0) by Blazeinchue.
// Provider, download, sync, update, WebView and telemetry wiring removed.
import React, {useEffect} from 'react';
import './global.css';
import 'react-native-reanimated';
import {NavigationContainer} from '@react-navigation/native';
import {createBottomTabNavigator} from '@react-navigation/bottom-tabs';
import {createNativeStackNavigator} from '@react-navigation/native-stack';
import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import {GestureHandlerRootView} from 'react-native-gesture-handler';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import {SystemBars} from 'react-native-edge-to-edge';
import BootSplash from 'react-native-bootsplash';
import {enableFreeze, enableScreens} from 'react-native-screens';
import {M3ThemeProvider} from './theme/M3ThemeProvider';
import AppDialogHost from './components/AppDialogHost';
import Discover from './screens/Discover';
import Library from './screens/Library';
import Queue from './screens/Queue';
import Settings from './screens/Settings';
import Player from './screens/Player';
import {BLAZE} from './theme/blaze';
import {navigationRef, RootStackParamList} from './lib/playback';
import {startAgentBridge} from './lib/agent/bridge';
import {listenForNativeQueueChanges, useQueue} from './lib/queue/queue';

enableScreens(true);
enableFreeze(true);

const Stack = createNativeStackNavigator<RootStackParamList>();
const Tab = createBottomTabNavigator();

const icon =
  (on: any, off: any) =>
  ({focused, color, size}: {focused: boolean; color: string; size: number}) => (
    <MaterialCommunityIcons name={focused ? on : off} color={color} size={size} />
  );

function Tabs() {
  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: BLAZE.accent,
        tabBarInactiveTintColor: BLAZE.onSurfaceVariant,
        tabBarStyle: {backgroundColor: BLAZE.surface, borderTopColor: BLAZE.outline},
      }}>
      <Tab.Screen name="Discover" component={Discover} options={{tabBarIcon: icon('compass', 'compass-outline')}} />
      <Tab.Screen name="Library" component={Library} options={{tabBarIcon: icon('folder-play', 'folder-play-outline')}} />
      <Tab.Screen name="Queue" component={Queue} options={{tabBarIcon: icon('playlist-play', 'playlist-play')}} />
      <Tab.Screen name="Settings" component={Settings} options={{title: 'About', tabBarIcon: icon('information', 'information-outline')}} />
    </Tab.Navigator>
  );
}

const App = () => {
  useEffect(() => {
    useQueue.getState().hydrate();
    const stopQueue = listenForNativeQueueChanges();
    return stopQueue;
  }, []);

  return (
    <SafeAreaProvider>
      <SystemBars style="light" />
      <M3ThemeProvider>
        <AppDialogHost />
        <GestureHandlerRootView style={{flex: 1, backgroundColor: BLAZE.background}}>
          <NavigationContainer
            ref={navigationRef}
            onReady={() => {
              BootSplash.hide({fade: true}).catch(() => {});
              startAgentBridge();
            }}
            theme={{
              dark: true,
              fonts: {
                regular: {fontFamily: 'System', fontWeight: '400'},
                medium: {fontFamily: 'System', fontWeight: '500'},
                bold: {fontFamily: 'System', fontWeight: '700'},
                heavy: {fontFamily: 'System', fontWeight: '800'},
              },
              colors: {
                background: BLAZE.background,
                card: BLAZE.surface,
                primary: BLAZE.accent,
                text: BLAZE.onSurface,
                border: BLAZE.outline,
                notification: BLAZE.accent,
              },
            }}>
            <Stack.Navigator screenOptions={{headerShown: false, animation: 'ios_from_right'}}>
              <Stack.Screen name="Tabs" component={Tabs} />
              <Stack.Screen
                name="Player"
                component={Player}
                options={{orientation: 'default', statusBarHidden: true, navigationBarHidden: true}}
              />
            </Stack.Navigator>
          </NavigationContainer>
        </GestureHandlerRootView>
      </M3ThemeProvider>
    </SafeAreaProvider>
  );
};

export default App;
