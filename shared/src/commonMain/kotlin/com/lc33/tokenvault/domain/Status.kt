package com.lc33.tokenvault.domain

/**
 * 密钥的**持久结论**（§5.3），落 `api_keys.health`。
 *
 * 红线 11 在类型层面的表达：**这里刻意没有 `NETWORK_ERROR` / `RATE_LIMITED` /
 * `UPSTREAM_ERROR`**。它们是瞬时状况，属于 [ProbeOutcome]，永远不写进 health——
 * 留在这个枚举里迟早会有人写进去，然后一次限流就把昨天验证过的密钥标成坏的。
 *
 * @param wireName 入库标识，不用 [name]（枚举改名不该改变已存数据的含义）。
 */
enum class KeyHealth(val wireName: String) {
    /** 初始状态。 */
    UNKNOWN("unknown"),

    /** 探测成功。 */
    OK("ok"),

    /** 401。 */
    UNAUTHORIZED("unauthorized"),

    /** 403 且未命中额度 / 客户端关键词。 */
    FORBIDDEN("forbidden"),

    /** 402，或任意 4xx/5xx 且上游消息命中额度关键词。**关键词优先于状态码**（§8.4）。 */
    INSUFFICIENT("insufficient"),

    /** 400 参数不被接受、404 路径错、返回非预期内容。 */
    CONFIG_ERROR("config_error"),

    /**
     * 上游按客户端指纹拦截。
     *
     * M0.5 实测：Agent Router 用 **401** 而不是 403，而且拦在鉴权之前（红线 33）。
     * 所以判定这一档的关键词检查必须排在 401 分支之前，否则"换个 UA 就能用"
     * 会被判成"密钥无效"——而那正是客户端伪装功能存在的理由。
     */
    CLIENT_BLOCKED("client_blocked"),
    ;

    /** 这一档算不算"这张密钥现在能用"。供应商聚合状态（§5.3 末尾）用它。 */
    val usable: Boolean get() = this == OK

    companion object {
        fun fromWireName(value: String): KeyHealth =
            entries.firstOrNull { it.wireName == value } ?: UNKNOWN
    }
}

/**
 * 最近一次探测**发生了什么**（§5.3），落 `api_keys.lastOutcome`。每次探测都写。
 *
 * [rewritesHealth] 是红线 11 的机器可读版本：分类器返回的 `KeyHealth?` 为 null 时
 * 不许改写持久结论。把它做成枚举上的属性而不是散在 `when` 里，是为了让"新增一档
 * outcome 时忘了想清楚它该不该改写 health"变成编译期就要面对的问题。
 */
enum class ProbeOutcome(val wireName: String, val rewritesHealth: Boolean) {
    /** 2xx 且能解析成 JSON 且没有 error 字段。**不要求文本非空**（红线 34）。 */
    SUCCESS("success", rewritesHealth = true),

    /** 401 / 403 / 402 / 400 / 404 / 额度或客户端关键词命中。 */
    CONCLUSIVE_FAIL("conclusive_fail", rewritesHealth = true),

    /** 超时 / DNS / TLS / 连接失败。 */
    NETWORK_ERROR("network_error", rewritesHealth = false),

    /** 429 且退避重试后仍然。退避时间取响应头与 body 里 `retry_after` 的较大值（§8.4）。 */
    RATE_LIMITED("rate_limited", rewritesHealth = false),

    /** 5xx 且重试后仍然。 */
    UPSTREAM_ERROR("upstream_error", rewritesHealth = false),

    /** 超总预算 / 撞 host 预算 / 金库锁定。连 `checkedAt` 都不写。 */
    SKIPPED("skipped", rewritesHealth = false),

    /** 用户取消。什么都不写。 */
    CANCELLED("cancelled", rewritesHealth = false),
    ;

    /**
     * 是不是瞬时状况。
     *
     * UI 据此决定要不要在主状态下面补一行"本轮网络不可达 · 上次成功于 3 小时前"（§5.3）。
     */
    val transient: Boolean
        get() = this == NETWORK_ERROR || this == RATE_LIMITED || this == UPSTREAM_ERROR

    companion object {
        fun fromWireName(value: String): ProbeOutcome =
            entries.firstOrNull { it.wireName == value } ?: SKIPPED
    }
}

/** 模型的探测状态（§5.3）。模型行同样有独立的 `lastOutcome`，规则与 [ProbeOutcome] 一致。 */
enum class ModelProbeState(val wireName: String) {
    UNKNOWN("unknown"),
    OK("ok"),

    /**
     * 模型不存在。
     *
     * M0.5 实测：DeepSeek 返回 **400** 并列出支持的模型名，JustDoWork 返回
     * **403 + 空 body**——**都不是 404**。§8.4 因此补了一条"4xx 且 message 命中
     * 模型关键词 → NOT_FOUND"，排在 400 的其它分支之前。
     */
    NOT_FOUND("not_found"),

    NO_ACCESS("no_access"),
    ERROR("error"),
    ;

    companion object {
        fun fromWireName(value: String): ModelProbeState =
            entries.firstOrNull { it.wireName == value } ?: UNKNOWN
    }
}

/**
 * 余额状态。**派生值，不入库**（§5.3）——它由金额、币种阈值与 `balanceError` 现算。
 *
 * 入库会立刻产生第二个真相：阈值改了之后库里的状态就是错的，而没人会想到去重算它。
 */
enum class BalanceState {
    UNKNOWN,
    OK,
    LOW,
    NEGATIVE,

    /** 查询失败。**必须与"余额为 0"可区分**（§9.3），所以它是独立的一档而不是 amount=0。 */
    ERROR,
}
