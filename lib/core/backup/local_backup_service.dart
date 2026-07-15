import 'dart:convert';

import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../../core/db/database.dart';
import '../../core/db/migrations.dart';
import '../../repositories/vault_repository.dart';

/// 本地备份服务：导出/导入 JSON 备份文件。
class LocalBackupService {
  LocalBackupService(this._vaultRepo);

  final VaultRepository _vaultRepo;

  /// 导出为 JSON 字符串（与 WebDAV 备份格式一致）。
  Future<String> exportData() async {
    final raw = await _vaultRepo.exportRaw();
    final data = <String, dynamic>{
      'schemaVersion': Migrations.latestVersion,
      ...raw,
    };
    return jsonEncode(data);
  }

  /// 从 JSON 字符串恢复数据。
  Future<LocalRestoreResult> importFromJson(String jsonStr) async {
    try {
      final data = jsonDecode(jsonStr) as Map<String, dynamic>;

      if (!data.containsKey('categories') ||
          !data.containsKey('providers') ||
          !data.containsKey('api_keys')) {
        return const LocalRestoreResult.failure('备份文件格式不正确');
      }

      final backupVersion = data['schemaVersion'] as int?;
      if (backupVersion == null) {
        return const LocalRestoreResult.failure('备份文件缺少版本信息，可能来自旧版本');
      }
      if (backupVersion > Migrations.latestVersion) {
        return LocalRestoreResult.failure(
            '备份文件版本($backupVersion)高于当前版本(${Migrations.latestVersion})，请先升级应用');
      }

      final db = await _getDb();
      await db.transaction((txn) async {
        await txn.delete('api_keys');
        await txn.delete('providers');
        await txn.delete('categories');

        for (final cat in data['categories'] as List) {
          await txn.insert('categories', cat as Map<String, Object?>);
        }
        for (final prov in data['providers'] as List) {
          await txn.insert('providers', prov as Map<String, Object?>);
        }
        for (final key in data['api_keys'] as List) {
          await txn.insert('api_keys', key as Map<String, Object?>);
        }
      });

      return const LocalRestoreResult.success();
    } catch (e) {
      return LocalRestoreResult.failure('恢复失败：$e');
    }
  }

  Future<Database> _getDb() async {
    final appDb = await AppDatabase.open();
    return appDb.db;
  }
}

class LocalRestoreResult {
  const LocalRestoreResult.success()
      : ok = true,
        error = null;
  const LocalRestoreResult.failure(String? errorMessage)
      : ok = false,
        error = errorMessage;

  final bool ok;
  final String? error;
}
