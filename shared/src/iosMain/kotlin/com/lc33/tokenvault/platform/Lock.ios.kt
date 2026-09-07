package com.lc33.tokenvault.platform

import platform.Foundation.NSRecursiveLock

/** iOS：可重入锁。 */
actual class Lock actual constructor() {
    private val delegate = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        delegate.lock()
        try {
            return block()
        } finally {
            delegate.unlock()
        }
    }
}
