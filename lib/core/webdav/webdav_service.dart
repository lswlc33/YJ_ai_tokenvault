import 'dart:convert';
import 'dart:typed_data';

import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:webdav_client/webdav_client.dart' as webdav;

import '../../core/db/database.dart';
import '../../core/db/migrations.dart';
import '../../repositories/settings_repository.dart';
import '../../repositories/vault_repository.dart';

/// WebDAV 备份服务。
class WebdavBackupService {
  WebdavBackupService(this._settingsRepo, this._vaultRepo, this._vaultKey);

  final SettingsRepository _settingsRepo;
  final VaultRepository _vaultRepo;
  final Uint8List _vaultKey;

  /// 获取 WebDAV 客户端。
  Future<webdav.Client?> _getClient() async {
    final settings = await _settingsRepo.load();
    final url = settings.webdavUrl;
    if (url == null || url.isEmpty) return null;

    final creds = await _settingsRepo.readWebdavCreds(_vaultKey);
    if (creds == null) return null;

    return webdav.newClient(url, user: creds.$1, password: creds.$2);
  }

  /// 备份到 WebDAV。
  Future<WebdavBackupResult> backup() async {
    final client = await _getClient();
    if (client == null) {
      return const WebdavBackupResult.failure('未配置 WebDAV');
    }

    try {
      final settings = await _settingsRepo.load();
      final dir = settings.webdavDir;

      // 确保远程目录存在
      await client.mkdir(dir);

      // 导出数据
      final raw = await _vaultRepo.exportRaw();
      final data = <String, dynamic>{
        'schemaVersion': Migrations.latestVersion,
        ...raw,
      };
      final json = jsonEncode(data);

      // 生成文件名
      final now = DateTime.now();
      final filename =
          'backup_${now.year}${now.month.toString().padLeft(2, '0')}${now.day.toString().padLeft(2, '0')}_${now.hour.toString().padLeft(2, '0')}${now.minute.toString().padLeft(2, '0')}.json';
      final remotePath = '$dir/$filename';

      // 上传
      final bytes = utf8.encode(json);
      await client.write(remotePath, bytes);

      // 更新最后备份时间
      await _settingsRepo.setLastArchiveAt(now.millisecondsSinceEpoch);

      return WebdavBackupResult.success(remotePath);
    } catch (e) {
      return WebdavBackupResult.failure('备份失败：$e');
    }
  }

  /// 从 WebDAV 恢复。
  Future<WebdavRestoreResult> restore(String remotePath) async {
    final client = await _getClient();
    if (client == null) {
      return const WebdavRestoreResult.failure('未配置 WebDAV');
    }

    try {
      final content = await client.read(remotePath);
      final json = utf8.decode(content);
      final data = jsonDecode(json) as Map<String, dynamic>;

      // 验证数据结构
      if (!data.containsKey('categories') ||
          !data.containsKey('providers') ||
          !data.containsKey('api_keys')) {
        return const WebdavRestoreResult.failure('备份文件格式不正确');
      }

      // 验证 schema 版本
      final backupVersion = data['schemaVersion'] as int?;
      if (backupVersion == null) {
        return const WebdavRestoreResult.failure('备份文件缺少版本信息，可能来自旧版本');
      }
      if (backupVersion > Migrations.latestVersion) {
        return WebdavRestoreResult.failure(
            '备份文件版本($backupVersion)高于当前版本(${Migrations.latestVersion})，请先升级应用');
      }

      // 写入数据库
      final db = await _getDb();
      await db.transaction((txn) async {
        // 清空现有数据
        await txn.delete('api_keys');
        await txn.delete('providers');
        await txn.delete('categories');

        // 恢复分类
        for (final cat in data['categories'] as List) {
          await txn.insert('categories', cat as Map<String, Object?>);
        }

        // 恢复厂家
        for (final prov in data['providers'] as List) {
          await txn.insert('providers', prov as Map<String, Object?>);
        }

        // 恢复 API Keys
        for (final key in data['api_keys'] as List) {
          await txn.insert('api_keys', key as Map<String, Object?>);
        }
      });

      return const WebdavRestoreResult.success();
    } catch (e) {
      return WebdavRestoreResult.failure('恢复失败：$e');
    }
  }

  /// 列出远程备份文件。
  Future<List<WebdavBackupEntry>> listBackups() async {
    final client = await _getClient();
    if (client == null) return [];

    try {
      final settings = await _settingsRepo.load();
      final dir = settings.webdavDir;

      final files = await client.readDir(dir);
      return files
          .where((f) => f.name != null && f.name!.endsWith('.json'))
          .map((f) => WebdavBackupEntry(
                name: f.name!,
                path: '$dir/${f.name}',
                size: f.size ?? 0,
                lastModified: f.mTime,
              ))
          .toList()
        ..sort((a, b) => b.name.compareTo(a.name));
    } catch (e) {
      return [];
    }
  }

  /// 删除远程备份文件。
  Future<bool> deleteBackup(String remotePath) async {
    final client = await _getClient();
    if (client == null) return false;

    try {
      await client.remove(remotePath);
      return true;
    } catch (e) {
      return false;
    }
  }

  /// 测试 WebDAV 连接。
  Future<bool> testConnection() async {
    final client = await _getClient();
    if (client == null) return false;

    try {
      await client.ping();
      return true;
    } catch (e) {
      return false;
    }
  }

  Future<Database> _getDb() async {
    final appDb = await AppDatabase.open();
    return appDb.db;
  }
}

/// 备份结果。
class WebdavBackupResult {
  const WebdavBackupResult.success(this.remotePath)
      : ok = true,
        error = null;
  const WebdavBackupResult.failure(this.error)
      : ok = false,
        remotePath = null;

  final bool ok;
  final String? remotePath;
  final String? error;
}

/// 恢复结果。
class WebdavRestoreResult {
  const WebdavRestoreResult.success()
      : ok = true,
        error = null;
  const WebdavRestoreResult.failure(String? errorMessage)
      : ok = false,
        error = errorMessage;

  final bool ok;
  final String? error;
}

/// 备份文件条目。
class WebdavBackupEntry {
  const WebdavBackupEntry({
    required this.name,
    required this.path,
    required this.size,
    this.lastModified,
  });

  final String name;
  final String path;
  final int size;
  final DateTime? lastModified;
}
