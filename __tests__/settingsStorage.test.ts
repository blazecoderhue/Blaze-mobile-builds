// Blaze Player: modified from Vega (Apache-2.0) by Blazeinchue (removed settings dropped).
const mockBooleanValues = new Map<string, boolean>();
const mockNumberValues = new Map<string, number>();

jest.mock('../src/lib/storage/StorageService', () => ({
  mainStorage: {
    getBool: (key: string, defaultValue = false) =>
      mockBooleanValues.has(key) ? mockBooleanValues.get(key) : defaultValue,
    setBool: (key: string, value: boolean) => mockBooleanValues.set(key, value),
    getString: () => undefined,
    setString: jest.fn(),
    getNumber: (key: string) => mockNumberValues.get(key),
    setNumber: (key: string, value: number) => mockNumberValues.set(key, value),
    getArray: () => undefined,
    setArray: jest.fn(),
    delete: jest.fn(),
  },
}));

import {
  SettingsKeys,
  settingsStorage,
} from '../src/lib/storage/SettingsStorage';

describe('settings defaults', () => {
  beforeEach(() => {
    mockBooleanValues.clear();
    mockNumberValues.clear();
  });

  it('enables default-on preferences when no value is stored', () => {
    expect(settingsStorage.isHapticFeedbackEnabled()).toBe(true);
    expect(settingsStorage.showMediaControls()).toBe(true);
    expect(settingsStorage.showHamburgerMenu()).toBe(true);
    expect(settingsStorage.isSwipeGestureEnabled()).toBe(true);
    expect(settingsStorage.showTabBarLabels()).toBe(true);
    expect(settingsStorage.showPlayerEpisodeSidebar()).toBe(true);
  });

  it('keeps intentional default-off preferences disabled', () => {
    expect(settingsStorage.hideSeekButtons()).toBe(false);
    expect(settingsStorage.isEnable2xGestureEnabled()).toBe(false);
  });

  it('defaults the accent to Blaze amber', () => {
    expect(settingsStorage.getPrimaryColor()).toBe('#F5C26B');
    expect(settingsStorage.getAccentSource()).toBe('custom');
  });

  it('preserves explicit user opt-outs', () => {
    mockBooleanValues.set(SettingsKeys.HAPTIC_FEEDBACK, false);
    mockBooleanValues.set(SettingsKeys.SHOW_MEDIA_CONTROLS, false);
    expect(settingsStorage.isHapticFeedbackEnabled()).toBe(false);
    expect(settingsStorage.showMediaControls()).toBe(false);
  });

  it('has no telemetry, DoH, WARP, download or torrent settings', () => {
    const keys = Object.values(SettingsKeys).join(' ').toLowerCase();
    for (const banned of ['telemetry', 'doh', 'warp', 'byedpi', 'torrent', 'download', 'tmdb']) {
      expect(keys).not.toContain(banned);
    }
  });
});
