import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/log/log_service.dart';
import '../core/probe/probe_service.dart';
import '../models/category.dart';
import '../models/provider_with_keys.dart';
import '../repositories/vault_repository.dart';
import 'lock_controller.dart';
import 'providers.dart';

/// 金库仓库：解锁后才可用。未解锁时抛错（UI 门卫已保证不会在锁定态访问）。
final vaultRepositoryProvider = FutureProvider<VaultRepository>((ref) async {
  final key = ref.watch(vaultKeyProvider);
  if (key == null) {
    throw StateError('金库未解锁');
  }
  final db = await ref.watch(databaseProvider.future);
  final crypto = ref.watch(cryptoServiceProvider);
  return VaultRepository(db, crypto, key);
});

/// 当前选中的分类筛选（null = 全部）。
final selectedCategoryProvider = StateProvider<int?>((ref) => null);

/// 分类列表。
final categoriesProvider = FutureProvider<List<Category>>((ref) async {
  final repo = await ref.watch(vaultRepositoryProvider.future);
  return repo.categories();
});

/// 仪表盘数据：随分类筛选变化刷新。
final dashboardProvider =
    FutureProvider<List<ProviderWithKeys>>((ref) async {
  final repo = await ref.watch(vaultRepositoryProvider.future);
  final categoryId = ref.watch(selectedCategoryProvider);
  return repo.dashboard(categoryId: categoryId);
});

/// 单厂家详情。
final providerDetailProvider =
    FutureProvider.family<ProviderWithKeys?, int>((ref, id) async {
  final repo = await ref.watch(vaultRepositoryProvider.future);
  return repo.providerDetail(id);
});

/// 汇总条数据。
class VaultSummary {
  const VaultSummary({
    required this.providerCount,
    required this.keyCount,
    required this.availableCount,
    required this.modelCount,
  });
  final int providerCount;
  final int keyCount;
  final int availableCount;
  final int modelCount;
}

final summaryProvider = FutureProvider<VaultSummary>((ref) async {
  final list = await ref.watch(dashboardProvider.future);
  var keyCount = 0;
  var available = 0;
  final allModels = <String>{};
  for (final pk in list) {
    keyCount += pk.keyCount;
    available += pk.availableCount;
    allModels.addAll(pk.keys.expand((k) => k.modelIds));
  }
  return VaultSummary(
    providerCount: list.length,
    keyCount: keyCount,
    availableCount: available,
    modelCount: allModels.length,
  );
});

/// 探测状态。
class ProbeState {
  const ProbeState({this.running = false, this.progress = 0, this.total = 0});
  final bool running;
  final int progress;
  final int total;
}

/// 探测控制器：一键探测所有启用检查的 Key。
final probeControllerProvider =
    StateNotifierProvider<ProbeController, ProbeState>((ref) {
  return ProbeController(ref);
});

class ProbeController extends StateNotifier<ProbeState> {
  ProbeController(this._ref) : super(const ProbeState());
  final Ref _ref;

  /// 探测单个厂家下所有启用检查的 Key。
  Future<void> probeProvider(int providerId) async {
    if (state.running) return;

    log.info('开始探测厂家 #$providerId', source: 'ProbeController');

    try {
      final repo = await _ref.read(vaultRepositoryProvider.future);
      final detail = await repo.providerDetail(providerId);
      if (detail == null) return;

      final service = ProbeService();
      final keys = detail.keys.where(
        (k) => k.keyCheckEnabled || k.modelListEnabled || k.balanceCheckEnabled,
      ).toList();

      state = ProbeState(running: true, progress: 0, total: keys.length);
      if (keys.isEmpty) {
        state = const ProbeState();
        return;
      }

      var done = 0;
      for (final k in keys) {
        final result = await service.probeAllChecks(
          k,
          baseUrl: detail.provider.fullApiUrl,
        );
        final now = DateTime.now().millisecondsSinceEpoch;
        if (result.models.isNotEmpty) {
          final updated = k.copyWith(
            models: result.models,
            status: result.status,
            balance: result.balance,
            balanceText: result.balanceText,
            lastCheckedAt: now,
          );
          await repo.updateKey(updated);
        } else {
          await repo.saveProbeResult(
            k.id,
            status: result.status.value,
            balance: result.balance,
            balanceText: result.balanceText,
            checkedAt: now,
          );
        }
        done++;
        state = ProbeState(running: true, progress: done, total: keys.length);
      }

      log.info('厂家 #$providerId 探测完成', source: 'ProbeController');
      _ref.invalidate(providerDetailProvider(providerId));
      _ref.invalidate(dashboardProvider);
      _ref.invalidate(summaryProvider);
    } catch (e) {
      log.error('厂家探测异常', source: 'ProbeController', detail: '$e');
    } finally {
      if (mounted) state = const ProbeState();
    }
  }

  /// 一键探测所有启用检查的 Key。
  ///
  /// 三个独立检查：
  /// 1. Key 有效性：GET {fullApiUrl}{modelsEndpoint} → 200 = 有效
  /// 2. 模型列表：同上响应中解析 data[].id
  /// 3. 余额查询：GET {fullApiUrl}{balanceEndpoint} → 提取 valuePath - usagePath
  Future<void> probeAll() async {
    if (state.running) return;

    log.info('开始全量探测', source: 'ProbeController');

    try {
      final repo = await _ref.read(vaultRepositoryProvider.future);
      final dashboard = await _ref.read(dashboardProvider.future);
      final service = ProbeService();

      // 统计需探测的 Key 数量（至少启用一个检查的）
      var total = 0;
      for (final pk in dashboard) {
        for (final k in pk.keys) {
          if (k.keyCheckEnabled || k.modelListEnabled || k.balanceCheckEnabled) {
            total++;
          }
        }
      }
      state = ProbeState(running: true, progress: 0, total: total);
      if (total == 0) {
        log.info('无可探测的 Key', source: 'ProbeController');
        state = const ProbeState();
        return;
      }

      log.info('共 $total 个 Key 需探测', source: 'ProbeController');
      var done = 0;
      for (final pk in dashboard) {
        for (final k in pk.keys) {
          if (!k.keyCheckEnabled &&
              !k.modelListEnabled &&
              !k.balanceCheckEnabled) {
            continue;
          }
          final result = await service.probeAllChecks(
            k,
            baseUrl: pk.provider.fullApiUrl,
          );
          final now = DateTime.now().millisecondsSinceEpoch;
          if (result.models.isNotEmpty) {
            final updated = k.copyWith(
              models: result.models,
              status: result.status,
              balance: result.balance,
              balanceText: result.balanceText,
              lastCheckedAt: now,
            );
            await repo.updateKey(updated);
          } else {
            await repo.saveProbeResult(
              k.id,
              status: result.status.value,
              balance: result.balance,
              balanceText: result.balanceText,
              checkedAt: now,
            );
          }
          done++;
          state = ProbeState(running: true, progress: done, total: total);
        }
      }

      log.info('全量探测完成', source: 'ProbeController');
      _ref.invalidate(dashboardProvider);
      _ref.invalidate(summaryProvider);
      for (final pk in dashboard) {
        _ref.invalidate(providerDetailProvider(pk.provider.id));
      }

      // 探测完成后刷新模型元数据
      try {
        final metaSvc = await _ref.read(modelMetadataServiceProvider.future);
        await metaSvc.refresh();
      } catch (_) {}
    } catch (e) {
      log.error('探测异常', source: 'ProbeController', detail: '$e');
    } finally {
      if (mounted) state = const ProbeState();
    }
  }
}
