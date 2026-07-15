import 'package:flutter/material.dart';

import 'theme_ext.dart';

/// Material You 主题：dynamic_color 取系统色（在 app.dart 注入），
/// 无动态色时用 seed 兜底。
class MaterialTheme {
  static ThemeData build({
    required Brightness brightness,
    ColorScheme? dynamicScheme,
  }) {
    final isDark = brightness == Brightness.dark;
    final scheme = dynamicScheme ??
        ColorScheme.fromSeed(
          seedColor: const Color(0xFF4C6FFF),
          brightness: brightness,
        );

    final bg = isDark ? const Color(0xFF181820) : const Color(0xFFF5F5F5);

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      brightness: brightness,
      scaffoldBackgroundColor: bg,
      cardTheme: CardThemeData(
        elevation: 0,
        color: isDark ? const Color(0xFF292C33) : Colors.white,
        shadowColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppStyle.material.cardRadius),
        ),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: bg,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: isDark ? const Color(0xFF292C33) : Colors.white,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
      ),
      navigationRailTheme: NavigationRailThemeData(
        backgroundColor: isDark ? const Color(0xFF292C33) : Colors.white,
        indicatorColor: scheme.primaryContainer,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: isDark
            ? const Color(0xFF292C33)
            : scheme.surfaceContainerHighest.withValues(alpha: 0.5),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: Color(0xFFBBC4EF)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: Color(0xFFBBC4EF)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: Color(0xFFBBC4EF), width: 1.5),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: isDark
            ? const Color(0xFF3A3D44)
            : scheme.outlineVariant.withValues(alpha: 0.3),
        thickness: 0.5,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return const Color(0xFFBBC4EF);
          }
          return null;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return const Color(0xFFBBC4EF).withValues(alpha: 0.3);
          }
          return null;
        }),
      ),
      textTheme: isDark
          ? const TextTheme(
              bodySmall: TextStyle(color: Color(0xFFA8AAB6)),
              bodyMedium: TextStyle(color: Color(0xFFA8AAB6)),
            )
          : null,
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      floatingActionButtonTheme: const FloatingActionButtonThemeData(
        backgroundColor: Color(0xFF267AF7),
        foregroundColor: Colors.white,
      ),
      extensions: const [AppStyle.material],
    );
  }
}
