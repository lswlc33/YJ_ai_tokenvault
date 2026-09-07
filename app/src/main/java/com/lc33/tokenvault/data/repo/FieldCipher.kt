package com.lc33.tokenvault.data.repo

import androidx.room.withTransaction
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.SecretFingerprint
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.platform.VaultSession

/**
 * 事务边界。
 *
 * 抽成接口而不是直接在仓库里用 `db.withTransaction {}`，是为了让仓库能在 **JVM 单测**里跑：
 * 本项目没有 Robolectric，Room 在 JVM 上起不来，所以测试里换一个"直接执行 block"的实现，
 * 用假 DAO 验仓库自己的逻辑（映射、AAD 绑对没绑对、两步写的顺序）。
 *
 * 这条取舍要说清代价：**SQL 层面的东西这么测不到**——外键 CASCADE、部分唯一索引
 * `idx_keys_default`、`(providerId, fingerprint)` 唯一约束，都只有真设备（或 Robolectric）
 * 才能验。§14.3 里那几项因此仍然挂在仪器测试上。
 */
interface TransactionRunner {
    suspend fun <R> inTransaction(block: suspend () -> R): R
}

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
