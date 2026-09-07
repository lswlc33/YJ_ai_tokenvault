package com.lc33.tokenvault.crypto

/**
 * 落库 / 落日志前的脱敏，**两道**（红线 32）。
 *
 * 第一道按**已知明文值**替换，第二道才是正则兜底。顺序不能倒过来，理由是实测出来的：
 * `EXAMPLEtoken0000/EXAMPLE0000000=` 这种形态的 base64 访问令牌不匹配任何一条 `sk-` /
 * `Bearer` 规则，只有"我知道这个值长什么样"这一道能拦住它——而它恰好是最敏感的一类
 * （new-api 的 `/api/user/self` 会把它连同邮箱一起回显）。
 *
 * 第一道除了整串，还要替换**前缀与后缀**，因为上游两种回显方式都遇到过：
 * - 前缀：错误消息里带 key 的开头（常见于 new-api 系）。
 * - 后缀：DeepSeek 的 401 回 `Your api key: ****alid is invalid`——掩掉了前面，
 *   **回显的是后 4 位**。M0.5 之前 §7.5 只写了前缀，挡不住这一形态。
 *
 * 取舍写清楚：宁可**多擦**也不少擦。多擦的代价是用户自己看的日志里少一个词，
 * 少擦的代价是凭据进了可能被分享出去的文本。所以后缀那一道故意不做"只在有掩码符号时才擦"
 * 的收紧——只加了两条防明显误伤的护栏（见 [suffixWorthRedacting]）。
 *
 * @param knownSecrets 当前会话已知的明文秘密。做成 lambda 而不是集合，是因为它会随
 *   "用户展开了某张密钥""刚解密了 WebDAV 口令"而增长，拦截器不该持有一份过期快照。
 * @param placeholder 替换后的占位串。做成参数是为了让 UI 层能注入一个本地化的值——
 *   `crypto/` 是纯 Kotlin 层，读不到 Android 资源。
 */
class Redactor(
    private val knownSecrets: () -> Collection<String> = { emptyList() },
    private val placeholder: String = DEFAULT_PLACEHOLDER,
) {

    fun scrub(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text

        // 第一道：已知明文值。长的先替换，否则短的先命中会把长的切碎，
        // 留下半截仍然可读的凭据。
        for (secret in knownSecrets().filter { it.length >= MIN_SECRET_LENGTH }.sortedByDescending { it.length }) {
            out = out.replace(secret, placeholder)
            out = out.replace(secret.take(PREFIX_LENGTH), placeholder)
            val suffix = secret.takeLast(SUFFIX_LENGTH)
            if (suffixWorthRedacting(suffix)) {
                out = out.replace(suffix, placeholder)
            }
        }

        // 第二道：正则兜底。挡的是"这一台设备上还没见过、但形态可认"的凭据。
        for (pattern in FALLBACK_PATTERNS) {
            out = pattern.replace(out) { match ->
                // 有捕获组的（JSON 字段名）保留键名，只擦值——不然日志会变成
                // 一串占位串，连"是哪个字段泄的"都看不出来。
                if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty()) {
                    "\"${match.groupValues[1]}\":\"$placeholder\""
                } else {
                    placeholder
                }
            }
        }
        return out
    }

    /**
     * 后缀值不值得替换。两条护栏，都是为了别把正常文本擦花：
     * - 含 `.` 或 `/` 的后缀跳过：邮箱结尾的 `.com`、URL 结尾的 `/v1` 会命中满屏的正常内容。
     * - 至少要有 3 个不同字符：`0000`、`aaaa` 这种低熵尾巴同样会误伤。
     */
    private fun suffixWorthRedacting(suffix: String): Boolean {
        if (suffix.length < SUFFIX_LENGTH) return false
        if (suffix.contains('.') || suffix.contains('/')) return false
        return suffix.toSet().size >= 3
    }

    companion object {
        const val DEFAULT_PLACEHOLDER = "<redacted>"

        /** 短于它的"秘密"不进第一道：4 位 PIN 那种长度替换起来只会把日志擦成一片。 */
        const val MIN_SECRET_LENGTH = 12
        const val PREFIX_LENGTH = 8
        const val SUFFIX_LENGTH = 4

        /**
         * 兜底正则。顺序有意义：先擦最具体的形态，最后才是宽的。
         *
         * 刻意**没有**一条"任意长 base64"的规则——那会把时间戳、id、哈希全擦掉，
         * 日志就没法用了。base64 形态的令牌靠第一道拦（红线 32 的原话）。
         */
        private val FALLBACK_PATTERNS = listOf(
            // OpenAI 系密钥
            Regex("""sk-[A-Za-z0-9_\-]{16,}"""),
            // Authorization: Bearer …
            Regex("""(?i)bearer\s+[A-Za-z0-9._\-+/=]{8,}"""),
            // JSON 里的凭据字段。键名留着，值擦掉。
            Regex("""(?i)"(access_token|refresh_token|api_key|apikey|token|secret|password|passwd|pwd)"\s*:\s*"[^"]*""""),
            // 邮箱：平台账号常常就是邮箱，而它同时也是最容易被顺手分享出去的一类
            Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
        )
    }
}
