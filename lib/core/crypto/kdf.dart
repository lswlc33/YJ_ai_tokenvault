import 'dart:convert';
import 'dart:typed_data';

import 'package:pointycastle/export.dart';

/// PBKDF2-HMAC-SHA256 密钥派生。
///
/// 与计划 §1.1 对齐：
///   vaultKey = PBKDF2-HMAC-SHA256(pin, salt, iterations, dkLen=32)
class Kdf {
  static const int keyLength = 32; // AES-256
  static const int defaultIterations = 10000;
  static const int saltLength = 16;

  /// 派生 32 字节 vaultKey。
  ///
  /// 注意：该方法是纯 CPU 计算，调用方应放入 [compute] 子线程执行，
  /// 避免阻塞 UI（见 CryptoService）。
  static Uint8List deriveKey(
    String pin,
    Uint8List salt, {
    int iterations = defaultIterations,
  }) {
    final derivator = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))
      ..init(Pbkdf2Parameters(salt, iterations, keyLength));
    return derivator.process(Uint8List.fromList(utf8.encode(pin)));
  }
}
