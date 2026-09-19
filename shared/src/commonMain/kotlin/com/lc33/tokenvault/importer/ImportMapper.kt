package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl

/** 解析记录 → 供应商合集。v3 起这里只产出名称、备注、官网等组织信息。 */
fun ParsedRecord.toProvider(): Provider? = Provider(
    name = name,
    note = note,
    websiteUrl = websiteUrl,
)

/** 解析记录 → 每一把导入 Key 共用的初始行为配置。 */
fun ParsedRecord.toKeySettings(): KeySettings {
    val baseUrl = apiBaseUrl.orEmpty()
    val normalized = normalizeBaseUrl(baseUrl)
    val endpoints = (normalized as? NormalizeResult.Ok)?.endpoints
    return KeySettings(
        apiBaseUrl = baseUrl,
        apiRoot = endpoints?.apiRoot ?: baseUrl,
        apiVersion = endpoints?.ver ?: "v1",
        supportedProtocols = supportedProtocols,
        // 这三项过去在解析器里认得、却在映射时被丢掉：导出一份再导入一次，
        // 路径覆盖 / 换算比 / customJson 配置全归零，用户看到的是"配置莫名其妙没了"。
        pathOverrides = pathOverrides,
        balanceKind = balanceKind,
        balanceBaseUrl = balanceBaseUrl,
        balanceUserId = balanceUserId,
        quotaPerUnit = quotaPerUnit,
        balanceConfig = balanceConfig.orEmpty().ifBlank { "{}" },
    )
}
