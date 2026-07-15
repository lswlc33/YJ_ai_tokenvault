import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// settings KV 表原始读写。加密由上层（SettingsRepository）决定。
class SettingsDao {
  SettingsDao(this._db);
  final Database _db;

  Future<Map<String, String>> getAll() async {
    final rows = await _db.query('settings');
    return {
      for (final r in rows)
        (r['key'] as String): (r['value'] as String? ?? ''),
    };
  }

  Future<String?> get(String key) async {
    final rows = await _db
        .query('settings', where: 'key = ?', whereArgs: [key], limit: 1);
    if (rows.isEmpty) return null;
    return rows.first['value'] as String?;
  }

  Future<void> put(String key, String? value, {DatabaseExecutor? txn}) async {
    await (txn ?? _db).insert(
      'settings',
      {'key': key, 'value': value},
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<void> putAll(Map<String, String?> kv, {DatabaseExecutor? txn}) async {
    final exec = txn ?? _db;
    final batch = exec.batch();
    kv.forEach((k, v) {
      batch.insert('settings', {'key': k, 'value': v},
          conflictAlgorithm: ConflictAlgorithm.replace);
    });
    await batch.commit(noResult: true);
  }

  Future<void> delete(String key) async {
    await _db.delete('settings', where: 'key = ?', whereArgs: [key]);
  }
}
