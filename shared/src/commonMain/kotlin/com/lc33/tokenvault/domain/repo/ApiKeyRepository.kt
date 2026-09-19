package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import kotlinx.coroutines.flow.Flow

/**
 * 同一供应商内已经有这把密钥了（指纹相同）。
 *
 * `api_keys(providerId, fingerprint)` 是**唯一索引**，所以这不是"礼貌提示"而是数据库
 * 根本不接受的写入。[add] 与 [ApiKeyRepository.replaceSecret] 在写之前预检，把它变成
 * 一个领域错误：调用方能据此给出可懂的失败，而不是让 SQLite 的约束异常从事务里冒出来
 * （那句 `UNIQUE constraint failed: api_keys.providerId, api_keys.fingerprint` 用户读不懂，
 * 日志里也指不出是哪一家）。
 *
 * [existingId] 是已存在那一行的 id，用于"其实已经导进来了"这种幂等处理；未知时为 null。
 */
class DuplicateApiKeyException(
    val providerId: Long,
    val existingId: Long? = null,
) : IllegalStateException("api key already exists in provider $providerId")

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
     * @throws DuplicateApiKeyException 这家已经有指纹相同的 Key（唯一索引，写不进去）
     */
    suspend fun add(
        providerId: Long,
        label: String,
        note: String,
        secret: CharArray,
        settings: KeySettings,
        balanceToken: CharArray? = null,
    ): Long

    /**
     * 换掉这把 Key 的密钥。
     *
     * @throws DuplicateApiKeyException 换上去的密钥与同家另一把 Key 指纹相同——那等于凭空
     * 造一条唯一索引冲突，必须让调用方知道，而不是让整笔写回滚后抛一句数据库错误。
     */
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

    /** 删除这把 Key（连同它的 KeySettings 与模型）。返回可撤销句柄。 */
    suspend fun delete(id: Long): UndoableDeletion?

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
