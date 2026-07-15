import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart' hide Provider;

import '../../core/probe/balance_presets.dart';
import '../../core/theme/responsive.dart';
import '../../core/theme/theme_ext.dart';
import '../../models/api_key.dart';
import '../../models/category.dart';
import '../../models/key_status.dart';
import '../../models/provider.dart';
import '../../models/provider_with_keys.dart';
import '../../state/vault_controllers.dart';

class EditorScreen extends ConsumerStatefulWidget {
  const EditorScreen({super.key, this.existing});

  final ProviderWithKeys? existing;

  @override
  ConsumerState<EditorScreen> createState() => _EditorScreenState();
}

class _KeyDraft {
  _KeyDraft({
    int? id,
    String label = '',
    String key = '',
    bool keyCheck = true,
    bool modelList = true,
    bool balanceCheck = false,
    String balanceEndpoint = '/user/balance',
    String balanceValuePath = 'total_credits',
    String balanceUsagePath = 'total_usage',
    String modelsEndpoint = '/models',
  })  : id = id ?? 0,
        labelCtrl = TextEditingController(text: label),
        keyCtrl = TextEditingController(text: key),
        balanceEndpointCtrl = TextEditingController(text: balanceEndpoint),
        balanceValuePathCtrl = TextEditingController(text: balanceValuePath),
        balanceUsagePathCtrl = TextEditingController(text: balanceUsagePath),
        modelsEndpointCtrl = TextEditingController(text: modelsEndpoint),
        keyCheckEnabled = keyCheck,
        modelListEnabled = modelList,
        balanceCheckNotifier = ValueNotifier(balanceCheck);

  final int id;
  final TextEditingController labelCtrl;
  final TextEditingController keyCtrl;
  final TextEditingController balanceEndpointCtrl;
  final TextEditingController balanceValuePathCtrl;
  final TextEditingController balanceUsagePathCtrl;
  final TextEditingController modelsEndpointCtrl;
  bool keyCheckEnabled;
  bool modelListEnabled;
  final ValueNotifier<bool> balanceCheckNotifier;
  VoidCallback? _balanceToggleListener;

  bool get balanceCheckEnabled => balanceCheckNotifier.value;
  set balanceCheckEnabled(bool v) => balanceCheckNotifier.value = v;

  void dispose() {
    _balanceToggleListener = null;
    balanceCheckNotifier.dispose();
    labelCtrl.dispose();
    keyCtrl.dispose();
    balanceEndpointCtrl.dispose();
    balanceValuePathCtrl.dispose();
    balanceUsagePathCtrl.dispose();
    modelsEndpointCtrl.dispose();
  }
}

class _EditorScreenState extends ConsumerState<EditorScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameCtrl;
  late final TextEditingController _urlCtrl;
  late final TextEditingController _apiPathCtrl;
  late final TextEditingController _noteCtrl;

  int _categoryId = Category.uncategorizedId;
  late String _probeType;
  final List<_KeyDraft> _keys = [];
  final List<int> _deletedKeyIds = [];
  bool _saving = false;

  bool get _isEdit => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final p = widget.existing?.provider;
    _nameCtrl = TextEditingController(text: p?.name ?? '');
    _urlCtrl = TextEditingController(text: p?.baseUrl ?? '');
    _apiPathCtrl = TextEditingController(text: p?.apiPath ?? '');
    _noteCtrl = TextEditingController(text: p?.note ?? '');
    _categoryId = p?.categoryId ?? Category.uncategorizedId;
    _probeType = p?.probeType ?? 'builtin';

    if (widget.existing == null) {
      for (final d in _keys) {
        d._balanceToggleListener = () => _onBalanceToggled(d);
        d.balanceCheckNotifier.addListener(d._balanceToggleListener!);
      }
    }

    if (widget.existing != null) {
      for (final k in widget.existing!.keys) {
        _keys.add(_KeyDraft(
          id: k.id,
          label: k.label,
          key: k.apiKey ?? '',
          keyCheck: k.keyCheckEnabled,
          modelList: k.modelListEnabled,
          balanceCheck: k.balanceCheckEnabled,
          balanceEndpoint: k.balanceEndpoint,
          balanceValuePath: k.balanceValuePath,
          balanceUsagePath: k.balanceUsagePath,
          modelsEndpoint: k.modelsEndpoint,
        ));
      }
    }
    if (_keys.isEmpty) _keys.add(_KeyDraft(label: '主号'));
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _urlCtrl.dispose();
    _apiPathCtrl.dispose();
    _noteCtrl.dispose();
    for (final k in _keys) {
      k.dispose();
    }
    super.dispose();
  }

  void _onBalanceToggled(_KeyDraft d) {
    if (!d.balanceCheckEnabled) return;
    final url = _urlCtrl.text.trim();
    if (url.isEmpty) return;
    final preset = detectBalancePreset(url);
    if (preset == null) return;
    d.balanceEndpointCtrl.text = preset.balanceEndpoint;
    d.balanceValuePathCtrl.text = preset.balanceValuePath;
    d.balanceUsagePathCtrl.text = preset.balanceUsagePath;
    if (preset.modelsEndpoint != null) {
      d.modelsEndpointCtrl.text = preset.modelsEndpoint!;
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('已自动识别 ${preset.label}，已填充余额查询配置'),
          duration: const Duration(seconds: 2),
        ),
      );
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    try {
      final repo = await ref.read(vaultRepositoryProvider.future);
      final now = DateTime.now().millisecondsSinceEpoch;
      final existing = widget.existing?.provider;
      final provider = Provider(
        id: existing?.id ?? 0,
        name: _nameCtrl.text.trim(),
        baseUrl: _urlCtrl.text.trim().isEmpty ? null : _urlCtrl.text.trim(),
        apiPath: _apiPathCtrl.text.trim(),
        categoryId: _categoryId,
        note: _noteCtrl.text.trim().isEmpty ? null : _noteCtrl.text.trim(),
        color: existing?.color,
        probeType: _probeType,
        sortOrder: existing?.sortOrder ?? 0,
        createdAt: existing?.createdAt ?? now,
        updatedAt: now,
      );

      final keys = <ApiKey>[];
      for (var i = 0; i < _keys.length; i++) {
        final d = _keys[i];
        if (d.keyCtrl.text.trim().isEmpty && d.id == 0) continue;
        keys.add(ApiKey(
          id: d.id,
          providerId: provider.id,
          label: d.labelCtrl.text.trim().isEmpty
              ? 'Key ${i + 1}'
              : d.labelCtrl.text.trim(),
          apiKey: d.keyCtrl.text.trim(),
          status: KeyStatus.unknown,
          sortOrder: i,
          keyCheckEnabled: d.keyCheckEnabled,
          modelListEnabled: d.modelListEnabled,
          balanceCheckEnabled: d.balanceCheckEnabled,
          balanceEndpoint: d.balanceEndpointCtrl.text.trim().isEmpty
              ? '/user/balance'
              : d.balanceEndpointCtrl.text.trim(),
          balanceValuePath: d.balanceValuePathCtrl.text.trim().isEmpty
              ? 'total_credits'
              : d.balanceValuePathCtrl.text.trim(),
          balanceUsagePath: d.balanceUsagePathCtrl.text.trim().isEmpty
              ? 'total_usage'
              : d.balanceUsagePathCtrl.text.trim(),
          modelsEndpoint: d.modelsEndpointCtrl.text.trim().isEmpty
              ? '/models'
              : d.modelsEndpointCtrl.text.trim(),
        ));
      }

      await repo.saveProvider(provider, keys, deletedKeyIds: _deletedKeyIds);
      ref.invalidate(dashboardProvider);
      ref.invalidate(categoriesProvider);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('保存失败：$e')));
        setState(() => _saving = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final r = Responsive.of(context);
    final style = context.appStyle;

    return Scaffold(
      appBar: AppBar(
        title: Text(_isEdit ? '编辑厂家' : '添加厂家'),
      ),
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
      body: Center(
        child: ConstrainedBox(
          constraints:
              BoxConstraints(maxWidth: r.isPhone ? double.infinity : 640),
          child: Form(
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
                        TextFormField(
                          controller: _nameCtrl,
                          decoration: const InputDecoration(
                            labelText: '厂家名称',
                            prefixIcon: Icon(Icons.business_outlined),
                          ),
                          validator: (v) =>
                              (v == null || v.trim().isEmpty) ? '请输入厂家名' : null,
                        ),
                        SizedBox(height: style.sectionSpacing),
                        TextFormField(
                          controller: _noteCtrl,
                          decoration: const InputDecoration(
                            labelText: '厂家备注',
                            prefixIcon: Icon(Icons.notes_outlined),
                          ),
                          maxLines: 2,
                        ),
                        SizedBox(height: style.sectionSpacing),
                        TextFormField(
                          controller: _urlCtrl,
                          decoration: const InputDecoration(
                            labelText: 'Base URL',
                            hintText: 'https://api.openai.com',
                            prefixIcon: Icon(Icons.link),
                          ),
                        ),
                        SizedBox(height: style.sectionSpacing),
                        TextFormField(
                          controller: _apiPathCtrl,
                          decoration: const InputDecoration(
                            labelText: 'API 后缀',
                            hintText: 'v1',
                            prefixIcon: Icon(Icons.route),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                SizedBox(height: style.largeSpacing),
                Row(
                  children: [
                    Text('API Keys',
                        style: Theme.of(context).textTheme.titleMedium),
                    const Spacer(),
                    TextButton.icon(
                      onPressed: () =>
                          setState(() => _keys.add(_KeyDraft(label: ''))),
                      icon: const Icon(Icons.add),
                      label: const Text('新增 Key'),
                    ),
                  ],
                ),
                SizedBox(height: style.itemSpacing),
                for (var i = 0; i < _keys.length; i++) _keyEditor(i),
                const SizedBox(height: 88),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _keyEditor(int i) {
    final d = _keys[i];
    final style = context.appStyle;
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
                  child: TextFormField(
                    controller: d.labelCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Key 备注',
                      isDense: true,
                      prefixIcon: Icon(Icons.label_outline),
                    ),
                  ),
                ),
                IconButton(
                  tooltip: '删除该 Key',
                  onPressed: _keys.length == 1
                      ? null
                      : () => setState(() {
                            if (d.id != 0) _deletedKeyIds.add(d.id);
                            _keys.removeAt(i).dispose();
                          }),
                  icon: const Icon(Icons.delete_outline),
                ),
              ],
            ),
            SizedBox(height: style.itemSpacing),
            TextFormField(
              controller: d.keyCtrl,
              decoration: const InputDecoration(
                labelText: 'API Key',
                hintText: 'sk-...',
                isDense: true,
                prefixIcon: Icon(Icons.vpn_key_outlined),
              ),
            ),
            SizedBox(height: style.smallSpacing),
            Text('探测设置',
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: Theme.of(context).hintColor)),
            _checkToggle('可用性检测', d.keyCheckEnabled,
                (v) => setState(() => d.keyCheckEnabled = v)),
            _checkToggle('模型列表检测', d.modelListEnabled,
                (v) => setState(() => d.modelListEnabled = v)),
            ValueListenableBuilder<bool>(
              valueListenable: d.balanceCheckNotifier,
              builder: (_, value, __) => _checkToggle('余额检测', value, (v) {
                d.balanceCheckEnabled = v;
                _onBalanceToggled(d);
              }),
            ),
            ValueListenableBuilder<bool>(
              valueListenable: d.balanceCheckNotifier,
              builder: (_, value, __) {
                if (!value) return const SizedBox.shrink();
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(height: style.smallSpacing),
                    TextFormField(
                      controller: d.balanceEndpointCtrl,
                      decoration: const InputDecoration(
                        labelText: '余额 API 路径',
                        hintText: '/user/balance 或 /credits (不包含 API 后缀)',
                        isDense: true,
                      ),
                    ),
                    SizedBox(height: style.itemSpacing),
                    TextFormField(
                      controller: d.balanceValuePathCtrl,
                      decoration: const InputDecoration(
                        labelText: '余额结果 JSON 路径',
                        hintText: 'data.total_credits - data.total_usage',
                        isDense: true,
                      ),
                    ),
                    SizedBox(height: style.microSpacing),
                    Text('支持运算表达式，如 data.total_credits - data.total_usage',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Theme.of(context).hintColor, fontSize: 11)),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _checkToggle(String label, bool value, ValueChanged<bool> onChanged) {
    return Row(
      children: [
        Expanded(
            child: Text(label, style: Theme.of(context).textTheme.bodyMedium)),
        Transform.scale(
          scale: 0.8,
          child: Switch(value: value, onChanged: onChanged),
        ),
      ],
    );
  }
}
