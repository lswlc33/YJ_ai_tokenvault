import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/theme_ext.dart';
import '../../core/webdav/webdav_service.dart';
import '../../state/lock_controller.dart';
import '../../state/providers.dart';
import '../../state/vault_controllers.dart';

class WebdavSettingsScreen extends ConsumerStatefulWidget {
  const WebdavSettingsScreen({super.key});

  @override
  ConsumerState<WebdavSettingsScreen> createState() =>
      _WebdavSettingsScreenState();
}

class _WebdavSettingsScreenState extends ConsumerState<WebdavSettingsScreen> {
  final _formKey = GlobalKey<FormState>();
  final _urlCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _dirCtrl = TextEditingController(text: '/AiTokenVault');
  final _backupDaysCtrl = TextEditingController(text: '7');

  bool _saving = false;
  bool _testing = false;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  @override
  void dispose() {
    _urlCtrl.dispose();
    _userCtrl.dispose();
    _passCtrl.dispose();
    _dirCtrl.dispose();
    _backupDaysCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadSettings() async {
    final repo = await ref.read(settingsRepositoryProvider.future);
    final settings = await repo.load();
    setState(() {
      _urlCtrl.text = settings.webdavUrl ?? '';
      _dirCtrl.text = settings.webdavDir;
      _backupDaysCtrl.text = '${settings.backupIntervalDays}';
    });
    final key = ref.read(vaultKeyProvider);
    if (key != null) {
      final creds = await repo.readWebdavCreds(key);
      if (creds != null) {
        setState(() {
          _userCtrl.text = creds.$1;
          _passCtrl.text = creds.$2;
        });
      }
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    try {
      final repo = await ref.read(settingsRepositoryProvider.future);
      final key = ref.read(vaultKeyProvider);
      if (key == null) throw Exception('未解锁');
      await repo.saveWebdav(
        url: _urlCtrl.text.trim(),
        user: _userCtrl.text.trim(),
        pass: _passCtrl.text,
        dir: _dirCtrl.text.trim(),
        vaultKey: key,
      );
      final days = int.tryParse(_backupDaysCtrl.text) ?? 7;
      await repo.setBackupIntervalDays(days);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('设置已保存')),
        );
        Navigator.of(context).pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('保存失败：$e')),
        );
      }
    } finally {
      setState(() => _saving = false);
    }
  }

  Future<void> _testConnection() async {
    setState(() => _testing = true);
    try {
      final repo = await ref.read(settingsRepositoryProvider.future);
      final vaultRepo = await ref.read(vaultRepositoryProvider.future);
      final key = ref.read(vaultKeyProvider);
      if (key == null) throw Exception('未解锁');
      await repo.saveWebdav(
        url: _urlCtrl.text.trim(),
        user: _userCtrl.text.trim(),
        pass: _passCtrl.text,
        dir: _dirCtrl.text.trim(),
        vaultKey: key,
      );
      final service = WebdavBackupService(repo, vaultRepo, key);
      final ok = await service.testConnection();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(ok ? '连接成功' : '连接失败')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('连接失败：$e')),
        );
      }
    } finally {
      setState(() => _testing = false);
    }
  }

  Future<void> _backup() async {
    setState(() => _saving = true);
    try {
      final repo = await ref.read(settingsRepositoryProvider.future);
      final vaultRepo = await ref.read(vaultRepositoryProvider.future);
      final key = ref.read(vaultKeyProvider);
      if (key == null) throw Exception('未解锁');
      final service = WebdavBackupService(repo, vaultRepo, key);
      final result = await service.backup();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
              content: Text(result.ok ? '备份成功' : (result.error ?? '备份失败'))),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('备份失败：$e')),
        );
      }
    } finally {
      setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final style = context.appStyle;

    return Scaffold(
      appBar: AppBar(title: const Text('WebDAV 备份')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _saving ? null : _save,
        icon: _saving
            ? SizedBox(
                width: style.spinnerSize,
                height: style.spinnerSize,
                child: const CircularProgressIndicator(strokeWidth: 2))
            : const Icon(Icons.save),
        label: const Text('保存'),
      ),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: EdgeInsets.all(style.pagePadding),
          children: [
            Card(
              child: Padding(
                padding: EdgeInsets.all(style.cardPadding),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('服务器设置',
                        style: Theme.of(context).textTheme.titleSmall),
                    SizedBox(height: style.itemSpacing),
                    TextFormField(
                      controller: _urlCtrl,
                      decoration: const InputDecoration(
                        labelText: 'WebDAV 服务器地址',
                        hintText: 'https://dav.example.com',
                        prefixIcon: Icon(Icons.cloud_outlined),
                      ),
                      validator: (v) => (v == null || v.trim().isEmpty)
                          ? '请输入服务器地址'
                          : null,
                    ),
                    SizedBox(height: style.sectionSpacing),
                    TextFormField(
                      controller: _userCtrl,
                      decoration: const InputDecoration(
                        labelText: '用户名',
                        prefixIcon: Icon(Icons.person_outlined),
                      ),
                      validator: (v) => (v == null || v.trim().isEmpty)
                          ? '请输入用户名'
                          : null,
                    ),
                    SizedBox(height: style.sectionSpacing),
                    TextFormField(
                      controller: _passCtrl,
                      decoration: const InputDecoration(
                        labelText: '密码',
                        prefixIcon: Icon(Icons.lock_outlined),
                      ),
                      obscureText: true,
                      validator: (v) => (v == null || v.trim().isEmpty)
                          ? '请输入密码'
                          : null,
                    ),
                    SizedBox(height: style.sectionSpacing),
                    TextFormField(
                      controller: _dirCtrl,
                      decoration: const InputDecoration(
                        labelText: '远程目录',
                        hintText: '/AiTokenVault',
                        prefixIcon: Icon(Icons.folder_outlined),
                      ),
                    ),
                    SizedBox(height: style.sectionSpacing),
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: _testing ? null : _testConnection,
                            icon: _testing
                                ? SizedBox(
                                    width: style.spinnerSize,
                                    height: style.spinnerSize,
                                    child: const CircularProgressIndicator(
                                        strokeWidth: 2))
                                : const Icon(Icons.wifi_find),
                            label: const Text('测试连接'),
                          ),
                        ),
                        SizedBox(width: style.itemSpacing),
                        Expanded(
                          child: FilledButton.icon(
                            onPressed: _saving ? null : _backup,
                            icon: _saving
                                ? SizedBox(
                                    width: style.spinnerSize,
                                    height: style.spinnerSize,
                                    child: const CircularProgressIndicator(
                                        strokeWidth: 2))
                                : const Icon(Icons.backup_outlined),
                            label: const Text('立即备份'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            SizedBox(height: style.sectionSpacing),
            Card(
              child: Padding(
                padding: EdgeInsets.all(style.cardPadding),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('自动备份',
                        style: Theme.of(context).textTheme.titleSmall),
                    SizedBox(height: style.itemSpacing),
                    TextFormField(
                      controller: _backupDaysCtrl,
                      decoration: const InputDecoration(
                        labelText: '备份周期（天）',
                        hintText: '7',
                        prefixIcon: Icon(Icons.calendar_today_outlined),
                      ),
                      keyboardType: TextInputType.number,
                    ),
                  ],
                ),
              ),
            ),
            SizedBox(height: style.sectionSpacing),
            Card(
              child: ListTile(
                leading: const Icon(Icons.history),
                title: const Text('历史备份'),
                subtitle: const Text('查看、恢复或删除历史备份'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(
                      builder: (_) => const _BackupHistoryScreen()),
                ),
              ),
            ),
            const SizedBox(height: 88),
          ],
        ),
      ),
    );
  }
}

class _BackupHistoryScreen extends ConsumerStatefulWidget {
  const _BackupHistoryScreen();

  @override
  ConsumerState<_BackupHistoryScreen> createState() =>
      _BackupHistoryScreenState();
}

class _BackupHistoryScreenState extends ConsumerState<_BackupHistoryScreen> {
  List<WebdavBackupEntry> _backups = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final repo = await ref.read(settingsRepositoryProvider.future);
      final vaultRepo = await ref.read(vaultRepositoryProvider.future);
      final key = ref.read(vaultKeyProvider);
      if (key == null) return;
      final service = WebdavBackupService(repo, vaultRepo, key);
      final backups = await service.listBackups();
      setState(() => _backups = backups);
    } catch (_) {} finally {
      setState(() => _loading = false);
    }
  }

  Future<void> _restore(WebdavBackupEntry entry) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('恢复备份？'),
        content: Text('将从 "${entry.name}" 恢复数据，当前数据将被覆盖。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('恢复',
                  style: TextStyle(color: Colors.red))),
        ],
      ),
    );
    if (confirm != true) return;

    try {
      final repo = await ref.read(settingsRepositoryProvider.future);
      final vaultRepo = await ref.read(vaultRepositoryProvider.future);
      final key = ref.read(vaultKeyProvider);
      if (key == null) return;
      final service = WebdavBackupService(repo, vaultRepo, key);
      final result = await service.restore(entry.path);
      if (mounted) {
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
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('恢复失败：$e')),
        );
      }
    }
  }

  Future<void> _delete(WebdavBackupEntry entry) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('删除备份？'),
        content: Text('将删除 "${entry.name}"，此操作不可恢复。',
            style: const TextStyle(color: Colors.red)),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('删除',
                  style: TextStyle(color: Colors.red))),
        ],
      ),
    );
    if (confirm != true) return;

    try {
      final repo = await ref.read(settingsRepositoryProvider.future);
      final vaultRepo = await ref.read(vaultRepositoryProvider.future);
      final key = ref.read(vaultKeyProvider);
      if (key == null) return;
      final service = WebdavBackupService(repo, vaultRepo, key);
      await service.deleteBackup(entry.path);
      _load();
    } catch (_) {}
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  @override
  Widget build(BuildContext context) {
    final style = context.appStyle;
    return Scaffold(
      appBar: AppBar(
        title: const Text('历史备份'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _load,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _backups.isEmpty
              ? const Center(child: Text('暂无备份'))
              : ListView.builder(
                  padding: EdgeInsets.all(style.pagePadding),
                  itemCount: _backups.length,
                  itemBuilder: (_, i) {
                    final b = _backups[i];
                    return Card(
                      margin: EdgeInsets.only(bottom: style.itemSpacing),
                      child: ListTile(
                        leading: const Icon(Icons.description_outlined),
                        title: Text(b.name),
                        subtitle: Text(
                          '${_formatSize(b.size)}'
                          '${b.lastModified != null ? ' · ${b.lastModified}' : ''}',
                        ),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              icon: const Icon(Icons.restore_outlined),
                              tooltip: '恢复',
                              onPressed: () => _restore(b),
                            ),
                            IconButton(
                              icon: const Icon(Icons.delete_outline,
                                  color: Colors.red),
                              tooltip: '删除',
                              onPressed: () => _delete(b),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
    );
  }
}
