package com.lc33.tokenvault.domain

/**
 * 明文秘密 → 遮蔽串（§6.2）。
 *
 * **遮蔽串不入库**：它是解密之后现算的，所以只出现在确实要展示密钥的详情页
 * （§6.1 推论 3）。存一列遮蔽串看着省事，代价是列表页从此带着一段可读的密钥片段，
 * 而那正是"锁上了但截图里还看得见"的来源。
 *
 * 保留头尾两段而不是全遮：用户手上常有好几把形状相似的密钥，不给任何字符就没法确认
 * "我改的是哪一把"。头尾各留几位是**可辨认**与**不可重建**之间的那条线。
 *
 * 三档规则，短值那两档是必要的——一个 10 个字符的值按"头 7 尾 4"遮完等于原样显示：
 *
 * | 明文长度 | 结果 |
 * | --- | --- |
 * | ≥ [PREFIX] + [SUFFIX] + [MIN_HIDDEN] | `sk-LWxU…JrG0` |
 * | ≥ [SHORT_MIN] | `sk…G0`（头尾各 2） |
 * | 更短 | 只有省略号：这种长度的值遮不住，而它本来也不该是一把真密钥 |
 */
object SecretMask {

    const val PREFIX = 7
    const val SUFFIX = 4

    /** 至少要藏住这么多字符，否则"遮蔽"只是装样子。 */
    const val MIN_HIDDEN = 4

    const val SHORT_MIN = 8
    private const val SHORT_KEEP = 2

    const val ELLIPSIS = "…"

    fun of(secret: CharArray): String {
        val n = secret.size
        return when {
            n >= PREFIX + SUFFIX + MIN_HIDDEN ->
                buildString {
                    appendRange(secret, 0, PREFIX)
                    append(ELLIPSIS)
                    appendRange(secret, n - SUFFIX, n)
                }

            n >= SHORT_MIN ->
                buildString {
                    appendRange(secret, 0, SHORT_KEEP)
                    append(ELLIPSIS)
                    appendRange(secret, n - SHORT_KEEP, n)
                }

            else -> ELLIPSIS
        }
    }

    /** 平台账号的用户名用同一套规则（红线 21：用户名与密钥同等对待）。 */
    fun ofUsername(username: CharArray): String = of(username)

    private fun StringBuilder.appendRange(chars: CharArray, from: Int, to: Int) {
        for (i in from until to) append(chars[i])
    }
}
