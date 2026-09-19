package com.lc33.tokenvault.domain

/**
 * 按**模型名前缀**归厂商族。纯函数，不依赖 models.dev。
 *
 * 为什么用前缀而不是目录匹配出来的厂商：一个中转站动辄几百个模型，其中相当一部分
 * 是本地目录匹配不上的（自部署、改名、上游没进 models.dev）。如果分组依赖匹配，
 * 这些模型会全掉进一个"未识别"大组，等于没有分组——而这恰恰是最需要分组的那批。
 * 前缀不一样，`deepseek-chat` 和 `deepseek-reasoner` 不管匹没匹配上都是 DeepSeek，
 * `gpt-4o` 和 `gpt-4.1-mini` 都是 GPT，这正是用户说的那条直觉规则。
 *
 * 规则：**先按 `/` 切，再取开头那一串字母**。
 *
 * 只按 `-` 切第一段是不够的，它会在这几种形状上出错：
 * - `Qwen3.5-397B-A17B` → 第一段是 `qwen3.5`，而同一家的 `qwen-plus` 是 `qwen`，
 *   一家被拆成两组。取字母段两者都是 `qwen`。
 * - `moonshot/kimi-k2` → 前缀是 `moonshot`，斜杠才是厂商与模型的分界。
 *
 * 字母段短到一个字符时退回第一段整体，否则 `o3-mini` 会变成一个孤零零的 "o" 组，
 * 而 `o1`、`o3`、`o4-mini` 本来是一族：退回后拿到 `o3`、`o1`，仍按 families 各自成组，
 * 但至少不会出现"一个字母一组"这种结果。
 */
object ModelFamily {

    /** 归一化后的族键（小写）。用于分组、排序、搜索。 */
    fun keyOf(modelId: String): String {
        val head = modelId.trim().substringBefore('/').substringBefore(':')
        if (head.isEmpty()) return OTHERS_KEY
        val letters = head.takeWhile { it.isLetter() }.lowercase()
        if (letters.length >= 2) return letters
        // 字母段太短：退回第一个 `-` 之前的整段（含数字），`o3-mini` → `o3`。
        return head.substringBefore('-').substringBefore('_').lowercase().ifEmpty { OTHERS_KEY }
    }

    /**
     * 展示用的名字。目录匹配上时用厂商的正式展示名（`DeepSeek`），否则把前缀键首字母大写。
     *
     * 两条路都保留是因为它们各有各的错：前缀推出来的 `Gpt` 不如目录里的 `OpenAI` 准确，
     * 但目录没有的时候也不能空着不显示。
     */
    fun displayOf(modelId: String, catalogVendorName: String?): String =
        catalogVendorName?.takeIf { it.isNotBlank() } ?: capitalize(keyOf(modelId))

    private fun capitalize(value: String): String =
        value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /**
     * 空 id、纯符号这类兜底分组键。
     *
     * **这是键不是文案**：界面看到它要去资源里取那句"其他"，不能把中文字面量写进
     * Kotlin（`ArchitectureRulesTest` 的中文 literals 那条，也是这个仓库的一贯红线）。
     */
    const val OTHERS_KEY = "other"
}
