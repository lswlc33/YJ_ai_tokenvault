package com.lc33.tokenvault.platform

import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionLocalOnly
/**
 * 系统剪贴板实现（iOS 端，阶段4）。接口语义与 Android 端的 AndroidSecureClipboard 逐条对齐。
 *
 * 三件事：
 *
 * 1. `localOnly`——不把剪贴板同步到 Handoff / 通用剪贴板，对应 Android 端的
 *    `EXTRA_IS_SENSITIVE`（iOS 没有系统级预览气泡，但跨设备同步同样不该带上密钥）。
 * 2. `autoClearSeconds` 秒后**内容未变则清空**。判“未变”很重要：用户复制完密钥又去复制了
 *    别的东西，这时清空会把他刚复制的内容也吞掉。
 * 3. 清空用空字符串覆盖，行为在各系统版本上可见且一致。
 *
 * 能力边界（与 Android 同）：明文在这里必然变成 String 交给系统进程，红线 1 在这一步
 * 无法维持；能做的是把窗口压到最短（自动清除）并且不跨设备。
 */
class IosSecureClipboard(
    private val scope: CoroutineScope,
    settings: SettingsRepository,
) : SecureClipboard {

    private var clearJob: Job? = null

    /**
     * 「待清除时刻」的落盘位置。`NSUserDefaults` 是这一层最合适的键值存储：
     * 启动极早期就读得到（不必等数据库就绪），而且只放一枚时刻。
     * 用 `Double` 存 epoch 毫秒而不是 `Long`——`setLong` 在 K/N 的映射里对应的是 32 位的
     * `setInteger:`，装不下 epoch 毫秒；毫秒值在 Double 的 52 位尾数里是精确的。
     */
    private val defaults = NSUserDefaults.standardUserDefaults

    @Volatile
    private var configuredSeconds: Int = SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS

    init {
        scope.launch {
            settings.observeClipboardClearSeconds().collect { configuredSeconds = it }
        }
    }

    override fun copy(label: String, value: CharArray, autoClearSeconds: Int) {
        // 与 Android 端同一条规则：调用方传默认值时跟随设置缓存，
        // 设置里「从不」= 0，`<= 0` 走不自动清除那一路。
        val effectiveSeconds = if (autoClearSeconds == SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS) {
            configuredSeconds
        } else {
            autoClearSeconds
        }

        val text = value.concatToString()
        UIPasteboard.generalPasteboard.setItems(
            // setItems 是唯一能挂 localOnly 的入口；类型键用 UTF-8 文本的 UTI。
            listOf(mapOf("public.utf8-plain-text" to text)),
            // ObjC 类方法 numberWithBool 在 Kotlin 侧不可见（NSNumber 类型映射特殊），
            // 用构造器 NSNumber(bool = true) 造布尔值。
            mapOf<Any?, Any?>(UIPasteboardOptionLocalOnly to NSNumber(bool = true)),
        )

        clearJob?.cancel()
        if (effectiveSeconds <= 0) {
            defaults.removeObjectForKey(KEY_CLEAR_AT)
            return
        }
        // 落盘"该清的时刻"：延时任务只活在进程里，应用被杀之后剪贴板上的明文就没人管了。
        // 只存时刻，**不存内容**（红线 1 的例外只到进程内存为止）。
        defaults.setDouble(
            (nowMillis() + effectiveSeconds * 1000L).toDouble(),
            forKey = KEY_CLEAR_AT,
        )

        clearJob = scope.launch {
            delay(effectiveSeconds * 1000L)
            // 内容未变才清：变了说明用户复制了别的东西，不能吞掉它。
            if (UIPasteboard.generalPasteboard.string == text) {
                clearNow()
            }
        }
    }

    /**
     * 补做上次没做成的自动清除（见 [SecureClipboard.recoverOverdueClear]）。
     *
     * iOS 上做不到"按标签认出是不是我们那份"：`UIPasteboard` 没有可读的条目标签，
     * 而 `items` 在 iOS 16+ 上读一次就会弹「粘贴」授权框——为一个可用性判断去弹系统框
     * 明显不划算。所以这里超时就直接清，代价是可能一起清掉用户后来复制的别的东西。
     * 选这一边是因为漏清的那一份是**密钥明文**（§7.5），而误清的代价用户一秒就看得出。
     */
    override fun recoverOverdueClear() {
        val clearAt = defaults.doubleForKey(KEY_CLEAR_AT)
        if (clearAt <= 0.0) return
        val remainingMs = clearAt.toLong() - nowMillis()
        if (remainingMs > 0L) {
            clearJob?.cancel()
            clearJob = scope.launch {
                delay(remainingMs)
                clearNow()
            }
            return
        }
        clearNow()
    }

    override fun clearNow() {
        clearJob?.cancel()
        defaults.removeObjectForKey(KEY_CLEAR_AT)
        UIPasteboard.generalPasteboard.string = ""
    }

    override fun read(): String? = UIPasteboard.generalPasteboard.string

    private companion object {
        const val KEY_CLEAR_AT = "vault.clipboard.clearAtEpochMs"
    }
}
