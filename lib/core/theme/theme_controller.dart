import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../models/app_settings.dart';
import '../../state/providers.dart';

/// 主题设置状态（风格 + 深浅色）。
class ThemeSettings {
  const ThemeSettings({
    this.style = AppThemeStyle.material,
    this.darkMode = AppDarkMode.system,
  });
  final AppThemeStyle style;
  final AppDarkMode darkMode;

  ThemeSettings copyWith({AppThemeStyle? style, AppDarkMode? darkMode}) =>
      ThemeSettings(
        style: style ?? this.style,
        darkMode: darkMode ?? this.darkMode,
      );
}

class ThemeController extends StateNotifier<ThemeSettings> {
  ThemeController(this._ref) : super(const ThemeSettings()) {
    _load();
  }
  final Ref _ref;

  Future<void> _load() async {
    try {
      final repo = await _ref.read(settingsRepositoryProvider.future);
      final s = await repo.load();
      state = ThemeSettings(style: s.themeStyle, darkMode: s.darkMode);
    } catch (_) {/* 保持默认 */}
  }

  Future<void> setStyle(AppThemeStyle style) async {
    state = state.copyWith(style: style);
    final repo = await _ref.read(settingsRepositoryProvider.future);
    await repo.setThemeStyle(style);
  }

  Future<void> setDarkMode(AppDarkMode mode) async {
    state = state.copyWith(darkMode: mode);
    final repo = await _ref.read(settingsRepositoryProvider.future);
    await repo.setDarkMode(mode);
  }
}

final themeControllerProvider =
    StateNotifierProvider<ThemeController, ThemeSettings>((ref) {
  return ThemeController(ref);
});
