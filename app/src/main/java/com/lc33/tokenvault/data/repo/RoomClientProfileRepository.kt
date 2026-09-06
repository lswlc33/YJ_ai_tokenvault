package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ClientProfileDao
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 客户端伪装预设。
 *
 * 预设是**数据不是代码**（红线 22），所以这里只是一个薄仓库：读走 `Flow`，写走 DAO。
 * 没有加解密——预设里不该有秘密，`headers` / `bodyPatch` 都是公开的指纹信息。
 * 唯一的保护是 [deleteCustom] 那条 SQL 带了 `builtinKey IS NULL`，内置预设删不掉。
 */
@Singleton
class RoomClientProfileRepository @Inject constructor(
    private val dao: ClientProfileDao,
) : ClientProfileRepository {

    override fun observeAll(): Flow<List<ClientProfile>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun findById(id: Long): ClientProfile? = dao.findById(id)?.toDomain()

    override suspend fun findByBuiltinKey(builtinKey: String): ClientProfile? =
        dao.findByBuiltinKey(builtinKey)?.toDomain()

    override suspend fun add(profile: ClientProfile): Long = dao.insert(profile.toEntity())

    override suspend fun update(profile: ClientProfile) = dao.update(profile.toEntity())

    override suspend fun deleteCustom(id: Long) = dao.deleteCustom(id)
}
