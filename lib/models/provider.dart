/// 厂家卡片。
class Provider {
  const Provider({
    required this.id,
    required this.name,
    this.baseUrl,
    this.apiPath = '',
    this.categoryId = 0,
    this.note,
    this.color,
    this.probeType = 'builtin',
    this.sortOrder = 0,
    this.createdAt = 0,
    this.updatedAt = 0,
  });

  final int id;
  final String name;
  final String? baseUrl;
  final String apiPath;
  final int categoryId;
  final String? note;
  final int? color;
  final String probeType;
  final int sortOrder;
  final int createdAt;
  final int updatedAt;

  /// 完整 API 端点：baseUrl + apiPath。
  String get fullApiUrl {
    final base = baseUrl ?? '';
    if (apiPath.isEmpty) return base;
    if (base.isEmpty) return apiPath;
    final b = base.endsWith('/') ? base.substring(0, base.length - 1) : base;
    final p = apiPath.startsWith('/') ? apiPath.substring(1) : apiPath;
    return '$b/$p';
  }

  Provider copyWith({
    int? id,
    String? name,
    Object? baseUrl = _sentinel,
    String? apiPath,
    int? categoryId,
    Object? note = _sentinel,
    Object? color = _sentinel,
    String? probeType,
    int? sortOrder,
    int? createdAt,
    int? updatedAt,
  }) =>
      Provider(
        id: id ?? this.id,
        name: name ?? this.name,
        baseUrl: baseUrl == _sentinel ? this.baseUrl : baseUrl as String?,
        apiPath: apiPath ?? this.apiPath,
        categoryId: categoryId ?? this.categoryId,
        note: note == _sentinel ? this.note : note as String?,
        color: color == _sentinel ? this.color : color as int?,
        probeType: probeType ?? this.probeType,
        sortOrder: sortOrder ?? this.sortOrder,
        createdAt: createdAt ?? this.createdAt,
        updatedAt: updatedAt ?? this.updatedAt,
      );

  factory Provider.fromMap(Map<String, Object?> m) => Provider(
        id: m['id'] as int,
        name: m['name'] as String,
        baseUrl: m['base_url'] as String?,
        apiPath: (m['api_path'] as String?) ?? '',
        categoryId: (m['category_id'] as int?) ?? 0,
        note: m['note'] as String?,
        color: m['color'] as int?,
        probeType: (m['probe_type'] as String?) ?? 'builtin',
        sortOrder: (m['sort_order'] as int?) ?? 0,
        createdAt: (m['created_at'] as int?) ?? 0,
        updatedAt: (m['updated_at'] as int?) ?? 0,
      );

  Map<String, Object?> toMap() => {
        if (id != 0) 'id': id,
        'name': name,
        'base_url': baseUrl,
        'api_path': apiPath,
        'category_id': categoryId,
        'note': note,
        'color': color,
        'probe_type': probeType,
        'sort_order': sortOrder,
        'created_at': createdAt,
        'updated_at': updatedAt,
      };

  static const Object _sentinel = Object();
}
