package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import kotlinx.coroutines.flow.Flow

interface ApiKeyRepository {
    fun observeByProvider(providerId: Long): Flow<List<ApiKey>>
    fun observeAll(): Flow<List<ApiKey>>
    suspend fun find(id: Long): ApiKey?

    /** 计算与落库相同的本机指纹；不落库、不泄露明文。 */
    suspend fun fingerprintOf(secret: CharArray): String

    /** 同一供应商内是否已存在相同指纹。用于 cURL 导入的重复密钥提示。 */
    suspend fun existsFingerprint(providerId: Long, fingerprint: String): Boolean

    /**
     * 新增一张 Key。
     *
     * @param secret 明文密钥。实现会算指纹、加密并落库；调用方负责擦掉自己的数组。
     * @param settings 这把 Key 的请求与探测配置。
     * @param balanceToken NewAPI 一类余额适配器的独立访问令牌明文；null 表示不写，空数组表示清掉。
     */
    suspend fun add(
        providerId: Long,
        label: String,
        note: String,
        secret: CharArray,
        settings: KeySettings,
        balanceToken: CharArray? = null,
    ): Long

    suspend fun replaceSecret(id: Long, secret: CharArray)

    /** 只改名称、备注与排序，不碰密文与行为配置。 */
    suspend fun updateMeta(key: ApiKey)

    /** 保存这把 Key 的行为配置。balanceToken 语义与 [add] 相同。 */
    suspend fun updateSettings(
        id: Long,
        settings: KeySettings,
        balanceToken: CharArray? = null,
    )

    suspend fun reveal(id: Long): CharArray

    /** 解出余额访问令牌明文。返回的数组归调用方擦。 */
    suspend fun revealBalanceToken(id: Long): CharArray?

    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun delete(id: Long)

    /** 同一供应商内重排 Key。排序本身就是优先级，v3 起没有默认 Key。 */
    suspend fun reorder(providerId: Long, idsInOrder: List<Long>)

    suspend fun applyProbeResult(
        id: Long,
        health: String,
        lastOutcome: String,
        detail: String?,
        httpStatus: Int?,
        latencyMs: Long?,
        checkedAt: Long,
        okAt: Long?,
    )

    suspend fun applyTransientOutcome(
        id: Long,
        lastOutcome: String,
        detail: String?,
        httpStatus: Int?,
        checkedAt: Long,
    )

    suspend fun updateBalance(id: Long, snapshot: BalanceSnapshot)
    suspend fun resetProbeResults()
}
