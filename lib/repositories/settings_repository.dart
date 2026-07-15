import 'dart:convert';
import 'dart:typed_data';

import 'package:pointycastle/export.dart' show InvalidCipherTextException;

import '../core/crypto/aes_gcm.dart';
import '../core/crypto/crypto_service.dart';
import '../core/crypto/kdf.dart';
import '../core/db/daos/settings_dao.dart';
import '../models/app_settings.dart';

/// 解锁结果：成功时携带内存 vaultKey。
class UnlockResult {
  const UnlockResult.success(this.vaultKey)
      : ok = true;
  const UnlockResult.failure()
      : ok = false,
        vaultKey = null;

  final bool ok;
  final Uint8List? vaultKey;
}

/// 设置 & 安全生命周期仓库（计划 §1.3 / §1.4）。
class SettingsRepository {
  SettingsRepository(this._dao, this._crypto);

  final SettingsDao _dao;
  final CryptoService _crypto;

  static const String _magic = 'VAULT_OK';

  /// 是否已设置过 PIN（即已完成首启引导）。
  Future<bool> hasPin() async =>
      (await _dao.get(SettingsKeys.pinVerifier)) != null;

  /// 首次设置 PIN：生成盐 → 派生 key → 加密魔法串存 pin_verifier。
  /// 返回内存 vaultKey（调用方持有以解锁会话）。
  Future<Uint8List> setupPin(String pin) async {
    final salt = _crypto.newSalt();
    const iters = Kdf.defaultIterations;
    final key = await _crypto.deriveKey(pin, salt, iterations: iters);
    final verifier = AesGcm.encryptString(_magic, key);

    await _dao.putAll({
      SettingsKeys.kdfSalt: base64.encode(salt),
      SettingsKeys.kdfIterations: '$iters',
      SettingsKeys.pinVerifier: verifier,
    });
    return key;
  }

  /// 用 PIN 尝试解锁：派生 key → 解密 verifier → GCM tag 校验。
  /// 若存储的迭代数高于当前默认，解锁成功后静默降级迭代数记录（不更换 key）。
  Future<UnlockResult> unlock(String pin) async {
    final saltB64 = await _dao.get(SettingsKeys.kdfSalt);
    final verifier = await _dao.get(SettingsKeys.pinVerifier);
    if (saltB64 == null || verifier == null) {
      return const UnlockResult.failure();
    }
    final iters =
        int.tryParse(await _dao.get(SettingsKeys.kdfIterations) ?? '') ??
            Kdf.defaultIterations;
    final salt = base64.decode(saltB64);
    final key = await _crypto.deriveKey(pin, salt, iterations: iters);
    try {
      final plain = AesGcm.decryptString(verifier, key);
      if (plain != _magic) return const UnlockResult.failure();

      // 静默迁移：迭代数高于当前默认时，只更新记录，不更换 key。
      if (iters > Kdf.defaultIterations) {
        await _dao.put(SettingsKeys.kdfIterations, '${Kdf.defaultIterations}');
      }
      return UnlockResult.success(key);
    } on InvalidCipherTextException {
      // GCM tag 校验失败 = PIN 错误
    } catch (e) {
      // 其他异常记录日志
      rethrow;
    }
    return const UnlockResult.failure();
  }

  /// 读取当前盐/迭代数（备份头部用）。
  Future<(Uint8List salt, int iters)?> readKdfParams() async {
    final saltB64 = await _dao.get(SettingsKeys.kdfSalt);
    if (saltB64 == null) return null;
    final salt = base64.decode(saltB64);
    final iters =
        int.tryParse(await _dao.get(SettingsKeys.kdfIterations) ?? '') ??
            Kdf.defaultIterations;
    return (salt, iters);
  }

  /// 派生新 PIN 的 key（换 PIN 时生成新盐；配合 VaultRepository.reEncryptAll）。
  Future<(Uint8List newKey, Uint8List newSalt, int iters)> deriveForNewPin(
      String newPin) async {
    final salt = _crypto.newSalt();
    const iters = Kdf.defaultIterations;
    final key = await _crypto.deriveKey(newPin, salt, iterations: iters);
    return (key, salt, iters);
  }

  /// 换 PIN 收尾：写入新盐/迭代数/verifier（在同一事务里由调用方协调）。
  Future<void> commitNewPin(
    Uint8List newKey,
    Uint8List newSalt,
    int iters,
  ) async {
    final verifier = AesGcm.encryptString(_magic, newKey);
    await _dao.putAll({
      SettingsKeys.kdfSalt: base64.encode(newSalt),
      SettingsKeys.kdfIterations: '$iters',
      SettingsKeys.pinVerifier: verifier,
    });
  }

  // ---- 普通设置读写 ----

  Future<AppSettings> load() async => AppSettings.fromKv(await _dao.getAll());

  Future<void> setThemeStyle(AppThemeStyle style) => _dao.put(
      SettingsKeys.themeMode, style == AppThemeStyle.miuix ? 'miuix' : 'material');

  Future<void> setDarkMode(AppDarkMode mode) =>
      _dao.put(SettingsKeys.darkMode, mode.name);

  Future<void> setAutoLockSeconds(int seconds) =>
      _dao.put(SettingsKeys.autoLockSeconds, '$seconds');

  Future<void> setBackupIntervalDays(int days) =>
      _dao.put(SettingsKeys.backupIntervalDays, '$days');

  Future<void> setBalanceQueryEnabled(bool enabled) =>
      _dao.put(SettingsKeys.balanceQueryEnabled, '$enabled');

  Future<void> setAutoProbeOnUnlock(bool enabled) =>
      _dao.put(SettingsKeys.autoProbeOnUnlock, '$enabled');

  Future<void> setAutoProbeIntervalMinutes(int minutes) =>
      _dao.put(SettingsKeys.autoProbeIntervalMinutes, '$minutes');

  Future<void> setModelMetadataUrl(String url) =>
      _dao.put(SettingsKeys.modelMetadataUrl, url);

  Future<void> setLastMetadataFetchAt(int timestamp) =>
      _dao.put(SettingsKeys.lastMetadataFetchAt, '$timestamp');

  Future<void> setModelMetadataCache(String json) =>
      _dao.put('model_metadata_cache', json);

  Future<String?> getModelMetadataCache() async =>
      _dao.get('model_metadata_cache');

  Future<void> setLastArchiveAt(int timestamp) =>
      _dao.put(SettingsKeys.lastArchiveAt, '$timestamp');

  /// WebDAV 凭据（账号/密码加密存）。
  Future<void> saveWebdav({
    required String url,
    required String user,
    required String pass,
    required String dir,
    required Uint8List vaultKey,
  }) async {
    await _dao.putAll({
      SettingsKeys.webdavUrl: url,
      SettingsKeys.webdavDir: dir,
      SettingsKeys.webdavUserEnc: AesGcm.encryptString(user, vaultKey),
      SettingsKeys.webdavPassEnc: AesGcm.encryptString(pass, vaultKey),
    });
  }

  Future<(String user, String pass)?> readWebdavCreds(Uint8List vaultKey) async {
    final u = await _dao.get(SettingsKeys.webdavUserEnc);
    final pw = await _dao.get(SettingsKeys.webdavPassEnc);
    if (u == null || pw == null) return null;
    final user = _crypto.tryDecrypt(u, vaultKey);
    final pass = _crypto.tryDecrypt(pw, vaultKey);
    if (user == null || pass == null) return null;
    return (user, pass);
  }
}
