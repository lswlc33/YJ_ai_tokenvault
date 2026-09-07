package com.lc33.tokenvault.platform

/**
 * 跨平台的可重入锁（红线 20：锁是平台能力）。
 *
 * 阶段3：`AutoLocker` / `VaultSession` 迁 commonMain 后，原来用的 JVM 专属
 * `synchronized(guard) {}` 在 iOS 上不可用。落成 expect/actual：
 * - Android/JVM：`java.util.concurrent.locks.ReentrantLock`；
 * - iOS：`NSRecursiveLock`。
 *
 * 语义与原 `synchronized` 对齐：可重入、同一把锁互斥。用 [withLock] 包住临界区。
 */
expect class Lock() {
    fun <T> withLock(block: () -> T): T
}
