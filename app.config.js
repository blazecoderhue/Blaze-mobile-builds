// Blaze Player: modified from Vega's app.config.js (Apache-2.0) by Blazeinchue.
// Firebase, Play Store/TV variants, signing, notifications, OkHttp/DoH, torrent and custom
// provider modules removed. Package com.blaze.player.
const VERSION = '0.1.0';
const VERSION_CODE = 1;

module.exports = () => {
  const plugins = [
    './plugins/with-blaze-agent.js',
    './plugins/android-native-config.js',
    './plugins/with-uri-permission-module.js',
    './plugins/with-proguard-rules.js',
    './plugins/with-jvm-args.js',
    './plugins/with-android-release-gradle.js',
    [
      'react-native-video',
      {
        enableNotificationControls: true,
        enableAndroidPictureInPicture: true,
        androidExtensions: {
          useExoplayerRtsp: false,
          useExoplayerSmoothStreaming: true,
          useExoplayerHls: true,
          useExoplayerDash: true,
        },
      },
    ],
    // Pinned: '+' resolves to cast-framework 23.x, which needs mediarouter 1.9 (compileSdk 37, AGP 9.1).
    ['react-native-google-cast', {expandedController: true, androidPlayServicesCastFrameworkVersion: '22.3.1'}],
    'react-native-edge-to-edge',
    [
      'react-native-bootsplash',
      {assetsDir: 'assets/bootsplash', android: {parentTheme: 'EdgeToEdge'}},
    ],
    [
      'expo-build-properties',
      {
        android: {
          usePrecompiledHeaders: true,
          enableMinifyInReleaseBuilds: true,
          enableShrinkResourcesInReleaseBuilds: true,
        },
      },
    ],
    'expo-font',
    'expo-status-bar',
  ];
  return {
    expo: {
      name: 'Blaze Player',
      slug: 'blaze-player',
      scheme: 'blazeplayer',
      version: VERSION,
      jsEngine: 'hermes',
      newArchEnabled: true,
      autolinking: {exclude: ['expo-splash-screen']},
      plugins,
      icon: './assets/icon.png',
      userInterfaceStyle: 'dark',
      backgroundColor: '#090E14',
      experiments: {reactCompiler: true},
      android: {
        package: 'com.blaze.player',
        versionCode: VERSION_CODE,
        minSdkVersion: 28,
        permissions: ['INTERNET', 'ACCESS_NETWORK_STATE', 'WAKE_LOCK', 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_MEDIA_PLAYBACK'],
        blockedPermissions: [
          'android.permission.MANAGE_EXTERNAL_STORAGE',
          'android.permission.READ_EXTERNAL_STORAGE',
          'android.permission.WRITE_EXTERNAL_STORAGE',
          'android.permission.READ_MEDIA_VIDEO',
          'android.permission.READ_MEDIA_AUDIO',
          'android.permission.REQUEST_INSTALL_PACKAGES',
          'android.permission.SYSTEM_ALERT_WINDOW',
          'android.permission.WRITE_SETTINGS',
          'com.google.android.gms.permission.AD_ID',
        ],
        allowBackup: false,
        adaptiveIcon: {
          foregroundImage: './assets/adaptive_icon.png',
          backgroundColor: '#090E14',
        },
        launchMode: 'singleTask',
        supportsPictureInPicture: true,
      },
      platforms: ['android'],
      extra: {upstream: 'https://github.com/vega-org/vega-app/tree/9ccc0c8'},
    },
  };
};
