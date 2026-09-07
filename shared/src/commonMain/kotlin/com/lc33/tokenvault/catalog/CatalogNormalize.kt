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

    /** 尾部日期后缀：`-20250101`（8 位）或 `-2025-01-01`（带分隔符）。 */
    private val DATE_SUFFIX = Regex("""-\d{8}$|-\d{4}-\d{2}-\d{2}$""")

    /** `:free` / `:thinking` / `:extended` 这类能力后缀。 */
    private val CAPABILITY_SUFFIX = Regex(""":(free|thinking|extended|beta|pro|latest)$""")

    fun normalize(modelId: String): String {
        var s = modelId.lowercase()
        s = s.removeSuffix("-latest")
        s = DATE_SUFFIX.replace(s, "")
        s = CAPABILITY_SUFFIX.replace(s, "")
        s = s.replace('.', '-').replace('_', '-')
        return s
    }
}
