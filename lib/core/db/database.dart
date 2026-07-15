import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'migrations.dart';

/// 数据库门面：桌面用 ffi，移动端用默认 sqflite factory。
///
/// main.dart 桌面分支必须先调用 [AppDatabase.initFfi]（见计划 §7）。
class AppDatabase {
  AppDatabase._(this.db);

  final Database db;

  static const String _fileName = 'ai_token_vault.db';
  static AppDatabase? _instance;

  /// 桌面（Windows/Linux/macOS）初始化 ffi。移动端无需调用。
  static void initFfi() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  }

  static bool get _isDesktop =>
      Platform.isWindows || Platform.isLinux || Platform.isMacOS;

  /// 打开（或复用）数据库。
  static Future<AppDatabase> open() async {
    if (_instance != null) return _instance!;

    final dbPath = await _resolvePath();
    final factory = _isDesktop ? databaseFactoryFfi : databaseFactory;

    final db = await factory.openDatabase(
      dbPath,
      options: OpenDatabaseOptions(
        version: Migrations.latestVersion,
        onConfigure: (d) async {
          await d.execute('PRAGMA foreign_keys = ON;');
        },
        onCreate: Migrations.onCreate,
        onUpgrade: Migrations.onUpgrade,
      ),
    );

    _instance = AppDatabase._(db);
    return _instance!;
  }

  static Future<String> _resolvePath() async {
    final dir = await getApplicationSupportDirectory();
    if (!await dir.exists()) await dir.create(recursive: true);
    return p.join(dir.path, _fileName);
  }

  Future<void> close() async {
    await db.close();
    _instance = null;
  }
}
