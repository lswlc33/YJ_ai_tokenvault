import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../../../models/category.dart';

/// 分类 DAO（无加密字段）。
class CategoryDao {
  CategoryDao(this._db);
  final Database _db;

  Future<List<Category>> getAll() async {
    final rows = await _db.query('categories', orderBy: 'sort_order ASC, id ASC');
    return rows.map(Category.fromMap).toList();
  }

  Future<int> insert(Category c) {
    final map = c.toMap()..remove('id');
    return _db.insert('categories', map);
  }

  Future<void> update(Category c) async {
    await _db.update('categories', c.toMap(),
        where: 'id = ?', whereArgs: [c.id]);
  }

  /// 删除分类；其下厂家的 category_id 由外键 SET DEFAULT 归 0（未分类）。
  /// 禁止删除内置未分类。
  Future<void> delete(int id) async {
    if (id == Category.uncategorizedId) return;
    await _db.delete('categories', where: 'id = ?', whereArgs: [id]);
  }

  /// 批量更新排序顺序。[orderedIds] 按新顺序排列。
  Future<void> reorder(List<int> orderedIds) async {
    await _db.transaction((txn) async {
      for (var i = 0; i < orderedIds.length; i++) {
        await txn.update(
          'categories',
          {'sort_order': i},
          where: 'id = ?',
          whereArgs: [orderedIds[i]],
        );
      }
    });
  }
}
