import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/theme/material_theme.dart';
import 'core/theme/miuix_theme.dart';
import 'core/theme/theme_controller.dart';
import 'features/home/home_shell.dart';
import 'features/lock/onboarding_screen.dart';
import 'features/lock/unlock_screen.dart';
import 'models/app_settings.dart';
import 'state/lock_controller.dart';

class AiTokenVaultApp extends ConsumerStatefulWidget {
  const AiTokenVaultApp({super.key});

  @override
  ConsumerState<AiTokenVaultApp> createState() => _AiTokenVaultAppState();
}

class _AiTokenVaultAppState extends ConsumerState<AiTokenVaultApp>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 自动锁定门卫（计划 §1.5）。
    final resumed = state == AppLifecycleState.resumed;
    final paused = state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden ||
        state == AppLifecycleState.inactive;
    if (resumed || paused) {
      ref.read(lockControllerProvider.notifier).onLifecycleChanged(resumed);
    }
  }

  ThemeMode _themeMode(AppDarkMode m) {
    switch (m) {
      case AppDarkMode.light:
        return ThemeMode.light;
      case AppDarkMode.dark:
        return ThemeMode.dark;
      case AppDarkMode.system:
        return ThemeMode.system;
    }
  }

  @override
  Widget build(BuildContext context) {
    final themeSettings = ref.watch(themeControllerProvider);

    return DynamicColorBuilder(
      builder: (lightDynamic, darkDynamic) {
        final ThemeData light;
        final ThemeData dark;
        if (themeSettings.style == AppThemeStyle.miuix) {
          light = MiuixTheme.build(brightness: Brightness.light);
          dark = MiuixTheme.build(brightness: Brightness.dark);
        } else {
          light = MaterialTheme.build(
              brightness: Brightness.light, dynamicScheme: lightDynamic);
          dark = MaterialTheme.build(
              brightness: Brightness.dark, dynamicScheme: darkDynamic);
        }

        return MaterialApp(
          title: '元记',
          debugShowCheckedModeBanner: false,
          theme: light,
          darkTheme: dark,
          themeMode: _themeMode(themeSettings.darkMode),
          home: const _Gate(),
        );
      },
    );
  }
}

/// 锁屏门卫：按 LockPhase 决定展示引导 / 解锁 / 主界面。
class _Gate extends ConsumerWidget {
  const _Gate();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final phase = ref.watch(lockControllerProvider).phase;
    final child = switch (phase) {
      LockPhase.loading =>
        const Scaffold(body: Center(child: CircularProgressIndicator())),
      LockPhase.onboarding => const OnboardingScreen(),
      LockPhase.locked => const UnlockScreen(),
      LockPhase.unlocked => const HomeShell(),
    };
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 250),
      child: KeyedSubtree(key: ValueKey(phase), child: child),
    );
  }
}
