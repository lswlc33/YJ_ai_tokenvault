package com.lc33.tokenvault.di

import com.lc33.tokenvault.platform.APP_VERSION_NAME
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.IosBootStore
import com.lc33.tokenvault.platform.IosSecureClipboard
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.createVaultDatabase
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import platform.UIKit.UIDevice

/**
 * iOS 平台模块（[platformModule] 的 actual）。
 *
 * 数据库、boot 存储、剪贴板的实现见 platform/ 各 iosMain 文件；
 * 占位符值喂给探测的 User-Agent 模板（BuiltinPresets 里那几条 {key}）。
 */
actual val platformModule: Module = module {

    single { createVaultDatabase() }

    single<BootStore> { IosBootStore(IosBootStore.defaultDirectory()) }

    single<SecureClipboard> { IosSecureClipboard(get(named(Qualifiers.APP_SCOPE)), get()) }

    single(named(Qualifiers.PLACEHOLDERS)) {
        mapOf(
            "app_version" to APP_VERSION_NAME,
            // 模板键名是数据（BuiltinPresets），commonMain 不感知平台，所以键名沿用
            // "android_release"；iOS 端把值换成系统版本，模板渲染不缺角。
            "android_release" to UIDevice.Companion.currentDevice.systemVersion,
            "arch" to "arm64",
        )
    }
}
