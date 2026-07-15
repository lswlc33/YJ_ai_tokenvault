import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/log/log_service.dart';
import '../../core/theme/theme_ext.dart';

class LogScreen extends StatefulWidget {
  const LogScreen({super.key});

  @override
  State<LogScreen> createState() => _LogScreenState();
}

class _LogScreenState extends State<LogScreen> {
  bool _autoScroll = true;
  LogLevel? _filterLevel;
  final ScrollController _scrollCtrl = ScrollController();
  String _searchQuery = '';
  List<LogEntry> _entries = [];
  Stream<List<LogEntry>>? _stream;

  @override
  void initState() {
    super.initState();
    _entries = log.entries;
    _stream = log.stream;
    _stream!.listen((entries) {
      if (mounted) {
        setState(() => _entries = entries);
        if (_autoScroll) _scrollToBottom();
      }
    });
  }

  @override
  void dispose() {
    _scrollCtrl.dispose();
    super.dispose();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent,
          duration: const Duration(milliseconds: 150),
          curve: Curves.easeOut,
        );
      }
    });
  }

  List<LogEntry> get _filtered {
    return _entries.where((e) {
      if (_filterLevel != null && e.level.value < _filterLevel!.value) {
        return false;
      }
      if (_searchQuery.isNotEmpty) {
        final q = _searchQuery.toLowerCase();
        return e.message.toLowerCase().contains(q) ||
            (e.detail?.toLowerCase().contains(q) ?? false) ||
            (e.source?.toLowerCase().contains(q) ?? false);
      }
      return true;
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final filtered = _filtered;
    final topPadding = MediaQuery.of(context).padding.top;

    return Column(
      children: [
        SizedBox(height: topPadding),
        // 工具栏
        Container(
          padding: EdgeInsets.symmetric(
              horizontal: style.smallSpacing, vertical: style.microSpacing),
          child: Wrap(
            spacing: style.microSpacing,
            runSpacing: style.microSpacing,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              _levelChip(LogLevel.debug),
              _levelChip(LogLevel.info),
              _levelChip(LogLevel.warn),
              _levelChip(LogLevel.error),
              _levelChip(null),
              SizedBox(
                width: 120,
                height: 32,
                child: TextField(
                  decoration: InputDecoration(
                    hintText: '搜索…',
                    hintStyle: theme.textTheme.bodySmall,
                    prefixIcon: const Icon(Icons.search, size: 16),
                    isDense: true,
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: 8, vertical: 4),
                    border: OutlineInputBorder(
                        borderRadius:
                            BorderRadius.circular(style.chipRadius)),
                    enabledBorder: OutlineInputBorder(
                        borderRadius:
                            BorderRadius.circular(style.chipRadius)),
                    focusedBorder: OutlineInputBorder(
                        borderRadius:
                            BorderRadius.circular(style.chipRadius)),
                  ),
                  style: theme.textTheme.bodySmall,
                  onChanged: (v) => setState(() => _searchQuery = v),
                ),
              ),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('自动滚动',
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.hintColor)),
                  Transform.scale(
                    scale: 0.7,
                    child: Switch(
                      value: _autoScroll,
                      onChanged: (v) {
                        setState(() => _autoScroll = v);
                        if (v) _scrollToBottom();
                      },
                    ),
                  ),
                ],
              ),
              IconButton(
                tooltip: '复制全部',
                iconSize: 18,
                onPressed: () => _copyAll(context),
                icon: const Icon(Icons.copy),
              ),
              IconButton(
                tooltip: '清空',
                iconSize: 18,
                onPressed: () {
                  log.clear();
                  setState(() => _entries = []);
                },
                icon: const Icon(Icons.delete_outline),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        // 日志列表
        Expanded(
          child: filtered.isEmpty
              ? Center(
                  child: Text('暂无日志',
                      style: theme.textTheme.bodyMedium
                          ?.copyWith(color: theme.hintColor)),
                )
              : Scrollbar(
                  controller: _scrollCtrl,
                  child: ListView.builder(
                    controller: _scrollCtrl,
                    addAutomaticKeepAlives: false,
                    padding: EdgeInsets.only(
                      left: style.smallSpacing,
                      right: style.smallSpacing,
                      top: style.microSpacing,
                      bottom: 80,
                    ),
                    itemCount: filtered.length,
                    itemBuilder: (_, i) => _LogTile(entry: filtered[i]),
                  ),
                ),
        ),
      ],
    );
  }

  Widget _levelChip(LogLevel? level) {
    final theme = Theme.of(context);
    final selected = _filterLevel == level;
    final label = level?.label ?? '全部';
    final color = switch (level) {
      LogLevel.debug => theme.hintColor,
      LogLevel.info => Colors.blue,
      LogLevel.warn => Colors.orange,
      LogLevel.error => Colors.red,
      _ => theme.colorScheme.primary,
    };
    return FilterChip(
      label: Text(label,
          style: TextStyle(fontSize: 11, color: selected ? Colors.white : color)),
      selected: selected,
      onSelected: (_) => setState(() => _filterLevel = level),
      selectedColor: color,
      checkmarkColor: Colors.white,
      padding: EdgeInsets.zero,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      visualDensity: VisualDensity.compact,
    );
  }

  void _copyAll(BuildContext context) {
    final buf = StringBuffer();
    for (final e in _filtered) {
      buf.write('[${e.timeStr}] [${e.level.label}] ');
      if (e.source != null) buf.write('[${e.source}] ');
      buf.writeln(e.message);
      if (e.detail != null) {
        for (final line in e.detail!.split('\n')) {
          buf.write('  $line\n');
        }
      }
      buf.writeln();
    }
    Clipboard.setData(ClipboardData(text: buf.toString()));
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('日志已复制')));
  }
}

class _LogTile extends StatelessWidget {
  const _LogTile({required this.entry});
  final LogEntry entry;
  static const int _detailCollapseThreshold = 10;
  static const int _messagePreviewLines = 3;
  static const int _maxInlineDetailLines = 30;
  static const int _maxInlineDetailChars = 2000;

  bool get _isLong {
    if (entry.message.split('\n').length > _messagePreviewLines) return true;
    final detail = entry.detail;
    if (detail != null) {
      if (detail.length > _maxInlineDetailChars) return true;
      if (detail.split('\n').length > _detailCollapseThreshold) return true;
    }
    return false;
  }

  List<String> _getPreviewLines() {
    final detail = entry.detail;
    if (detail == null) return const [];
    var lines = detail.split('\n');
    if (lines.length > _maxInlineDetailLines) {
      lines = lines.sublist(0, _maxInlineDetailLines);
    }
    return lines;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final color = switch (entry.level) {
      LogLevel.debug => theme.hintColor,
      LogLevel.info => Colors.blue,
      LogLevel.warn => Colors.orange,
      LogLevel.error => Colors.red,
    };

    final detail = entry.detail;
    final detailIsLong = _isLong;
    final previewLines = detailIsLong ? _getPreviewLines() : null;

    return RepaintBoundary(
      child: InkWell(
        onTap: detailIsLong ? () => _showDetail(context) : null,
        borderRadius: BorderRadius.circular(style.chipRadius),
        child: Padding(
          padding: EdgeInsets.symmetric(
              vertical: style.microSpacing, horizontal: style.microSpacing),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Text(entry.timeStr,
                      style: TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                          color: theme.hintColor)),
                  SizedBox(width: style.smallSpacing),
                  Container(
                    padding: EdgeInsets.symmetric(
                        horizontal: style.microSpacing,
                        vertical: style.microSpacing),
                    decoration: BoxDecoration(
                      color: color.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(3),
                    ),
                    child: Text(entry.level.label,
                        style: TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                            color: color)),
                  ),
                  if (entry.source != null) ...[
                    SizedBox(width: style.smallSpacing),
                    Text('[${entry.source}]',
                        style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w500,
                            color: theme.colorScheme.primary)),
                  ],
                  const Spacer(),
                  if (detailIsLong)
                    Padding(
                      padding: EdgeInsets.only(right: style.microSpacing),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text('展开',
                              style: TextStyle(
                                fontFamily: 'monospace',
                                fontSize: 10,
                                color: theme.colorScheme.primary,
                              )),
                          Icon(Icons.keyboard_arrow_down,
                              size: 14, color: theme.colorScheme.primary),
                        ],
                      ),
                    ),
                  InkWell(
                    onTap: () {
                      final text = detail != null
                          ? '${entry.message}\n$detail'
                          : entry.message;
                      Clipboard.setData(ClipboardData(text: text));
                      ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('已复制')));
                    },
                    child: Padding(
                      padding: EdgeInsets.all(style.microSpacing),
                      child:
                          Icon(Icons.copy, size: 14, color: theme.hintColor),
                    ),
                  ),
                ],
              ),
              Padding(
                padding: EdgeInsets.only(top: style.microSpacing),
                child: Text(
                  entry.message,
                  maxLines: _messagePreviewLines,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 12,
                      color: theme.colorScheme.onSurface),
                ),
              ),
              if (detail != null && detail.isNotEmpty)
                if (detailIsLong && previewLines != null)
                  Padding(
                    padding: EdgeInsets.only(top: style.microSpacing),
                    child: Text(
                      previewLines.join('\n'),
                      style: TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                          color: theme.hintColor),
                      maxLines: _maxInlineDetailLines,
                      overflow: TextOverflow.ellipsis,
                    ),
                  )
                else
                  Padding(
                    padding: EdgeInsets.only(top: style.microSpacing),
                    child: Text(
                      detail,
                      maxLines: 5,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                          color: theme.hintColor),
                    ),
                  ),
            ],
          ),
        ),
      ),
    );
  }

  void _showDetail(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    final fullText = entry.detail != null
        ? '${entry.message}\n${entry.detail}'
        : entry.message;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (_) => DraggableScrollableSheet(
        initialChildSize: 0.6,
        minChildSize: 0.2,
        maxChildSize: 0.95,
        expand: false,
        builder: (_, ctrl) => Padding(
          padding: EdgeInsets.all(style.pagePadding),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text('[${entry.timeStr}] ${entry.level.label}',
                      style: theme.textTheme.titleSmall),
                  if (entry.source != null)
                    Padding(
                      padding: EdgeInsets.only(left: style.smallSpacing),
                      child: Text('[${entry.source}]',
                          style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.primary)),
                    ),
                  const Spacer(),
                  IconButton(
                    icon: const Icon(Icons.copy, size: 18),
                    tooltip: '复制',
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: fullText));
                      ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('已复制')));
                    },
                  ),
                ],
              ),
              const Divider(),
              Expanded(
                child: SingleChildScrollView(
                  controller: ctrl,
                  child: SelectableText(
                    fullText,
                    style: const TextStyle(
                        fontFamily: 'monospace', fontSize: 13),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
