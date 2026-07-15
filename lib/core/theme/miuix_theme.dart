import 'package:flutter/material.dart';

import 'theme_ext.dart';

/// MIUIx 风格主题：纯黑背景、圆角矩形卡片、蓝色强调。
class MiuixTheme {
  static const _accentBlue = Color(0xFF267AF7);
  static const _accentOrange = Color(0xFFFF6A00);

  static ThemeData build({required Brightness brightness}) {
    final isDark = brightness == Brightness.dark;
    final scheme = ColorScheme.fromSeed(
      seedColor: _accentBlue,
      brightness: brightness,
    ).copyWith(
      primary: _accentBlue,
      secondary: _accentOrange,
      surface: isDark ? const Color(0xFF242424) : Colors.white,
    );

    final bg = isDark ? const Color(0xFF000000) : const Color(0xFFF2F3F5);

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      brightness: brightness,
      scaffoldBackgroundColor: bg,
      dividerColor: isDark ? const Color(0xFF2C2C2E) : const Color(0xFFE5E6EB),
      cardTheme: CardThemeData(
        elevation: 0,
        color: isDark ? const Color(0xFF242424) : Colors.white,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppStyle.miuix.cardRadius),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: isDark ? const Color(0xFF242424) : Colors.white,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return Colors.white;
          }
          return null;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return const Color(0xFF267AF7);
          }
          return null;
        }),
      ),
      textTheme: isDark
          ? const TextTheme(
              bodySmall: TextStyle(color: Color(0xFF7D7D7D)),
              bodyMedium: TextStyle(color: Color(0xFF7D7D7D)),
            )
          : null,
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: isDark ? const Color(0xFF434343) : const Color(0xFFF0F0F0),
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
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: _accentBlue,
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
        ),
      ),
      chipTheme: ChipThemeData(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: const BorderSide(color: Color(0xFF242424), width: 1),
        ),
        side: const BorderSide(color: Color(0xFF242424), width: 1),
        backgroundColor: Colors.black,
        labelStyle: const TextStyle(color: Color(0xFF7D7D7D)),
        selectedColor: const Color(0xFFBBC4EF),
        checkmarkColor: Colors.white,
        showCheckmark: false,
      ),
      floatingActionButtonTheme: const FloatingActionButtonThemeData(
        backgroundColor: Color(0xFF4C6FFF),
        foregroundColor: Colors.white,
      ),
      extensions: const [AppStyle.miuix],
    );
  }
}
