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

    /**
     * 「待清除时刻」的落盘位置。用 SharedPreferences 而不是 Room：这一层要在**启动极早期**
     * （可能数据库还没建、锁屏页还在读 boot）就能读到并清掉，而 Room 那条路会把它拖到
     * 数据库就绪之后——那正好是最需要它的时机。内容只有一枚时刻与一个标签，都不是秘密。
     */
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
        if (effectiveSeconds <= 0) {
            // 「从不」= 没有待清除时刻。留着上一次那份会让下一次启动误清一次内容。
            prefs.edit().remove(KEY_CLEAR_AT).remove(KEY_LABEL).apply()
            return
        }

        // 落盘"什么时候该清掉它"：延时任务活在进程里，进程没了任务就没了，
        // 而明文还在剪贴板里（§7.5）。标签是这份内容的**非秘密**指纹，用来在补清时
        // 分辨"用户后来复制的别的东西"，不写明文（红线 1 的例外只到进程内存为止）。
        prefs.edit()
            .putLong(KEY_CLEAR_AT, nowMillis() + effectiveSeconds * 1000L)
            .putString(KEY_LABEL, label)
            .apply()

        // 记下我们放进去的那份内容，到点只在"还是它"的时候才清。
        // 不比对的话，用户在这 60 秒里复制了别的东西会被我们一起吞掉。
        val ours = String(value)
        clearJob = scope.launch {
            delay(effectiveSeconds * 1000L)
            val now = currentText()
            // 读不回来时也清。Android 12 起，非聚焦应用读 `primaryClip` 只能拿到遮蔽过的
            // 空值，于是 `now == ours` 在**主场景**（复制完切去别处粘贴，那一刻我们必然在后台）
            // 永远不成立——这份明文密钥就永远留在剪贴板上，而这个类存在的理由就是不让它留着。
            // 两边代价不对等：误吞的只是用户这 60 秒里新复制的东西，漏清的是密钥。
            if (now == null || now.isEmpty() || now == ours) clearNow()
        }
    }

    /**
     * 启动时补做上次没做成的自动清除（见 [SecureClipboard.recoverOverdueClear]）。
     *
     * 三种情况：没有记录 → 什么都不做；已超时且剪贴板上那份**仍是我们放的**（按标签认）→
     * 立刻清；还没到点 → 按剩余时间重新起一次延时任务。
     *
     * 认不出标签时**不清**：宁可让那条明文多待到一个新复制动作覆盖它，也不要在用户刚复制完
     * 别的东西时把它一起吞掉。这不影响安全性上限——我们自己的那一份在 [clearNow] 与
     * 会话锁定（`VaultSession.lock`）两条路上都会被清掉。
     */
    override fun recoverOverdueClear() {
        val clearAt = prefs.getLong(KEY_CLEAR_AT, 0L)
        if (clearAt <= 0L) return
        val label = prefs.getString(KEY_LABEL, null)
        val remainingMs = clearAt - nowMillis()
        if (remainingMs > 0L) {
            clearJob?.cancel()
            clearJob = scope.launch {
                delay(remainingMs)
                // 判据与上面那条一致：读不出标签（后台限制）时也清，理由见 `recoverOverdueClear`
                // 末尾那段注释。
                val seen = currentLabel()
                if (label == null || seen == null || seen == label) clearNow()
            }
            return
        }
        val seen = currentLabel()
        if (label != null && seen != null && seen != label) {
            // 只有**读得出来、且确实不是我们那份**才放弃这条记录。Android 12 起后台读剪贴板
            // 拿到的是遮蔽过的空值（`currentLabel()` 于是为 null），按旧写法就是"永远认不出、
            // 于是永远不清"——而那份明文正躺在剪贴板上等着被清。
            prefs.edit().remove(KEY_CLEAR_AT).remove(KEY_LABEL).apply()
            return
        }
        clearNow()
    }

    override fun clearNow() {
        clearJob?.cancel()
        prefs.edit().remove(KEY_CLEAR_AT).remove(KEY_LABEL).apply()
        // 用空 ClipData 覆盖而不是 clearPrimaryClip()：后者在部分 ROM 上是空实现
        manager?.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    override fun read(): String? =
        manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    private fun currentText(): String? =
        manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    /**
     * 当前剪贴板条目的标签。`ClipDescription.label` 是公开信息，不含内容本身。
     * `getLabel()` 声明的是 `CharSequence`，所以这里要 `toString()` 一次才交得出 [String]
     * （与上面 [currentText] 同一条理由）。
     */
    private fun currentLabel(): String? =
        manager?.primaryClip?.description?.label?.toString()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val PREFS = "vault_clipboard"
        const val KEY_CLEAR_AT = "clearAtEpochMs"
        const val KEY_LABEL = "label"
    }
}
