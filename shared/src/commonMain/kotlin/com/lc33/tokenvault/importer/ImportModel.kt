package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol

/**
 * 文本导入解析器的输出（§11）。
 *
 * 这是**解析**的产物，还没落库。预览页拿它画卡片，确认后由导入编排层（data 层）
 * 转成领域对象写入。
 *
 * 两条与领域模型刻意不同的地方：
 *
 * 1. **秘密字段用 [CharArray]**（红线 1）：API Key、访问令牌、平台账号、平台密码
 *    都是明文，解析器从粘贴文本里提取后直接存成可擦的 `CharArray`，而不是再抄一份
 *    `String` 留在堆上。非秘密字段（名称、URL、模型 id）才用 `String`。
 * 2. **不分配 id**：这是"将要写库"的草稿，主键由数据库在插入时分配。账号与模型
 *    只有挂到供应商之后才有意义，所以它们嵌在记录里而不是独立成表。
 *
 * [issues] 是**已本地化的**问题描述还是原始的原因码，由调用方决定：解析器这一层是
 * 纯 Kotlin，读不到 Android 资源（红线 19），所以它只产出**机器可读的原因枚举**
 * （见 [ImportIssue]），文案由 UI 层统一翻译。
 */
data class ParsedRecord(
    // ---- 供应商字段（§11.1 那张表） ----
    val name: String,
    val note: String? = null,
    val websiteUrl: String? = null,

    /** 用户原样输入的地址。可能带 query（会记进 [issues]，预览时标红）。 */
    val apiBaseUrl: String? = null,

    /** 解析出的协议集合。由「支持端点类型」块给出。 */
    val supportedProtocols: Set<Protocol> = emptySet(),

    /** 余额查询类型。 */
    val balanceKind: BalanceKind = BalanceKind.NONE,

    /** 只有出现在「余额查询类型」之后才算余额地址；已剥离行尾说明文字。 */
    val balanceBaseUrl: String? = null,

    /** NewAPI 访问令牌**明文**。可能因为"字段名单独成行"而来自下一行。 */
    val balanceToken: CharArray? = null,

    val balanceUserId: String? = null,

    /** 客户端预设名 / builtinKey 的原文。匹配不到内置预设时记 issue 并忽略。 */
    val clientProfileRef: String? = null,

    // ---- 子项（嵌入，挂到供应商之后才有意义） ----
    val keys: List<ParsedKey> = emptyList(),
    val models: List<ParsedModel> = emptyList(),
    val accounts: List<ParsedAccount> = emptyList(),

    /** 解析过程中发现的问题。**不阻止导入**，只提示（§11.2）。 */
    val issues: List<ImportIssue> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedRecord) return false
        return name == other.name &&
            note == other.note &&
            websiteUrl == other.websiteUrl &&
            apiBaseUrl == other.apiBaseUrl &&
            supportedProtocols == other.supportedProtocols &&
            balanceKind == other.balanceKind &&
            balanceBaseUrl == other.balanceBaseUrl &&
            balanceToken.contentEquals(other.balanceToken) &&
            balanceUserId == other.balanceUserId &&
            clientProfileRef == other.clientProfileRef &&
            keys == other.keys &&
            models == other.models &&
            accounts == other.accounts &&
            issues == other.issues
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + supportedProtocols.hashCode()
        result = 31 * result + balanceKind.hashCode()
        result = 31 * result + (balanceToken?.contentHashCode() ?: 0)
        result = 31 * result + keys.hashCode()
        result = 31 * result + models.hashCode()
        result = 31 * result + accounts.hashCode()
        return result
    }
}

/** 一条待导入的密钥。label 由解析器按顺序分配（`主号` / `备用 2` / …），第一张默认。 */
data class ParsedKey(
    val label: String,
    val secret: CharArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedKey) return false
        return label == other.label && secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int = 31 * label.hashCode() + secret.contentHashCode()
}

/**
 * 一条待导入的模型。
 *
 * [needsReview] 是 `模型 id 含空格或大写字母` 的机器表达（§11.2 第一条）——`DeepSeek V4 Pro`
 * 这种"疑似显示名而非真实 id"。落库时转成 `AiModel.needsReview`。
 */
data class ParsedModel(
    val modelId: String,
    val protocol: Protocol,
    val needsReview: Boolean = false,
)

/**
 * 一条待导入的平台账号（§11.1 的账号分组规则）。
 *
 * 用户名与密码都可能为 null（"只记一半也是合法的记录"，§11.2 最后一条）。
 */
data class ParsedAccount(
    val label: String,
    val username: CharArray? = null,
    val password: CharArray? = null,
    val loginUrl: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedAccount) return false
        return label == other.label &&
            username.contentEquals(other.username) &&
            password.contentEquals(other.password) &&
            loginUrl == other.loginUrl
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + (username?.contentHashCode() ?: 0)
        result = 31 * result + (password?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 解析问题（机器可读，文案由 UI 层给，红线 19）。
 *
 * [detail] 只在"需要把具体值带给用户"时用（比如"客户端预设 `xxx` 匹配不到"）。
 */
enum class ImportIssue {
    /** 缺少必填的「供应商名称」。 */
    MISSING_NAME,

    /** `API请求地址` 带 query / fragment，或规范化失败。 */
    BAD_ENDPOINT,

    /** `http://` 地址。预览标红，需用户勾选 `allowInsecure`。 */
    INSECURE_ENDPOINT,

    /** 模型 id 疑似显示名（含空格或大写）。 */
    MODEL_NAME_REVIEW,

    /** 模型协议不在该供应商 `supportedProtocols` 里，会被自动补进去。 */
    MODEL_PROTOCOL_ADDED,

    /** `balanceKind = newapi` 但缺访问令牌或用户 ID。 */
    BALANCE_MISSING_CREDENTIAL,

    /** 客户端预设匹配不到内置预设，被忽略。 */
    CLIENT_PROFILE_UNKNOWN,

    /** `平台账号` 有但缺 `平台密码`（或反之）。 */
    ACCOUNT_INCOMPLETE,

    /** 同一条记录内重复的平台账号，后一条会被跳过。 */
    ACCOUNT_DUPLICATE,
}

/**
 * 单条记录解析失败。整条丢弃而不是局部保留（§11.2：预览页要能说清哪条坏了）。
 * 与 [ImportIssue.MISSING_NAME] 的区别：前者还能进预览页让用户补，后者直接出不了记录。
 */
data class ParseError(
    /** 这段文本里能认出的名字，认不出就是 null。用于"第几条坏了"的提示。 */
    val nameHint: String?,
    val message: String,
)
