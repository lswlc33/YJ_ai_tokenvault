import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:pointycastle/export.dart';

/// AES-256-GCM 字段加密。
///
/// 存储格式（计划 §1.2）：`nonce(12) || ciphertext || tag(16)`，整体 Base64。
/// GCM 的 nonce 每次随机生成，绝不复用。
class AesGcm {
  static const int nonceLength = 12;
  static const int tagLength = 16; // 128-bit tag
  static const int _tagBits = tagLength * 8;

  static final Random _rng = Random.secure();

  /// 生成密码学安全随机字节。
  static Uint8List randomBytes(int length) {
    final b = Uint8List(length);
    for (var i = 0; i < length; i++) {
      b[i] = _rng.nextInt(256);
    }
    return b;
  }

  /// 加密明文字符串，返回 Base64(nonce || ciphertext+tag)。
  static String encryptString(String plaintext, Uint8List key) {
    return base64.encode(
      encryptBytes(Uint8List.fromList(utf8.encode(plaintext)), key),
    );
  }

  /// 解密 Base64(nonce || ciphertext+tag) → 明文字符串。
  static String decryptString(String packedBase64, Uint8List key) {
    return utf8.decode(decryptBytes(base64.decode(packedBase64), key));
  }

  /// 加密任意字节，返回 nonce || ciphertext+tag（未 Base64）。
  static Uint8List encryptBytes(Uint8List plaintext, Uint8List key) {
    final nonce = randomBytes(nonceLength);
    final cipher = GCMBlockCipher(AESEngine())
      ..init(
        true,
        AEADParameters(KeyParameter(key), _tagBits, nonce, Uint8List(0)),
      );
    final out = cipher.process(plaintext); // ciphertext || tag
    final packed = Uint8List(nonce.length + out.length)
      ..setRange(0, nonce.length, nonce)
      ..setRange(nonce.length, nonce.length + out.length, out);
    return packed;
  }

  /// 解密 nonce || ciphertext+tag → 明文字节。GCM tag 校验失败会抛异常。
  static Uint8List decryptBytes(Uint8List packed, Uint8List key) {
    if (packed.length < nonceLength + tagLength) {
      throw ArgumentError('密文长度不足，无法解密');
    }
    final nonce = packed.sublist(0, nonceLength);
    final body = packed.sublist(nonceLength); // ciphertext || tag
    final cipher = GCMBlockCipher(AESEngine())
      ..init(
        false,
        AEADParameters(KeyParameter(key), _tagBits, nonce, Uint8List(0)),
      );
    return cipher.process(body);
  }
}
