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
    /**
     * 已格式化的余额。null 表示**没有可显示的金额**，而那有两种原因，由 [balanceFailed] 区分。
     */
    val balance: UiMoney?,
    /**
     * 余额查询**试过且失败了**。
     *
     * 三种状态必须在 UI 上可区分（§9.3），而单一个 `balance: UiMoney?` 只能表达两种：
     *
     * - 有金额（含 0）——`balance != null`；
     * - 试过但失败——`balance == null && balanceFailed`；
     * - **根本没配置 / 从未查过**——`balance == null && !balanceFailed`。
     *
     * 把后两种归成一类的表现很具体：一个刚建好、压根没开余额查询的供应商，
     * 会在余额明细里被列到“查询失败”下面，于是用户去查一个不存在的故障。
     */
    val balanceFailed: Boolean = false,
    val health: UiHealth,
    /** `lastOutcome` 是瞬时类时为真：主状态仍是上一次的持久结论（红线 11）。 */
    val staleThisRound: Boolean = false,
    /**
     * 手动排序的次序（`providers.sortOrder`）。「手动排序」这一档用它排；其它档忽略它。
     */
    val sortOrder: Int = 0,
    /**
     * 这家最近一次探测的时间戳（该家所有密钥 `checkedAt` 的最大值）。「最近探测」这一档
     * 用它排；null 表示从没探测过，排最后。
     */
    val lastProbeAt: Long? = null,
)

data class UiKeyRow(
    val id: Long,
    val label: String,
    val providerId: Long,
    /**
     * 遮蔽串。**解密之后现算**（§6.1 推论 3），所以它只出现在详情页；这个类里没有明文字段，
     * 也就画不出明文。
     */
    val masked: String,
    val health: UiHealth,
    val latencyMs: Long?,
    /**
     * 最近一次探测的时间戳，**不是格式化好的相对时间串**。
     *
     * 相对时间的文案在 `strings.xml` 里（"3 小时前"），取它要 `stringResource`，
     * 而 ViewModel 拿不到资源。所以这里给时间戳，分档与文案由页面用
     * `relativeBucketOf` + `relativeTimeLabel` 现做——那两个函数本来就是为此拆开的。
     */
    val checkedAt: Long?,
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
