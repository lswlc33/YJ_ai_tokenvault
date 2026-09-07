package com.lc33.tokenvault.data.repo

import androidx.room.Transactor
import androidx.room.useWriterConnection
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.SecretFingerprint
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.platform.VaultSession

/**
 * 事务边界（接口在 shared 的 domain/repo/TransactionRunner.kt）。
 *
 * Room KMP 的 commonMain 没有 room-ktx 那个顶层 `db.withTransaction`，等价物是
 * `useWriterConnection { it.withTransaction { … } }`：拿一条写连接，在上面开事务。
 * 块内的 DAO 挂起调用通过协程上下文自动汇入同一条连接，与 room-ktx 的线程局部
 * 事务语义等价。事务类型用 IMMEDIATE：业务里的事务都是"读一遭再写"，DEFERRED
 * 在读后升级写锁时可能撞 SQLITE_BUSY；和 Android 版 room-ktx 的行为对齐
 * （它内部也是 IMMEDIATE）。
 */
class RoomTransactionRunner constructor(
    private val db: VaultDatabase,
) : TransactionRunner {
    override suspend fun <R> inTransaction(block: suspend () -> R): R =
        db.useWriterConnection {
            it.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        }
}

/**
 * 字段级加解密的收口。
 *
 * 每次都经过 [VaultSession] 借密钥（红线 6：DEK 只由唯一会话对象持有，他人短暂借用），
 * 所以锁定态调用它一定抛 `VaultLockedException`，而不是拿到一把过期的子密钥。
 * 把 `withFieldKey` 的三个用法收在这里，是为了让"忘了绑 AAD"没有藏身处——
 * 这个类的每个方法都强制要求一个 [FieldAad]（红线 24）。
 */
class FieldCipher constructor(
    private val session: VaultSession,
    private val box: SecretBox,
) {

    fun seal(plaintext: ByteArray, aad: FieldAad): ByteArray =
        session.withFieldKey { key -> box.seal(plaintext, key, aad) }

    /** 解密失败一律抛（红线 8）。`where` 用 AAD 本身：它已经是"表:行:列"，正是要的定位信息。 */
    fun open(envelope: ByteArray, aad: FieldAad): ByteArray =
        session.withFieldKey { key -> box.open(envelope, key, aad, aad.toString()) }

    fun fingerprint(plaintext: ByteArray): String =
        session.withFingerprintKey { key -> SecretFingerprint.of(plaintext, key) }
}
