import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:window_manager/window_manager.dart';

import 'app.dart';
import 'core/db/database.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final isDesktop = Platform.isWindows || Platform.isLinux || Platform.isMacOS;

  if (isDesktop) {
    // 桌面必须先初始化 sqflite ffi（计划 §0）。
    AppDatabase.initFfi();

    // 窗口：可自由缩放，设合理初始/最小尺寸，便于预览手机/平板断点切换。
    await windowManager.ensureInitialized();
    const options = WindowOptions(
      size: Size(1100, 760),
      minimumSize: Size(360, 600), // 允许缩到手机窄屏宽度以预览自适应
      center: true,
      title: '元记',
      titleBarStyle: TitleBarStyle.normal,
    );
    windowManager.waitUntilReadyToShow(options, () async {
      await windowManager.show();
      await windowManager.focus();
    });
  }

  runApp(const ProviderScope(child: AiTokenVaultApp()));
}
