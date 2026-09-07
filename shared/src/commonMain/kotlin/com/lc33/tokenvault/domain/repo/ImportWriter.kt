package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.importer.ParsedRecord
import com.lc33.tokenvault.importer.toProvider

/**
 * 文本导入的落库编排（§11.2：确认后在**单个事务**里写入）。
 *
 * 一条记录的写入顺序：供应商 → 密钥（第一张自动默认，靠 [ApiKeyRepository.add] 的
 * 默认 Key 不变量）→ 平台账号 → 模型。这个顺序不是随意的：
 *
 * - 密钥 / 账号 / 模型都有外键指向供应商，必须先有 `providerId`。
 * - 密钥第一张默认这件事是 `ApiKeyRepository.add` 自己保证的（§6.3），这里不用也不该
 *   再去设默认——重复设一遍等于给"两张默认"留了第二个入口。
 *
 * 整批放在 [TransactionRunner] 里：内层各仓库的 `inTransaction` 会 join 外层事务
 * （Room 的 `withTransaction` 支持嵌套），所以"半批成功"这个状态不会被观察到，
 * 也不会在崩溃后留在库里。
 */
class ImportWriter constructor(
    private val providers: ProviderRepository,
    private val keys: ApiKeyRepository,
    private val accounts: ProviderAccountRepository,
    private val models: ModelRepository,
    private val transactions: TransactionRunner,
) {

    /** 写入一批解析结果。返回成功写了几条供应商（供 UI 提示"导入完成 N 家"）。 */
    suspend fun write(records: List<ParsedRecord>): Int {
        var count = 0
        transactions.inTransaction {
            for (record in records) {
                val provider = record.toProvider() ?: continue
                val providerId = providers.save(provider, record.balanceToken)

                for (key in record.keys) {
                    keys.add(providerId, key.label, key.secret)
                }
                for (account in record.accounts) {
                    accounts.add(
                        providerId = providerId,
                        label = account.label,
                        username = account.username,
                        password = account.password,
                        loginUrl = account.loginUrl ?: provider.websiteUrl,
                    )
                }
                for (model in record.models) {
                    models.add(providerId, model.modelId, model.protocol, model.needsReview)
                }
                count++
            }
        }
        return count
    }
}
