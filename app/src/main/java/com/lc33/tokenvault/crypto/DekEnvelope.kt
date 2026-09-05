package com.lc33.tokenvault.crypto

/**
 * DEK 的包裹槽位（§7.1 的三条路径）。
 *
 * 三条路径包的是**同一个 DEK**（红线 25），所以加一条只是多一次 wrap，业务数据零改动。
 * 这也是"改 PIN 必须 O(1)"（红线 2）的实现前提：改 PIN 只重新包裹一次 DEK，
 * 一次 boot 写入，业务表零 UPDATE。
 */
enum class DekSlot(val storageKey: String) {
    /** PIN / 口令派生的 KEK 包裹。 */
    Pin("pin"),

    /** Keystore 硬件密钥包裹（别名 `vault_bio`，`setUserAuthenticationRequired(true)`）。 */
    Biometric("biometric"),

    /** 恢复密钥派生的 KEK 包裹。解决"忘记 6 位 PIN 导致整库不可读"这个最可能真实发生的事故。 */
    Recovery("recovery"),
}

/**
 * DEK 的生成与包裹。
 *
 * 这一层刻意**不知道 KEK 是怎么来的**：PIN 走 Argon2id、生物识别走 Keystore、
 * 恢复密钥走 Argon2id，三者在这里都只是"32 字节的 KEK"。分开的收益是
 * `crypto/` 完全不必碰 Keystore（那是 `platform/` 的事，也是 Android 依赖的来源）。
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
