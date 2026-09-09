package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import kotlinx.coroutines.flow.Flow

/**
 * 模型。
 *
 * 手动录入走 [add]；自动发现走 [applyDiscovered]，后者在数据层执行三路合并，
 * 保证 manual 行永不被自动同步改动（红线 13）。
 */
interface ModelRepository {

    fun observeByProvider(providerId: Long): Flow<List<AiModel>>

    /**
     * 新增一条手动录入的模型（`source = MANUAL`，`discoveredVia = null`）。
     *
     * @param needsReview 疑似显示名而非真实 id（`DeepSeek V4 Pro` 这种，§11.2）。
     * @return 新行 id。
     */
    suspend fun add(
        providerId: Long,
        modelId: String,
        protocol: Protocol,
        needsReview: Boolean = false,
    ): Long

    /**
     * 应用一次成功的模型列表拉取。`modelIds` 为空表示上游确认当前协议没有模型，
     * 因此同协议的 discovered 行会被停用；解析失败时调用方不应调用本方法。
     */
    suspend fun applyDiscovered(providerId: Long, protocol: Protocol, modelIds: List<String>)
}
