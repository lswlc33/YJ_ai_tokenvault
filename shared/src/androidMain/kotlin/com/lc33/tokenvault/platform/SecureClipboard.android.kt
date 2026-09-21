package com.lc33.tokenvault.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * 系统剪贴板实现（Android 端）。接口 [SecureClipboard] 已迁 commonMain，实现留在 app。
 *
 * 只做两件事：
 *
 * 1. 写：`EXTRA_IS_SENSITIVE = true`。Android 13+ 据此不在屏幕上弹出内容预览气泡——
 *    否则复制一次密钥，屏幕上就会飘出它的前几个字符，而那个气泡会被截屏、被录屏。
 * 2. 读：粘贴导入用的「从剪贴板填充」。
 *
 * 不再自动清除剪贴板（决策见 none.md §7.5）：复制后明文留在系统剪贴板里由用户自行处理，
 * 不复刻自动清除那套实现。
 */
class AndroidSecureClipboard(
    context: Context,
) : SecureClipboard {

    private val manager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun copy(label: String, value: CharArray) {
        val clip = ClipData.newPlainText(label, String(value)).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        manager?.setPrimaryClip(clip)
    }

    override fun read(): String? =
        manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}