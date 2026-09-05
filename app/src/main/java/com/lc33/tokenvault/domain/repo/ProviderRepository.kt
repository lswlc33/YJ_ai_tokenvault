package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import kotlinx.coroutines.flow.Flow

/**
 * 供应商。
 *
 * `balanceTokenEnc` 那一列是**密文**，所以新增与保存要额外接一个明文参数：
 * 加密发生在实现里（它借 DEK），领域模型上永远只有密文（§6.1 推论 3）。
 */
interface ProviderRepository {

    /** 管理页与仪表盘计数都读这一条聚合查询，而不是各自去数（否则两个页面的数字会对不上）。 */
    fun observeSummaries(): Flow<List<ProviderSummary>>

    fun observeProvider(id: Long): Flow<Provider?>

    suspend fun find(id: Long): Provider?

    /**
     * 新增或保存。返回这一行的 id（新增时是新分配的）。
     *
     * @param balanceToken NewAPI 那类适配器要用的独立访问令牌**明文**。
     *   - `null` = 不动这一列（编辑时用户没碰那个输入框）；
     *   - 空数组 = 清掉它；
     *   - 非空 = 用当前 DEK 重新加密后覆盖。
     *
     *   三档而不是两档是必要的：编辑页拿不到已存的明文（它是密文，只有需要发请求时才解开），
     *   所以"输入框是空的"不能当成"用户要清掉令牌"——那会让每次改备注都顺手把令牌删掉。
     *   调用方负责在返回后擦掉自己那份明文。
     */
    suspend fun save(provider: Provider, balanceToken: CharArray? = null): Long

    /** 删除。连带删这家的密钥、账号、模型（外键 CASCADE），所以调用方必须先做二次确认。 */
    suspend fun delete(id: Long)

    suspend fun setGroup(ids: List<Long>, groupId: Long?)
}
