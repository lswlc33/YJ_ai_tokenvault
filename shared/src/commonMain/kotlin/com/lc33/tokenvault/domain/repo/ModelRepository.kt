package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import kotlinx.coroutines.flow.Flow

/**
 * 模型。
 *
 * 模型列表绑定在密钥上：同一供应商的不同 Key 可能拿到不同分组权限。手动录入走 [add]；
 * 自动发现走 [applyDiscovered]，后者在数据层执行三路合并，保证 manual 行永不被自动
 * 同步改动（红线 13）。
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
        keyId: Long,
        modelId: String,
        protocol: Protocol,
        needsReview: Boolean = false,
    ): Long

    /**
     * 应用一次成功的模型列表拉取。`modelIds` 为空表示这把 Key 在当前协议下没有模型，
     * 因此同 Key、同协议的 discovered 行会被停用；解析失败时调用方不应调用本方法。
     */
    suspend fun applyDiscovered(
        providerId: Long,
        keyId: Long,
        protocol: Protocol,
        modelIds: List<String>,
    )

    /** 落库模型探测结论。state 非 UNKNOWN 时才改写持久结论。 */
    suspend fun applyProbeResult(
        id: Long,
        state: String,
        lastOutcome: String,
        detail: String?,
        latencyMs: Long?,
        probedAt: Long,
    )

    /** 只写模型探测的瞬时结论，持久 state 不动（红线 11）。 */
    suspend fun applyTransientOutcome(
        id: Long,
        lastOutcome: String,
        detail: String?,
        probedAt: Long,
    )

    /** 手动编辑模型。模型表没有密文，可以整行保存。 */
    suspend fun update(model: AiModel)

    suspend fun delete(id: Long)

    /** 开启自动模型列表前清空这家已保存的模型列表。 */
    suspend fun clearByProvider(providerId: Long)
}