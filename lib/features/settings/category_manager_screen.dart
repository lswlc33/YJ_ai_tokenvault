import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../models/category.dart';
import '../../state/vault_controllers.dart';

/// 分类管理页：增删改分类。
class CategoryManagerScreen extends ConsumerStatefulWidget {
  const CategoryManagerScreen({super.key});

  @override
  ConsumerState<CategoryManagerScreen> createState() =>
      _CategoryManagerScreenState();
}

class _CategoryManagerScreenState
    extends ConsumerState<CategoryManagerScreen> {
  @override
  Widget build(BuildContext context) {
    final categories = ref.watch(categoriesProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('分类管理'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            tooltip: '添加分类',
            onPressed: () => _showEditDialog(context, ref),
          ),
        ],
      ),
      body: categories.when(
        data: (list) {
          if (list.isEmpty) {
            return const Center(child: Text('暂无分类'));
          }
          final customizable = list.where((c) => !c.isUncategorized).toList();
          final uncategorized = list.where((c) => c.isUncategorized).toList();
          final sorted = [...customizable, ...uncategorized];
          return ReorderableListView.builder(
            buildDefaultDragHandles: false,
            itemCount: sorted.length,
            onReorderItem: (oldIndex, newIndex) => _onReorder(ref, sorted, oldIndex, newIndex),
            itemBuilder: (context, index) {
              final c = sorted[index];
              return ReorderableDragStartListener(
                key: ValueKey(c.id),
                index: index,
                child: ListTile(
                  leading: Icon(
                    c.isUncategorized ? Icons.folder_off_outlined : Icons.folder_outlined,
                  ),
                  title: Text(c.name),
                  subtitle: c.isUncategorized ? const Text('内置分类，不可删除') : null,
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (!c.isUncategorized) ...[
                        IconButton(
                          icon: const Icon(Icons.edit_outlined),
                          tooltip: '编辑',
                          onPressed: () =>
                              _showEditDialog(context, ref, category: c),
                        ),
                        IconButton(
                          icon: const Icon(Icons.delete_outline),
                          tooltip: '删除',
                          onPressed: () =>
                              _confirmDelete(context, ref, c),
                        ),
                      ],
                      if (!c.isUncategorized)
                        const Icon(Icons.drag_handle),
                    ],
                  ),
                ),
              );
            },
          );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('加载失败：$e')),
      ),
    );
  }

  Future<void> _onReorder(
    WidgetRef ref,
    List<Category> sorted,
    int oldIndex,
    int newIndex,
  ) async {
    final item = sorted.removeAt(oldIndex);
    sorted.insert(newIndex, item);

    final repo = await ref.read(vaultRepositoryProvider.future);
    await repo.reorderCategories([for (final c in sorted) c.id]);
    ref.invalidate(categoriesProvider);
  }

  Future<void> _showEditDialog(
    BuildContext context,
    WidgetRef ref, {
    Category? category,
  }) async {
    final controller = TextEditingController(text: category?.name ?? '');
    final isEdit = category != null;

    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(isEdit ? '编辑分类' : '添加分类'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: '分类名称',
            hintText: '请输入分类名称',
          ),
          onSubmitted: (_) => Navigator.pop(context, true),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('保存'),
          ),
        ],
      ),
    );

    if (result != true) return;

    final name = controller.text.trim();
    if (name.isEmpty) return;

    final repo = await ref.read(vaultRepositoryProvider.future);
    if (isEdit) {
      await repo.updateCategory(category.copyWith(name: name));
    } else {
      await repo.addCategory(Category(id: 0, name: name));
    }
    ref.invalidate(categoriesProvider);
  }

  Future<void> _confirmDelete(
    BuildContext context,
    WidgetRef ref,
    Category category,
  ) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除分类？'),
        content: Text('「${category.name}」将被删除，其下的厂家将归入"未分类"。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );

    if (result != true) return;

    final repo = await ref.read(vaultRepositoryProvider.future);
    await repo.deleteCategory(category.id);
    ref.invalidate(categoriesProvider);
    ref.invalidate(dashboardProvider);
  }
}
