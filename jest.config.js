// Blaze Player: modified from Vega (Apache-2.0) by Blazeinchue (transform list for RN/Expo ESM).
module.exports = {
  preset: '@react-native/jest-preset',
  moduleNameMapper: {
    '^@expo/ui/jetpack-compose$':
      '<rootDir>/__mocks__/expo-ui-jetpack-compose.js',
    '^@expo/ui/jetpack-compose/modifiers$':
      '<rootDir>/__mocks__/expo-ui-jetpack-compose-modifiers.js',
  },
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|@react-navigation|expo(nent)?|@expo(nent)?/.*|expo-.*|react-native-.*|nativewind|react-native-css-interop)/)',
  ],
  testPathIgnorePatterns: ['/node_modules/', '/android/'],
};
