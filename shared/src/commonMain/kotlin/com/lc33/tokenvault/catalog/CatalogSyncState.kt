package com.lc33.tokenvault.catalog

/**
 * 目录同步的状态。纯数据，放 `catalog/` 是为了让 UI 与引擎都能引用而不产生反向依赖。
 *
 * 刻意做成"阶段"而不是一个百分比：上游不报 Content-Length 时（Cloudflare 上是
 * chunked 传输，实测就没有可用的长度）百分比只能是猜的，而猜出来的进度条走到 99%
 * 卡住比根本没有进度条更让人以为程序死了。
 */
sealed interface CatalogSyncState {

    /** 还没同步过，或者上一次的结果早就被消费掉了。 */
    data object Idle : CatalogSyncState

    /** 正在下那 4.7 MB。蜂窝网上这一发是要等的。 */
    data object Downloading : CatalogSyncState

    /** 正在往库里写。[rows] 是已经写入的行数，分母是本次解出来的总数。 */
    data class Importing(val rows: Int, val total: Int) : CatalogSyncState

    /**
     * 一次同步结束。
     *
     * [changed] = false 表示上游回了 304、本地那份就是最新的：界面该说"目录已是最新"，
     * 而不是"已更新到 7,864 条"——后者会让人以为刚发生了什么，其实什么都没变。
     */
    data class Synced(
        val models: Int,
        val vendors: Int,
        val atMillis: Long,
        val changed: Boolean,
    ) : CatalogSyncState

    /**
     * 失败。[reason] 是给日志与诊断看的英文原因串，**不是**界面文案——
     * 用户看到的那句从资源里取（见 `model_catalog_sync_failed`）。
     */
    data class Failed(val reason: String) : CatalogSyncState
}
