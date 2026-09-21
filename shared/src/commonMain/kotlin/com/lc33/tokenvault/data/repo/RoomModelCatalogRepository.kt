package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.catalog.ModelCatalogMatcher
import com.lc33.tokenvault.catalog.CatalogNormalize
import com.lc33.tokenvault.catalog.CatalogParser
import com.lc33.tokenvault.data.dao.ModelCatalogDao
import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.dao.ModelVendorDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.data.mapper.toMatcherEntry
import com.lc33.tokenvault.domain.model.CatalogModel
import com.lc33.tokenvault.domain.model.CatalogVendor
import com.lc33.tokenvault.domain.repo.ModelCatalogRepository
import com.lc33.tokenvault.domain.repo.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * models.dev 目录的 Room 实现。
 *
 * 这张表没有秘密、不加密，所以锁定态也能读写（`engine/CatalogSync` 在解锁之后才跑，
 * 但读路径不限）。写入方只有 [replace] 与 [rekeyAllModels] 两处。
 */
class RoomModelCatalogRepository constructor(
    private val catalogDao: ModelCatalogDao,
    private val vendorDao: ModelVendorDao,
    private val modelDao: ModelDao,
    private val providerDao: ProviderDao,
    private val transactions: TransactionRunner,
) : ModelCatalogRepository {

    override suspend fun modelCount(): Int = catalogDao.count()

    override suspend fun vendorCount(): Int = vendorDao.count()

    override suspend fun replace(snapshot: CatalogParser.Result): Int = transactions.inTransaction {
        // 先清后写，且两张表在同一次事务里：见接口上那段"新目录 + 旧厂商表"的理由。
        catalogDao.clear()
        vendorDao.clear()
        snapshot.models.chunked(CHUNK).forEach { catalogDao.upsertAll(it.map { row -> row.toEntity() }) }
        vendorDao.upsertAll(snapshot.vendors.map { it.toEntity() })
        snapshot.models.size
    }

    /**
     * 全库回填。
     *
     * 逐行走三级查询是「模型行数 × 3 次索引查询」——用户那份库是 694 行，两千次查询
     * 加上每次的 IPC 就成了「刷一次目录等一下」。改成一次把 7.8k 行目录读进内存、
     * 建三份哈希，剩下的全是内存查表。
     *
     * 只写**变了的那些行**：全库 694 行原样写回会让 `observeAll()` 的流重发一轮，
     * 界面上所有正在显示的列表都闪一次，而其中大多数行什么都没变。
     */
    override suspend fun rekeyAllModels(): Int {
        val catalog = catalogDao.findAll()
        if (catalog.isEmpty()) return 0
        // 一次投影成匹配器要的纯数据，然后建三份哈希。
        // 逐行 toMatcherEntry 再 groupBy 的顺序不能反：groupBy 完再 map 会让同一批
        // 实体被反复投影，而这里是 7.8k 行。
        val entries = catalog.map { it.toMatcherEntry() }
        val byQualifiedId = entries.groupBy { it.qualifiedId }
        val byModelId = entries.groupBy { it.modelId }
        val byNormId = entries.groupBy { it.normId }
        val hintByProvider = providerDao.findAll().associate { it.id to it.name }

        val changed = modelDao.findAll().mapNotNull { row ->
            val key = matchKey(
                modelId = row.modelId,
                vendorHint = hintByProvider[row.providerId],
                byQualifiedId = byQualifiedId,
                byModelId = byModelId,
                byNormId = byNormId,
            )
            // 空串和 null 等价：都是"没挂上目录"。上游哪天把某个模型撤了，回填要能把它
            // 从挂上变成没挂上，所以这里不能只写"新值非空才更新"。
            val next = key ?: ""
            row.takeIf { (it.catalogKey ?: "") != next }?.copy(catalogKey = key)
        }
        if (changed.isEmpty()) return 0
        transactions.inTransaction {
            changed.chunked(CHUNK).forEach { modelDao.updateAll(it) }
        }
        return changed.count { it.catalogKey != null }
    }

    /**
     * 单个模型的匹配走 DAO 的三条索引查询，不走 [rekeyAllModels] 那套内存哈希。
     *
     * 理由很直接：这条的调用方是"刚发现一个模型"，为了一行把 7.8k 行目录读进内存
     * 是纯粹的浪费；那套批量只在整库回填时才划算。
     */
    override suspend fun lookup(modelId: String, vendorHint: String?): CatalogModel? {
        val bare = modelId.substringAfterLast('/')
        val byQualifiedId = if (modelId.contains('/')) {
            catalogDao.findByQualifiedId(modelId).map { it.toMatcherEntry() }
        } else {
            emptyList()
        }
        val hit = ModelCatalogMatcher.match(
            modelId = modelId,
            vendorHint = vendorHint,
            byQualifiedId = byQualifiedId,
            byModelId = catalogDao.findByModelId(modelId).map { it.toMatcherEntry() },
            byNormId = catalogDao.findByNormId(CatalogNormalize.normalize(bare)).map { it.toMatcherEntry() },
        )
        return hit?.let { entry -> catalogDao.findByKey(entry.key)?.toDomain() }
    }

    /**
     * 批量取目录条目，一次 IN 一批，而不是每个键一次查询。
     *
     * 原来这里是 `keys.mapNotNull { findByKey(it) }`：openrouter 那把 Key 有 445 个模型，
     * 就是 445 次 prepared statement 加 445 次调度，模型页要等它跑完才画得出能力 chip。
     * 分批 400 个一批是不赌 SQLite 的绑定变量上限（老一些的版本是 999）。
     */
    override suspend fun findByKeys(keys: Collection<String>): Map<String, CatalogModel> {
        if (keys.isEmpty()) return emptyMap()
        return keys.distinct()
            .chunked(400)
            .flatMap { chunk -> catalogDao.findByKeysIn(chunk) }
            .associate { it.key to it.toDomain() }
    }

    /**
     * 只补 `catalogKey IS NULL` 的行，且每行走三条索引查询。
     *
     * 与 [rekeyAllModels] 的分工：这里管"刚刷新出来的那几十行"，那里管"每次同步后的全库
     * 重算"。两边都用最优于自己那个规模的查法——反过来用就分别是"为几十行读 7.8k 行"
     * 和"为几百行走上千次查询"。
     */
    override suspend fun rekeyUnkeyedModelsOfKey(providerId: Long, keyId: Long): Int {
        val rows = modelDao.findUnkeyedByProviderAndKey(providerId, keyId)
        if (rows.isEmpty()) return 0
        val hint = providerDao.findById(providerId)?.name
        val updated = rows.mapNotNull { row ->
            lookup(modelId = row.modelId, vendorHint = hint)?.key?.let { row.copy(catalogKey = it) }
        }
        if (updated.isEmpty()) return 0
        transactions.inTransaction { updated.chunked(CHUNK).forEach { modelDao.updateAll(it) } }
        return updated.size
    }

    override suspend fun clear() = transactions.inTransaction {
        catalogDao.clear()
        vendorDao.clear()
    }

    override fun observeVendors(): Flow<List<CatalogVendor>> =
        vendorDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** 三级匹配的内存版：候选来自上面那三份哈希，决策仍然交给纯函数。 */
    private fun matchKey(
        modelId: String,
        vendorHint: String?,
        byQualifiedId: Map<String, List<ModelCatalogMatcher.CatalogEntry>>,
        byModelId: Map<String, List<ModelCatalogMatcher.CatalogEntry>>,
        byNormId: Map<String, List<ModelCatalogMatcher.CatalogEntry>>,
    ): String? =
        // qualifiedId 这一级只有含斜杠的输入才可能命中（键本身就是 `vendor/model`），
        // 裸 id 传进去查只会得到空列表，白跑一次哈希。
        ModelCatalogMatcher.match(
            modelId = modelId,
            vendorHint = vendorHint,
            byQualifiedId = if (modelId.contains('/')) byQualifiedId[modelId].orEmpty() else emptyList(),
            byModelId = byModelId[modelId].orEmpty(),
            byNormId = byNormId[CatalogNormalize.normalize(modelId.substringAfterLast('/'))].orEmpty(),
        )?.key

    private companion object {
        /** 单批写入的行数。SQLite 每条语句的绑定参数有上限，一次塞 7.8k 行会撞。 */
        const val CHUNK = 500
    }
}
