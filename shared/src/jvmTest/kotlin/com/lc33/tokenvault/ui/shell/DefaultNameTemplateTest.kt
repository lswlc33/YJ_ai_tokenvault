package com.lc33.tokenvault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 默认名模板替换（「密钥 N」「供应商 N」这类）。
 *
 * 盯住一个真实的坑：这里曾经用 `String.format`，而它是 **JVM 专有**的扩展——
 * Android/JVM 编得过、Kotlin/Native 上根本没有这个函数，iOS 目标直接编译失败
 * （2026-09-15 CI 的 ios job 就是这么红的，安卓那一半全绿）。
 * 谁再想改回 `format`，先看这里。
 */
class DefaultNameTemplateTest {

    @Test
    fun `中文模板回填序号`() {
        assertEquals("密钥 3", defaultNameFromTemplate("密钥 %1\$d", 3))
    }

    @Test
    fun `英文模板回填序号`() {
        assertEquals("Provider 12", defaultNameFromTemplate("Provider %1\$d", 12))
    }

    @Test
    fun `序号就是十进制，不做本地化`() {
        // 千分位之类会随平台与语言变，而这是标识用的序号，必须各端一致。
        assertEquals("密钥 1000", defaultNameFromTemplate("密钥 %1\$d", 1000))
    }
}
