package com.lc33.tokenvault.backup

import com.lc33.tokenvault.crypto.ByteArrayAsBase64
import com.lc33.tokenvault.crypto.KdfParams
import kotlinx.serialization.Serializable

/**
 * 备份包的明文 header（§12.1）。
 *
 * 它要能被肉眼 + 任何 JSON 工具读懂，因为它是"备份口令忘了 / 包损坏"时唯一的诊断入口。
 * KDF 参数、盐、nonce 都在这里——所以**任何设备**都能照着它重派生密钥（红线 7）。
 *
 * [format] / [schema] 用于版本校验：高于当前版本拒绝并提示升级，低于走显式迁移（红线 9）。
 */
@Serializable
data class BackupHeader(
    val format: Int = FORMAT_VERSION,
    val schema: Int = SCHEMA_VERSION,
    val createdAt: Long,
    val deviceId: String,
    val revision: Long,
    val kdf: KdfParams,
    val cipher: String = CIPHER_AES_256_GCM,
    @Serializable(with = ByteArrayAsBase64::class) val nonce: ByteArray,
    val itemCounts: BackupItemCounts = BackupItemCounts(),
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val SCHEMA_VERSION = 1
        const val CIPHER_AES_256_GCM = "aes-256-gcm"
        const val NONCE_BYTES = 12
    }
}

/** 各表条目数，恢复进度展示用。 */
@Serializable
data class BackupItemCounts(
    val providers: Int = 0,
    val keys: Int = 0,
    val accounts: Int = 0,
    val models: Int = 0,
    val profiles: Int = 0,
)
