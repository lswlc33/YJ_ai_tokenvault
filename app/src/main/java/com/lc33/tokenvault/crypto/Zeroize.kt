package com.lc33.tokenvault.crypto

/**
 * 置零。
 *
 * 红线 6 要求锁定时把 DEK 与派生子密钥清零，而 Kotlin 里没有"用完就擦"的语义——
 * 所以擦除必须是显式动作，且必须放在 `finally` 里（中途抛异常时也要擦）。
 *
 * 说清能力边界：这只能擦掉**我们自己持有的那一份**。JVM 可能在 GC 搬动对象时留下
 * 副本，`String` 更是不可变、根本擦不掉——所以明文秘密一律用 [ByteArray] /
 * [CharArray] 传递，绝不落进 `String`（红线 1）。
 */
internal fun ByteArray.zeroize() {
    fill(0)
}

/**
 * 用 `Char(0)` 而不是空格：字面意义上的置零。
 *
 * 刻意写成 `Char(0)` 而不是把那个字符直接放进单引号里——后者虽然能编译，但会让
 * git 把整个文件判成二进制（diff 出不来、review 看不见），而且在编辑器里是隐形的。
 */
internal fun CharArray.zeroize() {
    fill(Char(0))
}

/**
 * 借用一段字节，用完必擦。
 *
 * `session.withFieldKey { … }` 那套借用模型（§7.4）的底座：调用方拿到的是引用，
 * 但引用的生命周期被这个函数框住，不会被存下来长期持有。
 */
internal inline fun <R> ByteArray.borrow(block: (ByteArray) -> R): R = try {
    block(this)
} finally {
    zeroize()
}

/**
 * 定时安全比较。
 *
 * 用在"校验值是否相等"的地方。`contentEquals` 会在第一个不同的字节处返回，
 * 于是比较耗时泄漏了前缀匹配了多少位。
 */
internal fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (size != other.size) return false
    var diff = 0
    for (i in indices) {
        diff = diff or (this[i].toInt() xor other[i].toInt())
    }
    return diff == 0
}
