package com.lc33.tokenvault.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 平台登录账号。**纯记录，不是密码管理器**（§2.3）。
 *
 * 用户名也加密：账号与密码成对才有价值，只加密密码等于把一半信息留在明文里；
 * 而且账号往往是邮箱或手机号，本身就是个人信息。所以搜索只索引 [label]。
 */
@Entity(
    tableName = "provider_accounts",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["providerId", "sortOrder", "id"]),
        Index(value = ["providerId", "usernameFp"], unique = true),
    ],
)
data class ProviderAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val label: String = "",

    /** AAD = `provider_accounts:{id}:usernameEnc`。**与密码列的 AAD 不同**，否则两列能互换。 */
    val usernameEnc: ByteArray? = null,
    val usernameFp: String? = null,

    /** AAD = `provider_accounts:{id}:passwordEnc`。 */
    val passwordEnc: ByteArray? = null,

    val loginUrl: String? = null,

    /** CSV：`github,linuxdo`。 */
    val loginMethods: String = "",
    val note: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderAccountEntity) return false
        return id == other.id &&
            providerId == other.providerId &&
            usernameEnc.contentEquals(other.usernameEnc) &&
            passwordEnc.contentEquals(other.passwordEnc) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (usernameEnc?.contentHashCode() ?: 0)
        result = 31 * result + (passwordEnc?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 客户端伪装预设。内置与自定义**同表**，靠 [builtinKey] 区分。
 *
 * 自定义预设的 `builtinKey` 是 NULL，而 SQLite 的唯一索引把多个 NULL 视为互不相同，
 * 所以那条唯一索引不会挡住多条自定义预设。
 */
@Entity(
    tableName = "client_profiles",
    indices = [Index(value = ["builtinKey"], unique = true)],
)
data class ClientProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val builtinKey: String? = null,
    val userAgent: String,

    /** JSON **数组** of `[key, value]`，不是对象：部分上游看请求头顺序，而对象顺序不保证。 */
    val headers: String = "[]",

    /** JSON merge patch。 */
    val bodyPatch: String = "{}",

    /** CSV，空表示通用。**是排序提示不是硬过滤**（§8.2）。 */
    val protocols: String = "",

    val verified: Boolean = false,
    val builtinRev: Int = 0,

    /** 用户改过则升级时不覆盖——既能随版本修正指纹，又不丢用户抓包校准的结果。 */
    val userEdited: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ApiKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["providerId", "keyId", "modelId", "protocol"], unique = true),
        Index(value = ["keyId"]),
        Index(value = ["catalogKey"]),
    ],
)
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,

    /** 这份模型列表属于哪张 Key。旧数据迁移时会挂到默认 Key。 */
    val keyId: Long? = null,

    val modelId: String,
    val protocol: String,
    val displayName: String? = null,

    /** `manual` | `discovered`。手动录入的**永不被自动同步改动**（红线 13）。 */
    val source: String = "manual",

    /** 这一行从哪个协议的列表里发现的。"上游消失即删除"只在同协议内生效（红线 30）。 */
    val discoveredVia: String? = null,

    val favorite: Boolean = false,
    val needsReview: Boolean = false,
    val catalogKey: String? = null,
    val probeState: String = "unknown",
    val lastOutcome: String = "skipped",
    val probeDetail: String? = null,
    val latencyMs: Long? = null,
    val probedAt: Long? = null,
    val firstSeenAt: Long,
    val lastSeenAt: Long? = null,
    val sortOrder: Int = 0,
)

/**
 * models.dev 的派生索引，只存用得上的字段。匹配是索引查询而不是全表扫描。
 *
 * 表与索引随 v1 建好，v9 补上详情页要展示的几列。写入方是 `engine/CatalogSync`
 * （整表替换），读取方是 `data/repo/RoomModelCatalogRepository` 与 catalogKey 回填。
 *
 * **四个 id 各不相同，别混**（这是本表最容易搞错的地方，规则全在 `CatalogParser` 里）：
 *
 * - [providerSlug]：models.dev 的**外层 key**，即「这份列表是谁给的」。
 * - [key] = `providerSlug/mapKey`：**目录主键，唯一性靠外层兜底**。
 *   不能用 `vendor/model` 当主键——`deepseek/deepseek-v3.2` 这种带真厂商前缀的 mapKey
 *   在多家聚合站下各有一份（实测带斜杠的 4,508 个 mapKey 里 4,419 个前缀 ≠ 外层 slug，
 *   同一个 mapKey 最多被十几家重复挂出），拿它当主键会互相覆盖，最后留下**哪一家的价格
 *   全看 JSON 里的顺序**。分开之后重复是好事：多条候选，由 [canonical] 挑出原创那条。
 * - [modelId] = mapKey **原样**（可能带斜杠）。这是用户手里那个字符串的对照物：
 *   中转站列表里写的就是 `deepseek/deepseek-v3.2`，剥掉前缀反而精确匹配不上了。
 * - [vendor] = mapKey 的斜杠前缀，无斜杠时就是 [providerSlug]：**展示与分组用的厂商**。
 *   用户要的「OpenAI 一类、DeepSeek 一类」就是这一列。注意它不总是真厂商——
 *   原创条目 `openai` 名下的 `gpt-4o` 算出 `openai`（对），聚合站 `tokengo` 名下的
 *   `deepseek/deepseek-chat` 算出 `deepseek`（也对），而 `tokengo` 名下的裸 id 算出
 *   `tokengo`（那是「这份列表来自 tokengo」，宁可这样也不靠猜归到某个品牌）。
 *   **别用 [family] 当厂商**：GPT 系列的 family 是 `gpt`，不是 `openai`。
 *
 * [qualifiedId] 是第四级查找键，专门补上「裸 mapKey 的原创条目」够不到的那一路：
 * `openai` 名下 mapKey 是 `gpt-4o`，而用户输入 `openai/gpt-4o` 时 [modelId] 精确匹配不上，
 * 靠 `vendor/bareModelId` 拼出来的 `openai/gpt-4o` 才能直达。
 */
@Entity(
    tableName = "model_catalog",
    indices = [
        Index(value = ["modelId"]),
        Index(value = ["normId"]),
        Index(value = ["qualifiedId"]),
        // 分组：整页按厂商取数、算每组数量都要按 vendor 过一遍表。
        Index(value = ["vendor"]),
        Index(value = ["family"]),
        Index(value = ["providerSlug"]),
    ],
)
data class ModelCatalogEntity(
    /** 目录主键 `providerSlug/mapKey`，见类注释。 */
    @PrimaryKey val key: String,

    /** models.dev 的外层 key——「这份列表是谁给的」。 */
    val providerSlug: String = "",

    /** 展示与分组用的厂商 slug：mapKey 的斜杠前缀，无斜杠时是 [providerSlug]。 */
    val vendor: String = "",

    /** mapKey 原样，可能带 `vendor/` 前缀。 */
    val modelId: String,

    /** `vendor/裸 id`，第一级查找键（见类注释最后一段）。 */
    val qualifiedId: String = "",

    /** 归一化后的**裸** id，用于第三级模糊匹配（去掉 `-latest`、日期后缀、`:free`）。 */
    val normId: String,
    val name: String? = null,
    val family: String? = null,
    val contextLimit: Int? = null,
    val outputLimit: Int? = null,
    val costInput: Double? = null,
    val costOutput: Double? = null,
    val costCacheRead: Double? = null,
    val costCacheWrite: Double? = null,

    /** CSV。 */
    val inputModalities: String? = null,
    val outputModalities: String? = null,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val attachment: Boolean = false,
    val releaseDate: String? = null,
    val lastUpdated: String? = null,

    /**
     * 这一行是不是**厂商自己挂出来的**，判据是 [providerSlug] == [vendor]。
     *
     * 同一个模型在目录里会有多条候选，而**上下文窗口、是否支持工具调用这些参数是各家
     * 自己填的**（聚合站会写自己截断后的窗口）。消歧必须先看这一列：不优先原创条目而
     * 「按 lastUpdated 取最新」，就会把某家转售条目的规格当成厂方规格显示在模型详情页上。
     */
    val canonical: Boolean = false,

    /** 厂商展示名（[vendor] 对应的那家，来自 models.dev 外层 `name`，如 `OpenAI`）。 */
    val vendorName: String? = null,
    val description: String? = null,

    /** 能力位。models.dev 有 `structured_output` / `open_weights`，详情页要说清楚。 */
    val structuredOutput: Boolean = false,
    val openWeights: Boolean = false,

    /** `preview` / `deprecated` 之类；models.dev 的 `status`，缺失为 null。 */
    val status: String? = null,

    /** 训练知识截止时间，models.dev 的 `knowledge`。 */
    val knowledgeCutoff: String? = null,
)

/**
 * models.dev 的厂商表（222 行量级），外层 key 一家一行。
 *
 * 存在的理由是 [ModelCatalogEntity.vendor] 只是 slug，分组标题要的是 `OpenAI` 这种展示名，
 * 「查看官方文档」要的是 `doc`。目录行上冗余一份
 * [ModelCatalogEntity.vendorName] 是为了整页分组不必每条再回这张表查——注意冗余的是
 * **按 vendor 查到的那一家**，不是 providerSlug：聚合站 `tokengo` 名下
 * `deepseek/deepseek-chat` 的 vendor 是 `deepseek`，展示名就该是 DeepSeek 的。
 */
@Entity(
    tableName = "model_vendors",
    indices = [Index(value = ["slug"], unique = true)],
)
data class ModelVendorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** models.dev 的外层 key，与 [ModelCatalogEntity.providerSlug] 同一个东西。 */
    val slug: String,
    val name: String,

    /** 上游 API 根地址，只用于展示「它的官方端点长什么样」，不发请求。 */
    val apiUrl: String? = null,
    val docUrl: String? = null,
)

@Entity(tableName = "probe_runs")
data class ProbeRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** `all` | `provider:{id}` | `key:{id}` | `model:{id}`。 */
    val scope: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val total: Int = 0,
    val done: Int = 0,
    val okCount: Int = 0,
    val failCount: Int = 0,
    val providerTotal: Int = 0,
    val providerDone: Int = 0,
    val providerOk: Int = 0,
    val providerFail: Int = 0,
    val keyTotal: Int = 0,
    val keyDone: Int = 0,
    val keyOk: Int = 0,
    val keyFail: Int = 0,
    val cancelled: Boolean = false,
)

/**
 * 日志。
 *
 * [message] / [detail] / 三个报文列**入库前都必须过 `Redactor.scrub`**（红线 32）。
 * 这一层不做脱敏——脱敏需要"当前会话已知的秘密"，那是 `VaultSession` 的知识；
 * 放在这里会变成"有时候脱敏了有时候没脱"。
 *
 * [requestUrl] / [requestBody] / [responseBody] 只有 HTTP 类日志才填（"网络请求需要完整
 * 记录"），点击这类日志进详情页看原文。**请求头永不落库**：那里是 `Authorization`，
 * 而 body 里的密钥由凭据列表那道脱敏兜住。
 */
@Entity(
    tableName = "audit_log",
    indices = [Index(value = ["at"], orders = [Index.Order.DESC])],
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val level: String,
    val category: String,
    val providerId: Long? = null,

    /** 归到具体某张密钥上。详情页的"查看日志"按它过滤。 */
    val keyId: Long? = null,
    val runId: Long? = null,
    val message: String,
    val detail: String? = null,

    /** 请求地址（已去掉 query 与 fragment，路径保留）。 */
    val requestUrl: String? = null,
    val requestBody: String? = null,
    val responseBody: String? = null,
)

/**
 * 设置项。
 *
 * [value] 与 [valueBlob] **互斥**：加密设置项（WebDAV 凭据）走 blob 而不是把密文 base64
 * 塞进 TEXT 列——往 TEXT 里塞二进制迟早出编码问题（§6.2）。
 *
 * `themeMode` / `localeTag` / `onboarded` **不在这张表里**：它们的权威存储是 boot，
 * 因为锁屏页要用（红线 31）。`SettingsRepository` 对这三个键透传到 `BootStore`。
 */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String? = null,
    val valueBlob: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppSettingEntity) return false
        return key == other.key && value == other.value && valueBlob.contentEquals(other.valueBlob)
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + (value?.hashCode() ?: 0)
        result = 31 * result + (valueBlob?.contentHashCode() ?: 0)
        return result
    }
}
