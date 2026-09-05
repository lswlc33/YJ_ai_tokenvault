package com.lc33.tokenvault.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * `CharArray` ↔ UTF-8 `ByteArray`，**不经过 `String`**。
 *
 * 存在的唯一理由是红线 1：`String` 不可变、擦不掉，一旦明文密钥进了 `String`，
 * 它就会留在堆上直到进程结束（还可能被 intern 或写进 dump）。而 `String(chars)` /
 * `String.toByteArray()` 这两个最顺手的写法恰好都会造一个。
 *
 * 中间那个 NIO 缓冲的 backing array 也带着明文，所以这里显式擦掉它——擦不掉的部分
 * 要诚实说清：`encode` 可能在内部另有临时数组，我们只能擦拿得到的那一份。
 */
internal fun CharArray.toUtf8(): ByteArray {
    // wrap 不复制字符，所以调用方那份 CharArray 仍然是唯一的明文副本
    val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
    val out = ByteArray(encoded.remaining())
    encoded.get(out)
    if (encoded.hasArray()) encoded.array().fill(0)
    return out
}

internal fun ByteArray.utf8Chars(): CharArray {
    val decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(this))
    val out = CharArray(decoded.remaining())
    decoded.get(out)
    if (decoded.hasArray()) decoded.array().fill(Char(0))
    return out
}
