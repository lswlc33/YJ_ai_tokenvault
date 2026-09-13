package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.ApiKeyWithSettingsRow
import com.lc33.tokenvault.data.dao.ClientProfileDao
import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.dao.KeySettingsDao
import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.dao.ProviderAccountDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.entity.KeySettingsEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.domain.repo.UndoableDeletion

/**
 * 「删除 + 可撤销」的共用实现。
 *
 * 四个可撤销的删除（供应商 / 密钥 / 模型 / 平台账号）都走这里，而不是各自在仓库里写一遍
 * 「先读快照、按外键顺序写回」——那条顺序错了只在真设备上炸（外键约束），
 * 集中一处才守得住。
 *
 * 三条贯穿全部写回的规则：
 *
 * 1. **按原主键写回。** 现有 `@Insert` 生成的 SQL 是 `VALUES (nullif(?, 0), …)`，
 *    传 `id != 0` 即写显式主键。这不是巧合而是必需：字段级密文的 AAD 绑定
 *    `表:主键:列`（红线 24），换一个 id 写回会让 `secretEnc` / `balanceTokenEnc` /
 *    `usernameEnc` 全部解不开。
 * 2. **先父后子。** Room 默认开 `PRAGMA foreign_keys`，写回顺序必须是
 *    provider → api_keys → key_settings → models / provider_accounts。
 * 3. **失败即整体回滚。** 写回在事务里跑，遇到唯一索引冲突直接抛，由事务回滚并让
 *    [UndoableDeletion.undo] 返回 false，而不是留下"恢复了一半"的库。
 *
 * 构造器非空、且只在真实现里注入；JVM 单测用假 DAO 构造仓库时不传它，于是 delete
 * 只是删除、返回 null（没有撤销句柄），不会因为缺 DAO 而崩。
 */
class UndoRestorer(
    private val providerDao: ProviderDao,
    private val apiKeyDao: ApiKeyDao,
    private val settingsDao: KeySettingsDao,
    private val modelDao: ModelDao,
    private val accountDao: ProviderAccountDao,
    private val groupDao: GroupDao,
    private val profileDao: ClientProfileDao,
    private val transactions: TransactionRunner,
) {
    /** 删除一把 Key（级联带走 key_settings 与它的模型），返回撤销句柄。 */
    suspend fun deleteKey(id: Long): UndoableDeletion? {
        val row = apiKeyDao.findById(id)
        if (row == null) {
            apiKeyDao.delete(id)
            return null
        }
        val models = modelDao.findByProviderAndKey(row.key.providerId, id)
        apiKeyDao.delete(id)
        return UndoableDeletion {
            runCatching {
                transactions.inTransaction {
                    if (providerDao.findById(row.key.providerId) == null) {
                        error("provider ${row.key.providerId} no longer exists")
                    }
                    apiKeyDao.insertRaw(row.key)
                    row.settings?.let { settingsDao.insert(it.orDropDeadProfile()) }
                    insertModels(models)
                }
            }.isSuccess
        }
    }

    /** 删除一个模型，返回撤销句柄。 */
    suspend fun deleteModel(id: Long): UndoableDeletion? {
        val snapshot = modelDao.findById(id)
        if (snapshot == null) {
            modelDao.delete(id)
            return null
        }
        modelDao.delete(id)
        return UndoableDeletion {
            runCatching {
                transactions.inTransaction { insertModels(listOf(snapshot)) }
            }.isSuccess
        }
    }

    /** 删除一条平台账号，返回撤销句柄。 */
    suspend fun deleteAccount(id: Long): UndoableDeletion? {
        val snapshot = accountDao.findById(id)
        if (snapshot == null) {
            accountDao.delete(id)
            return null
        }
        accountDao.delete(id)
        return UndoableDeletion {
            runCatching {
                transactions.inTransaction {
                    // 父供应商可能在同一次操作里也被删了，外键会拒绝——那就是撤销失败。
                    accountDao.insert(snapshot)
                }
            }.isSuccess
        }
    }

    /** 删除一个供应商（级联带走 Key、KeySettings、模型、账号），返回撤销句柄。 */
    suspend fun deleteProvider(id: Long): UndoableDeletion? {
        val provider = providerDao.findById(id)
        if (provider == null) {
            providerDao.delete(id)
            return null
        }
        val keys = apiKeyDao.findByProvider(id)
        val models = modelDao.findByProvider(id)
        val accounts = accountDao.findByProvider(id)
        providerDao.delete(id)
        return UndoableDeletion {
            runCatching {
                transactions.inTransaction {
                    restoreProvider(provider, keys, models, accounts)
                }
            }.isSuccess
        }
    }

    private suspend fun restoreProvider(
        provider: ProviderEntity,
        keys: List<ApiKeyWithSettingsRow>,
        models: List<ModelEntity>,
        accounts: List<ProviderAccountEntity>,
    ) {
        // 分组可能在供应商删除之后也被删了（删分组只把 provider.groupId 置空，但那发生在
        // provider 已不存在之后，所以这里必须自己兜住这个悬空引用，否则外键会拒绝整次恢复）。
        val groupId = provider.groupId?.takeIf { groupDao.findById(it) != null }
        providerDao.insert(provider.copy(groupId = groupId))
        for (row in keys) {
            apiKeyDao.insertRaw(row.key)
            row.settings?.let { settingsDao.insert(it.orDropDeadProfile()) }
        }
        insertModels(models)
        for (account in accounts) accountDao.insert(account)
    }

    private suspend fun insertModels(models: List<ModelEntity>) {
        for (model in models) {
            // 必须看返回值：insertIgnoring 遇唯一冲突不抛异常、只返回 -1，
            // 沿用"没抛错即成功"会把模型静默丢掉，用户看到"已恢复"却少了几行。
            val id = modelDao.insertIgnoring(model)
            if (id <= 0L) error("model ${model.modelId} could not be restored")
        }
    }

    /**
     * 客户端预设是 `SET_NULL` 外键，可能在这期间被删掉。把它置空继续恢复，比让整次撤销
     * 失败更有用——密钥本身、端点、协议都在，只丢一个预设选择。
     */
    private suspend fun KeySettingsEntity.orDropDeadProfile(): KeySettingsEntity {
        val profileId = clientProfileId ?: return this
        return if (profileDao.findById(profileId) != null) this else copy(clientProfileId = null)
    }
}
