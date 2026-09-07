package com.lc33.tokenvault.net

/**
 * 手动 HTTP 代理的运行时值（§7.5、§13.4）。
 *
 * 替代原先的 `java.net.Proxy`：`net/` 迁 commonMain 后拿不到 `java.net`，这里用一个
 * 纯数据类表达 `host:port`。引擎层把它翻译成各自平台引擎的代理配置。
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
)

/**
 * 解析手动代理串 `host:port` → [ProxyConfig]。空串 / 非法串返回 null（走系统代理）。
 *
 * 这是纯函数，放进 net 层唯一一处：`net` 层的这道判断只该有一份
 * （§7.5 的原话——"纯函数 + 一处调用点，不允许各处自己判"）。
 *
 * 支持 IPv6 用方括号（`[::1]:8080`）；缺端口时用 80。
 */
fun parseProxy(hostPort: String?): ProxyConfig? {
    if (hostPort.isNullOrBlank()) return null
    val s = hostPort.trim()
    // 拒绝 URL 形式的输入（`http://…`、`host/path`）：这里只收 `host[:port]`。
    // 与 ProxyViewModel.isValidHostPort 的判断保持一致。
    if (s.contains("://") || s.contains('/')) return null
    // 纯冒号（空 host 空 port）这类无意义输入直接拒绝。
    if (s == ":") return null
    // IPv6：`[::1]:8080`。取最后一个 `:` 前的 `[...]` 里的内容当 host。
    val host: String
    val port: Int
    val lastColon = s.lastIndexOf(':')
    if (s.startsWith("[") && lastColon > 0 && s.substring(0, lastColon).endsWith("]")) {
        host = s.substring(1, lastColon - 1)
        port = s.substring(lastColon + 1).toIntOrNull() ?: 80
    } else if (lastColon > 0) {
        host = s.substring(0, lastColon)
        port = s.substring(lastColon + 1).toIntOrNull() ?: 80
    } else {
        host = s
        port = 80
    }
    if (host.isBlank()) return null
    return ProxyConfig(host = host, port = port)
}
