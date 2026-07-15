import 'dart:typed_data';

import 'crypto_service.dart';

/// 绑定"内存 vaultKey + CryptoService"的字段级加解密器。
/// 传入 DAO，使其在读写敏感列时透明加解密。vaultKey 锁定即失效。
class FieldCipher {
  const FieldCipher(this._service, this._key);

  final CryptoService _service;
  final Uint8List _key;

  String? enc(String? plaintext) => _service.encrypt(plaintext, _key);

  /// 解密；失败（如 key 不匹配）返回 null，不抛异常，便于容错展示。
  String? dec(String? packed) => _service.tryDecrypt(packed, _key);
}
