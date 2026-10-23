// Blaze Player: modified from Vega (Apache-2.0) by Blazeinchue.
import {beforeEach, describe, expect, it, jest} from '@jest/globals';

const mockStorageValues = new Map<string, unknown>();

jest.mock('react-native-mmkv-storage', () => ({
  MMKVLoader: class {
    withInstanceID() {
      return this;
    }

    initialize() {
      return {
        getString: (key: string) => mockStorageValues.get(key),
        setString: (key: string, value: string) =>
          mockStorageValues.set(key, value),
        getBool: (key: string) => mockStorageValues.get(key),
        setBool: (key: string, value: boolean) =>
          mockStorageValues.set(key, value),
        getInt: (key: string) => mockStorageValues.get(key),
        setInt: (key: string, value: number) =>
          mockStorageValues.set(key, value),
        getItem: (key: string) => mockStorageValues.get(key) ?? null,
        setItem: (key: string, value: string) =>
          mockStorageValues.set(key, value),
        removeItem: (key: string) => mockStorageValues.delete(key),
        clearStore: () => mockStorageValues.clear(),
      };
    }
  },
}));

const loadThemeStore = () => {
  let store!: typeof import('../src/lib/zustand/themeStore').default;
  jest.isolateModules(() => {
    store = require('../src/lib/zustand/themeStore').default;
  });
  return store;
};

describe('theme store', () => {
  beforeEach(() => {
    mockStorageValues.clear();
  });

  it('persists the accent colour and source across reloads', () => {
    const first = loadThemeStore();
    first.getState().setSource('custom');
    first.getState().setPrimary('#FF0000');

    const next = loadThemeStore();
    expect(next.getState().primary).toBe('#FF0000');
    expect(next.getState().source).toBe('custom');
  });
});
