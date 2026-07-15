import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/widget_previews.dart';

import '../../core/backup/local_backup_service.dart';
import '../../core/log/log_service.dart';
import '../../core/theme/theme_controller.dart';
import '../../core/theme/theme_ext.dart';
import '../../models/app_settings.dart';
import '../../state/lock_controller.dart';
import '../../state/providers.dart';
import '../../state/vault_controllers.dart';
import 'category_manager_screen.dart';
import 'webdav_settings_screen.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = ref.watch(themeControllerProvider);
    final ctrl = ref.read(themeControllerProvider.notifier);

    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListView(
        padding: EdgeInsets.zero,
        children: [
          _section('外观'),
          Card(
            margin: EdgeInsets.symmetric(
                horizontal: context.appStyle.pagePadding,
                vertical: context.appStyle.microSpacing),
            child: Column(children: [
              ListTile(
                leading: const Icon(Icons.palette_outlined),
                title: const Text('主题风格'),
                trailing: DropdownButton<AppThemeStyle>(
                  value: theme.style,
                  onChanged: (s) => s == null ? null : ctrl.setStyle(s),
                  items: const [
                    DropdownMenuItem(
                        value: AppThemeStyle.material, child: Text('Material')),
                    DropdownMenuItem(
                        value: AppThemeStyle.miuix, child: Text('MIUIx')),
                  ],
                ),
              ),
              ListTile(
                leading: const Icon(Icons.brightness_6_outlined),
                title: const Text('颜色模式'),
                trailing: DropdownButton<AppDarkMode>(
                  value: theme.darkMode,
                  onChanged: (m) => m == null ? null : ctrl.setDarkMode(m),
                  items: const [
                    DropdownMenuItem(
                        value: AppDarkMode.system, child: Text('跟随系统')),
                    DropdownMenuItem(
                        value: AppDarkMode.light, child: Text('浅色')),
                    DropdownMenuItem(
                        value: AppDarkMode.dark, child: Text('深色')),
                  ],
                ),
              ),
            ]),
          ),
          _section('安全'),
          Card(
            margin: EdgeInsets.symmetric(
                horizontal: context.appStyle.pagePadding,
                vertical: context.appStyle.microSpacing),
            child: Column(children: [
              SwitchListTile(
                secondary: const Icon(Icons.fingerprint),
                title: const Text('生物识别认证'),
                subtitle: const Text('使用指纹或面容解锁'),
                value: ref.watch(lockControllerProvider).biometricAvailable,
                onChanged: (v) async {
                  if (v) {
                    await ref
                        .read(lockControllerProvider.notifier)
                        .enableBiometric();
                  } else {
                    await ref
                        .read(lockControllerProvider.notifier)
                        .clearBiometricData();
                  }
                },
              ),
              const _AutoLockTile(),
              ListTile(
                leading: const Icon(Icons.lock_reset_outlined),
                title: const Text('修改 PIN'),
                subtitle: const Text('将重新加密全部敏感字段'),
                onTap: () {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('修改 PIN 功能待接入')),
                  );
                },
              ),
              ListTile(
                leading: const Icon(Icons.lock_outline),
                title: const Text('立即锁定'),
                onTap: () => ref.read(lockControllerProvider.notifier).lock(),
              ),
            ]),
          ),
          _section('探测'),
          Card(
            margin: EdgeInsets.symmetric(
                horizontal: context.appStyle.pagePadding,
                vertical: context.appStyle.microSpacing),
            child: Column(children: [
              const _AutoProbeOnUnlockTile(),
              const _AutoProbeIntervalTile(),
              const _ModelMetadataTile(),
              ListTile(
                leading: const Icon(Icons.refresh),
                title: const Text('立即探测'),
                onTap: () {
                  ref.read(probeControllerProvider.notifier).probeAll();
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('开始探测...')),
                  );
                },
              ),
            ]),
          ),
          _section('数据'),
          Card(
            margin: EdgeInsets.symmetric(
                horizontal: context.appStyle.pagePadding,
                vertical: context.appStyle.microSpacing),
            child: Column(children: [
              ListTile(
                leading: const Icon(Icons.folder_outlined),
                title: const Text('分类管理'),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(
                      builder: (_) => const CategoryManagerScreen()),
                ),
              ),
              ListTile(
                leading: const Icon(Icons.cleaning_services_outlined),
                title: const Text('清理缓存'),
                subtitle: const Text('清除缓存的余额、模型列表等数据'),
                onTap: () async {
                  final repo = await ref.read(vaultRepositoryProvider.future);
                  await repo.clearProbeCache();
                  ref.invalidate(dashboardProvider);
                  ref.invalidate(summaryProvider);
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('缓存已清理')),
                    );
                  }
                },
              ),
              ListTile(
                leading: const Icon(Icons.delete_sweep_outlined),
                title: const Text('清理日志'),
                subtitle: const Text('清除所有操作日志'),
                onTap: () {
                  log.clear();
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('日志已清理')),
                  );
                },
              ),
            ]),
          ),
          _section('同步与备份'),
          Card(
            margin: EdgeInsets.symmetric(
                horizontal: context.appStyle.pagePadding,
                vertical: context.appStyle.microSpacing),
            child: Column(children: [
              ListTile(
                leading: const Icon(Icons.cloud_outlined),
                title: const Text('WebDAV 备份'),
                subtitle: const Text('备份和恢复数据到 WebDAV 服务器'),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(
                      builder: (_) => const WebdavSettingsScreen()),
                ),
              ),
              const Divider(height: 1),
              const _LocalExportTile(),
              const Divider(height: 1),
              const _LocalImportTile(),
            ]),
          ),
          _section('关于'),
          Card(
            margin: EdgeInsets.symmetric(
                horizontal: context.appStyle.pagePadding,
                vertical: context.appStyle.microSpacing),
            child: Column(children: [
              ListTile(
                leading: const Icon(Icons.info_outline),
                title: const Text('关于元记'),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const _AboutScreen()),
                ),
              ),
            ]),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  Widget _section(String title) {
    return Builder(builder: (context) {
      final theme = Theme.of(context);
      final style = context.appStyle;
      return Padding(
        padding: EdgeInsets.fromLTRB(
            style.pagePadding + 4, style.pagePadding, style.pagePadding, 0),
        child: Text(title,
            style: theme.textTheme.labelLarge
                ?.copyWith(color: style.sectionHeaderColor)),
      );
    });
  }
}

class _AutoLockTile extends ConsumerStatefulWidget {
  const _AutoLockTile();
  @override
  ConsumerState<_AutoLockTile> createState() => _AutoLockTileState();
}

class _AutoLockTileState extends ConsumerState<_AutoLockTile> {
  Future<AppSettings>? _future;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??=
        ref.read(settingsRepositoryProvider.future).then((r) => r.load());
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(settingsRepositoryProvider);
    return FutureBuilder<AppSettings>(
      future: _future,
      builder: (context, snap) {
        final raw = snap.data?.autoLockSeconds ?? 60;
        const validValues = {0, 30, 60, 300};
        final seconds = validValues.contains(raw) ? raw : 60;
        return ListTile(
          leading: const Icon(Icons.timer_outlined),
          title: const Text('自动锁定'),
          subtitle: Text(seconds == 0 ? '已关闭' : '$seconds 秒后锁定'),
          trailing: DropdownButton<int>(
            value: seconds,
            onChanged: (v) async {
              if (v == null) return;
              final repo = await ref.read(settingsRepositoryProvider.future);
              await repo.setAutoLockSeconds(v);
              _future = ref
                  .read(settingsRepositoryProvider.future)
                  .then((r) => r.load());
              setState(() {});
            },
            items: const [
              DropdownMenuItem(value: 0, child: Text('关闭')),
              DropdownMenuItem(value: 30, child: Text('30 秒')),
              DropdownMenuItem(value: 60, child: Text('60 秒')),
              DropdownMenuItem(value: 300, child: Text('5 分钟')),
            ],
          ),
        );
      },
    );
  }
}

class _AutoProbeOnUnlockTile extends ConsumerStatefulWidget {
  const _AutoProbeOnUnlockTile();
  @override
  ConsumerState<_AutoProbeOnUnlockTile> createState() =>
      _AutoProbeOnUnlockTileState();
}

class _AutoProbeOnUnlockTileState
    extends ConsumerState<_AutoProbeOnUnlockTile> {
  Future<AppSettings>? _future;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??=
        ref.read(settingsRepositoryProvider.future).then((r) => r.load());
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(settingsRepositoryProvider);
    return FutureBuilder<AppSettings>(
      future: _future,
      builder: (context, snap) {
        final enabled = snap.data?.autoProbeOnUnlock ?? false;
        return SwitchListTile(
          secondary: const Icon(Icons.lock_open_outlined),
          title: const Text('解锁自动探测'),
          value: enabled,
          onChanged: (v) async {
            final repo = await ref.read(settingsRepositoryProvider.future);
            await repo.setAutoProbeOnUnlock(v);
            _future = ref
                .read(settingsRepositoryProvider.future)
                .then((r) => r.load());
            setState(() {});
          },
        );
      },
    );
  }
}

class _AutoProbeIntervalTile extends ConsumerStatefulWidget {
  const _AutoProbeIntervalTile();
  @override
  ConsumerState<_AutoProbeIntervalTile> createState() =>
      _AutoProbeIntervalTileState();
}

class _AutoProbeIntervalTileState
    extends ConsumerState<_AutoProbeIntervalTile> {
  Future<AppSettings>? _future;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??=
        ref.read(settingsRepositoryProvider.future).then((r) => r.load());
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(settingsRepositoryProvider);
    return FutureBuilder<AppSettings>(
      future: _future,
      builder: (context, snap) {
        final minutes = snap.data?.autoProbeIntervalMinutes ?? 0;
        String label;
        switch (minutes) {
          case 0:
            label = '关闭';
          case 60:
            label = '1 小时';
          case 360:
            label = '6 小时';
          case 1440:
            label = '1 天';
          default:
            label = '$minutes 分钟';
        }
        return ListTile(
          leading: const Icon(Icons.schedule_outlined),
          title: const Text('定时自动探测'),
          subtitle: Text(label),
          trailing: DropdownButton<int>(
            value: minutes,
            onChanged: (v) async {
              if (v == null) return;
              final repo = await ref.read(settingsRepositoryProvider.future);
              await repo.setAutoProbeIntervalMinutes(v);
              _future = ref
                  .read(settingsRepositoryProvider.future)
                  .then((r) => r.load());
              setState(() {});
            },
            items: const [
              DropdownMenuItem(value: 0, child: Text('关闭')),
              DropdownMenuItem(value: 60, child: Text('1 小时')),
              DropdownMenuItem(value: 360, child: Text('6 小时')),
              DropdownMenuItem(value: 1440, child: Text('1 天')),
            ],
          ),
        );
      },
    );
  }
}

class _ModelMetadataTile extends ConsumerStatefulWidget {
  const _ModelMetadataTile();
  @override
  ConsumerState<_ModelMetadataTile> createState() => _ModelMetadataTileState();
}

class _ModelMetadataTileState extends ConsumerState<_ModelMetadataTile> {
  Future<AppSettings>? _future;
  bool _updating = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??=
        ref.read(settingsRepositoryProvider.future).then((r) => r.load());
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(settingsRepositoryProvider);
    return FutureBuilder<AppSettings>(
      future: _future,
      builder: (context, snap) {
        final url =
            snap.data?.modelMetadataUrl ?? 'https://models.dev/models.json';
        final lastAt = snap.data?.lastMetadataFetchAt;
        final lastStr = lastAt != null
            ? '${DateTime.fromMillisecondsSinceEpoch(lastAt).month}-${DateTime.fromMillisecondsSinceEpoch(lastAt).day} ${DateTime.fromMillisecondsSinceEpoch(lastAt).hour}:${DateTime.fromMillisecondsSinceEpoch(lastAt).minute.toString().padLeft(2, '0')}'
            : '从未更新';
        return Column(
          children: [
            ListTile(
              leading: const Icon(Icons.dataset_outlined),
              title: const Text('模型元数据接口'),
              subtitle:
                  Text(lastStr, style: Theme.of(context).textTheme.bodySmall),
            ),
            Padding(
              padding: EdgeInsets.fromLTRB(context.appStyle.pagePadding, 0,
                  context.appStyle.pagePadding, 8),
              child: Row(
                children: [
                  Expanded(
                    child: TextFormField(
                      initialValue: url,
                      decoration: InputDecoration(
                        hintText: 'https://models.dev/models.json',
                        isDense: true,
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(16),
                          borderSide: BorderSide(
                              color: context.appStyle.sectionHeaderColor),
                        ),
                        enabledBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(16),
                          borderSide: BorderSide(
                              color: context.appStyle.sectionHeaderColor),
                        ),
                        focusedBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(16),
                          borderSide: BorderSide(
                              color: context.appStyle.sectionHeaderColor,
                              width: 1.5),
                        ),
                      ),
                      onFieldSubmitted: (v) async {
                        final repo =
                            await ref.read(settingsRepositoryProvider.future);
                        await repo.setModelMetadataUrl(v.trim());
                        _future = ref
                            .read(settingsRepositoryProvider.future)
                            .then((r) => r.load());
                        setState(() {});
                      },
                    ),
                  ),
                  SizedBox(width: context.appStyle.smallSpacing),
                  IconButton.filled(
                    style: IconButton.styleFrom(
                      backgroundColor: context.appStyle.sectionHeaderColor,
                      foregroundColor: Colors.white,
                    ),
                    onPressed: _updating
                        ? null
                        : () async {
                            setState(() => _updating = true);
                            try {
                              final svc = await ref
                                  .read(modelMetadataServiceProvider.future);
                              await svc.refresh();
                              _future = ref
                                  .read(settingsRepositoryProvider.future)
                                  .then((r) => r.load());
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(content: Text('元数据已更新')),
                                );
                              }
                            } catch (e) {
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(content: Text('更新失败: $e')),
                                );
                              }
                            } finally {
                              if (mounted) setState(() => _updating = false);
                            }
                          },
                    icon: _updating
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.cloud_download_outlined, size: 20),
                    tooltip: '立即更新元数据',
                  ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

class _LocalExportTile extends ConsumerStatefulWidget {
  const _LocalExportTile();
  @override
  ConsumerState<_LocalExportTile> createState() => _LocalExportTileState();
}

class _LocalExportTileState extends ConsumerState<_LocalExportTile> {
  bool _exporting = false;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.file_download_outlined),
      title: const Text('导出到本地'),
      subtitle: const Text('将数据备份为 JSON 文件'),
      trailing: _exporting
          ? const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2))
          : null,
      onTap: _exporting ? null : _export,
    );
  }

  Future<void> _export() async {
    setState(() => _exporting = true);
    try {
      final vaultRepo = await ref.read(vaultRepositoryProvider.future);
      final service = LocalBackupService(vaultRepo);
      final json = await service.exportData();

      final now = DateTime.now();
      final filename =
          '元记备份_${now.year}${now.month.toString().padLeft(2, '0')}${now.day.toString().padLeft(2, '0')}_${now.hour.toString().padLeft(2, '0')}${now.minute.toString().padLeft(2, '0')}.json';

      final result = await FilePicker.platform.saveFile(
        fileName: filename,
        type: FileType.custom,
        allowedExtensions: ['json'],
        dialogTitle: '保存备份文件',
      );

      if (result != null) {
        await File(result).writeAsString(json);
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('已导出到 $result')),
          );
        }
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('导出失败：$e')),
        );
      }
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }
}

class _LocalImportTile extends ConsumerStatefulWidget {
  const _LocalImportTile();
  @override
  ConsumerState<_LocalImportTile> createState() => _LocalImportTileState();
}

class _LocalImportTileState extends ConsumerState<_LocalImportTile> {
  bool _importing = false;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.file_upload_outlined),
      title: const Text('从本地导入'),
      subtitle: const Text('从 JSON 文件恢复数据'),
      trailing: _importing
          ? const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2))
          : null,
      onTap: _importing ? null : _import,
    );
  }

  Future<void> _import() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['json'],
      dialogTitle: '选择备份文件',
    );
    if (result == null || result.files.isEmpty) return;

    final file = result.files.first;
    if (file.path == null) return;

    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('恢复备份？'),
        content: const Text('将从所选文件恢复数据，当前数据将被覆盖。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('恢复', style: TextStyle(color: Colors.red))),
        ],
      ),
    );
    if (confirm != true) return;

    setState(() => _importing = true);
    try {
      final json = await File(file.path!).readAsString();
      final vaultRepo = await ref.read(vaultRepositoryProvider.future);
      final service = LocalBackupService(vaultRepo);
      final result = await service.importFromJson(json);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
              content: Text(result.ok ? '恢复成功' : (result.error ?? '恢复失败'))),
        );
        if (result.ok) {
          ref.invalidate(dashboardProvider);
          ref.invalidate(categoriesProvider);
        }
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('恢复失败：$e')),
        );
      }
    } finally {
      if (mounted) setState(() => _importing = false);
    }
  }
}

class _AboutScreen extends StatelessWidget {
  const _AboutScreen();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = context.appStyle;
    return Scaffold(
      appBar: AppBar(title: const Text('关于')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 400),
          child: Padding(
            padding: EdgeInsets.all(style.largeSpacing),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.shield_outlined,
                    size: 72, color: theme.colorScheme.primary),
                SizedBox(height: style.sectionSpacing),
                Text('元记',
                    style: theme.textTheme.headlineSmall
                        ?.copyWith(fontWeight: FontWeight.bold)),
                SizedBox(height: style.microSpacing),
                Text('v0.1.0',
                    style: theme.textTheme.bodyMedium
                        ?.copyWith(color: theme.hintColor)),
                SizedBox(height: style.largeSpacing),
                Text(
                  '词元 API Key 记录本\n纯本地加密存储，集中管理端点与余额',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium
                      ?.copyWith(color: theme.hintColor),
                ),
                SizedBox(height: style.largeSpacing),
                Card(
                  child: Padding(
                    padding: EdgeInsets.all(style.cardPadding),
                    child: Column(children: [
                      _infoRow('平台', 'Flutter / Dart'),
                      _infoRow('加密', 'AES-256-GCM + PBKDF2'),
                      _infoRow('存储', 'SQLite (本地加密)'),
                      _infoRow('备份', 'WebDAV'),
                    ]),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Builder(builder: (context) {
      final theme = Theme.of(context);
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          SizedBox(
              width: 60,
              child: Text(label,
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.hintColor))),
          Expanded(child: Text(value, style: theme.textTheme.bodySmall)),
        ]),
      );
    });
  }
}

@Preview(name: 'About Screen')
Widget previewAboutScreen() {
  return const _AboutScreen();
}
