import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/responsive.dart';
import '../../core/theme/theme_ext.dart';
import '../../core/utils/masking.dart';
import '../../models/category.dart';
import '../../models/provider_with_keys.dart';
import '../../state/vault_controllers.dart';
import '../detail/detail_screen.dart';
import '../editor/editor_screen.dart';
import 'status_dot.dart';

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final r = Responsive.of(context);
    final style = context.appStyle;
    final dashboard = ref.watch(dashboardProvider);
    final categories = ref.watch(categoriesProvider);
    final selected = ref.watch(selectedCategoryProvider);
    final probeState = ref.watch(probeControllerProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('仪表盘'),
        actions: [
          if (probeState.running)
            Padding(
              padding: EdgeInsets.all(style.itemSpacing),
              child: Center(
                child: Text('${probeState.progress}/${probeState.total}',
                    style: const TextStyle(fontSize: 12)),
              ),
            ),
          IconButton(
            tooltip: '刷新探测',
            icon: probeState.running
                ? SizedBox(
                    width: style.spinnerSize,
                    height: style.spinnerSize,
                    child: const CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.refresh),
            onPressed: probeState.running
                ? null
                : () => ref.read(probeControllerProvider.notifier).probeAll(),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const EditorScreen()),
        ),
        icon: const Icon(Icons.add),
        label: const Text('添加厂家'),
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: BoxConstraints(maxWidth: r.contentMaxWidth),
            child: RefreshIndicator(
              onRefresh: () async => ref.invalidate(dashboardProvider),
              child: CustomScrollView(
                slivers: [
                  SliverToBoxAdapter(child: _SummaryBar()),
                  SliverToBoxAdapter(
                    child: categories.when(
                      data: (list) => list.length <= 1
                          ? const SizedBox.shrink()
                          : Card(
                              margin: EdgeInsets.symmetric(
                                  horizontal: style.pagePadding,
                                  vertical: style.microSpacing),
                              child: _CategoryChips(
                                categories: list,
                                selected: selected,
                                onSelect: (id) => ref
                                    .read(selectedCategoryProvider.notifier)
                                    .state = id,
                              ),
                            ),
                      loading: () => const SizedBox.shrink(),
                      error: (_, __) => const SizedBox.shrink(),
                    ),
                  ),
                  dashboard.when(
                    data: (list) {
                      if (list.isEmpty) {
                        return const SliverFillRemaining(
                          hasScrollBody: false,
                          child: _EmptyState(),
                        );
                      }
                      return SliverPadding(
                        padding: EdgeInsets.all(style.pagePadding),
                        sliver: SliverList(
                          delegate: SliverChildBuilderDelegate(
                            (context, i) => _ProviderTile(
                              data: list[i],
                              onTap: () => Navigator.of(context).push(
                                MaterialPageRoute(
                                  builder: (_) => DetailScreen(
                                      providerId: list[i].provider.id),
                                ),
                              ),
                            ),
                            childCount: list.length,
                          ),
                        ),
                      );
                    },
                    loading: () => const SliverFillRemaining(
                      child: Center(child: CircularProgressIndicator()),
                    ),
                    error: (e, _) => SliverFillRemaining(
                      child: Center(child: Text('加载失败：$e')),
                    ),
                  ),
                  const SliverToBoxAdapter(child: SizedBox(height: 88)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SummaryBar extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final style = context.appStyle;
    final theme = Theme.of(context);
    final summary = ref.watch(summaryProvider);

    return Padding(
      padding: EdgeInsets.fromLTRB(
          style.pagePadding, style.pagePadding, style.pagePadding, 4),
      child: Card(
        child: Padding(
          padding: EdgeInsets.symmetric(
              horizontal: style.cardPadding, vertical: style.itemSpacing),
          child: summary.when(
            data: (s) => Row(
              children: [
                _metric(theme, '厂家', '${s.providerCount}'),
                _sep(theme),
                _metric(theme, 'Key', '${s.keyCount}'),
                _sep(theme),
                _metric(theme, '可用', '${s.availableCount}',
                    color: s.availableCount > 0 ? Colors.green : null),
                _sep(theme),
                _metric(theme, '模型', '${s.modelCount}'),
              ],
            ),
            loading: () => const SizedBox(height: 40),
            error: (_, __) => const SizedBox(height: 40),
          ),
        ),
      ),
    );
  }

  Widget _metric(ThemeData theme, String label, String value, {Color? color}) {
    return Expanded(
      child: Column(
        children: [
          Text(value,
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
                color: color,
              )),
          const SizedBox(height: 2),
          Text(label,
              style:
                  theme.textTheme.bodySmall?.copyWith(color: theme.hintColor)),
        ],
      ),
    );
  }

  Widget _sep(ThemeData theme) => Container(
        width: 1,
        height: 28,
        color: theme.colorScheme.outlineVariant,
      );
}

class _CategoryChips extends StatelessWidget {
  const _CategoryChips({
    required this.categories,
    required this.selected,
    required this.onSelect,
  });

  final List<Category> categories;
  final int? selected;
  final ValueChanged<int?> onSelect;

  @override
  Widget build(BuildContext context) {
    final style = context.appStyle;
    return SizedBox(
      height: 64,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: EdgeInsets.symmetric(horizontal: style.pagePadding),
        children: [
          _chip(context, '全部', selected == null, () => onSelect(null)),
          for (final c in categories)
            _chip(context, c.name, selected == c.id, () => onSelect(c.id)),
        ],
      ),
    );
  }

  Widget _chip(
      BuildContext context, String label, bool active, VoidCallback onTap) {
    final style = context.appStyle;
    return Padding(
      padding: EdgeInsets.only(right: style.smallSpacing),
      child: ChoiceChip(
        label: Text(label),
        selected: active,
        onSelected: (_) => onTap(),
      ),
    );
  }
}

class _ProviderTile extends StatelessWidget {
  const _ProviderTile({required this.data, required this.onTap});
  final ProviderWithKeys data;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final p = data.provider;
    final accent =
        p.color != null ? Color(p.color!) : theme.colorScheme.primary;

    return Card(
      margin: EdgeInsets.only(bottom: style.itemSpacing),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(style.cardRadius),
        child: Padding(
          padding: EdgeInsets.all(style.cardPadding),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: accent.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(10),
                ),
                alignment: Alignment.center,
                child: Text(
                  p.name.isNotEmpty ? p.name.characters.first : '?',
                  style: TextStyle(color: accent, fontWeight: FontWeight.bold),
                ),
              ),
              SizedBox(width: style.itemSpacing),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(p.name,
                        style: theme.textTheme.titleSmall?.copyWith(
                          color: theme.colorScheme.onSurface,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis),
                    const SizedBox(height: 2),
                    Text(
                      Masking.maskEndpoint(p.baseUrl),
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.hintColor),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              SizedBox(width: style.smallSpacing),
              _stat(Icons.key, '${data.keyCount}', theme),
              SizedBox(width: style.microSpacing),
              _stat(Icons.check_circle_outline, '${data.availableCount}', theme,
                  color: data.availableCount > 0 ? Colors.green : null),
              SizedBox(width: style.microSpacing),
              _stat(Icons.smart_toy_outlined, '${data.totalModelCount}', theme),
              SizedBox(width: style.microSpacing),
              StatusDot(data.aggregateStatus, size: 10),
            ],
          ),
        ),
      ),
    );
  }

  Widget _stat(IconData icon, String value, ThemeData theme, {Color? color}) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: color ?? theme.hintColor),
        const SizedBox(width: 2),
        Text(value, style: theme.textTheme.bodySmall?.copyWith(color: color)),
      ],
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.inbox_outlined, size: 64, color: theme.hintColor),
          SizedBox(height: style.itemSpacing),
          Text('还没有厂家', style: theme.textTheme.titleMedium),
          SizedBox(height: style.microSpacing),
          Text('点右下角「添加厂家」录入你的第一张卡片',
              style:
                  theme.textTheme.bodySmall?.copyWith(color: theme.hintColor)),
        ],
      ),
    );
  }
}

