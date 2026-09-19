package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.catalog.CatalogParser
import com.lc33.tokenvault.domain.model.CatalogModel
import com.lc33.tokenvault.domain.model.CatalogVendor
import kotlinx.coroutines.flow.Flow

/**
 * models.dev 目录。
 *
 * 这张表和别的不一样：**它不是用户数据，是一份可以随时重下的外部缓存**。所以这里
 * 没有单条增删改，只有整表替换；也没有"删除目录"这个动作——最多是把缓存清掉，
 * 下次同步再拉回来。正因为如此，它不进备份（`backup/` 导出的是用户的库，
 * 4.7 MB 的上游数据跟着走只会让备份包变大变慢）。
 *
 * 目录本身**没有秘密**，也不解密，所以锁定态照样能读——模型页在锁定前后都要显示分组。
 */
interface ModelCatalogRepository {

    /** 目录行数（模型）。0 = 从来没同步成功过。 */
    suspend fun modelCount(): Int

    suspend fun vendorCount(): Int

    /**
     * 整表替换成 [snapshot]，返回写入的模型行数。
     *
     * **必须是一个事务**：目录行和厂商行分两次提交，中间被杀进程就会留下
     * 「新目录 + 旧厂商表」这种没有报错但分组标题全对不上的状态。
     * 清空再写而不是差量 upsert，是因为上游会删模型；只 upsert 的话那些被上游
     * 撤下的条目会永久留在本地，界面上继续显示一个已经不存在的模型。
     */
    suspend fun replace(snapshot: CatalogParser.Result): Int

    /**
     * 重算全库 `models.catalogKey`，返回挂上目录的行数。
     *
     * 为什么每次同步都要全量重算而不是只补空的那些：上游会把模型从一家挪到另一家、
     * 也会给先前没有的厂商补条目，只补空的话第一次同步之后新挂上的候选永远进不来。
     * 694 行对 7.8k 行目录，内存里三份哈希一遍扫过去是毫秒级的事。
     */
    suspend fun rekeyAllModels(): Int

    /**
     * 单个模型的三级匹配。回填走的是内存里的批量版本，这一条给"刚发现一个新模型、
     * 只想知道它属于谁"那种增量场合（`probe/ModelMerger` 落库之后）。
     *
     * @param vendorHint 供应商名 / host 的消歧提示，见 [com.lc33.tokenvault.catalog.ModelCatalogMatcher]。
     */
    suspend fun lookup(modelId: String, vendorHint: String?): CatalogModel?

    /**
     * 给一把 Key 下**还没挂上目录**的模型行补 `catalogKey`，返回补上的行数。
     *
     * 存在的理由是"刷新模型列表"这条路：上游新发现了 30 个模型，如果不顺手匹配，
     * 它们会一直待在未识别组里，直到下一次目录同步（最坏 7 天）才突然长出厂商和价格——
     * 用户会把这看成 bug。
     *
     * **只扫 `catalogKey IS NULL` 的行**：已经挂上的那些交给 [rekeyAllModels] 在每次同步后
     * 统一重算，这里再查一遍是纯粹的浪费。走的是三条索引查询而不是 [rekeyAllModels] 那套
     * 内存哈希，因为一次刷新通常只新增几十个模型，为它们把 7.8k 行目录读进内存不划算。
     */
    suspend fun rekeyUnkeyedModelsOfKey(providerId: Long, keyId: Long): Int

    /** 按主键取回一批目录行（模型页把整页的元数据一次捞出来用）。 */
    suspend fun findByKeys(keys: Collection<String>): Map<String, CatalogModel>

    /** 整表清掉（只给"重置缓存"这类操作用；日常不需要）。 */
    suspend fun clear()

    /** 厂商列表，按展示名排好序。 */
    fun observeVendors(): Flow<List<CatalogVendor>>
}
