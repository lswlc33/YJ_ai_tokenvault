package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.Provider

/**
 * 反向导出：把一个供应商合集与其 Key 导出成与导入相同的文本格式。
 *
 * **导出写字段 = 导入认字段**：这里输出的每一行都必须能被 [TextImporter] 读回来，
 * 否则"导出一份备份、重装后导入"会静默丢掉配置。新增字段时两边都要动，
 * 并由 `TextExporterTest` 的往返断言（余额 kind / 请求地址 / 访问令牌 / 用户ID /
 * 换算比 / 余额配置 / 路径覆盖 / 登录方式）把不对称钉住。
 *
 * v3 后行为配置在每把 Key 上，而文本格式是"一条记录一份配置"，没有"每把 Key 独立配置"
 * 的表达。所以这里取排序第一的 Key 作为代表配置（与 `ImportWriter` 落库时的行为一致：
 * 同一记录里的 Key 共享一份初始配置），其余 Key 只出行密钥。Key 之间配置真的不同时
 * 额外写一行以 `#` 开头的告知——导入器会忽略未知行，用户能看见这条限制。
 */
object TextExporter {

    private fun Protocol.exportAlias(): String = when (this) {
        Protocol.CHAT -> "Chat Completions"
        Protocol.RESPONSES -> "Responses"
        Protocol.ANTHROPIC -> "Anthropic Messages"
    }

    private fun Protocol.exportModelSuffix(): String = when (this) {
        Protocol.CHAT -> "Chat"
        Protocol.RESPONSES -> "Responses"
        Protocol.ANTHROPIC -> "Anthropic"
    }

    /**
     * 余额类型按 [BalanceKind.wireName] 导出，导入侧同一张表认回来。
     *
     * 旧实现只有 NEWAPI 与 DEEPSEEK 两档有名字（后者写成"官方接口"，靠 host 反推），
     * 于是 openrouter / siliconflow / moonshot / volcengine / customJson 五档导出时
     * 直接消失——重装导入后余额查询类型变成"不查"。`官方接口` 那条路径仍然保留在
     * **导入**侧（那是用户手写的写法），导出侧不再用它：host 反推不出来时会退成
     * customJson，等于一次静默降级的往返损失。
     */
    private fun BalanceKind.exportName(): String? =
        takeIf { this != BalanceKind.NONE }?.wireName

    private fun LoginMethod.exportName(): String = wireName

    /**
     * 单行化：字段值里出现换行会把一条记录劈成两条（`---` 之外的换行就是行分隔符）。
     * 密钥与令牌本来就不该含空白，这里只是兜住"用户在 label 里敲了回车"这种脏数据。
     */
    private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ')

    /**
     * @param keys 该合集下的 Key，按界面上的排序传入；`secret` 与 `balanceToken` 都是
     *   **已解密的明文**，调用方负责只在用户明确点"导出"时才把明文交进来。
     * @param clientProfileNames `clientProfileId` → 预设名。文本格式里「客户端预设」写的是
     *   名字不是 ID（跨设备 ID 必然不同），所以只有传了名字才导得出这一行。
     */
    fun exportProvider(
        provider: Provider,
        keys: List<ExportedKey>,
        accounts: List<ExportedAccount>,
        models: List<AiModel>,
        clientProfileNames: Map<Long, String> = emptyMap(),
    ): String = buildString {
        val settings = keys.firstOrNull()?.key?.settings
        if (keys.size > 1 && keys.distinctBy { it.key.settings }.size > 1) {
            // 告知行必须落在**记录开头**：`#` 开头的行不是已知字段，只在块外才会被整行忽略，
            // 放到模型列表之后就成了一条凭空的"模型"。
            appendLine(MULTI_KEY_WARNING) // i18n-exempt: 导出文件内容，不是 UI 文案
        }
        appendLine("供应商名称 ${provider.name.oneLine()}") // i18n-exempt: 导出格式的字段名
        appendLine("备注 ${provider.note ?: "无"}") // i18n-exempt: 导出格式的字段名
        provider.websiteUrl?.let { appendLine("官网链接 $it") } // i18n-exempt: 导出格式的字段名
        keys.forEach { exported ->
            // label 在前、密钥在最后：导入侧按"最后一个空白段是密钥"切，所以 label 里的
            // 空格不会把密钥挤错位。
            appendLine("API Key ${exported.key.label.ifBlank { "主号" }.oneLine()} ${exported.secret.trim()}") // i18n-exempt: 导出格式的字段名
        }
        settings?.apiBaseUrl?.takeIf { it.isNotBlank() }?.let {
            appendLine("API请求地址 $it") // i18n-exempt: 导出格式的字段名
        }

        val protocols = settings?.supportedProtocols.orEmpty()
        if (protocols.isNotEmpty()) {
            appendLine()
            appendLine("支持端点类型") // i18n-exempt: 导出格式的字段名
            appendLine()
            protocols.forEach { appendLine("- ${it.exportAlias()}") }
        }

        settings?.pathOverrides?.forEach { (protocol, path) ->
            // 每行一条：`路径覆盖 <wireName> <完整路径>`，与导入侧的切法一一对应。
            appendLine("路径覆盖 ${protocol.wireName} ${path.oneLine()}") // i18n-exempt: 导出格式的字段名
        }

        if (models.isNotEmpty()) {
            appendLine()
            appendLine("模型列表") // i18n-exempt: 导出格式的字段名
            models.forEach { appendLine("${it.modelId} ${it.protocol.exportModelSuffix()}") }
        }

        settings?.clientProfileId?.let { id ->
            clientProfileNames[id]?.let { appendLine("客户端预设 ${it.oneLine()}") } // i18n-exempt: 导出格式的字段名
        }

        if (settings != null && settings.balanceKind != BalanceKind.NONE) {
            val kindName = settings.balanceKind.exportName()
            if (kindName != null) {
                appendLine()
                appendLine("余额查询类型 $kindName") // i18n-exempt: 导出格式的字段名
                settings.balanceBaseUrl?.let { appendLine("请求地址 $it") } // i18n-exempt: 导出格式的字段名
                // 访问令牌紧跟「余额查询类型」之后，与导入侧"值可能在下一行"的宽容读法兼容。
                keys.firstOrNull()?.balanceToken?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    appendLine("访问令牌 $it") // i18n-exempt: 导出格式的字段名
                }
                settings.balanceUserId?.let { appendLine("用户ID $it") } // i18n-exempt: 导出格式的字段名
                settings.quotaPerUnit?.let { appendLine("换算比 $it") } // i18n-exempt: 导出格式的字段名
                settings.balanceConfig
                    .takeIf { it.isNotBlank() && it != "{}" }
                    ?.let { appendLine("余额配置 ${it.oneLine()}") } // i18n-exempt: 导出格式的字段名
            }
        }

        accounts.forEach { account ->
            appendLine()
            appendLine("平台账号 ${account.username.oneLine()}") // i18n-exempt: 导出格式的字段名
            account.password?.let { appendLine("平台密码 $it") } // i18n-exempt: 导出格式的字段名
            appendLine("账号备注 ${account.label.oneLine()}") // i18n-exempt: 导出格式的字段名
            account.loginUrl?.let { appendLine("登录地址 $it") } // i18n-exempt: 导出格式的字段名
            if (account.loginMethods.isNotEmpty()) {
                // 逗号分隔：导入侧同时认逗号、空格与 `/`，逗号是这里唯一不会误伤的写法
                // （用户名/网址里都可能出现 `/`）。
                appendLine(
                    "登录方式 ${account.loginMethods.joinToString(",") { it.exportName() }}", // i18n-exempt: 导出格式的字段名
                )
            }
        }
    }

    /** 一把 Key 的导出输入：Key 本身 + 两份只有导出时才解开的明文。 */
    data class ExportedKey(
        val key: ApiKey,
        val secret: String,
        /** NewAPI / 火山那类适配器用的独立令牌明文（`settings.balanceTokenEnc` 解密后的值）。 */
        val balanceToken: String? = null,
    )

    data class ExportedAccount(
        val label: String,
        val username: String,
        val password: String?,
        val loginUrl: String? = null,
        val loginMethods: Set<LoginMethod> = emptySet(),
    )

    const val PLAINTEXT_WARNING = "# 警告：本文件包含明文 API 密钥与平台密码，请妥善保管，用完即删" // i18n-exempt: 导出文件内容，不是 UI 文案

    const val MULTI_KEY_WARNING = "# 提示：本合集里多把 Key 的配置不同，上面这份是排序第一把 Key 的配置，其余 Key 导入后会共用它" // i18n-exempt: 导出文件内容，不是 UI 文案
}
