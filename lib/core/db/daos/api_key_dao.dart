import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../../../models/api_key.dart';
import '../../crypto/field_cipher.dart';

/// API Key DAO：负责 api_key ↔ api_key_enc 透明加解密。
class ApiKeyDao {
  ApiKeyDao(this._db, this._cipher);
  final Database _db;
  final FieldCipher _cipher;

  Future<List<ApiKey>> getByProvider(int providerId,
      {DatabaseExecutor? txn}) async {
    final rows = await (txn ?? _db).query('api_keys',
        where: 'provider_id = ?',
        whereArgs: [providerId],
        orderBy: 'sort_order ASC, id ASC');
    return rows.map(_fromRow).toList();
  }

  Future<List<ApiKey>> getAll() async {
    final rows = await _db.query('api_keys', orderBy: 'provider_id, sort_order');
    return rows.map(_fromRow).toList();
  }

  Future<ApiKey?> getById(int id) async {
    final rows =
        await _db.query('api_keys', where: 'id = ?', whereArgs: [id], limit: 1);
    if (rows.isEmpty) return null;
    return _fromRow(rows.first);
  }

  Future<int> insert(ApiKey key, {DatabaseExecutor? txn}) {
    return (txn ?? _db).insert('api_keys', _toRow(key));
  }

  Future<void> update(ApiKey key, {DatabaseExecutor? txn}) async {
    await (txn ?? _db).update('api_keys', _toRow(key),
        where: 'id = ?', whereArgs: [key.id]);
  }

  /// 仅更新探测结果字段（不碰加密的 api_key）。
  Future<void> updateProbeResult(
    int id, {
    required int status,
    double? balance,
    String? balanceText,
    required int lastCheckedAt,
    DatabaseExecutor? txn,
  }) async {
    await (txn ?? _db).update(
      'api_keys',
      {
        'status': status,
        'balance': balance,
        'balance_text': balanceText,
        'last_checked_at': lastCheckedAt,
      },
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<void> delete(int id, {DatabaseExecutor? txn}) async {
    await (txn ?? _db).delete('api_keys', where: 'id = ?', whereArgs: [id]);
  }

  Future<void> deleteByProvider(int providerId, {DatabaseExecutor? txn}) async {
    await (txn ?? _db)
        .delete('api_keys', where: 'provider_id = ?', whereArgs: [providerId]);
  }

  Future<void> clearAllProbeResults() async {
    await _db.update('api_keys', {
      'status': 0,
      'balance': null,
      'balance_text': null,
      'last_checked_at': null,
      'models': null,
    });
  }

  Future<void> updateModels(
    int id, {
    required List<String> models,
    DatabaseExecutor? txn,
  }) async {
    await (txn ?? _db).update(
      'api_keys',
      {'models': models.isEmpty ? null : models.join('\n')},
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  ApiKey _fromRow(Map<String, Object?> row) {
    final m = Map<String, Object?>.from(row);
    m['api_key'] = _cipher.dec(row['api_key_enc'] as String?);
    m.remove('api_key_enc');
    return ApiKey.fromMap(m);
  }

  Map<String, Object?> _toRow(ApiKey k) {
    final m = k.toMap();
    m['api_key_enc'] = _cipher.enc(k.apiKey);
    m.remove('api_key');
    return m;
  }
}
