// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.lc33.tokenvault.ui.miuix.liquid

// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).
// 自 MIUIX v0.9.1 示例工程 vendor 而来：底层只依赖 miuix-blur 公开 API；升级 MIUIX 时需对照官方示例同步。

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/** Lightweight stand-in for Kyant's `vibrancy()`. */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
