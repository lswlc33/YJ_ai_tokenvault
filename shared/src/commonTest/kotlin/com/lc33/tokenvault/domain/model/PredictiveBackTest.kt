package com.lc33.tokenvault.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PredictiveBackTest {
    @Test
    fun `返回动画只保留三种可选样式`() {
        assertEquals(
            listOf(
                PredictiveBackStyle.None,
                PredictiveBackStyle.Miuix,
                PredictiveBackStyle.Scale,
            ),
            PredictiveBackStyle.entries,
        )
    }

    @Test
    fun `旧版缩放系设置迁移到缩放`() {
        assertEquals(PredictiveBackStyle.Scale, PredictiveBackStyle.fromStorage("aosp"))
        assertEquals(PredictiveBackStyle.Scale, PredictiveBackStyle.fromStorage("classic"))
    }

    @Test
    fun `未知返回动画设置回退到MIUIX`() {
        assertEquals(PredictiveBackStyle.Miuix, PredictiveBackStyle.fromStorage("unknown"))
        assertEquals(PredictiveBackStyle.Miuix, PredictiveBackStyle.fromStorage(null))
    }
}