import 'provider.dart';
import 'api_key.dart';
import 'key_status.dart';

/// 组合视图：一张厂家卡片 + 其下所有 Key（仪表盘/详情页用）。
class ProviderWithKeys {
  const ProviderWithKeys({required this.provider, required this.keys});

  final Provider provider;
  final List<ApiKey> keys;

  int get keyCount => keys.length;

  /// 可用 Key 数量（status == ok）。
  int get availableCount => keys.where((k) => k.status == KeyStatus.ok).length;

  /// 模型总数（去重）。
  int get totalModelCount {
    final all = <String>{};
    for (final k in keys) {
      all.addAll(k.modelIds);
    }
    return all.length;
  }

  KeyStatus get aggregateStatus {
    if (keys.isEmpty) return KeyStatus.unknown;
    if (keys.any((k) => k.status == KeyStatus.invalid)) {
      return KeyStatus.invalid;
    }
    if (keys.any((k) => k.status == KeyStatus.overdue)) {
      return KeyStatus.overdue;
    }
    if (keys.any((k) => k.status == KeyStatus.insufficient)) {
      return KeyStatus.insufficient;
    }
    if (keys.every((k) => k.status == KeyStatus.ok)) return KeyStatus.ok;
    return KeyStatus.unknown;
  }

  double? get totalBalanceForEnabled {
    final withBalance =
        keys.where((k) => k.balanceCheckEnabled && k.balance != null);
    if (withBalance.isEmpty) return null;
    return withBalance.fold<double>(0, (s, k) => s + (k.balance ?? 0));
  }

  ProviderWithKeys copyWith({Provider? provider, List<ApiKey>? keys}) =>
      ProviderWithKeys(
        provider: provider ?? this.provider,
        keys: keys ?? this.keys,
      );
}
