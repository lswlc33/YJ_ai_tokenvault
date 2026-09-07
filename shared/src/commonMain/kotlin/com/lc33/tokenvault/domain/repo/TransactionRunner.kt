package com.lc33.tokenvault.domain.repo

/**
 * 事务边界。
 *
 * 抽成接口而不是直接在仓库里用 `db.withTransaction {}`，是为了让仓库能在 **JVM 单测**里跑：
 * 本项目没有 Robolectric，Room 在 JVM 上起不来，所以测试里换一个"直接执行 block"的实现，
 * 用假 DAO 验仓库自己的逻辑（映射、AAD 绑对没绑对、两步写的顺序）。
 *
 * 阶段3：接口迁 commonMain（`inTransaction` 签名纯 Kotlin），Room 实现
 * （`RoomTransactionRunner`，依赖 `db.withTransaction`）留在 app 的 data 层。
 */
interface TransactionRunner {
    suspend fun <R> inTransaction(block: suspend () -> R): R
}
