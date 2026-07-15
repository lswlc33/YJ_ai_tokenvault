import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:local_auth/local_auth.dart';
import 'package:path_provider/path_provider.dart';
import 'package:pointycastle/export.dart';

/// 生物识别 + 安全存储服务。
///
/// 使用 AES-GCM 加密 vaultKey 后存入本地文件。
class BiometricService {
  BiometricService() : _auth = LocalAuthentication();

  final LocalAuthentication _auth;
  static const String _keyFileName = 'biometric_vault.enc';
  static const String _saltFileName = 'biometric_vault.salt';

  Future<bool> isAvailable() async {
    try {
      final canCheck = await _auth.canCheckBiometrics;
      final isDeviceSupported = await _auth.isDeviceSupported();
      return canCheck && isDeviceSupported;
    } catch (_) {
      return false;
    }
  }

  Future<bool> authenticate({String reason = '解锁元记'}) async {
    try {
      return await _auth.authenticate(
        localizedReason: reason,
        options: const AuthenticationOptions(
          stickyAuth: true,
          biometricOnly: false,
        ),
      );
    } catch (_) {
      return false;
    }
  }

  Future<void> saveVaultKey(Uint8List key) async {
    final dir = await _appDir;
    final salt = _randomBytes(32);
    final encKey = _deriveKey(salt);
    final nonce = _randomBytes(12);
    final encrypted = _aesGcmEncrypt(key, encKey, nonce);
    // 格式: nonce(12) + tag(16) + ciphertext
    final packed = Uint8List.fromList([...nonce, ...encrypted]);
    await File('${dir.path}/$_saltFileName')
        .writeAsBytes(salt, flush: true);
    await File('${dir.path}/$_keyFileName')
        .writeAsBytes(packed, flush: true);
  }

  Future<Uint8List?> loadVaultKey() async {
    try {
      final dir = await _appDir;
      final saltFile = File('${dir.path}/$_saltFileName');
      final keyFile = File('${dir.path}/$_keyFileName');
      if (!await saltFile.exists() || !await keyFile.exists()) return null;
      final salt = await saltFile.readAsBytes();
      final packed = await keyFile.readAsBytes();
      if (packed.length < 28) return null; // nonce(12) + tag(16) minimum
      final nonce = packed.sublist(0, 12);
      final encrypted = packed.sublist(12);
      final encKey = _deriveKey(salt);
      return _aesGcmDecrypt(encrypted, encKey, nonce);
    } catch (_) {
      return null;
    }
  }

  Future<void> deleteVaultKey() async {
    final dir = await _appDir;
    final saltFile = File('${dir.path}/$_saltFileName');
    final keyFile = File('${dir.path}/$_keyFileName');
    if (await saltFile.exists()) await saltFile.delete();
    if (await keyFile.exists()) await keyFile.delete();
  }

  Future<Directory> get _appDir async => getApplicationSupportDirectory();

  Uint8List _deriveKey(Uint8List salt) {
    final deviceBytes = utf8.encode(Platform.operatingSystemVersion);
    final combined = Uint8List(salt.length + deviceBytes.length)
      ..setRange(0, salt.length, salt)
      ..setRange(salt.length, salt.length + deviceBytes.length, deviceBytes);
    final digest = SHA256Digest();
    return digest.process(combined);
  }

  Uint8List _aesGcmEncrypt(Uint8List plain, Uint8List key, Uint8List nonce) {
    final params = AEADParameters(
      KeyParameter(key),
      128, // tag length in bits
      nonce,
      Uint8List(0), // no AAD
    );
    final cipher = GCMBlockCipher(AESEngine());
    cipher.init(true, params);
    return cipher.process(plain);
  }

  Uint8List _aesGcmDecrypt(
      Uint8List encrypted, Uint8List key, Uint8List nonce) {
    final params = AEADParameters(
      KeyParameter(key),
      128,
      nonce,
      Uint8List(0),
    );
    final cipher = GCMBlockCipher(AESEngine());
    cipher.init(false, params);
    return cipher.process(encrypted);
  }

  Uint8List _randomBytes(int length) {
    final rng = Random.secure();
    return Uint8List.fromList(
        List.generate(length, (_) => rng.nextInt(256)));
  }
}
