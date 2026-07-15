import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/auth/biometric_service.dart';
import '../core/crypto/crypto_service.dart';
import '../core/db/database.dart';
import '../core/db/daos/settings_dao.dart';
import '../core/model_metadata/model_metadata_service.dart';
import '../repositories/settings_repository.dart';

/// 加密服务（无状态单例）。
final cryptoServiceProvider = Provider<CryptoService>((ref) {
  return const CryptoService();
});

/// 生物识别 + 安全存储服务（无状态单例）。
final biometricServiceProvider = Provider<BiometricService>((ref) {
  return BiometricService();
});

/// 数据库（异步单例）。桌面 ffi 初始化在 main.dart 完成。
final databaseProvider = FutureProvider<AppDatabase>((ref) async {
  return AppDatabase.open();
});

/// 设置仓库（依赖 db + crypto）。
final settingsRepositoryProvider =
    FutureProvider<SettingsRepository>((ref) async {
  final db = await ref.watch(databaseProvider.future);
  final crypto = ref.watch(cryptoServiceProvider);
  return SettingsRepository(SettingsDao(db.db), crypto);
});

/// 模型元数据服务。
final modelMetadataServiceProvider =
    FutureProvider<ModelMetadataService>((ref) async {
  final repo = await ref.watch(settingsRepositoryProvider.future);
  return ModelMetadataService(repo);
});
