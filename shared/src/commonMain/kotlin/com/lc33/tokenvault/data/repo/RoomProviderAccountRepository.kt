package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.repo.TransactionRunner

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.toUtf8
import com.lc33.tokenvault.crypto.utf8Chars
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.dao.ProviderAccountDao
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.mapper.toLoginMethodsCsv
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.ProviderAccount
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ProviderAccountRepository
import com.lc33.tokenvault.domain.repo.UndoableDeletion
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
class RoomProviderAccountRepository constructor(
    private val dao: ProviderAccountDao,
    private val cipher: FieldCipher,
    private val transactions: TransactionRunner,
    private val now: () -> Long,
    private val audit: AuditLogRepository? = null,
    private val restorer: UndoRestorer? = null,
) : ProviderAccountRepository {

    override fun observeByProvider(providerId: Long): Flow<List<ProviderAccount>> =
        dao.observeByProvider(providerId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(
        providerId: Long,
        label: String,
        username: CharArray?,
        password: CharArray?,
        loginUrl: String?,
        loginMethods: Set<LoginMethod>,
        note: String?,
    ): Long {
        // 空用户名统一成"没有用户名"（null）：`usernameEnc` / `usernameFp` 两列都是可空的，
        // 而 `(providerId, usernameFp)` 是**唯一索引**——留一个空串指纹就会给 `""` 算出一个
        // 真实指纹，于是同一家里第二条"没填用户名"的账号（只有标签 + 密码的账号很常见，
        // 比如只需要登录链接的站点）会被索引挡掉，报一个用户完全看不懂的约束冲突。
        // NULL 不参与唯一性比较，所以两条并存。密码同理：空串不当成"有一个空密码"。
        val username = username?.takeIf { it.isNotEmpty() }
        val password = password?.takeIf { it.isNotEmpty() }
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
                        loginMethods = loginMethods.toLoginMethodsCsv(),
                        note = note?.trim()?.takeIf { it.isNotEmpty() },
                        sortOrder = dao.countByProvider(providerId),
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
            }.also { id ->
                audit.recordSafe(LogLevel.INFO, LogCategory.ACCOUNT, "provider account added", "id=$id", providerId = providerId)
            }
        } finally {
            usernameBytes?.zeroize()
            passwordBytes?.zeroize()
        }
    }

    override suspend fun update(
        id: Long,
        label: String,
        username: CharArray?,
        password: CharArray?,
        loginUrl: String?,
        loginMethods: Set<LoginMethod>,
        note: String?,
    ) {
        requireNotNull(dao.findById(id)) { "provider account $id not found" }
        val usernameBytes = username?.toUtf8()
        val passwordBytes = password?.toUtf8()
        try {
            val usernameFp = usernameBytes
                ?.takeIf { it.isNotEmpty() }
                ?.let { cipher.fingerprint(it) }
            val stamp = now()
            transactions.inTransaction {
                dao.setMeta(
                    id = id,
                    label = label.trim(),
                    loginUrl = loginUrl,
                    loginMethods = loginMethods.toLoginMethodsCsv(),
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    now = stamp,
                )
                username?.let {
                    val bytes = usernameBytes ?: ByteArray(0)
                    dao.setUsername(
                        id = id,
                        enc = if (bytes.isEmpty()) null else cipher.seal(bytes, aadUsername(id)),
                        fp = usernameFp,
                        now = stamp,
                    )
                }
                password?.let {
                    val bytes = passwordBytes ?: ByteArray(0)
                    dao.setPassword(
                        id = id,
                        enc = if (bytes.isEmpty()) null else cipher.seal(bytes, aadPassword(id)),
                        now = stamp,
                    )
                }
            }
            audit.recordSafe(LogLevel.INFO, LogCategory.ACCOUNT, "provider account updated", "id=$id label=${label.trim()}", providerId = dao.findById(id)?.providerId)
        } finally {
            usernameBytes?.zeroize()
            passwordBytes?.zeroize()
        }
    }

    override suspend fun delete(id: Long): UndoableDeletion? {
        val providerId = dao.findById(id)?.providerId
        val undo = restorer?.deleteAccount(id)
        if (restorer == null) dao.delete(id)
        audit.recordSafe(LogLevel.WARN, LogCategory.ACCOUNT, "provider account deleted", "id=$id", providerId = providerId)
        return undo
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

    override suspend fun revealPassword(id: Long): CharArray? {
        val row = requireNotNull(dao.findById(id)) { "provider account $id not found" }
        val enc = row.passwordEnc ?: return null
        val plain = cipher.open(enc, aadPassword(id))
        return try {
            plain.utf8Chars()
        } finally {
            plain.zeroize()
        }
    }

    /**
     * 只改登录方式。
     *
     * 整行替换（[ProviderAccountDao.update]）在这里是安全的：这一行是刚从库里读回来的原样
     * 数据，两段密文与 `usernameFp` 一起写回去，值不变。少开一条只改两列的语句，DAO 里
     * 就少一个"改了 A 忘了 B"的机会。
     */
    override suspend fun setLoginMethods(id: Long, methods: Set<LoginMethod>) {
        val existing = dao.findById(id) ?: throw IllegalStateException("provider account $id not found")
        dao.update(existing.copy(loginMethods = methods.toLoginMethodsCsv(), updatedAt = now()))
        audit.recordSafe(LogLevel.INFO, LogCategory.ACCOUNT, "account login methods updated", "id=$id methods=${methods.joinToString { it.wireName }}", providerId = existing.providerId)
    }

    private fun aadUsername(id: Long) = FieldAad.of(TABLE, id, COLUMN_USERNAME)
    private fun aadPassword(id: Long) = FieldAad.of(TABLE, id, COLUMN_PASSWORD)

    private companion object {
        const val TABLE = "provider_accounts"
        const val COLUMN_USERNAME = "usernameEnc"
        const val COLUMN_PASSWORD = "passwordEnc"
    }
}
