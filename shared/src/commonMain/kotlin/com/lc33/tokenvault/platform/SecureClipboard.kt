package com.lc33.tokenvault.platform

/**
 * 剪贴板（§7.5）。**API 密钥、平台账号、平台密码走同一条路径，没有例外**（红线 21）。
 *
 * 接口存在的理由和 [BootStore] 一样：**能在 JVM 单测里换实现**。`ClipboardManager` 只能从
 * `Context` 拿，而 ViewModel 那一层要测的东西（"离开页面前擦掉明文"、"锁定时清空状态"）
 * 与剪贴板具体怎么写完全无关——为了它给整套 ViewModel 测试拉上 Robolectric 不值得。
 *
 * 阶段3：接口迁入 commonMain，平台实现（AndroidSecureClipboard / iOS 版）留在各端。
 */
interface SecureClipboard {

    /**
     * 复制。
     *
     * @param label 剪贴板条目的标签。会被别的应用看到，所以**不要写"API 密钥"**这类
     *   把内容性质说出来的词——写供应商名或一个中性词。
     * @param value 明文。本函数不擦它：调用方通常是在 `withFieldKey {}` 里现解出来的，
     *   擦除时机归调用方。
     * @param autoClearSeconds 0 或负数表示不自动清除（设置里可以关）。
     */
    fun copy(label: String, value: CharArray, autoClearSeconds: Int)

    /** 立刻清空（设置里那个"立即清除剪贴板"的按钮）。 */
    fun clearNow()

    /**
     * 补做上次没做成的自动清除：进程被杀 / 设备重启之后，那条"到点清除"的延时任务就没了，
     * 而密钥明文还在系统剪贴板里躺着（§7.5）。所以 [copy] 会把**计划清除的时刻**持久化，
     * 应用入口在启动时调一次本方法：已经超时就立刻清，还没到就重新起计时。
     *
     * 持久化的只有时刻（和 Android 侧那条不含明文的标签），**绝不把明文写进任何持久存储**——
     * 那等于把红线 1 的例外从"进程内存"扩大到"磁盘"。
     *
     * 默认空实现：JVM 那个假剪贴板不落盘，也就没有可恢复的超时。
     */
    fun recoverOverdueClear() = Unit

    /**
     * 读剪贴板文本。粘贴导入的"从剪贴板填充"用它。
     *
     * 返回 null 表示剪贴板里没有文本（可能是空、或是一张图 / 一个文件）。
     * 读取本身是平台能力，所以也走这个接口而不是让 ViewModel 直接拿 `ClipboardManager`。
     */
    fun read(): String?

    companion object {
        /** 默认 60 秒（§7.5）。设置里可改，0 表示不清除。 */
        const val DEFAULT_AUTO_CLEAR_SECONDS = 60
    }
}
