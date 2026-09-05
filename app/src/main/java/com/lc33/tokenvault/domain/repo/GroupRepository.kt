package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.Group
import kotlinx.coroutines.flow.Flow

/**
 * 仓库接口。**实现在 `data/`，ViewModel 只依赖这一层**（CLAUDE.md 的分层那一节）。
 *
 * 为什么接口放在 `domain/`：这个包是纯 Kotlin，零 Android 依赖。接口写在这里，
 * ViewModel 就无法顺手拿到 Room 实体或 DAO——它连那些类型的名字都看不见。
 * 反过来把接口放 `data/` 的表现是：某天有人在 ViewModel 里 `import ...entity.ProviderEntity`
 * 直接改一行，而那绕过了映射器与加密。
 *
 * 事务与加密都发生在实现里：接口上出现的明文一律是 [CharArray]（可擦，红线 1），
 * 密文一律是 `ByteArray`。
 */
interface GroupRepository {

    /** 分组连带它下面的供应商数。管理页那排筛选 chip 要显示计数。 */
    fun observeGroups(): Flow<List<Group>>

    /** 新建。返回新分组的 id。同名会被唯一索引挡住，调用方要处理失败。 */
    suspend fun add(name: String): Long

    suspend fun rename(id: Long, name: String)

    /**
     * 删除。
     *
     * 供应商**不跟着删**：外键是 `SET NULL`，所以那些供应商回到"未分组"。
     * 反过来（连带删供应商）是不可挽回的删密钥，而用户点"删除分组"时想删的是分组。
     */
    suspend fun delete(id: Long)

    suspend fun reorder(idsInOrder: List<Long>)
}
