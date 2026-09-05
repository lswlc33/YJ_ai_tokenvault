package com.lc33.tokenvault.crypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * `ByteArray` ↔ base64 字符串。
 *
 * 不用 kotlinx.serialization 的默认行为（一个 JSON 数字数组）：一份 16 字节的盐会变成
 * 16 个数字加逗号，一份包裹后的 DEK 上百字节，于是 boot 文件从"能一眼看懂"变成没法用
 * 眼睛检查。而 boot 文件是**唯一在出问题时只能靠肉眼看**的存储——落到
 * `LockPhase.BootCorrupt` 之后就没有别的诊断手段了。
 *
 * 放在 `crypto/` 而不是 `platform/`：[KdfParams] 的盐是第一个需要它的地方，而 KdfParams
 * 又会被 boot 存储和备份包 header 同时用到。放在更下层，两边都能引用。
 *
 * 用 `java.util.Base64` 而不是 `android.util.Base64`：后者是 Android 类型，会让
 * `crypto/` 违反"纯 Kotlin 层零 Android 依赖"，也就没法在 JVM 单测里覆盖。
 */
object ByteArrayAsBase64 : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ByteArrayAsBase64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(java.util.Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        java.util.Base64.getDecoder().decode(decoder.decodeString())
}
