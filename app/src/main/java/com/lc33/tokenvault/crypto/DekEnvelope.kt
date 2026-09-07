package com.lc33.tokenvault.crypto

/**
 * DEK 的包裹槽位（阶段1 迁移后只剩 PIN 一条路）。
 *
 * 阶段1 安全模型简化（迁移计划.md）：删掉生物识别与恢复密钥两条路，只保留 PIN。
 * 包的是**同一个 DEK**（红线 25），所以改 PIN 只重新包裹一次 DEK、一次 boot 写入、
 * 业务表零 UPDATE（红线 2 的 O(1)）。
 */
enum class DekSlot(val storageKey: String) {
    /** PIN / 口令派生的 KEK 包裹。 */
    Pin("pin"),
}

/**
 * DEK 的生成与包裹。
 *
 * 这一层刻意**不知道 KEK 是怎么来的**：PIN 走 PBKDF2，在这里只是"32 字节的 KEK"。
 * 分开的收益是 `crypto/` 完全不必碰 Keystore（阶段1 已删除）。
 *
 * 不设 `dekVerifier`：`wrap` 出来的本身就是 AES-GCM，解包时的 tag 校验已经能判断
 * KEK 对不对，再存一个校验密文只是多一处要维护一致性的状态（§7.1）。
 */
class DekEnvelope(
    private val box: SecretBox = SecretBox(),
    private val random: RandomBytes = SecureRandomBytes,
) {

    /** 随机生成 DEK。一旦生成**永不改变**——改 PIN 只换包裹，不换 DEK（红线 2）。 */
    fun generateDek(): ByteArray = random.nextBytes(DEK_BYTES)

    /**
     * 用 [kek] 包裹 [dek]。
     *
     * 不擦两个入参：DEK 归会话管（红线 6），KEK 归调用方（通常在 `finally` 里擦）。
     */
    fun wrap(dek: ByteArray, kek: ByteArray, slot: DekSlot): ByteArray {
        require(dek.size == DEK_BYTES) { "DEK must be $DEK_BYTES bytes, got ${dek.size}" }
        return box.seal(dek, kek, FieldAad.ofDekSlot(slot.storageKey))
    }

    /**
     * 解包。KEK 不对、密文被改、或者密文被搬到了别的槽位，一律抛
     * [DecryptionFailedException]——解锁页据此显示"PIN 错误"并累加失败计数。
     */
    fun unwrap(wrapped: ByteArray, kek: ByteArray, slot: DekSlot): ByteArray {
        val dek = box.open(
            envelope = wrapped,
            key = kek,
            aad = FieldAad.ofDekSlot(slot.storageKey),
            where = "boot:dek:${slot.storageKey}",
        )
        if (dek.size != DEK_BYTES) {
            dek.zeroize()
            throw DecryptionFailedException("boot:dek:${slot.storageKey} (unwrapped DEK has wrong length)")
        }
        return dek
    }

    companion object {
        const val DEK_BYTES = 32
    }
}
