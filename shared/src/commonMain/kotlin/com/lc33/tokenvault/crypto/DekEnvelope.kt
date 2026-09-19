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
 * **过去"不设 dekVerifier"这条只对 PIN 路径成立**：`wrap` 出来的本身就是 AES-GCM，
 * 解包时的 tag 校验已经能判断 KEK 对不对，再存一份校验密文只是多一处要维护一致性的状态。
 * 但**平台 DEK 那条路（生物识别把 DEK 原样交回）没有任何 tag 可验**——拿到 32 字节就算成功，
 * 于是"Keystore / Keychain 给回了别的东西"会变成"解锁成功但字段密文全解不开"。
 * 所以那条路要用 [sealCheck] / [verifyCheck] 这一份独立校验密文（`boot.dekCheck`）。
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

        /**
         * 校验密文里那份**固定明文**。ASCII，不进 strings.xml（它不是给用户看的文案，
         * 而是密码学常量，跟着语言变等于让所有已引导设备的校验失效）。
         * 私有：暴露出去等于给人一份可写的数组常量。
         */
        private val CHECK_PLAINTEXT: ByteArray = "yuanji/dek-check/v1/pinned".encodeToByteArray()
    }

    /**
     * 生成 `boot.dekCheck` —— 平台 DEK 那条路的身份校验密文。
     *
     * 用 `HKDF(DEK, "yuanji/dekcheck/v1")` 而不是 DEK 本身作密钥（`crypto/Hkdf` 开头那条
     * "DEK 从不直接参与加密"的规矩在这里同样成立）。明文固定、AAD 固定，所以只有 IV 变：
     * 每次引导 / 改 PIN 都会得到一份新密文，这不影响校验，因为校验的是解出来的内容。
     *
     * 不擦 [dek]：它归会话管（红线 6）。
     */
    fun sealCheck(dek: ByteArray): ByteArray {
        require(dek.size == DEK_BYTES) { "DEK must be $DEK_BYTES bytes, got ${dek.size}" }
        val checkKey = Hkdf.dekCheckKey(dek)
        try {
            return box.seal(CHECK_PLAINTEXT, checkKey, FieldAad.ofDekCheck())
        } finally {
            checkKey.zeroize()
        }
    }

    /**
     * 校验平台给回的 [dek] 是不是**当初那一份**。
     *
     * 返回 false 而不是抛：这里的"失败"是"这条路拿回来的东西不对"，调用方要据此把会话
     * 判为不可用并清掉那份 DEK，而不需要知道是 tag 不对还是版本不对（两种都同样不可用）。
     * 但任何**结构性**异常（封套版本不认识）照样往上抛，因为它意味着 boot 被改坏了，
     * 与"凭据不对"是两件事，不该被混成一次解锁失败。
     */
    fun verifyCheck(dek: ByteArray, check: ByteArray): Boolean {
        if (dek.size != DEK_BYTES) return false
        if (!isKnownSecretBoxEnvelope(check)) {
            throw UnsupportedEnvelopeException("unknown envelope version ${check.firstOrNull()} at boot:dek-check")
        }
        val checkKey = Hkdf.dekCheckKey(dek)
        return try {
            val plain = box.open(check, checkKey, FieldAad.ofDekCheck(), "boot:dek-check")
            try {
                plain.contentEquals(CHECK_PLAINTEXT)
            } finally {
                // 解出来的那份固定明文没有用处，留着的唯一代价是它可能被误当成"某个密钥"
                plain.zeroize()
            }
        } catch (_: DecryptionFailedException) {
            false
        } finally {
            checkKey.zeroize()
        }
    }
}
