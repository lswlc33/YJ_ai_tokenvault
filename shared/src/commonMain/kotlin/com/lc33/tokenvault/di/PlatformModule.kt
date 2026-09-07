package com.lc33.tokenvault.di

import org.koin.core.module.Module

/**
 * 平台模块（阶段4）。
 *
 * 收容四个没法在 commonMain 构造的东西：
 * - `VaultDatabase`——Android 走框架 SupportSQLite（保持既有用户库文件与 WAL 行为不变），
 *   iOS 走 common builder + BundledSQLiteDriver + 外键/手写索引的驱动包装层；
 * - `BootStore`——Android 是 java.io.File 的原子写实现，iOS 用 NSFileManager；
 * - `SecureClipboard`——Android 的 ClipboardManager / iOS 的 UIPasteboard；
 * - `placeholders`——User-Agent 模板的占位符值（系统版本、架构）。
 *
 * 各平台启动时把它和 [coreModule]/[viewModelModule] 一起塞进 Koin。
 */
expect val platformModule: Module
