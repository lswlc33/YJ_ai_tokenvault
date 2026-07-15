/// 敏感信息脱敏展示。
class Masking {
  /// API Key 脱敏：保留头 4 尾 4，中间星号。短 key 全星。
  static String maskKey(String? key) {
    if (key == null || key.isEmpty) return '••••';
    final k = key.trim();
    if (k.length <= 8) return '•' * k.length;
    return '${k.substring(0, 4)}${'•' * 6}${k.substring(k.length - 4)}';
  }

  /// 端点脱敏：只显示 host（含端口），隐藏路径。
  static String maskEndpoint(String? url) {
    if (url == null || url.isEmpty) return '未设置端点';
    try {
      final u = Uri.parse(url);
      if (u.host.isEmpty) return url;
      if (u.hasPort && u.port != 80 && u.port != 443) {
        return '${u.host}:${u.port}';
      }
      return u.host;
    } catch (_) {
      return url;
    }
  }
}
