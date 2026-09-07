package com.lc33.tokenvault.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub Releases API 返回的一条 release，只保留更新页要用的字段。
 *
 * 这是纯数据（`update/` 包零平台依赖，见 ci.yml 的分层规则）。JSON 里的其余字段
 * （assets、author、reactions……）一律 `ignoreUnknownKeys` 丢掉，不落进内存。
 *
 * @param tagName 发布标签。正式版是 `v{versionName}`（如 `v0.1.0`，release.yml 约定
 *   tag 与 vaultVersionName 一致），nightly 是固定的 `nightly-build`。
 * @param prerelease 是否预发布。nightly 走 `--prerelease`，正式版是 false。
 */
@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("prerelease") val prerelease: Boolean = false,
    /** release 的网页地址，`去下载`直接打开它。 */
    @SerialName("html_url") val htmlUrl: String? = null,
)
