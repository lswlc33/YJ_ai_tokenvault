package com.lc33.tokenvault.catalog

/**
 * 模型 id 归一化（§10 第三级匹配的前置）。
 *
 * 归一化规则（§10）：转小写 → 去 `-latest` → 去尾部 `-\d{8}` 或 `-\d{4}-\d{2}-\d{2}`
 * → 去 `:free` / `:thinking` 等后缀 → `.` 与 `_` 归一为 `-`。
 *
 * 目的：`claude-opus-4-latest` 与 `claude-opus-4` 指向同一个模型，只是别名不同。
 * 归一化后两者都变成 `claude-opus-4`，于是第三级匹配能命中。
 */
object CatalogNormalize {

    /**
     * 尾部日期后缀：`-20250101`（8 位）、`-2025-01-01`（带分隔符）、`-240428`（6 位，YYMMDD）。
     *
     * 6 位那一档是 2026-09-20 拿真机库里那份模型列表实测出来的：**火山引擎 133 个模型
     * 只匹配上 28 个**，未匹配的样本全是 `doubao-lite-128k-240428`、`doubao-pro-4k-240515`
     * 这种带 6 位上线日的 id——Volcengine 的命名习惯是 YYMMDD 而不是 ISO 日期。
     * 少这一条，那一家就等于整片认不出来。
     *
     * 顺序上把 8 位放在 6 位之前：同一条正则里先列出的分支优先，反过来
     * `20250101` 会被 6 位分支先咬掉前 6 个字符、剩下 `01` 挂在中段。
     */
    private val DATE_SUFFIX = Regex("""-\d{8}$|-\d{4}-\d{2}-\d{2}$|-\d{6}$""")

    /**
     * `:free` / `:thinking` / `:batch` 这类能力后缀。
     *
     * `batch` 同样是实测补上的：openrouter 那 445 个里有几十个 `...:batch` 的批处理档位
     * （`openai/gpt-6-astra:batch`），不剥掉就落在未匹配里。
     */
    private val CAPABILITY_SUFFIX = Regex(""":(free|thinking|extended|beta|pro|latest|batch)$""")

    fun normalize(modelId: String): String {
        var s = modelId.lowercase()
        // **反复剥离到不动点**：两类正则都锚在行尾 `$`，所以一次只能剥掉最末尾那一层。
        // 复合后缀 `gpt-4o-2024-08-06:free` 就是旧实现的漏网之鱼：日期那条先跑，但串
        // 尾是 `:free` 匹配不上；能力后缀再把 `:free` 剥掉后日期才露在尾部，却已经过了
        // 它那一轮。结果留在 `gpt-4o-2024-08-06`，与目录里的 `gpt-4o` 对不上，
        // 第三级归一化匹配整段失效。每一轮只会让串变短，所以循环必然收敛。
        while (true) {
            val stripped = s.removeSuffix("-latest")
                .let { DATE_SUFFIX.replace(it, "") }
                .let { CAPABILITY_SUFFIX.replace(it, "") }
            if (stripped == s) break
            s = stripped
        }
        return s.replace('.', '-').replace('_', '-')
    }
}
