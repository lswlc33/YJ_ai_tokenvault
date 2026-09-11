package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderAccount

/**
 * 反向导出：把一个供应商合集与其 Key 导出成与导入相同的文本格式。
 *
 * v3 后行为配置在 Key 上；导出时取该合集下排序第一的启用 Key 作为文本格式的代表。
 * 文本格式本身没有“每把 Key 独立配置”的表达，多把 Key 只会逐行导出密钥。
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

    private fun BalanceKind.exportName(): String? = when (this) {
        BalanceKind.NEWAPI -> "NewAPI"
        BalanceKind.DEEPSEEK -> "官方接口" // i18n-exempt: 导入格式的“官方接口”字样
        else -> null
    }

    fun exportProvider(
        provider: Provider,
        keys: List<Pair<ApiKey, String>>,
        accounts: List<ExportedAccount>,
        models: List<AiModel>,
    ): String = buildString {
        val settings = keys.firstOrNull()?.first?.settings
        appendLine("供应商名称 ${provider.name}") // i18n-exempt: 导出格式的字段名
        appendLine("备注 ${provider.note ?: "无"}") // i18n-exempt: 导出格式的字段名
        provider.websiteUrl?.let { appendLine("官网链接 $it") } // i18n-exempt: 导出格式的字段名
        keys.forEach { (key, secret) ->
            appendLine("API Key ${key.label.ifBlank { "主号" }} $secret") // i18n-exempt: 导出格式的字段名
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

        if (models.isNotEmpty()) {
            appendLine()
            appendLine("模型列表") // i18n-exempt: 导出格式的字段名
            models.forEach { appendLine("${it.modelId} ${it.protocol.exportModelSuffix()}") }
        }

        if (settings != null && settings.balanceKind != BalanceKind.NONE) {
            val kindName = settings.balanceKind.exportName()
            if (kindName != null) {
                appendLine()
                appendLine("余额查询类型 $kindName") // i18n-exempt: 导出格式的字段名
                settings.balanceBaseUrl?.let { appendLine("请求地址 $it") } // i18n-exempt: 导出格式的字段名
                settings.balanceUserId?.let { appendLine("用户ID $it") } // i18n-exempt: 导出格式的字段名
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

    data class ExportedAccount(
        val label: String,
        val username: String,
        val password: String?,
        val loginUrl: String? = null,
        val loginMethods: Set<LoginMethod> = emptySet(),
    )

    const val PLAINTEXT_WARNING = "# 警告：本文件包含明文 API 密钥与平台密码，请妥善保管，用完即删" // i18n-exempt: 导出文件内容，不是 UI 文案
}
