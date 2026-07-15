import 'dart:typed_data';

import '../core/crypto/crypto_service.dart';
import '../core/crypto/field_cipher.dart';
import '../core/db/database.dart';
import '../core/db/daos/api_key_dao.dart';
import '../core/db/daos/category_dao.dart';
import '../core/db/daos/provider_dao.dart';
import '../core/db/daos/settings_dao.dart';
import '../models/api_key.dart';
import '../models/category.dart';
import '../models/provider.dart';
import '../models/provider_with_keys.dart';

/// 金库仓库：厂家 / Key / 分类 的读写编排。
/// 依赖内存 vaultKey，锁定后应丢弃本实例并重建。
class VaultRepository {
  VaultRepository(this._appDb, this._crypto, Uint8List vaultKey)
      : _cipher = FieldCipher(_crypto, vaultKey),
        _vaultKey = vaultKey {
    _categoryDao = CategoryDao(_appDb.db);
    _providerDao = ProviderDao(_appDb.db, _cipher);
    _apiKeyDao = ApiKeyDao(_appDb.db, _cipher);
    _settingsDao = SettingsDao(_appDb.db);
  }

  final AppDatabase _appDb;
  final CryptoService _crypto;
  final FieldCipher _cipher;
  final Uint8List _vaultKey;

  late final CategoryDao _categoryDao;
  late final ProviderDao _providerDao;
  late final ApiKeyDao _apiKeyDao;
  late final SettingsDao _settingsDao;

  // ---- 分类 ----
  Future<List<Category>> categories() => _categoryDao.getAll();
  Future<int> addCategory(Category c) => _categoryDao.insert(c);
  Future<void> updateCategory(Category c) => _categoryDao.update(c);
  Future<void> deleteCategory(int id) => _categoryDao.delete(id);
  Future<void> reorderCategories(List<int> orderedIds) => _categoryDao.reorder(orderedIds);

  // ---- 仪表盘：厂家 + 其 Key 组合 ----
  Future<List<ProviderWithKeys>> dashboard({int? categoryId}) async {
    final providers = categoryId == null
        ? await _providerDao.getAll()
        : await _providerDao.getByCategory(categoryId);
    final allKeys = await _apiKeyDao.getAll();
    final keysByProvider = <int, List<ApiKey>>{};
    for (final k in allKeys) {
      keysByProvider.putIfAbsent(k.providerId, () => []).add(k);
    }
    return [
      for (final p in providers)
        ProviderWithKeys(provider: p, keys: keysByProvider[p.id] ?? []),
    ];
  }

  Future<ProviderWithKeys?> providerDetail(int providerId) async {
    final p = await _providerDao.getById(providerId);
    if (p == null) return null;
    final keys = await _apiKeyDao.getByProvider(providerId);
    return ProviderWithKeys(provider: p, keys: keys);
  }

  // ---- 保存整张厂家卡片（厂家 + 其下多个 Key），事务包裹 ----
  /// 传入 provider（id==0 为新建）与要落库的 keys 列表；[deletedKeyIds] 为需删除的 Key。
  /// 返回 provider id。
  Future<int> saveProvider(
    Provider provider,
    List<ApiKey> keys, {
    List<int> deletedKeyIds = const [],
  }) async {
    return _appDb.db.transaction<int>((txn) async {
      int providerId = provider.id;
      if (providerId == 0) {
        providerId = await _providerDao.insert(provider, txn: txn);
      } else {
        await _providerDao.update(provider, txn: txn);
      }
      for (final id in deletedKeyIds) {
        await _apiKeyDao.delete(id, txn: txn);
      }
      for (final k in keys) {
        final key = k.copyWith(providerId: providerId);
        if (key.id == 0) {
          await _apiKeyDao.insert(key, txn: txn);
        } else {
          await _apiKeyDao.update(key, txn: txn);
        }
      }
      return providerId;
    });
  }

  Future<void> deleteProvider(int id) async {
    // api_keys 由外键 ON DELETE CASCADE 一并删除。
    await _providerDao.delete(id);
  }

  // ---- 单 Key 操作 ----
  Future<int> addKey(ApiKey key) => _apiKeyDao.insert(key);
  Future<void> updateKey(ApiKey key) => _apiKeyDao.update(key);
  Future<void> deleteKey(int id) => _apiKeyDao.delete(id);

  Future<void> clearProbeCache() => _apiKeyDao.clearAllProbeResults();

  Future<void> saveProbeResult(
    int keyId, {
    required int status,
    double? balance,
    String? balanceText,
    required int checkedAt,
  }) =>
      _apiKeyDao.updateProbeResult(
        keyId,
        status: status,
        balance: balance,
        balanceText: balanceText,
        lastCheckedAt: checkedAt,
      );

  // ---- 全量导出（备份用）：敏感列保持入库时的加密形态 ----
  Future<Map<String, List<Map<String, Object?>>>> exportRaw() async {
    final db = _appDb.db;
    return {
      'categories': await db.query('categories'),
      'providers': await db.query('providers'),
      'api_keys': await db.query('api_keys'),
    };
  }

  /// 换 PIN：逐条把敏感密文用 oldKey→newKey 重加密（子线程），事务替换。
  /// PIN 元数据（新盐 / 迭代数 / verifier）在**同一事务**内用 txn 写入，
  /// 避免事务进行中再用外层 db 造成 sqflite 死锁。
  Future<void> reEncryptAll(
    Uint8List newKey, {
    required String saltB64,
    required int iterations,
    required String pinVerifier,
  }) async {
    final oldKey = _vaultKey;
    final db = _appDb.db;

    final providers = await db.query('providers');
    final keys = await db.query('api_keys');
    final settings = await _settingsDao.getAll();

    // 收集所有需重加密的密文（顺序固定，便于回填）
    final providerCts = [for (final r in providers) r['base_url_enc'] as String?];
    final keyCts = [for (final r in keys) r['api_key_enc'] as String?];
    final webdavUser = settings['webdav_user_enc'];
    final webdavPass = settings['webdav_pass_enc'];

    final reProvider = await _crypto.reEncryptAll(providerCts, oldKey, newKey);
    final reKey = await _crypto.reEncryptAll(keyCts, oldKey, newKey);
    final reWebdav = await _crypto.reEncryptAll(
        [webdavUser, webdavPass], oldKey, newKey);

    await db.transaction((txn) async {
      for (var i = 0; i < providers.length; i++) {
        await txn.update('providers', {'base_url_enc': reProvider[i]},
            where: 'id = ?', whereArgs: [providers[i]['id']]);
      }
      for (var i = 0; i < keys.length; i++) {
        await txn.update('api_keys', {'api_key_enc': reKey[i]},
            where: 'id = ?', whereArgs: [keys[i]['id']]);
      }
      if (webdavUser != null) {
        await _settingsDao.put('webdav_user_enc', reWebdav[0], txn: txn);
      }
      if (webdavPass != null) {
        await _settingsDao.put('webdav_pass_enc', reWebdav[1], txn: txn);
      }
      // 在同一事务内提交 PIN 元数据。
      await _settingsDao.putAll({
        'kdf_salt': saltB64,
        'kdf_iterations': '$iterations',
        'pin_verifier': pinVerifier,
      }, txn: txn);
    });
  }
}
