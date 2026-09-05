package com.lc33.tokenvault.crypto

/**
 * 恢复密钥（红线 25、§7.1）。
 *
 * 32 个 hex 字符，引导流程最后一步生成并全屏显示，要求用户勾选"我已保存"才能继续。
 * 它解决的是本项目**最可能真实发生的事故**：忘记 6 位 PIN 导致整库不可读。
 * 没有它，唯一出路是备份包，而备份口令默认就是那个 PIN。
 *
 * 为什么是 hex 而不是助记词：助记词要带词表（几十 KB）、要处理语言选择，而且用户抄写
 * 时更容易把相近的词写错；hex 只有 16 个字符，抄错了也能一眼看出来。128 位熵对
 * "抵抗离线穷举"来说完全够——它是这个应用里**唯一**真正高强度的凭据。
 */
object RecoveryKey {

    private const val HEX = "0123456789abcdef"

    /** 32 hex 字符 = 128 位熵。 */
    const val LENGTH = 32

    /** 显示时每 4 个字符一组，抄写不容易串行。 */
    const val GROUP_SIZE = 4

    /**
     * 生成。返回 [CharArray] 而不是 `String`：它是明文秘密，得能擦掉（红线 1）。
     *
     * 逐字符取模而不是"取一个随机数再转 hex"：后者要处理正负号与前导零，
     * 而漏掉前导零会让熵悄悄少几位。
     */
    fun generate(random: RandomBytes = SecureRandomBytes): CharArray {
        val bytes = random.nextBytes(LENGTH)
        return try {
            CharArray(LENGTH) { i -> HEX[(bytes[i].toInt() and 0xFF) % HEX.length] }
        } finally {
            bytes.zeroize()
        }
    }

    /**
     * 规范化用户输入：去掉所有空白与连字符、转小写。
     *
     * 用户会照着分组抄，回填时几乎一定带空格或短横线。不做规范化的表现是
     * "我抄得没错但它说不对"，而这是恢复密钥最不能出现的体验。
     *
     * **换行也必须跳掉**：恢复密钥的正常保管方式是密码管理器的备注或一个 txt 文件，
     * 粘贴过来几乎一定带一个结尾换行；多行输入框里用户也能直接按回车。带着 `\n` 就是
     * 33 个字符，于是应用告诉他"恢复密钥是 32 个字符"——正是上面那句 KDoc 说绝不能出现的
     * 体验。所以这里用 [Char.isWhitespace] 而不是枚举几个字符：枚举早晚漏一个。
     */
    fun normalize(input: CharArray): CharArray {
        val kept = CharArray(input.size)
        var n = 0
        for (c in input) {
            if (c.isWhitespace() || c == '-') continue
            kept[n++] = c.lowercaseChar()
        }
        // 无条件擦 `kept`：`copyOf(n)` **永远**新分配一份，所以 `kept` 每次都是该丢掉的
        // 中间产物。写成"长度变了才擦"会恰好漏掉最常见的那条路径——用户直接粘贴、
        // 输入里没有分隔符时 `n == kept.size`，于是完整的明文恢复密钥留在堆上不擦。
        return kept.copyOf(n).also { kept.zeroize() }
    }

    /** 是不是 32 个 hex 字符。规范化之后再判。 */
    fun isWellFormed(normalized: CharArray): Boolean =
        normalized.size == LENGTH && normalized.all { it in HEX }

    /**
     * 分组显示用的字符串。
     *
     * **只在即将展示的那一刻调用**：它会产生一个擦不掉的 `String`（红线 1），
     * 所以展示页必须同时加 `FLAG_SECURE`（§7.5），而且离开页面就丢弃引用。
     */
    fun formatForDisplay(key: CharArray): String = buildString {
        key.forEachIndexed { index, c ->
            if (index > 0 && index % GROUP_SIZE == 0) append(' ')
            append(c)
        }
    }
}
