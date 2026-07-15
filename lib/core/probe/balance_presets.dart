class BalancePreset {
  const BalancePreset({
    required this.label,
    required this.balanceEndpoint,
    required this.balanceValuePath,
    required this.balanceUsagePath,
    this.modelsEndpoint,
  });

  final String label;
  final String balanceEndpoint;
  final String balanceValuePath;
  final String balanceUsagePath;
  final String? modelsEndpoint;
}

const Map<String, BalancePreset> kBalancePresets = {
  'deepseek': BalancePreset(
    label: 'DeepSeek',
    balanceEndpoint: '/user/balance',
    balanceValuePath: 'balance_infos[0].total_balance',
    balanceUsagePath: 'balance_infos[0].total_granted',
  ),
  'siliconflow': BalancePreset(
    label: 'SiliconFlow',
    balanceEndpoint: '/user/info',
    balanceValuePath: 'data.totalBalance',
    balanceUsagePath: 'data.totalBalance',
  ),
  'openrouter': BalancePreset(
    label: 'OpenRouter',
    balanceEndpoint: '/credits',
    balanceValuePath: 'data.total_credits',
    balanceUsagePath: 'data.total_usage',
  ),
  'moonshot': BalancePreset(
    label: 'Moonshot / Kimi',
    balanceEndpoint: '/users/me/balance',
    balanceValuePath: 'data.available_balance',
    balanceUsagePath: 'data.available_balance',
  ),
  'aihubmix': BalancePreset(
    label: 'AIhubmix',
    balanceEndpoint: '/user/balance',
    balanceValuePath: 'balance_infos[0].total_balance',
    balanceUsagePath: 'balance_infos[0].total_granted',
  ),
  'zhipu': BalancePreset(
    label: '智谱 / GLM',
    balanceEndpoint: '/api/paas/v4/users/me',
    balanceValuePath: 'data.resources',
    balanceUsagePath: 'data.resources',
  ),
  'minimax': BalancePreset(
    label: 'MiniMax',
    balanceEndpoint: '/user/balance',
    balanceValuePath: 'total_balance',
    balanceUsagePath: 'total_balance',
  ),
  'volcengine': BalancePreset(
    label: '火山引擎 / 豆包',
    balanceEndpoint: '/api/v1/account/balance',
    balanceValuePath: 'data.balance',
    balanceUsagePath: 'data.balance',
  ),
};

BalancePreset? detectBalancePreset(String url) {
  final lower = url.toLowerCase();
  for (final entry in kBalancePresets.entries) {
    if (lower.contains(entry.key)) {
      return entry.value;
    }
  }
  return null;
}
