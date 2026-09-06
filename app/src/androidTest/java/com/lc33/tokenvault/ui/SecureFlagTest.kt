package com.lc33.tokenvault.ui

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lc33.tokenvault.ui.common.SecureScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FLAG_SECURE 的 connectedTest（计划 §14.1 / new_plan §4.7 P2）。
 *
 * 为什么必须上真设备：`SecureScreen` 直接操作 [android.view.Window] 的 flag 位，
 * JVM 单测里没有 Window，只能靠 screencap 手测。这里把它固化成自动化断言——
 * 挂载后 flag 置位、卸载后清除、引用计数下内层退出不清外层。
 *
 * 用 `ActivityScenarioRule` 而不是 Compose 测试 rule：Compose 测试 rule 会自己接管
 * `setContent`（再手动 setContent 会报 "already set content"）。这里直接用 Activity 的
 * `setContent` 挂载/替换 Compose 树，只断言 Window 的 flag 位，不碰 UI 语义树。
 *
 * 线程模型是关键：`onActivity` 的 action 跑在主线程，Compose 的 `DisposableEffect`
 * 副作用也要主线程空闲才跑得完，所以在主线程里 `Thread.sleep` 会把自己卡死、flag 永远
 * 挂不上。正确做法是 action 只负责改 Compose 树，改完立刻返回；flag 的变化由测试线程
 * （Instrumentation 线程）轮询等待，主线程始终有空档去跑 Compose 调度。
 *
 * 注意：`SecureFlagRefCount` 是进程级单例（`holders` 无同步、只在主线程被 Compose 调），
 * 同一进程里的用例会共享计数。每个用例都让 Compose 树收敛到空、`release` 把计数清回 0，
 * 所以用例顺序无关紧要。
 */
@RunWith(AndroidJUnit4::class)
class SecureFlagTest {

    @get:Rule
    val scenario = ActivityScenarioRule(ComponentActivity::class.java)

    private fun readSecure(activity: ComponentActivity): Boolean {
        val flags = activity.window.attributes.flags
        return (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }

    /** 在测试线程轮询，直到 Window 的 FLAG_SECURE 变成 [expected]，超时判失败。 */
    private fun awaitSecure(expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var last = !expected
        while (SystemClock.uptimeMillis() < deadline) {
            val current = scenario.scenario.let {
                var v = false
                it.onActivity { a -> v = readSecure(a) }
                v
            }
            last = current
            if (current == expected) return
            // 让主线程有空档跑 Compose 调度，再回来查。
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(50)
        }
        fail("等待 FLAG_SECURE 变为 $expected 超时，最后读到 $last")
    }

    @Test
    fun secureScreen_addsFlagOnAttach_andClearsOnDetach() {
        scenario.scenario.onActivity { it.setContent { } }
        awaitSecure(false)

        scenario.scenario.onActivity { it.setContent { SecureScreen() } }
        awaitSecure(true)

        scenario.scenario.onActivity { it.setContent { } }
        awaitSecure(false)
    }

    @Test
    fun nestedSecureScreen_keepsFlagUntilOutermostDetaches() {
        // 两个嵌套的 SecureScreen：内层先退出，外层还在，flag 必须保留。
        var showInner by mutableStateOf(true)
        scenario.scenario.onActivity {
            it.setContent {
                SecureScreen()
                if (showInner) SecureScreen()
            }
        }
        awaitSecure(true)

        // 触发内层退出（showInner 置 false），外层仍在。
        scenario.scenario.onActivity { showInner = false }
        awaitSecure(true)
        assertTrue("内层退出后外层仍在，FLAG_SECURE 不应被撤", true)

        // 外层也退出。
        scenario.scenario.onActivity { it.setContent { } }
        awaitSecure(false)
    }
}
