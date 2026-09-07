package com.lc33.tokenvault.crypto

/**
 * `CharArray` ↔ UTF-8 `ByteArray`，**不经过 `String`**，跨平台。
 *
 * 存在的唯一理由是红线 1：`String` 不可变、擦不掉，一旦明文密钥进了 `String`，
 * 它就会留在堆上直到进程结束（还可能被 intern 或写进 dump）。而 `String(chars)` /
 * `String.toByteArray()` 这两个最顺手的写法恰好都会造一个。
 *
 * 为什么手写 UTF-8：JVM 上可以用 `java.nio` 的编解码器（不经过 String），但
 * Kotlin/Native 没有等价的非 String 编解码 API。UTF-8 本身是确定性纯算法，
 * 手写一份就同时覆盖两端，且不引入任何平台依赖。
 *
 * 严格按 RFC 3629：处理 1–4 字节序列、代理对、非法序列（非法处替换为 U+FFFD，
 * 与 `String.toByteArray()` 的宽松解码行为一致）。
 */
fun CharArray.toUtf8(): ByteArray {
    val out = ArrayList<Byte>(size)
    var i = 0
    while (i < size) {
        val c = this[i].code
        val cp = if (isHighSurrogate(c) && i + 1 < size && isLowSurrogate(this[i + 1].code)) {
            toCodePoint(c, this[i + 1].code).also { i++ }
        } else {
            c
        }
        when {
            cp < 0x80 -> out.add(cp.toByte())
            cp < 0x800 -> {
                out.add((0xC0 or (cp shr 6)).toByte())
                out.add((0x80 or (cp and 0x3F)).toByte())
            }
            cp < 0x10000 -> {
                out.add((0xE0 or (cp shr 12)).toByte())
                out.add((0x80 or ((cp shr 6) and 0x3F)).toByte())
                out.add((0x80 or (cp and 0x3F)).toByte())
            }
            else -> {
                out.add((0xF0 or (cp shr 18)).toByte())
                out.add((0x80 or ((cp shr 12) and 0x3F)).toByte())
                out.add((0x80 or ((cp shr 6) and 0x3F)).toByte())
                out.add((0x80 or (cp and 0x3F)).toByte())
            }
        }
        i++
    }
    return out.toByteArray()
}

fun ByteArray.utf8Chars(): CharArray {
    val out = ArrayList<Char>(size)
    var i = 0
    while (i < size) {
        val b0 = this[i].toInt() and 0xFF
        when {
            b0 < 0x80 -> { out.add(b0.toChar()); i += 1 }
            b0 < 0xC0 -> { out.add('\uFFFD'); i += 1 } // 非法续字节
            b0 < 0xE0 -> {
                if (i + 1 < size) {
                    val cp = ((b0 and 0x1F) shl 6) or (this[i + 1].toInt() and 0x3F)
                    out.add(cp.toChar()); i += 2
                } else { out.add('\uFFFD'); i += 1 }
            }
            b0 < 0xF0 -> {
                if (i + 2 < size) {
                    val cp = ((b0 and 0x0F) shl 12) or ((this[i + 1].toInt() and 0x3F) shl 6) or
                        (this[i + 2].toInt() and 0x3F)
                    out.add(cp.toChar()); i += 3
                } else { out.add('\uFFFD'); i += 1 }
            }
            else -> {
                if (i + 3 < size) {
                    val cp = ((b0 and 0x07) shl 18) or ((this[i + 1].toInt() and 0x3F) shl 12) or
                        ((this[i + 2].toInt() and 0x3F) shl 6) or (this[i + 3].toInt() and 0x3F)
                    if (cp >= 0x10000) {
                        val v = cp - 0x10000
                        out.add((0xD800 + (v shr 10)).toChar())
                        out.add((0xDC00 + (v and 0x3FF)).toChar())
                    } else {
                        out.add(cp.toChar())
                    }
                    i += 4
                } else { out.add('\uFFFD'); i += 1 }
            }
        }
    }
    return out.toCharArray()
}

private fun isHighSurrogate(c: Int) = c in 0xD800..0xDBFF
private fun isLowSurrogate(c: Int) = c in 0xDC00..0xDFFF
private fun toCodePoint(high: Int, low: Int) = 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
