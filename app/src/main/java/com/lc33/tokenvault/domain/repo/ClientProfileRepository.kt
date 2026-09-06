package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.ClientProfile
import kotlinx.coroutines.flow.Flow

/**
 * 客户端伪装预设（§8.2）。
 *
 * 预设是**数据不是代码**（红线 22）：探测代码里不允许硬编码任何 `User-Agent` 或特征头，
 * 一律从这里读。内置预设与用户自定义同表，靠 `builtinKey` 区分。
 *
 * 只读 `Flow` 就够：预设的增删改走 [add] / [update] / [deleteCustom]，`deleteCustom`
 * 的 SQL 带了 `builtinKey IS NULL`，所以内置预设删不掉（只能编辑）。
 */
interface ClientProfileRepository {

    fun observeAll(): Flow<List<ClientProfile>>

    suspend fun findById(id: Long): ClientProfile?

    /** 按 `builtinKey` 找。`ProfileSeeder` 与 `HeaderAssembler` 都靠它。 */
    suspend fun findByBuiltinKey(builtinKey: String): ClientProfile?

    /** 新增（用户自定义或 cURL 导入）。 */
    suspend fun add(profile: ClientProfile): Long

    /** 更新。`userEdited` 由调用方决定——用户手动改过就该置 true，升级才不覆盖。 */
    suspend fun update(profile: ClientProfile)

    /** 删自定义预设。内置（`builtinKey` 非空）删不掉。 */
    suspend fun deleteCustom(id: Long)
}
