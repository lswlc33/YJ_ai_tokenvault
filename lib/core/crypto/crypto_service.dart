
import 'package:flutter/foundation.dart';

import 'aes_gcm.dart';
import 'kdf.dart';

/// KDF 参数（跨 isolate 传递用）。
class _KdfArgs {
  const _KdfArgs(this.pin, this.salt, this.iterations);
  final String pin;
  final Uint8List salt;
  final int iterations;
}

Uint8List _deriveKeyIsolate(_KdfArgs a) =>
    Kdf.deriveKey(a.pin, a.salt, iterations: a.iterations);

/// 重加密任务参数：把一批"旧密文"用 oldKey 解密后再用 newKey 加密。
class ReEncryptArgs {
  const ReEncryptArgs(this.oldKey, this.newKey, this.values);
  final Uint8List oldKey;
  final Uint8List newKey;

  /// 待迁移的密文列表（Base64）。null 元素保持 null（空字段）。
  final List<String?> values;
}

List<String?> _reEncryptIsolate(ReEncryptArgs a) {
  return a.values.map((packed) {
    if (packed == null || packed.isEmpty) return packed;
    try {
      final plain = AesGcm.decryptString(packed, a.oldKey);
      return AesGcm.encryptString(plain, a.newKey);
    } catch (_) {
      return packed; // 解密失败时保留原密文
    }
  }).toList();
}

/// 加密门面：所有"重活"（KDF、批量重加密）走 [compute] 子线程，避免 UI 卡顿。
/// 单字段加解密开销极小，直接同步执行。
class CryptoService {
  const CryptoService();

  /// PIN → vaultKey，放子线程。
  Future<Uint8List> deriveKey(
    String pin,
    Uint8List salt, {
    int iterations = Kdf.defaultIterations,
  }) {
    return compute(_deriveKeyIsolate, _KdfArgs(pin, salt, iterations));
  }

  /// 生成新盐。
  Uint8List newSalt() => AesGcm.randomBytes(Kdf.saltLength);

  // ---- 单字段（同步）----
  String? encrypt(String? plaintext, Uint8List key) =>
      (plaintext == null) ? null : AesGcm.encryptString(plaintext, key);

  String? decrypt(String? packed, Uint8List key) =>
      (packed == null || packed.isEmpty)
          ? null
          : AesGcm.decryptString(packed, key);

  /// 尝试解密，失败返回 null（用于容错展示）。
  String? tryDecrypt(String? packed, Uint8List key) {
    try {
      return decrypt(packed, key);
    } catch (_) {
      return null;
    }
  }

  // ---- 批量重加密（子线程）----
  Future<List<String?>> reEncryptAll(
    List<String?> values,
    Uint8List oldKey,
    Uint8List newKey,
  ) {
    return compute(_reEncryptIsolate, ReEncryptArgs(oldKey, newKey, values));
  }
}
