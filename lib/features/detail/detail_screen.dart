import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/widget_previews.dart';
import 'package:intl/intl.dart';

import '../../core/model_metadata/model_metadata_service.dart';
import '../../core/theme/theme_ext.dart';
import '../../core/utils/masking.dart';
import '../../models/api_key.dart';
import '../../models/key_status.dart';
import '../../models/model_info.dart';
import '../../models/provider_with_keys.dart';
import '../../state/providers.dart';
import '../../state/vault_controllers.dart';
import '../dashboard/status_dot.dart';
import '../editor/editor_screen.dart';

class DetailScreen extends ConsumerWidget {
  const DetailScreen({super.key, required this.providerId});
  final int providerId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(providerDetailProvider(providerId));
    final probeState = ref.watch(probeControllerProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('厂家详情'),
        actions: [
          detail.maybeWhen(
            data: (d) => d == null
                ? const SizedBox()
                : Row(
                    children: [
                      IconButton(
                        tooltip: '刷新探测',
                        icon: probeState.running
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child:
                                    CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(Icons.refresh),
                        onPressed: probeState.running
                            ? null
                            : () => ref
                                .read(probeControllerProvider.notifier)
                                .probeProvider(providerId),
                      ),
                      IconButton(
                        tooltip: '删除',
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () => _confirmDelete(context, ref, d),
                      ),
                    ],
                  ),
            orElse: () => const SizedBox(),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () async {
          final d = detail.valueOrNull;
          if (d == null) return;
          await Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => EditorScreen(existing: d)),
          );
          ref.invalidate(providerDetailProvider(providerId));
          ref.invalidate(dashboardProvider);
        },
        child: const Icon(Icons.edit),
      ),
      body: detail.when(
        data: (d) =>
            d == null ? const Center(child: Text('厂家不存在')) : _Body(data: d),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('加载失败：$e')),
      ),
    );
  }

  Future<void> _confirmDelete(
      BuildContext context, WidgetRef ref, ProviderWithKeys d) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('删除厂家？'),
        content: Text('「${d.provider.name}」及其下 ${d.keyCount} 个 Key 将被删除，不可恢复。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('删除')),
        ],
      ),
    );
    if (ok != true) return;
    final repo = await ref.read(vaultRepositoryProvider.future);
    await repo.deleteProvider(d.provider.id);
    ref.invalidate(dashboardProvider);
    if (context.mounted) Navigator.of(context).pop();
  }
}

class _Body extends ConsumerStatefulWidget {
  const _Body({required this.data});
  final ProviderWithKeys data;

  @override
  ConsumerState<_Body> createState() => _BodyState();
}

class _BodyState extends ConsumerState<_Body> {
  Map<String, ModelMetadataEntry> _metadataMap = {};

  @override
  void initState() {
    super.initState();
    _loadMetadata();
  }

  Future<void> _loadMetadata() async {
    try {
      final svc = await ref.read(modelMetadataServiceProvider.future);
      final p = widget.data.provider;
      final allModelIds = widget.data.keys.expand((k) => k.modelIds).toSet();
      final map = <String, ModelMetadataEntry>{};
      for (final modelId in allModelIds) {
        final meta = await svc.match(p.name, modelId);
        if (meta != null) map[modelId] = meta;
      }
      if (mounted) setState(() => _metadataMap = map);
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final p = widget.data.provider;

    return ListView(
      padding: EdgeInsets.all(style.pagePadding),
      children: [
        Card(
          child: Padding(
            padding: EdgeInsets.all(style.cardPadding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(p.name, style: theme.textTheme.titleLarge),
                SizedBox(height: style.smallSpacing),
                Row(
                  children: [
                    Expanded(
                      child: SelectableText(
                        p.fullApiUrl.isNotEmpty ? p.fullApiUrl : '未设置端点',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          fontFamily: 'monospace',
                          color: theme.colorScheme.primary,
                        ),
                      ),
                    ),
                    if (p.fullApiUrl.isNotEmpty)
                      IconButton(
                        icon: const Icon(Icons.copy, size: 16),
                        tooltip: '复制端点',
                        visualDensity: VisualDensity.compact,
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: p.fullApiUrl));
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('端点已复制')),
                          );
                        },
                      ),
                  ],
                ),
                if (p.note != null && p.note!.isNotEmpty) ...[
                  SizedBox(height: style.smallSpacing),
                  Text(p.note!,
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.hintColor)),
                ],
              ],
            ),
          ),
        ),
        SizedBox(height: style.sectionSpacing),
        Text('API Keys（${widget.data.keyCount}）',
            style: theme.textTheme.titleMedium),
        SizedBox(height: style.itemSpacing),
        for (final k in widget.data.keys)
          _KeyTile(
            apiKey: k,
            providerName: p.name,
            metadataMap: _metadataMap,
          ),
      ],
    );
  }
}

void _copy(BuildContext context, String value, String what) {
  Clipboard.setData(ClipboardData(text: value));
  ScaffoldMessenger.of(context)
      .showSnackBar(SnackBar(content: Text('$what 已复制')));
}

class _KeyTile extends StatefulWidget {
  const _KeyTile({
    required this.apiKey,
    required this.providerName,
    this.metadataMap = const {},
  });
  final ApiKey apiKey;
  final String providerName;
  final Map<String, ModelMetadataEntry> metadataMap;

  @override
  State<_KeyTile> createState() => _KeyTileState();
}

class _KeyTileState extends State<_KeyTile> {
  bool _revealed = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final k = widget.apiKey;
    final keyText = k.apiKey ?? '';
    final display = _revealed ? keyText : Masking.maskKey(keyText);
    final checked = k.lastCheckedAt == null
        ? '从未探测'
        : DateFormat('MM-dd HH:mm')
            .format(DateTime.fromMillisecondsSinceEpoch(k.lastCheckedAt!));

    return Card(
      margin: EdgeInsets.only(bottom: style.itemSpacing),
      child: Padding(
        padding: EdgeInsets.all(style.cardPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                    child: Text(k.label,
                        style: theme.textTheme.titleSmall,
                        overflow: TextOverflow.ellipsis)),
                StatusDot(k.status, size: 10, label: true),
              ],
            ),
            SizedBox(height: style.smallSpacing),
            Row(
              children: [
                Expanded(
                  child: Text(display,
                      style: const TextStyle(fontFamily: 'monospace')),
                ),
                IconButton(
                  iconSize: 18,
                  visualDensity: VisualDensity.compact,
                  tooltip: _revealed ? '隐藏' : '显示',
                  icon: Icon(_revealed
                      ? Icons.visibility_off_outlined
                      : Icons.visibility_outlined),
                  onPressed: () => setState(() => _revealed = !_revealed),
                ),
                IconButton(
                  iconSize: 18,
                  visualDensity: VisualDensity.compact,
                  tooltip: '复制 Key',
                  icon: const Icon(Icons.copy),
                  onPressed: () => _copy(context, keyText, 'Key'),
                ),
              ],
            ),
            SizedBox(height: style.microSpacing),
            Wrap(
              spacing: style.smallSpacing,
              runSpacing: style.microSpacing,
              children: [
                _statusChip(k.status),
                if (k.modelListEnabled) _modelsChip(k),
                if (k.balanceCheckEnabled) _balanceChip(k),
              ],
            ),
            SizedBox(height: style.microSpacing),
            Text('探测于 $checked',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.hintColor)),
            if (k.models.isNotEmpty) ...[
              SizedBox(height: style.smallSpacing),
              InkWell(
                borderRadius: BorderRadius.circular(style.chipRadius),
                onTap: () => _showModels(context, k),
                child: Container(
                  width: double.infinity,
                  padding: EdgeInsets.all(style.smallSpacing),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest
                        .withValues(alpha: 0.3),
                    borderRadius: BorderRadius.circular(style.chipRadius),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Text('模型列表（${k.models.length}）',
                              style: theme.textTheme.bodySmall
                                  ?.copyWith(fontWeight: FontWeight.bold)),
                          const Spacer(),
                          Icon(Icons.chevron_right,
                              size: 16, color: theme.hintColor),
                        ],
                      ),
                      SizedBox(height: style.microSpacing),
                      Text(
                        k.modelIds.take(5).join(', ') +
                            (k.models.length > 5
                                ? ' ... +${k.models.length - 5}'
                                : ''),
                        style: theme.textTheme.bodySmall?.copyWith(
                            fontFamily: 'monospace', color: theme.hintColor),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _statusChip(KeyStatus status) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final color = switch (status) {
      KeyStatus.ok => Colors.green,
      KeyStatus.invalid => Colors.red,
      KeyStatus.insufficient => Colors.orange,
      KeyStatus.overdue => Colors.red,
      _ => theme.hintColor,
    };
    final text = switch (status) {
      KeyStatus.ok => '可用',
      KeyStatus.invalid => '失效',
      KeyStatus.insufficient => '余额不足',
      KeyStatus.overdue => '欠费中',
      _ => '未探测',
    };
    return Container(
      padding: EdgeInsets.symmetric(
          horizontal: style.smallSpacing, vertical: style.microSpacing),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(style.chipRadius),
      ),
      child:
          Text(text, style: theme.textTheme.bodySmall?.copyWith(color: color)),
    );
  }

  Widget _modelsChip(ApiKey k) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    return Container(
      padding: EdgeInsets.symmetric(
          horizontal: style.smallSpacing, vertical: style.microSpacing),
      decoration: BoxDecoration(
        color: theme.colorScheme.primary.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(style.chipRadius),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.smart_toy_outlined,
              size: 14, color: theme.colorScheme.primary),
          SizedBox(width: style.microSpacing),
          Text('${k.models.length} 模型',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.primary)),
        ],
      ),
    );
  }

  Widget _balanceChip(ApiKey k) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    String text;
    if (k.balance != null) {
      text = '\$${k.balance!.toStringAsFixed(2)}';
    } else if (k.balanceText != null) {
      text = k.balanceText!;
    } else {
      text = '余额未知';
    }
    return Container(
      padding: EdgeInsets.symmetric(
          horizontal: style.smallSpacing, vertical: style.microSpacing),
      decoration: BoxDecoration(
        color: theme.colorScheme.secondary.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(style.chipRadius),
      ),
      child: Text(text,
          style: theme.textTheme.bodySmall
              ?.copyWith(color: theme.colorScheme.secondary)),
    );
  }

  void _showModels(BuildContext context, ApiKey key) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (_) => _ModelsSheet(
        models: key.models,
        keyLabel: key.label,
        providerName: widget.providerName,
        metadataMap: widget.metadataMap,
      ),
    );
  }
}

class _ModelsSheet extends StatefulWidget {
  const _ModelsSheet({
    required this.models,
    required this.keyLabel,
    required this.providerName,
    this.metadataMap = const {},
  });
  final List<ModelInfo> models;
  final String keyLabel;
  final String providerName;
  final Map<String, ModelMetadataEntry> metadataMap;

  @override
  State<_ModelsSheet> createState() => _ModelsSheetState();
}

class _ModelsSheetState extends State<_ModelsSheet> {
  String _filter = '';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final filtered = widget.models
        .where((m) => m.id.toLowerCase().contains(_filter.toLowerCase()))
        .toList()
      ..sort((a, b) {
        final aFree = a.id.toLowerCase().contains('free');
        final bFree = b.id.toLowerCase().contains('free');
        if (aFree && !bFree) return -1;
        if (!aFree && bFree) return 1;
        return a.id.compareTo(b.id);
      });

    return DraggableScrollableSheet(
      initialChildSize: 0.65,
      minChildSize: 0.2,
      maxChildSize: 0.95,
      expand: false,
      builder: (_, ctrl) => Padding(
        padding: EdgeInsets.fromLTRB(
            style.pagePadding, 0, style.pagePadding, style.pagePadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 40,
                height: 4,
                margin: const EdgeInsets.only(top: 8, bottom: 12),
                decoration: BoxDecoration(
                  color: theme.hintColor.withValues(alpha: 0.3),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${widget.keyLabel}（${widget.models.length} 模型）',
                    style: theme.textTheme.titleMedium,
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.close, size: 20),
                  tooltip: '关闭',
                  onPressed: () => Navigator.pop(context),
                ),
              ],
            ),
            SizedBox(height: style.smallSpacing),
            TextField(
              decoration: InputDecoration(
                hintText: '搜索模型...',
                prefixIcon: const Icon(Icons.search, size: 18),
                isDense: true,
                filled: true,
                fillColor: theme.colorScheme.surfaceContainerHighest
                    .withValues(alpha: 0.3),
                border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(style.chipRadius),
                    borderSide: BorderSide.none),
              ),
              onChanged: (v) => setState(() => _filter = v),
            ),
            SizedBox(height: style.smallSpacing),
            Expanded(
              child: filtered.isEmpty
                  ? const Center(child: Text('没有匹配的模型'))
                  : ListView.separated(
                      controller: ctrl,
                      itemCount: filtered.length,
                      separatorBuilder: (_, __) =>
                          SizedBox(height: style.smallSpacing),
                      itemBuilder: (_, i) {
                        final model = filtered[i];
                        final meta = widget.metadataMap[model.id];
                        return _ModelTile(model: model, metadata: meta);
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ModelTile extends StatelessWidget {
  const _ModelTile({required this.model, this.metadata});
  final ModelInfo model;
  final ModelMetadataEntry? metadata;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final m = model;
    final meta = metadata;

    return Card(
      child: Padding(
        padding: EdgeInsets.all(style.cardPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(m.id,
                          style: TextStyle(
                              fontFamily: 'monospace',
                              fontSize: 13,
                              fontWeight: FontWeight.w500,
                              color: theme.colorScheme.onSurface)),
                      if (meta != null && meta.name.isNotEmpty)
                        Text(meta.name,
                            style: theme.textTheme.bodySmall
                                ?.copyWith(color: theme.hintColor)),
                    ],
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.copy, size: 16),
                  tooltip: '复制模型 ID',
                  visualDensity: VisualDensity.compact,
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: m.id));
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('模型名已复制')),
                    );
                  },
                ),
              ],
            ),
            _buildInfo(theme, style, meta),
          ],
        ),
      ),
    );
  }

  Widget _buildInfo(ThemeData theme, AppStyle style, ModelMetadataEntry? meta) {
    final chips = <Widget>[];

    // 上下文长度：优先用 metadata 的 contextLimit
    final ctx = meta?.contextLimit ?? model.contextLength;
    if (ctx != null) {
      chips.add(_infoChip(theme, style, '上下文 ${_formatNumber(ctx)}'));
    }

    // 输出限制
    if (meta?.outputLimit != null) {
      chips.add(
          _infoChip(theme, style, '输出 ${_formatNumber(meta!.outputLimit!)}'));
    }

    // 输入模态
    if (meta != null && meta.inputModalities.isNotEmpty) {
      final labels = meta.inputModalities.map(_modalityLabel).join('/');
      chips.add(_infoChip(theme, style, '输入: $labels'));
    }

    // 输出模态
    if (meta != null && meta.outputModalities.isNotEmpty) {
      final labels = meta.outputModalities.map(_modalityLabel).join('/');
      chips.add(_infoChip(theme, style, '输出: $labels'));
    }

    if (chips.isEmpty) return const SizedBox.shrink();

    return Padding(
      padding: EdgeInsets.only(top: style.microSpacing),
      child: Wrap(
        spacing: style.smallSpacing,
        runSpacing: style.microSpacing,
        children: chips,
      ),
    );
  }

  Widget _infoChip(ThemeData theme, AppStyle style, String text) {
    return Container(
      padding: EdgeInsets.symmetric(
          horizontal: style.smallSpacing, vertical: style.microSpacing),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.4),
        borderRadius: BorderRadius.circular(style.chipRadius),
      ),
      child: Text(text,
          style: theme.textTheme.bodySmall
              ?.copyWith(fontFamily: 'monospace', fontSize: 11)),
    );
  }

  String _modalityLabel(String m) {
    return switch (m) {
      'text' => '文本',
      'image' => '图像',
      'pdf' => 'PDF',
      'audio' => '音频',
      'video' => '视频',
      _ => m,
    };
  }

  String _formatNumber(int n) {
    if (n >= 1000000) return '${(n / 1000000).toStringAsFixed(1)}M';
    if (n >= 1000) return '${(n / 1000).toStringAsFixed(0)}K';
    return '$n';
  }
}

@Preview(name: 'ModelTile - Basic')
Widget previewModelTileBasic() {
  return const _ModelTile(
    model: ModelInfo(id: 'gpt-4o'),
  );
}

@Preview(name: 'ModelTile - With Metadata')
Widget previewModelTileWithMetadata() {
  return const _ModelTile(
    model: ModelInfo(id: 'claude-3-5-sonnet'),
    metadata: ModelMetadataEntry(
      id: 'claude-3-5-sonnet',
      name: 'Claude 3.5 Sonnet',
      contextLimit: 200000,
      outputLimit: 8192,
      inputModalities: ['text', 'image'],
      outputModalities: ['text'],
      reasoning: false,
      toolCall: true,
    ),
  );
}

@Preview(name: 'ModelTile - Free Model')
Widget previewModelTileFree() {
  return const _ModelTile(
    model: ModelInfo(
      id: 'deepseek-chat-free',
      raw: {
        'context_length': 64000,
        'pricing': {'prompt': '0', 'completion': '0'},
      },
    ),
  );
}
