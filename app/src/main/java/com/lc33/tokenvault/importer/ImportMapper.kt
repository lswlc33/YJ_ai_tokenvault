package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl

/**
 * 把解析结果映射成领域对象（纯函数，零 Android / 零 DEK）。
 *
 * 与 [TextImporter] 分开，是因为"解析"与"落库前的定型"是两件事：解析只认格式，
 * 这里才做 URL 规范化、协议补齐这类**语义**决策。分开后单测可以各测各的，
 * 而 [ParsedRecord] 这一层还留着原始输入（`apiBaseUrl` 原样），方便预览页对照。
 */

/** 一条解析记录 → 可写库的供应商。规范化失败时返回 null（调用方据此跳过并提示）。 */
fun ParsedRecord.toProvider(): Provider? {
    // 没有地址的供应商仍可写库（用户先建个空壳再补）
    val baseUrl = apiBaseUrl
    if (baseUrl.isNullOrBlank()) {
        return Provider(
            name = name,
            note = note,
            websiteUrl = websiteUrl,
            apiBaseUrl = "",
            apiRoot = "",
            supportedProtocols = supportedProtocols,
            balanceKind = balanceKind,
            balanceBaseUrl = balanceBaseUrl,
            balanceUserId = balanceUserId,
        )
    }

    return when (val result = normalizeBaseUrl(baseUrl)) {
        is NormalizeResult.Err -> {
            // 地址带 query 等：保留原样但标 issue，仍可导入（用户能在编辑页改）
            Provider(
                name = name,
                note = note,
                websiteUrl = websiteUrl,
                apiBaseUrl = baseUrl,
                apiRoot = baseUrl,
                supportedProtocols = supportedProtocols,
                balanceKind = balanceKind,
                balanceBaseUrl = balanceBaseUrl,
                balanceUserId = balanceUserId,
            )
        }
        is NormalizeResult.Ok -> {
            val endpoints = result.endpoints
            Provider(
                name = name,
                note = note,
                websiteUrl = websiteUrl,
                apiBaseUrl = baseUrl,
                apiRoot = endpoints.apiRoot,
                apiVersion = endpoints.ver,
                supportedProtocols = supportedProtocols,
                allowInsecure = endpoints.insecure,
                balanceKind = balanceKind,
                // 余额地址为空或等于 origin 则存 null（§11.1）
                balanceBaseUrl = balanceBaseUrl?.takeIf { it != endpoints.origin },
                balanceUserId = balanceUserId,
            )
        }
    }
}
