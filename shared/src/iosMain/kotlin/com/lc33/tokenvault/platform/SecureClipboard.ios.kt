package com.lc33.tokenvault.platform

import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSNumber
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
        UIPasteboard.general.setItems(
            // setItems 是唯一能挂 localOnly 的入口；类型键用 UTF-8 文本的 UTI。
            listOf(mapOf("public.utf8-plain-text" to text)),
            mapOf<Any?, Any?>(UIPasteboardOptionLocalOnly to NSNumber.numberWithBool(true)),
        )

        clearJob?.cancel()
        if (effectiveSeconds <= 0) return

        clearJob = scope.launch {
            delay(effectiveSeconds * 1000L)
            // 内容未变才清：变了说明用户复制了别的东西，不能吞掉它。
            if (UIPasteboard.general.string == text) {
                UIPasteboard.general.string = ""
            }
        }
    }

    override fun clearNow() {
        clearJob?.cancel()
        UIPasteboard.general.string = ""
    }

    override fun read(): String? = UIPasteboard.general.string
}
