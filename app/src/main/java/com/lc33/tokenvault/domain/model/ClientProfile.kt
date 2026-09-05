package com.lc33.tokenvault.domain.model

import com.lc33.tokenvault.domain.Protocol

/**
 * 客户端伪装预设。**数据不是代码**（红线 22）：探测代码里不允许硬编码任何 `User-Agent`
 * 或特征头，全部从这里读。
 *
 * 内置预设与用户自定义**同一张表**，靠 [builtinKey] 区分。升级时按 [builtinRev] 刷新
 * **未被用户改过**（`userEdited == false`）的内置条目——这样既能随版本修正指纹，
 * 又不会覆盖用户抓包校准的结果。
 */
data class ClientProfile(
    val id: Long = 0,
    val name: String,

    /** 内置标识；自定义为 null。SQLite 把多个 NULL 视为互不相同，所以唯一索引不挡自定义。 */
    val builtinKey: String? = null,

    val userAgent: String,

    /**
     * 有序键值对。
     *
     * **是 `List<Pair>` 而不是 `Map`**：部分上游会看请求头顺序，而 JSON 对象在不同解析器下
     * 顺序不保证（§6.2 末尾）。入库时序列化成 `JsonArray` of `[key, value]`。
     */
    val headers: List<Pair<String, String>> = emptyList(),

    /** JSON merge patch（RFC 7386），叠到极简探测 body 上。`null` 值表示删键。 */
    val bodyPatch: String = "{}",

    /**
     * 适用协议。空 = 通用。
     *
     * **这是排序提示，不是硬过滤**（§8.2，M0.5 实测）：`claude_code` 预设是在 **CHAT**
     * 协议上过了 Agent Router 的闸的。中转站的闸只看请求头、不管你打的是哪条路由，
     * 所以按协议硬筛会让 CHAT 协议永远试不到唯一有效的那个预设。
     */
    val protocols: Set<Protocol> = emptySet(),

    /** 被真实请求验证成功过。UI 在预设名后打对勾。 */
    val verified: Boolean = false,

    val builtinRev: Int = 0,
    val userEdited: Boolean = false,
    val sortOrder: Int = 0,
)

/** 一轮探测。用于"上次探测"摘要与"仅重试失败项 / 未探测项"。 */
data class ProbeRun(
    val id: Long = 0,

    /** `all` / `provider:{id}` / `key:{id}` / `model:{id}`。 */
    val scope: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val total: Int = 0,
    val done: Int = 0,
    val okCount: Int = 0,
    val failCount: Int = 0,
    val cancelled: Boolean = false,
) {
    /** 未探测项数。`total - done` 而不是单独存一列：存了就会有两个真相。 */
    val skippedCount: Int get() = (total - done).coerceAtLeast(0)
}

/** 日志级别。 */
enum class LogLevel(val wireName: String) {
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    ;

    companion object {
        fun fromWireName(value: String): LogLevel =
            entries.firstOrNull { it.wireName == value } ?: INFO
    }
}

/** 日志分类。用于日志页的筛选。 */
enum class LogCategory(val wireName: String) {
    LOCK("lock"),
    VAULT("vault"),
    PROBE("probe"),
    BALANCE("balance"),
    CATALOG("catalog"),
    BACKUP("backup"),
    HTTP("http"),
    ACCOUNT("account"),
    ;

    companion object {
        fun fromWireName(value: String): LogCategory =
            entries.firstOrNull { it.wireName == value } ?: VAULT
    }
}

/**
 * 一条日志。
 *
 * [message] 与 [detail] **入库前必须过 `Redactor.scrub`**（红线 32）。这一层不做脱敏——
 * 脱敏需要"当前会话已知的秘密"，而那是 `VaultSession` 的知识；放在这里会变成
 * "有时候脱敏了有时候没脱"。
 */
data class AuditEntry(
    val id: Long = 0,
    val at: Long,
    val level: LogLevel,
    val category: LogCategory,
    val providerId: Long? = null,

    /** 归到具体某张密钥上。详情页的"查看日志"按它过滤。 */
    val keyId: Long? = null,
    val runId: Long? = null,
    val message: String,
    val detail: String? = null,
)
