// Blaze Player: modified from Vega (Apache-2.0) by Blazeinchue. Download/DoH/WARP/ByeDPI/
// telemetry/TMDB/torrent/updater settings removed; Blaze amber default accent.
import {mainStorage} from './StorageService';

/**
 * Storage keys for settings
 */
export enum SettingsKeys {
  // UI preferences
  PRIMARY_COLOR = 'primaryColor',
  IS_CUSTOM_THEME = 'isCustomTheme',
  SHOW_TAB_BAR_LABELS = 'showTabBarLabels',
  CUSTOM_COLOR = 'customColor',
  ACCENT_SOURCE = 'accentSource',
  DYNAMIC_INFO_ACCENT = 'dynamicInfoAccent',
  // Feedback settings
  HAPTIC_FEEDBACK = 'hapticFeedback',

  // Player settings
  SHOW_MEDIA_CONTROLS = 'showMediaControls',
  SHOW_HAMBURGER_MENU = 'showHamburgerMenu',
  HIDE_SEEK_BUTTONS = 'hideSeekButtons',
  SHOW_PLAYER_EPISODE_SIDEBAR = 'showPlayerEpisodeSidebar',
  ENABLE_2X_GESTURE = 'enable2xGesture',
  ENABLE_SWIPE_GESTURE = 'enableSwipeGesture',

  // Subtitle settings
  SUBTITLE_FONT_SIZE = 'subtitleFontSize',
  SUBTITLE_OPACITY = 'subtitleOpacity',
  SUBTITLE_BOTTOM_PADDING = 'subtitleBottomPadding',
  SUBTITLE_TEXT_COLOR = 'subtitleTextColor',
  SUBTITLE_FONT_FAMILY = 'subtitleFontFamily',
  SUBTITLE_EDGE_TYPE = 'subtitleEdgeType',
  SUBTITLE_EDGE_COLOR = 'subtitleEdgeColor',
  SUBTITLE_OUTLINE_WIDTH = 'subtitleOutlineWidth',

  LIST_VIEW_TYPE = 'viewType',

  // Remote Playback
  ALWAYS_CAST_MODE = 'alwaysCastMode',
}

/**
 * Settings storage manager
 */
export class SettingsStorage {
  isAlwaysCastMode(): boolean {
    return mainStorage.getBool(SettingsKeys.ALWAYS_CAST_MODE);
  }

  setAlwaysCastMode(enabled: boolean): void {
    mainStorage.setBool(SettingsKeys.ALWAYS_CAST_MODE, enabled);
  }

  // Theme settings
  getPrimaryColor(): string {
    return mainStorage.getString(SettingsKeys.PRIMARY_COLOR) || '#F5C26B';
  }

  setPrimaryColor(color: string): void {
    mainStorage.setString(SettingsKeys.PRIMARY_COLOR, color);
  }

  isCustomTheme(): boolean {
    return mainStorage.getBool(SettingsKeys.IS_CUSTOM_THEME);
  }

  setCustomTheme(isCustom: boolean): void {
    mainStorage.setBool(SettingsKeys.IS_CUSTOM_THEME, isCustom);
  }

  getCustomColor(): string {
    return mainStorage.getString(SettingsKeys.CUSTOM_COLOR) || '#FFFFFF';
  }

  setCustomColor(color: string): void {
    mainStorage.setString(SettingsKeys.CUSTOM_COLOR, color);
  }

  /**
   * Accent source for the Material 3 palette. `wallpaper` follows Material You
   * (Android 12+), `custom` derives the palette from the stored seed color.
   */
  getAccentSource(): 'wallpaper' | 'custom' {
    return mainStorage.getString(SettingsKeys.ACCENT_SOURCE) === 'wallpaper'
      ? 'wallpaper'
      : 'custom';
  }

  setAccentSource(source: 'wallpaper' | 'custom'): void {
    mainStorage.setString(SettingsKeys.ACCENT_SOURCE, source);
  }

  isDynamicInfoAccentEnabled(): boolean {
    return mainStorage.getBool(SettingsKeys.DYNAMIC_INFO_ACCENT, true);
  }

  setDynamicInfoAccentEnabled(enabled: boolean): void {
    mainStorage.setBool(SettingsKeys.DYNAMIC_INFO_ACCENT, enabled);
  }

  // UI preferences
  showTabBarLabels(): boolean {
    return mainStorage.getBool(SettingsKeys.SHOW_TAB_BAR_LABELS, true);
  }

  setShowTabBarLabels(show: boolean): void {
    mainStorage.setBool(SettingsKeys.SHOW_TAB_BAR_LABELS, show);
  }

  isHapticFeedbackEnabled(): boolean {
    return mainStorage.getBool(SettingsKeys.HAPTIC_FEEDBACK, true);
  }
  setHapticFeedbackEnabled(enabled: boolean): void {
    mainStorage.setBool(SettingsKeys.HAPTIC_FEEDBACK, enabled);
  }

  // Player settings
  showMediaControls(): boolean {
    return mainStorage.getBool(SettingsKeys.SHOW_MEDIA_CONTROLS, true);
  }

  setShowMediaControls(show: boolean): void {
    mainStorage.setBool(SettingsKeys.SHOW_MEDIA_CONTROLS, show);
  }

  showHamburgerMenu(): boolean {
    return mainStorage.getBool(SettingsKeys.SHOW_HAMBURGER_MENU, true);
  }

  setShowHamburgerMenu(show: boolean): void {
    mainStorage.setBool(SettingsKeys.SHOW_HAMBURGER_MENU, show);
  }

  hideSeekButtons(): boolean {
    return mainStorage.getBool(SettingsKeys.HIDE_SEEK_BUTTONS, false);
  }

  setHideSeekButtons(hide: boolean): void {
    mainStorage.setBool(SettingsKeys.HIDE_SEEK_BUTTONS, hide);
  }

  showPlayerEpisodeSidebar(): boolean {
    return mainStorage.getBool(SettingsKeys.SHOW_PLAYER_EPISODE_SIDEBAR, true);
  }

  setShowPlayerEpisodeSidebar(show: boolean): void {
    mainStorage.setBool(SettingsKeys.SHOW_PLAYER_EPISODE_SIDEBAR, show);
  }

  isEnable2xGestureEnabled(): boolean {
    return mainStorage.getBool(SettingsKeys.ENABLE_2X_GESTURE, false);
  }

  setEnable2xGesture(enabled: boolean): void {
    mainStorage.setBool(SettingsKeys.ENABLE_2X_GESTURE, enabled);
  }

  isSwipeGestureEnabled(): boolean {
    return mainStorage.getBool(SettingsKeys.ENABLE_SWIPE_GESTURE, true);
  }

  setSwipeGestureEnabled(enabled: boolean): void {
    mainStorage.setBool(SettingsKeys.ENABLE_SWIPE_GESTURE, enabled);
  }

  // Subtitle settings
  getSubtitleFontSize(): number {
    return mainStorage.getNumber(SettingsKeys.SUBTITLE_FONT_SIZE) ?? 16;
  }

  setSubtitleFontSize(size: number): void {
    mainStorage.setNumber(SettingsKeys.SUBTITLE_FONT_SIZE, size);
  }

  getSubtitleOpacity(): number {
    const opacityStr = mainStorage.getString(SettingsKeys.SUBTITLE_OPACITY);
    return opacityStr !== undefined && opacityStr !== '' ? parseFloat(opacityStr) : 1;
  }

  setSubtitleOpacity(opacity: number): void {
    mainStorage.setString(SettingsKeys.SUBTITLE_OPACITY, opacity.toString());
  }

  getSubtitleBottomPadding(): number {
    return mainStorage.getNumber(SettingsKeys.SUBTITLE_BOTTOM_PADDING) ?? 10;
  }

  setSubtitleBottomPadding(padding: number): void {
    mainStorage.setNumber(SettingsKeys.SUBTITLE_BOTTOM_PADDING, padding);
  }

  getSubtitleTextColor(): string {
    return mainStorage.getString(SettingsKeys.SUBTITLE_TEXT_COLOR) || '#FFFFFF';
  }

  setSubtitleTextColor(color: string): void {
    mainStorage.setString(SettingsKeys.SUBTITLE_TEXT_COLOR, color);
  }

  getSubtitleFontFamily(): string {
    return mainStorage.getString(SettingsKeys.SUBTITLE_FONT_FAMILY) || 'default';
  }

  setSubtitleFontFamily(font: string): void {
    mainStorage.setString(SettingsKeys.SUBTITLE_FONT_FAMILY, font);
  }

  getSubtitleEdgeType(): 'outline' | 'dropShadow' | 'raised' | 'depressed' | 'none' {
    const val = mainStorage.getString(SettingsKeys.SUBTITLE_EDGE_TYPE);
    if (
      val === 'dropShadow' ||
      val === 'raised' ||
      val === 'depressed' ||
      val === 'none'
    ) {
      return val;
    }
    return 'outline';
  }

  setSubtitleEdgeType(
    edgeType: 'outline' | 'dropShadow' | 'raised' | 'depressed' | 'none',
  ): void {
    mainStorage.setString(SettingsKeys.SUBTITLE_EDGE_TYPE, edgeType);
  }

  getSubtitleEdgeColor(): string {
    return mainStorage.getString(SettingsKeys.SUBTITLE_EDGE_COLOR) || '#000000';
  }

  setSubtitleEdgeColor(color: string): void {
    mainStorage.setString(SettingsKeys.SUBTITLE_EDGE_COLOR, color);
  }

  getSubtitleOutlineWidth(): number {
    return mainStorage.getNumber(SettingsKeys.SUBTITLE_OUTLINE_WIDTH) ?? 2;
  }

  setSubtitleOutlineWidth(width: number): void {
    mainStorage.setNumber(SettingsKeys.SUBTITLE_OUTLINE_WIDTH, width);
  }

  getListViewType(): number {
    return parseInt(
      mainStorage.getString(SettingsKeys.LIST_VIEW_TYPE) || '1',
      10,
    );
  }

  setListViewType(type: number): void {
    mainStorage.setString(SettingsKeys.LIST_VIEW_TYPE, type.toString());
  }

  // Generic get/set methods for settings not covered by specific methods
  getBool(key: string, defaultValue = false): boolean {
    return mainStorage.getBool(key, defaultValue);
  }

  setBool(key: string, value: boolean): void {
    mainStorage.setBool(key, value);
  }

}

// Export a singleton instance
export const settingsStorage = new SettingsStorage();
