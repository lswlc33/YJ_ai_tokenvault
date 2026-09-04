package com.lc33.tokenvault.screens.model

/**
 * 页面层的展示模型。
 *
 * 刻意与将来的领域模型分开：这里的每个字段都是**已经算好、可以直接画**的东西
 * ——金额是格式化后的字符串（§9.1 要求先定点舍入再展示，页面不许自己做浮点运算）、
 * 时间是"3 小时前"这种相对串、密钥只有遮蔽串。
 *
 * 这样约束的直接好处是：列表页拿不到任何明文（§6.1 推论 3），想画也画不出来。
 */

/** 状态的四档。映射到 `StatusPalette` 的四个颜色与 strings 里的四条文案。 */
enum class UiHealth {
    Ok,
    Warn,
    Error,
    Unknown,
}

/** 已格式化的金额。币种单独存，因为首页按币种分组且**不做汇率换算**（§9.3）。 */
data class UiMoney(val currency: String, val amount: String)

/**
 * 用户自定义的分组。管理页的筛选条就是它们。
 *
 * [id] 为 null 表示「全部」那一枚伪分组——它不入库，只是筛选条上的第一项。
 */
data class UiGroup(
    val id: Long?,
    val name: String,
    val providerCount: Int,
)

data class UiProviderRow(
    val id: Long,
    val name: String,
    val note: String?,
    val host: String,
    val protocols: List<String>,
    val colorIndex: Int,
    val pinned: Boolean,
    val groupId: Long?,
    val keyCount: Int,
    val okKeyCount: Int,
    val modelCount: Int,
    val accountCount: Int,
    val balance: UiMoney?,
    val health: UiHealth,
    /** `lastOutcome` 是瞬时类时为真：主状态仍是上一次的持久结论（红线 11）。 */
    val staleThisRound: Boolean = false,
)

data class UiKeyRow(
    val id: Long,
    val label: String,
    val providerId: Long,
    /** 入库时算好的静态遮蔽串。这个类里**没有明文字段**，所以画不出明文（§6.1 推论 3）。 */
    val masked: String,
    val health: UiHealth,
    val latencyMs: Long?,
    val checkedAgo: String?,
    val isDefault: Boolean,
)

enum class UiModelSource {
    Manual,
    Discovered,
}

data class UiModelRow(
    val id: Long,
    val modelId: String,
    val displayName: String?,
    val providerId: Long,
    val protocol: String,
    val source: UiModelSource,
    val health: UiHealth,
    val enabled: Boolean,
    val contextLabel: String?,
)

data class UiAccountRow(
    val id: Long,
    val label: String,
    val providerId: Long,
    /** 只给遮蔽串。密码连遮蔽串都不给——它只在展开时现算（红线 21）。 */
    val maskedUsername: String,
)
