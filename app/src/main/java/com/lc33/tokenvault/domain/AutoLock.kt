package com.lc33.tokenvault.domain

/**
 * 切后台之后多久锁（§7.4）。
 *
 * **「从不」是显式的一档，不用 `null` 也不用 `-1` 表示。** 这是个安全设置，
 * 而哨兵值在算术里会静默变成"0 秒后锁"或者"永不锁"——两个方向都错得很难看：
 * 前者让用户以为应用坏了，后者让 DEK 一直留在内存里而设置页还画着"1 分钟后"。
 * 分成两个类型之后，"忘了处理从不"是编译期就要面对的问题。
 */
sealed interface AutoLockTimeout {

    /** 不自动锁定。只剩手动「立即锁定」与冷启动这两条路。 */
    data object Never : AutoLockTimeout

    /** [seconds] 秒之后锁。0 表示切后台即锁。 */
    data class After(val seconds: Int) : AutoLockTimeout
}

/**
 * 下拉的五档与它们的存储形态。
 *
 * 两条约束：
 *
 * 1. **[OPTIONS] 的顺序必须与 `R.array.auto_lock_options` 一一对应**，因为
 *    `AppDropdownRow` 的 API 是 `selectedIndex`。数量不一致的表现不是崩溃而是错位
 *    （选「立即」得到 5 分钟），所以 `ArchitectureRulesTest` 会比这两边的条数。
 * 2. **落库存的是秒数而不是下拉的下标**。存下标的代价是"以后在中间插一档"会让所有
 *    已存的设置悄悄改变含义——用户选的「立即」会变成「30 秒」，而没有任何迁移能发现它。
 */
object AutoLockPolicy {

    /** §7.4 的默认值。 */
    const val DEFAULT_SECONDS = 60

    val DEFAULT: AutoLockTimeout = AutoLockTimeout.After(DEFAULT_SECONDS)

    /** 与 `R.array.auto_lock_options` 同序：立即 / 30 秒 / 1 分钟 / 5 分钟 / 从不。 */
    val OPTIONS: List<AutoLockTimeout> = listOf(
        AutoLockTimeout.After(0),
        AutoLockTimeout.After(30),
        AutoLockTimeout.After(60),
        AutoLockTimeout.After(300),
        AutoLockTimeout.Never,
    )

    /** 下拉下标 → 时限。越界回到默认档而不是抛：越界只可能来自资源与这张表不一致。 */
    fun at(index: Int): AutoLockTimeout = OPTIONS.getOrNull(index) ?: DEFAULT

    /**
     * 时限 → 下拉下标。
     *
     * 表里没有的秒数（用户从旧版本升上来、或者手改过库）落到默认档那一枚上，
     * 这样下拉至少有一枚是选中的；真正生效的值仍然是存储里那个。
     */
    fun indexOf(timeout: AutoLockTimeout): Int =
        OPTIONS.indexOf(timeout).takeIf { it >= 0 } ?: OPTIONS.indexOf(DEFAULT)

    /**
     * 存储形态 → 时限。
     *
     * [stored] 为 null 表示**这个键还没写过**，于是给 [DEFAULT]——它与 [AutoLockTimeout.Never]
     * 是两件不同的事，所以这里刻意不返回 `Int?`：那样"没设置过"和"设成从不"会挤进同一个值。
     * 解析不出来也回到默认：一段坏字符串不该让金库变成永不上锁。
     */
    fun decode(stored: String?): AutoLockTimeout = when {
        stored == null -> DEFAULT
        stored == NEVER -> AutoLockTimeout.Never
        else -> stored.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { AutoLockTimeout.After(it) } ?: DEFAULT
    }

    /** 时限 → 存储形态。写成可读的 `never` 而不是哨兵数字，好让直接查库的人看得懂。 */
    fun encode(timeout: AutoLockTimeout): String = when (timeout) {
        AutoLockTimeout.Never -> NEVER
        is AutoLockTimeout.After -> timeout.seconds.toString()
    }

    /** 「从不」的存储形态。ASCII，不是给用户看的文案。 */
    const val NEVER = "never"
}
