/// 分类（计划 §2.1 categories）。"未分类"为内置 id=0，不可删。
class Category {
  const Category({
    required this.id,
    required this.name,
    this.sortOrder = 0,
  });

  final int id;
  final String name;
  final int sortOrder;

  static const int uncategorizedId = 0;
  bool get isUncategorized => id == uncategorizedId;

  Category copyWith({int? id, String? name, int? sortOrder}) => Category(
        id: id ?? this.id,
        name: name ?? this.name,
        sortOrder: sortOrder ?? this.sortOrder,
      );

  factory Category.fromMap(Map<String, Object?> m) => Category(
        id: m['id'] as int,
        name: m['name'] as String,
        sortOrder: (m['sort_order'] as int?) ?? 0,
      );

  Map<String, Object?> toMap() => {
        'id': id,
        'name': name,
        'sort_order': sortOrder,
      };
}
