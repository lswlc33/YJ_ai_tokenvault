import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../../../models/provider.dart';
import '../../crypto/field_cipher.dart';

/// 厂家 DAO：负责 base_url ↔ base_url_enc 的透明加解密。
class ProviderDao {
  ProviderDao(this._db, this._cipher);
  final Database _db;
  final FieldCipher _cipher;

  Future<List<Provider>> getAll() async {
    final rows =
        await _db.query('providers', orderBy: 'sort_order ASC, id ASC');
    return rows.map(_fromRow).toList();
  }

  Future<List<Provider>> getByCategory(int categoryId) async {
    final rows = await _db.query('providers',
        where: 'category_id = ?',
        whereArgs: [categoryId],
        orderBy: 'sort_order ASC, id ASC');
    return rows.map(_fromRow).toList();
  }

  Future<Provider?> getById(int id) async {
    final rows =
        await _db.query('providers', where: 'id = ?', whereArgs: [id], limit: 1);
    if (rows.isEmpty) return null;
    return _fromRow(rows.first);
  }

  Future<int> insert(Provider provider, {DatabaseExecutor? txn}) {
    return (txn ?? _db).insert('providers', _toRow(provider));
  }

  Future<void> update(Provider provider, {DatabaseExecutor? txn}) async {
    await (txn ?? _db).update('providers', _toRow(provider),
        where: 'id = ?', whereArgs: [provider.id]);
  }

  Future<void> delete(int id, {DatabaseExecutor? txn}) async {
    await (txn ?? _db).delete('providers', where: 'id = ?', whereArgs: [id]);
  }

  Provider _fromRow(Map<String, Object?> row) {
    final m = Map<String, Object?>.from(row);
    m['base_url'] = _cipher.dec(row['base_url_enc'] as String?);
    m.remove('base_url_enc');
    return Provider.fromMap(m);
  }

  Map<String, Object?> _toRow(Provider p) {
    final m = p.toMap();
    m['base_url_enc'] = _cipher.enc(p.baseUrl);
    m.remove('base_url');
    return m;
  }
}
