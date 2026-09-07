package com.lc33.tokenvault.data.repo

import androidx.room.withTransaction
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.SecretFingerprint
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.platform.VaultSession

/**
 * 事务边界（接口在 shared 的 domain/repo/TransactionRunner.kt）。
 *
 * Room 实现依赖 `db.withTransaction`，所以留在这里（data 层）。
 */
class RoomTransactionRunner constructor(
    private val db: VaultDatabase,
) : TransactionRunner {
    override suspend fun <R> inTransaction(block: suspend () -> R): R = db.withTransaction { block() }
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
