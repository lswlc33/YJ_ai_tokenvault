package com.lc33.tokenvault

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * 仪器测试要跑在 `HiltTestApplication` 上，否则 `@HiltAndroidTest` 注入不进去。
 * 由 `defaultConfig.testInstrumentationRunner` 指定（计划.md §14.1）。
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
