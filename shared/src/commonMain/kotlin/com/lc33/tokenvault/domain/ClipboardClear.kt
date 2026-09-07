package com.lc33.tokenvault.domain

/**
 * 复制后多久自动清空剪贴板（§7.5）。
 *
 * 存的是**秒数**，不是下拉下标——与 [AutoLockPolicy] 同一套理由：以后在中间插一档
 * 不会让已存的设置悄悄改变含义。`0` 表示不清除（「从不」这一档），不是哨兵 `-1`。
 */
object ClipboardClearPolicy {

    /** 默认 60 秒。与 `SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS` 一致（§7.5）。 */
    const val DEFAULT_SECONDS = 60

    /** 与 `R.array.clipboard_clear_options` 同序：30 秒 / 60 秒 / 5 分钟 / 从不。 */
    val OPTIONS: List<Int> = listOf(30, 60, 300, 0)

    /** 下拉下标 → 秒数。越界回到默认档（只可能来自资源与这张表不一致）。 */
    fun at(index: Int): Int = OPTIONS.getOrNull(index) ?: DEFAULT_SECONDS

    /** 秒数 → 下拉下标。表里没有的秒数落到默认档，让下拉至少有一枚选中。 */
    fun indexOf(seconds: Int): Int =
        OPTIONS.indexOf(seconds).takeIf { it >= 0 } ?: OPTIONS.indexOf(DEFAULT_SECONDS)

    /**
     * 存储形态 → 秒数。null = 键还没写过 → [DEFAULT_SECONDS]；坏值也回默认。
     * 读方向单向容错，与 [AutoLockPolicy.decode] 同款。
     */
    fun decode(stored: String?): Int =
        stored?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: DEFAULT_SECONDS

    /** 秒数 → 存储形态。 */
    fun encode(seconds: Int): String = seconds.toString()
}
