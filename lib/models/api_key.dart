import 'key_status.dart';
import 'model_info.dart';

/// 一个厂家下的某个 API Key（计划 §2.1 api_keys）。
///
/// 持有**明文** [apiKey]；db 列 `api_key_enc` 的加解密由 DAO 完成。
class ApiKey {
  const ApiKey({
    required this.id,
    required this.providerId,
    this.label = '',
    this.apiKey,
    this.status = KeyStatus.unknown,
    this.lastCheckedAt,
    this.sortOrder = 0,
    this.models = const [],
    this.keyCheckEnabled = true,
    this.modelListEnabled = true,
    this.balanceCheckEnabled = false,
    this.balanceEndpoint = '/user/balance',
    this.balanceValuePath = 'total_credits',
    this.balanceUsagePath = 'total_usage',
    this.modelsEndpoint = '/models',
    this.balance,
    this.balanceText,
  });

  final int id;
  final int providerId;
  final String label;
  final String? apiKey;
  final KeyStatus status;
  final int? lastCheckedAt;
  final int sortOrder;
  final List<ModelInfo> models;

  final bool keyCheckEnabled;
  final bool modelListEnabled;
  final bool balanceCheckEnabled;

  final String balanceEndpoint;
  final String balanceValuePath;
  final String balanceUsagePath;
  final String modelsEndpoint;

  final double? balance;
  final String? balanceText;

  List<String> get modelIds => models.map((m) => m.id).toList();

  ApiKey copyWith({
    int? id,
    int? providerId,
    String? label,
    Object? apiKey = _sentinel,
    KeyStatus? status,
    Object? lastCheckedAt = _sentinel,
    int? sortOrder,
    List<ModelInfo>? models,
    bool? keyCheckEnabled,
    bool? modelListEnabled,
    bool? balanceCheckEnabled,
    String? balanceEndpoint,
    String? balanceValuePath,
    String? balanceUsagePath,
    String? modelsEndpoint,
    Object? balance = _sentinel,
    Object? balanceText = _sentinel,
  }) =>
      ApiKey(
        id: id ?? this.id,
        providerId: providerId ?? this.providerId,
        label: label ?? this.label,
        apiKey: apiKey == _sentinel ? this.apiKey : apiKey as String?,
        status: status ?? this.status,
        lastCheckedAt: lastCheckedAt == _sentinel
            ? this.lastCheckedAt
            : lastCheckedAt as int?,
        sortOrder: sortOrder ?? this.sortOrder,
        models: models ?? this.models,
        keyCheckEnabled: keyCheckEnabled ?? this.keyCheckEnabled,
        modelListEnabled: modelListEnabled ?? this.modelListEnabled,
        balanceCheckEnabled: balanceCheckEnabled ?? this.balanceCheckEnabled,
        balanceEndpoint: balanceEndpoint ?? this.balanceEndpoint,
        balanceValuePath: balanceValuePath ?? this.balanceValuePath,
        balanceUsagePath: balanceUsagePath ?? this.balanceUsagePath,
        modelsEndpoint: modelsEndpoint ?? this.modelsEndpoint,
        balance: balance == _sentinel ? this.balance : balance as double?,
        balanceText:
            balanceText == _sentinel ? this.balanceText : balanceText as String?,
      );

  factory ApiKey.fromMap(Map<String, Object?> m) => ApiKey(
        id: m['id'] as int,
        providerId: m['provider_id'] as int,
        label: (m['label'] as String?) ?? '',
        apiKey: m['api_key'] as String?,
        status: KeyStatus.fromValue(m['status'] as int?),
        lastCheckedAt: m['last_checked_at'] as int?,
        sortOrder: (m['sort_order'] as int?) ?? 0,
        models: _parseModels(m['models'] as String?),
        keyCheckEnabled: (m['key_check_enabled'] as int?) != 0,
        modelListEnabled: (m['model_list_enabled'] as int?) != 0,
        balanceCheckEnabled: (m['balance_check_enabled'] as int?) != 0,
        balanceEndpoint: (m['balance_endpoint'] as String?) ?? '/user/balance',
        balanceValuePath: (m['balance_value_path'] as String?) ?? 'total_credits',
        balanceUsagePath: (m['balance_usage_path'] as String?) ?? 'total_usage',
        modelsEndpoint: (m['models_endpoint'] as String?) ?? '/models',
        balance: (m['balance'] as num?)?.toDouble(),
        balanceText: m['balance_text'] as String?,
      );

  Map<String, Object?> toMap() => {
        if (id != 0) 'id': id,
        'provider_id': providerId,
        'label': label,
        'status': status.value,
        'last_checked_at': lastCheckedAt,
        'sort_order': sortOrder,
        'models': models.isEmpty ? null : ModelInfo.encodeList(models),
        'key_check_enabled': keyCheckEnabled ? 1 : 0,
        'model_list_enabled': modelListEnabled ? 1 : 0,
        'balance_check_enabled': balanceCheckEnabled ? 1 : 0,
        'balance_endpoint': balanceEndpoint,
        'balance_value_path': balanceValuePath,
        'balance_usage_path': balanceUsagePath,
        'models_endpoint': modelsEndpoint,
        'balance': balance,
        'balance_text': balanceText,
      };

  static List<ModelInfo> _parseModels(String? s) {
    if (s == null || s.isEmpty) return const [];
    if (s.startsWith('[')) return ModelInfo.parseList(s);
    return s
        .split('\n')
        .where((e) => e.isNotEmpty)
        .map((id) => ModelInfo(id: id))
        .toList();
  }

  static const Object _sentinel = Object();
}
