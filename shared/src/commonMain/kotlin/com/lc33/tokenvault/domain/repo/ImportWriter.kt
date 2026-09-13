package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.importer.ParsedRecord
import com.lc33.tokenvault.importer.toKeySettings
import com.lc33.tokenvault.importer.toProvider

/**
 * 文本导入的落库编排。
 *
 * v3 的写入顺序：供应商合集 → 每把 Key（带行为配置）→ 平台账号 → 模型。
 * 导入格式没有“每把 Key 单独配置”的表达，所以同一记录里的 Key 先共享一份初始配置，
 * 用户之后可以在 Key 设置页里逐把调整。
 */
class ImportWriter constructor(
    private val providers: ProviderRepository,
    private val keys: ApiKeyRepository,
    private val accounts: ProviderAccountRepository,
    private val models: ModelRepository,
    private val transactions: TransactionRunner,
) {
    /** 把一条 cURL 解析结果写入已有供应商；只取第一把 Key 与解析出的模型。 */
    suspend fun writeKeyToProvider(providerId: Long, record: ParsedRecord): Long? {
        val parsedKey = record.keys.firstOrNull() ?: return null
        val settings = record.toKeySettings()
        val keyId = keys.add(
            providerId = providerId,
            label = parsedKey.label,
            note = "",
            secret = parsedKey.secret,
            settings = settings,
            balanceToken = record.balanceToken,
        )
        record.models.forEach { model ->
            models.add(providerId, keyId, model.modelId, model.protocol, model.needsReview)
        }
        return keyId
    }

    suspend fun write(records: List<ParsedRecord>): Int {
        var count = 0
        transactions.inTransaction {
            for (record in records) {
                val provider = record.toProvider() ?: continue
                val providerId = providers.save(provider)
                val settings = record.toKeySettings()

                val keyIds = record.keys.map { key ->
                    keys.add(
                        providerId = providerId,
                        label = key.label,
                        note = "",
                        secret = key.secret,
                        settings = settings,
                        balanceToken = record.balanceToken,
                    )
                }
                for (account in record.accounts) {
                    accounts.add(
                        providerId = providerId,
                        label = account.label,
                        username = account.username,
                        password = account.password,
                        loginUrl = account.loginUrl ?: provider.websiteUrl,
                        loginMethods = account.loginMethods,
                    )
                }

                val firstKeyId = keyIds.firstOrNull()
                if (firstKeyId != null) {
                    for (model in record.models) {
                        models.add(providerId, firstKeyId, model.modelId, model.protocol, model.needsReview)
                    }
                }
                count++
            }
        }
        return count
    }
}
