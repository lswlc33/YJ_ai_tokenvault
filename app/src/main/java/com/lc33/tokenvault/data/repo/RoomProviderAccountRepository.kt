package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.toUtf8
import com.lc33.tokenvault.crypto.utf8Chars
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.dao.ProviderAccountDao
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.di.NowEpochMs
import com.lc33.tokenvault.domain.model.ProviderAccount
import com.lc33.tokenvault.domain.repo.ProviderAccountRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 平台账号。
 *
 * 用户名与密码**都加密**（红线 21：只加密密码等于把邮箱 / 手机号留在明文里）。
 * 两列各有各的 AAD（`usernameEnc` 与 `passwordEnc`），否则用户名密文能被搬到密码列上。
 *
 * 和密钥表同一个理由，新增是**两步写**（红线 24）：`usernameEnc` / `passwordEnc` 的 AAD
 * 绑主键，而主键是插入时才分配的。先插入拿到 id，再用真 AAD 加密回填，包在事务里。
 */
@Singleton
class RoomProviderAccountRepository @Inject constructor(
    private val dao: ProviderAccountDao,
    private val cipher: FieldCipher,
    private val transactions: TransactionRunner,
    @param:NowEpochMs private val now: () -> Long,
) : ProviderAccountRepository {

    override fun observeByProvider(providerId: Long): Flow<List<ProviderAccount>> =
        dao.observeByProvider(providerId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(
        providerId: Long,
        label: String,
        username: CharArray?,
        password: CharArray?,
        loginUrl: String?,
    ): Long {
        val usernameBytes = username?.toUtf8()
        val passwordBytes = password?.toUtf8()
        return try {
            // 用户名指纹不依赖 id，插入前算好；`(providerId, usernameFp)` 唯一索引
            // 因此在插入那一刻就能挡住重复录入
            val usernameFp = usernameBytes?.let { cipher.fingerprint(it) }
            val stamp = now()
            transactions.inTransaction {
                val id = dao.insert(
                    ProviderAccountEntity(
                        providerId = providerId,
                        label = label.trim(),
                        usernameEnc = null,
                        usernameFp = usernameFp,
                        passwordEnc = null,
                        loginUrl = loginUrl,
                        sortOrder = dao.findAll().count { it.providerId == providerId },
                        createdAt = stamp,
                        updatedAt = stamp,
                    ),
                )
                usernameBytes?.let { bytes ->
                    dao.setUsername(id, cipher.seal(bytes, aadUsername(id)), usernameFp!!, stamp)
                }
                passwordBytes?.let { bytes ->
                    dao.setPassword(id, cipher.seal(bytes, aadPassword(id)), stamp)
                }
                id
            }
        } finally {
            usernameBytes?.zeroize()
            passwordBytes?.zeroize()
        }
    }

    override suspend fun revealUsername(id: Long): CharArray? {
        val row = requireNotNull(dao.findById(id)) { "provider account $id not found" }
        val enc = row.usernameEnc ?: return null
        val plain = cipher.open(enc, aadUsername(id))
        return try {
            plain.utf8Chars()
        } finally {
            plain.zeroize()
        }
    }

    private fun aadUsername(id: Long) = FieldAad.of(TABLE, id, COLUMN_USERNAME)
    private fun aadPassword(id: Long) = FieldAad.of(TABLE, id, COLUMN_PASSWORD)

    private companion object {
        const val TABLE = "provider_accounts"
        const val COLUMN_USERNAME = "usernameEnc"
        const val COLUMN_PASSWORD = "passwordEnc"
    }
}
