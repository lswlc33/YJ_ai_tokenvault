package com.lc33.tokenvault.domain

/**
 * 余额适配器的种类（§9.2）。
 *
 * `newapi` 与 `deepseek` 已在 M0.5 用真实账号验证过（fixture 在
 * `app/src/test/resources/fixtures/balance/`）；其余四个的字段名以公开文档为依据但
 * **未用真实账号验证**，M7 逐个实测，实测不通过就删掉预设而不是留着——留一个永远返回 0
 * 或永远匹配不上的预设比没有预设更糟（§9.2 里那段反面教材写的就是这件事）。
 *
 * @param usesOwnToken 为真表示它用**独立的访问令牌 + 用户 ID**（在中转站个人安全设置里
 *   获取），与 API 密钥无关；为假表示复用该供应商的默认 API 密钥——所以"删除默认 Key
 *   后自动顶上"那条不变量（红线 6.3）直接决定余额还查不查得出来。
 */
enum class BalanceKind(val wireName: String, val usesOwnToken: Boolean) {
    NONE("none", usesOwnToken = false),

    /** new-api / one-api 及衍生，绝大多数中转站。`quota / quotaPerUnit`，实测 quotaPerUnit = 500000。 */
    NEWAPI("newapi", usesOwnToken = true),

    /** DeepSeek 官方。`balance_infos[0].total_balance` 是**字符串**，要 parse。 */
    DEEPSEEK("deepseek", usesOwnToken = false),

    OPENROUTER("openrouter", usesOwnToken = false),
    SILICONFLOW("siliconflow", usesOwnToken = false),
    MOONSHOT("moonshot", usesOwnToken = false),

    /** 任意站：配置 method / path / headers / valuePath / usedPath / currency。**不支持表达式**。 */
    CUSTOM_JSON("customJson", usesOwnToken = false),
    ;

    companion object {
        /** new-api 系的默认换算比。M0.5 在两家上都实测到 500000。 */
        const val NEWAPI_DEFAULT_QUOTA_PER_UNIT = 500_000.0

        fun fromWireName(value: String): BalanceKind =
            entries.firstOrNull { it.wireName == value } ?: NONE
    }
}

/** 平台账号的登录方式。只做记录，不承担任何登录能力。 */
enum class LoginMethod(val wireName: String) {
    GITHUB("github"),
    LINUX_DO("linuxdo"),
    ;

    companion object {
        fun fromWireName(value: String): LoginMethod? = entries.firstOrNull { it.wireName == value }
    }
}

/** 模型来源（§8.3 的三路合并）。 */
enum class ModelSource(val wireName: String) {
    /** 用户手动录入。**自动同步永不改动它**（红线 13）。 */
    MANUAL("manual"),

    /** 从接口拉到的。"上游消失即停用"只对它生效，且只在 `discoveredVia` 那个协议内生效（红线 30）。 */
    DISCOVERED("discovered"),
    ;

    companion object {
        fun fromWireName(value: String): ModelSource =
            entries.firstOrNull { it.wireName == value } ?: MANUAL
    }
}

/**
 * 探测级别（§8.3）。
 *
 * @param costsQuota 会不会花钱。**红线 36**：花钱的级别只能用户手动点，
 *   且开关按供应商单独存——自动路径（自动探测 / 定时 / 解锁后 / 重试失败项）上
 *   碰到它一概标 [ProbeOutcome.SKIPPED] 并说明原因。
 */
enum class ProbeLevel(val wireName: String, val costsQuota: Boolean) {
    /** 连通性 / 模型列表。`GET {models}`，零成本。 */
    L1_REACHABILITY("l1", costsQuota = false),

    /**
     * 密钥有效性。**当前实现**是拿该 Key 走一次 `GET {models}`，零成本
     * （`costsQuota` 按最坏情况标真，见下）。
     *
     * 已知局限（改之前先读这里）：models 路由**不鉴权**的站点上，任何密钥都会拿到
     * 2xx，于是坏密钥被标成可用。真实调用（`ProbeRequestBuilder.inference`）目前只在
     * 手动长按模型那条 L3 路径上发（红线 36：要花钱的不进自动路径），所以这里没有
     * "未鉴权就升级为推理调用"——那段升级逻辑并不存在，别再照着旧注释去接设置项。
     * `costsQuota = true` 是因为"若将来允许升级，这一次就会花钱"，按最坏情况标记。
     */
    L2_KEY_VALIDITY("l2", costsQuota = true),

    /** 模型可用性。逐模型发极简推理调用，必然花钱，默认关。 */
    L3_MODEL("l3", costsQuota = true),

    /** 余额。见第 9 节，零成本。 */
    L4_BALANCE("l4", costsQuota = false),
    ;

    companion object {
        fun fromWireName(value: String): ProbeLevel? = entries.firstOrNull { it.wireName == value }
    }
}
