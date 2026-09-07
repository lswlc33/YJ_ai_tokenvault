package com.lc33.tokenvault.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 系统剪贴板实现（Android 端）。接口 [SecureClipboard] 已迁 commonMain，实现留在 app。
 *
 * 三件事：
 *
 * 1. `EXTRA_IS_SENSITIVE = true`。Android 13+ 据此不再弹出内容预览气泡——否则复制一次密钥，
 *    屏幕上就会飘出它的前几个字符，而那个气泡会被截屏、会被录屏、也会出现在演示视频里。
 * 2. `autoClearSeconds` 秒后**内容未变则清空**。判"未变"很重要：用户复制完密钥又去复制了
 *    别的东西，这时清空会把他刚复制的内容也吞掉。
 * 3. 清空用一条**空 `ClipData`** 覆盖，而不是 `clearPrimaryClip()`——后者在部分 ROM 上是
 *    空实现，而"以为清了其实没清"比不清更糟。
 *
 * 说清能力边界：明文在这里**必然要变成 `CharSequence`**，因为框架接口只吃它，
 * 而剪贴板本身就是把内容交给另一个进程。所以红线 1 在这一步无法维持——
 * 能做的是把窗口压到最短（自动清除）并且不留预览。
 */
class AndroidSecureClipboard(
    context: Context,
    private val scope: CoroutineScope,
    settings: SettingsRepository,
) : SecureClipboard {

    private val manager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var clearJob: Job? = null

    /**
     * 自动清除秒数的缓存。权威在 `app_settings.clipboardClearSeconds`（红线 31），
     * 这里订阅同一条流、缓存到 volatile，`copy` 时读缓存——`copy` 是同步方法，不能挂起
     * 去等 Room。Room 首帧到达前用默认值（60），与设置页显示一致。
     */
    @Volatile
    private var configuredSeconds: Int = SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS

    init {
        scope.launch {
            settings.observeClipboardClearSeconds().collect { configuredSeconds = it }
        }
    }

    override fun copy(label: String, value: CharArray, autoClearSeconds: Int) {
        // 调用方传的通常是 [SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS]，这里统一换成
        // "跟随设置"的缓存值。设置里「从不」= 0，`<= 0` 走不自动清除那一路。
        val effectiveSeconds = if (autoClearSeconds == SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS) {
            configuredSeconds
        } else {
            autoClearSeconds
        }

        val clip = ClipData.newPlainText(label, String(value)).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        manager?.setPrimaryClip(clip) ?: return

        clearJob?.cancel()
        if (effectiveSeconds <= 0) return

        // 记下我们放进去的那份内容，到点只在"还是它"的时候才清。
        // 不比对的话，用户在这 60 秒里复制了别的东西会被我们一起吞掉。
        val ours = String(value)
        clearJob = scope.launch {
            delay(effectiveSeconds * 1000L)
            if (currentText() == ours) clearNow()
        }
    }

    override fun clearNow() {
        clearJob?.cancel()
        // 用空 ClipData 覆盖而不是 clearPrimaryClip()：后者在部分 ROM 上是空实现
        manager?.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    override fun read(): String? =
        manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    private fun currentText(): String? =
        manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}
