package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderAccount

/**
 * 反向导出（§11.3）：把供应商导出成与导入相同的文本格式。
 *
 * 纯函数：接收领域对象与"密钥 / 密码该不该给明文"的标志，拼出文本。**不读 DEK**——
 * 明文还是遮蔽串由调用方决定（调用方负责 `reveal` 与 `SecretMask.of`），这里只管格式。
 *
 * 遮蔽串用 [SecretMask] 现算，但那一层是 `domain` 的纯逻辑，所以这里也不碰。
 * 反向导出在 v1 里是可砍项（§14.4），先落这个纯函数 + 单测，UI 的 SAF 保存入口后续接。
 */
object TextExporter {

    /** 协议 → 导入格式里的别名（`支持端点类型` 块里的写法）。 */
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
     * 余额类型还原成导入格式的写法。只有 newapi 与官方接口（DeepSeek）在导入格式里有
     * 稳定表达；其余（customJson / openrouter 等）反向导出时省略余额段——它们没有
     * 对应的导入写法，硬写一个会误导下次导入。
     */
    private fun BalanceKind.exportName(): String? = when (this) {
        BalanceKind.NEWAPI -> "NewAPI"
        BalanceKind.DEEPSEEK -> "官方接口" // i18n-exempt: 导入格式的"官方接口"字样
        else -> null
    }

    /**
     * 导出单个供应商。
     *
     * @param keys label 到密钥**展示串**（明文或遮蔽，调用方决定）。
     * @param accounts 平台账号。username / password 是**展示串**（明文或遮蔽）。
     * @param models 模型。
     */
    fun exportProvider(
        provider: Provider,
        keys: List<Pair<String, String>>,
        accounts: List<ExportedAccount>,
        models: List<AiModel>,
    ): String = buildString {
        // 下面的字段名是导出格式的协议（与导入对称），不是 UI 文案（i18n-exempt）
        appendLine("供应商名称 ${provider.name}") // i18n-exempt: 导出格式的字段名
        appendLine("备注 ${provider.note ?: "无"}") // i18n-exempt: 导出格式的字段名
        provider.websiteUrl?.let { appendLine("官网链接 $it") } // i18n-exempt: 导出格式的字段名
        keys.forEach { (label, secret) ->
            appendLine("API Key $secret")
        }
        appendLine("API请求地址 ${provider.apiBaseUrl}") // i18n-exempt: 导出格式的字段名

        if (provider.supportedProtocols.isNotEmpty()) {
            appendLine()
            appendLine("支持端点类型") // i18n-exempt: 导出格式的字段名
            appendLine()
            provider.supportedProtocols.forEach { appendLine("- ${it.exportAlias()}") }
        }

        if (models.isNotEmpty()) {
            appendLine()
            appendLine("模型列表") // i18n-exempt: 导出格式的字段名
            models.forEach { appendLine("${it.modelId} ${it.protocol.exportModelSuffix()}") }
        }

        if (provider.balanceKind != BalanceKind.NONE) {
            val kindName = provider.balanceKind.exportName()
            if (kindName != null) {
                appendLine()
                appendLine("余额查询类型 $kindName") // i18n-exempt: 导出格式的字段名
                provider.balanceBaseUrl?.let { appendLine("请求地址 $it") } // i18n-exempt: 导出格式的字段名
                provider.balanceUserId?.let { appendLine("用户ID $it") } // i18n-exempt: 导出格式的字段名
            }
        }

        accounts.forEach { account ->
            appendLine()
            appendLine("平台账号 ${account.username}") // i18n-exempt: 导出格式的字段名
            account.password?.let { appendLine("平台密码 $it") } // i18n-exempt: 导出格式的字段名
            appendLine("账号备注 ${account.label}") // i18n-exempt: 导出格式的字段名
            account.loginUrl?.let { appendLine("登录地址 $it") } // i18n-exempt: 导出格式的字段名
        }
    }

    /** 导出时的账号。username / password 是展示串（明文或遮蔽）。 */
    data class ExportedAccount(
        val label: String,
        val username: String,
        val password: String?,
        val loginUrl: String? = null,
        val loginMethods: Set<LoginMethod> = emptySet(),
    )

    /** 明文导出时插在文件开头的警告注释（§11.3）。 */
    const val PLAINTEXT_WARNING = "# 警告：本文件包含明文 API 密钥与平台密码，请妥善保管，用完即删" // i18n-exempt: 导出文件内容，不是 UI 文案
}
