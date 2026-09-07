package com.lc33.tokenvault.platform

import java.util.concurrent.locks.ReentrantLock

/** JVM：可重入锁（与 Android 一致）。 */
actual class Lock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T {
        delegate.lock()
        try {
            return block()
        } finally {
            delegate.unlock()
        }
    }
}
