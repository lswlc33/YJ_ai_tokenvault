import 'dart:convert';

class ModelInfo {
  const ModelInfo({required this.id, this.raw = const {}});

  final String id;
  final Map<String, dynamic> raw;

  int? get contextLength =>
      (raw['context_length'] ?? raw['max_context_length'] ?? raw['context_window']) as int?;

  double? get inputPrice {
    final p = raw['pricing'];
    if (p is Map) {
      final v = p['prompt'] ?? p['input'];
      if (v is num) return v.toDouble();
      if (v is String) return double.tryParse(v);
    }
    final v = raw['input_price'] ?? raw['input_cost'];
    if (v is num) return v.toDouble();
    if (v is String) return double.tryParse(v);
    return null;
  }

  double? get outputPrice {
    final p = raw['pricing'];
    if (p is Map) {
      final v = p['completion'] ?? p['output'];
      if (v is num) return v.toDouble();
      if (v is String) return double.tryParse(v);
    }
    final v = raw['output_price'] ?? raw['output_cost'];
    if (v is num) return v.toDouble();
    if (v is String) return double.tryParse(v);
    return null;
  }

  String? get ownedBy => raw['owned_by'] as String?;

  bool? get supportsVision {
    final caps = raw['capabilities'];
    if (caps is Map) return caps['vision'] as bool?;
    return raw['vision'] as bool?;
  }

  bool? get supportsFunctionCalling {
    final caps = raw['capabilities'];
    if (caps is Map) return caps['function_calling'] as bool?;
    return raw['function_calling'] as bool?;
  }

  bool? get supportsStreaming {
    final caps = raw['capabilities'];
    if (caps is Map) return caps['streaming'] as bool?;
    return raw['streaming'] as bool?;
  }

  Map<String, dynamic> toJson() => {'id': id, ...raw};

  factory ModelInfo.fromJson(Map<String, dynamic> json) {
    final id = json['id'] as String? ?? '';
    final raw = Map<String, dynamic>.from(json)..remove('id');
    return ModelInfo(id: id, raw: raw);
  }

  static List<ModelInfo> parseList(String? s) {
    if (s == null || s.isEmpty) return const [];
    final decoded = jsonDecode(s);
    if (decoded is List) {
      return decoded
          .whereType<Map<String, dynamic>>()
          .map(ModelInfo.fromJson)
          .toList();
    }
    return const [];
  }

  static String encodeList(List<ModelInfo> models) {
    return jsonEncode(models.map((m) => m.toJson()).toList());
  }
}
