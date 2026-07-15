import 'dart:convert';

import 'package:dio/dio.dart';

import '../../core/log/log_service.dart';
import '../../repositories/settings_repository.dart';

/// 模型元数据条目（来自 models.dev）。
class ModelMetadataEntry {
  const ModelMetadataEntry({
    required this.id,
    required this.name,
    this.reasoning = false,
    this.toolCall = false,
    this.inputModalities = const [],
    this.outputModalities = const [],
    this.contextLimit,
    this.outputLimit,
  });

  final String id;
  final String name;
  final bool reasoning;
  final bool toolCall;
  final List<String> inputModalities;
  final List<String> outputModalities;
  final int? contextLimit;
  final int? outputLimit;

  factory ModelMetadataEntry.fromJson(Map<String, dynamic> json) {
    final modalities = json['modalities'] as Map<String, dynamic>?;
    final inputRaw = modalities?['input'] as List?;
    final outputRaw = modalities?['output'] as List?;
    final limit = json['limit'] as Map<String, dynamic>?;
    return ModelMetadataEntry(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      reasoning: json['reasoning'] as bool? ?? false,
      toolCall: json['tool_call'] as bool? ?? false,
      inputModalities:
          inputRaw?.whereType<String>().toList() ?? const [],
      outputModalities:
          outputRaw?.whereType<String>().toList() ?? const [],
      contextLimit: limit?['context'] as int?,
      outputLimit: limit?['output'] as int?,
    );
  }
}

/// 模型元数据服务：拉取 models.dev 元数据，缓存到本地，在模型列表中匹配展示。
class ModelMetadataService {
  ModelMetadataService(this._repo, {Dio? dio}) : _dio = dio ?? Dio();

  final Dio _dio;
  final SettingsRepository _repo;
  static const _src = 'Metadata';

  Map<String, ModelMetadataEntry>? _cache;

  /// 从缓存或网络获取元数据。
  Future<Map<String, ModelMetadataEntry>> getMetadata({
    bool forceRefresh = false,
  }) async {
    if (_cache != null && !forceRefresh) return _cache!;

    // 尝试读本地缓存
    if (!forceRefresh) {
      final cached = await _repo.getModelMetadataCache();
      if (cached != null && cached.isNotEmpty) {
        try {
          _cache = _parseMetadata(cached);
          return _cache!;
        } catch (_) {
          // 缓存损坏，重新拉取
        }
      }
    }

    // 从网络拉取
    return refresh();
  }

  /// 从网络刷新元数据并缓存。
  Future<Map<String, ModelMetadataEntry>> refresh() async {
    final settings = await _repo.load();
    final url = settings.modelMetadataUrl;
    if (url.isEmpty) return _cache ?? {};

    log.info('拉取模型元数据: $url', source: _src);
    try {
      final resp = await _dio.get(
        url,
        options: Options(
          connectTimeout: const Duration(seconds: 15),
          receiveTimeout: const Duration(seconds: 30),
        ),
      );
      if (resp.statusCode == 200 && resp.data is Map) {
        final jsonStr = jsonEncode(resp.data);
        _cache = _parseFromMap(resp.data.cast<String, dynamic>());
        await _repo.setModelMetadataCache(jsonStr);
        await _repo
            .setLastMetadataFetchAt(DateTime.now().millisecondsSinceEpoch);
        log.info('元数据更新完成: ${_cache!.length} 条',
            source: _src);
        return _cache!;
      }
      log.warn('元数据响应异常: ${resp.statusCode}', source: _src);
    } catch (e) {
      log.error('元数据拉取失败', source: _src, detail: '$e');
    }
    return _cache ?? {};
  }

  /// 匹配元数据，支持多种策略：
  /// 1. 精确匹配 `providerName/modelId`
  /// 2. 尝试从 metadata 的 id 字段中提取 provider 前缀匹配
  /// 3. 仅用 modelId 在所有 metadata id 中查找（去掉 provider 前缀的部分）
  Future<ModelMetadataEntry?> match(String providerName, String modelId) async {
    await _ensureCache();

    // 策略 1：精确匹配 provider/modelId
    final exactKey = '$providerName/$modelId';
    if (_cache!.containsKey(exactKey)) return _cache![exactKey];

    // 策略 2：遍历所有 metadata，找到 id 末尾匹配 modelId 的条目
    // models.dev 格式: "deepseek/deepseek-r1"，API 返回 modelId: "deepseek-r1"
    for (final entry in _cache!.values) {
      final entryId = entry.id;
      // 从 "deepseek/deepseek-r1" 提取 "deepseek-r1"
      final slashIndex = entryId.indexOf('/');
      if (slashIndex != -1) {
        final suffix = entryId.substring(slashIndex + 1);
        if (suffix == modelId || suffix.toLowerCase() == modelId.toLowerCase()) {
          return entry;
        }
      }
    }

    // 策略 3：modelId 本身包含 provider 前缀（如 "deepseek/deepseek-r1"）
    if (modelId.contains('/')) {
      if (_cache!.containsKey(modelId)) return _cache![modelId];
    }

    return null;
  }

  Future<void> _ensureCache() async {
    if (_cache != null) return;
    final cached = await _repo.getModelMetadataCache();
    if (cached != null && cached.isNotEmpty) {
      try {
        _cache = _parseMetadata(cached);
      } catch (_) {
        _cache = {};
      }
    } else {
      _cache = {};
    }
  }

  Map<String, ModelMetadataEntry> _parseFromMap(Map<String, dynamic> data) {
    final result = <String, ModelMetadataEntry>{};
    for (final entry in data.entries) {
      if (entry.value is Map<String, dynamic>) {
        try {
          final meta = ModelMetadataEntry.fromJson(
              entry.value.cast<String, dynamic>());
          result[entry.key] = meta;
        } catch (_) {
          // 跳过解析失败的条目
        }
      }
    }
    return result;
  }

  Map<String, ModelMetadataEntry> _parseMetadata(String jsonStr) {
    final decoded = jsonDecode(jsonStr);
    if (decoded is Map<String, dynamic>) {
      return _parseFromMap(decoded);
    }
    return {};
  }
}
