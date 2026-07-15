import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/auth/biometric_service.dart';
import '../core/log/log_service.dart';
import '../repositories/settings_repository.dart';
import 'providers.dart';
import 'vault_controllers.dart';

/// 锁屏阶段。
enum LockPhase {
  loading,
  onboarding,
  locked,
  unlocked,
}

@immutable
class LockState {
  const LockState({
    required this.phase,
    this.vaultKey,
    this.error,
    this.busy = false,
    this.biometricAvailable = false,
    this.biometricMode = false,
  });

  final LockPhase phase;
  final Uint8List? vaultKey;
  final String? error;
  final bool busy;
  final bool biometricAvailable;
  final bool biometricMode; // true = 当前走生物识别流程

  LockState copyWith({
    LockPhase? phase,
    Uint8List? vaultKey,
    Object? error = _s,
    bool? busy,
    bool? biometricAvailable,
    bool? biometricMode,
  }) =>
      LockState(
        phase: phase ?? this.phase,
        vaultKey: vaultKey ?? this.vaultKey,
        error: error == _s ? this.error : error as String?,
        busy: busy ?? this.busy,
        biometricAvailable: biometricAvailable ?? this.biometricAvailable,
        biometricMode: biometricMode ?? this.biometricMode,
      );

  static const Object _s = Object();
}

/// 锁控制器：管理 PIN 生命周期与内存 vaultKey。
///
/// 解锁路径：
/// 1. 如果有已存的 vaultKey（安全存储）+ 生物识别可用 → 生物识别解锁（毫秒级）
/// 2. 否则 → PIN 解锁（PBKDF2 派生，秒级）
class LockController extends StateNotifier<LockState> {
  LockController(this._ref) : super(const LockState(phase: LockPhase.loading)) {
    _init();
  }

  final Ref _ref;
  int? _backgroundedAt;
  Timer? _probeTimer;

  BiometricService get _biometric => _ref.read(biometricServiceProvider);
  Future<SettingsRepository> get _repo =>
      _ref.read(settingsRepositoryProvider.future);

  Future<void> _init() async {
    try {
      final repo = await _repo;
      final has = await repo.hasPin();
      if (!has) {
        log.info('首次启动，进入引导设置', source: 'LockController');
        state = const LockState(phase: LockPhase.onboarding);
        return;
      }

      // 检查生物识别 + 已存 vaultKey
      final bioAvail = await _biometric.isAvailable();
      final storedKey = await _biometric.loadVaultKey();
      final canBiometric = bioAvail && storedKey != null;

      log.info('初始化: PIN已设=$has, 生物识别可用=$bioAvail, 已存Key=${storedKey != null}',
          source: 'LockController');

      state = LockState(
        phase: LockPhase.locked,
        biometricAvailable: bioAvail,
      );

      // 如果可以生物识别，自动尝试
      if (canBiometric) {
        _tryBiometricUnlock(storedKey);
      }
    } catch (e) {
      log.error('初始化失败', source: 'LockController', detail: '$e');
      state = LockState(phase: LockPhase.locked, error: '初始化失败：$e');
    }
  }

  /// 自动尝试生物识别解锁。
  Future<void> _tryBiometricUnlock(Uint8List storedKey) async {
    try {
      state = state.copyWith(busy: true, biometricMode: true);
      log.info('尝试生物识别解锁', source: 'LockController');
      final ok = await _biometric.authenticate(reason: '验证身份以解锁元记');
      if (ok) {
        log.info('生物识别解锁成功', source: 'LockController');
        state = LockState(
          phase: LockPhase.unlocked,
          vaultKey: storedKey,
          biometricAvailable: state.biometricAvailable,
        );
        _onUnlocked();
      } else {
        log.info('生物识别取消/失败，降级为 PIN', source: 'LockController');
        state = state.copyWith(busy: false, biometricMode: false);
      }
    } catch (e) {
      log.error('生物识别异常', source: 'LockController', detail: '$e');
      state = state.copyWith(busy: false, biometricMode: false, error: '$e');
    }
  }

  /// 首次设置 PIN。
  Future<void> setupPin(String pin) async {
    state = state.copyWith(busy: true, error: null);
    try {
      final repo = await _repo;
      final key = await repo.setupPin(pin);
      await _biometric.saveVaultKey(key);
      final bioAvail = await _biometric.isAvailable();
      log.info('PIN 设置成功，生物识别可用=$bioAvail', source: 'LockController');
      state = LockState(
        phase: LockPhase.unlocked,
        vaultKey: key,
        biometricAvailable: bioAvail,
      );
      _onUnlocked();
    } catch (e) {
      log.error('PIN 设置失败', source: 'LockController', detail: '$e');
      state = state.copyWith(busy: false, error: '$e');
    }
  }

  /// PIN 解锁。
  Future<void> unlock(String pin) async {
    state = state.copyWith(busy: true, error: null, biometricMode: false);
    try {
      final repo = await _repo;
      final result = await repo.unlock(pin);
      if (result.ok) {
        final key = result.vaultKey!;
        await _biometric.saveVaultKey(key);
        final bioAvail = await _biometric.isAvailable();
        log.info('PIN 解锁成功', source: 'LockController');
        state = LockState(
          phase: LockPhase.unlocked,
          vaultKey: key,
          biometricAvailable: bioAvail,
        );
        _onUnlocked();
      } else {
        log.warn('PIN 错误', source: 'LockController');
        state = state.copyWith(busy: false, error: 'PIN 错误');
      }
    } catch (e) {
      log.error('解锁异常', source: 'LockController', detail: '$e');
      state = state.copyWith(busy: false, error: '$e');
    }
  }

  /// 手动触发生物识别解锁（用户点击按钮）。
  Future<void> unlockWithBiometric() async {
    final storedKey = await _biometric.loadVaultKey();
    if (storedKey == null) {
      state = state.copyWith(error: '请先用 PIN 解锁一次以启用生物识别');
      return;
    }
    _tryBiometricUnlock(storedKey);
  }

  Future<void> _onUnlocked() async {
    try {
      final repo = await _repo;
      final settings = await repo.load();
      if (settings.autoProbeOnUnlock) {
        _ref.read(probeControllerProvider.notifier).probeAll();
      }
      _probeTimer?.cancel();
      if (settings.autoProbeIntervalMinutes > 0) {
        _probeTimer = Timer.periodic(
          Duration(minutes: settings.autoProbeIntervalMinutes),
          (_) {
            if (state.phase == LockPhase.unlocked) {
              _ref.read(probeControllerProvider.notifier).probeAll();
            }
          },
        );
      }
      // 解锁时刷新模型元数据（静默）
      try {
        final metaSvc = await _ref.read(modelMetadataServiceProvider.future);
        await metaSvc.getMetadata();
      } catch (_) {}
    } catch (e) {
      log.error('_onUnlocked 异常', source: 'LockController', detail: '$e');
    }
  }

  void lock() {
    log.info('金库已锁定', source: 'LockController');
    _backgroundedAt = null;
    _probeTimer?.cancel();
    _probeTimer = null;
    final oldKey = state.vaultKey;
    if (oldKey != null) {
      for (var i = 0; i < oldKey.length; i++) {
        oldKey[i] = 0;
      }
    }
    state = LockState(
      phase: LockPhase.locked,
      biometricAvailable: state.biometricAvailable,
    );
  }

  /// 启用生物识别：验证身份后保存当前 vaultKey。
  Future<void> enableBiometric() async {
    final key = state.vaultKey;
    if (key == null) return;
    final ok = await _biometric.authenticate(reason: '验证身份以启用生物识别');
    if (!ok) return;
    await _biometric.saveVaultKey(key);
    state = state.copyWith(biometricAvailable: true);
  }

  /// 清除生物识别数据（换 PIN / 关闭生物识别时）。
  Future<void> clearBiometricData() async {
    await _biometric.deleteVaultKey();
    state = state.copyWith(biometricAvailable: false);
  }

  Future<void> onLifecycleChanged(bool resumed) async {
    if (state.phase != LockPhase.unlocked) return;
    if (!resumed) {
      _backgroundedAt = DateTime.now().millisecondsSinceEpoch;
      return;
    }
    final since = _backgroundedAt;
    _backgroundedAt = null;
    if (since == null) return;

    try {
      final repo = await _repo;
      final settings = await repo.load();
      if (!settings.autoLockEnabled) return;
      final elapsed = DateTime.now().millisecondsSinceEpoch - since;
      if (elapsed >= settings.autoLockSeconds * 1000) {
        lock();
      }
    } catch (e) {
      log.error('生命周期处理异常', source: 'LockController', detail: '$e');
    }
  }

  @override
  void dispose() {
    _probeTimer?.cancel();
    super.dispose();
  }
}

final lockControllerProvider =
    StateNotifierProvider<LockController, LockState>((ref) {
  return LockController(ref);
});

final vaultKeyProvider = Provider<Uint8List?>((ref) {
  return ref.watch(lockControllerProvider).vaultKey;
});
