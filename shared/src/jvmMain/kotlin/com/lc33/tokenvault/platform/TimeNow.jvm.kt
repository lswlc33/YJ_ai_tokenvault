package com.lc33.tokenvault.platform

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun monotonicNanoTime(): Long = System.nanoTime()
