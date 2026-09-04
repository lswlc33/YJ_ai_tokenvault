package com.lc33.tokenvault

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.lc33.tokenvault.ui.shell.AppRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * 必须继承 `FragmentActivity`：`androidx.biometric.BiometricPrompt` 的构造函数要求它，
 * `ComponentActivity` 不够（计划.md §15.1）。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 任务切换器里不留应用截图（计划.md §7.5）
        setRecentsScreenshotEnabled(false)
        setContent { AppRoot() }
    }
}
