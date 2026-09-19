package com.lc33.tokenvault.engine

import com.lc33.tokenvault.catalog.CatalogParser
import com.lc33.tokenvault.catalog.CatalogSyncState
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ModelCatalogRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.net.CATALOG_MAX_DOWNLOAD_BYTES
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * models.dev 目录的同步：下载 → 解析 → 整表替换 → 回填 `models.catalogKey`。
 *
 * 这是"什么时候刷什么"那一半，真正干活的三段各自在别处：解码在 `catalog/CatalogParser`
 * （纯函数）、落库在 [ModelCatalogRepository]、匹配在 `catalog/ModelCatalogMatcher`（纯函数）。
 * 放 `engine/` 而不是 `catalog/`：`catalog/` 是纯 Kotlin 包，这里要碰 Ktor。
 *
 * ## 为什么整条链路只有一个 [Mutex]
 *
 * 三个入口会同时想要同步：冷启动解锁后的那发自动检查、进模型页时的那发、设置页的
 * 「立即更新」。不挡住的后果不是"下载两次"这么轻——两份 4.7 MB 同时在内存里、
 * 再加上两次整表替换，第二次的 `clear()` 会把第一次刚写完的数据抹掉。
 * 用 `tryLock` 而不是排队：后到的那一发既不是新信息也不该插队，直接放弃即可，
 * 先起来的那一发会把状态更新好。
 *
 * ## 为什么把下载和解析分开封顶
 *
 * 4.7 MB 的 JSON 在手机上解一次是有代价的，所以整表替换前先看 ETag：上游没变就一发
 * 304 完事，连字节都不读。[CATALOG_MAX_DOWNLOAD_BYTES] 那道封顶挡的是另一件事——
 * 反代或 DNS 出问题时这个 URL 后面可能是任意大的文件，不设上限就是等着一次 OOM。
 */
class CatalogSync constructor(
    private val client: HttpClient,
    private val catalog: ModelCatalogRepository,
    private val settings: SettingsRepository,
    private val now: () -> Long,
    private val audit: AuditLogRepository? = null,
) {

    private val _state = MutableStateFlow<CatalogSyncState>(CatalogSyncState.Idle)

    /** UI 订阅这条流画进度。它不是数据库状态，所以不做成 `Flow` 从 Room 发。 */
    val state: StateFlow<CatalogSyncState> = _state.asStateFlow()

    private val running = Mutex()

    /**
     * 按需同步。
     *
     * @param force true = 用户按了「立即更新」，跳过 7 天判定与自动更新开关。
     * @return 这一次真的跑了同步（含 304）返回 true；被开关/间隔挡下、或已有一发在跑，
     *   返回 false。**调用方据此决定要不要念一句"正在更新目录"**——返回 false 却报
     *   "开始更新"，用户等到的就是没发生的事。
     */
    suspend fun syncIfNeeded(force: Boolean): Boolean {
        if (!force) {
            if (!settings.observeCatalogAutoUpdate().first()) return false
            val last = settings.observeCatalogLastSyncAt().first()
            // last == 0 = 从来没成功同步过：首次进入必须全量拉一次，
            // 这一条不在"间隔"那个判断里，因为它没有"距今多久"可言。
            if (last != 0L && now() - last < REFRESH_INTERVAL_MILLIS) return false
        }
        if (!running.tryLock()) return false
        try {
            syncUnlocked()
            return true
        } finally {
            running.unlock()
        }
    }

    /**
     * 强制同步（设置页那颗按钮）。
     *
     * 已有一发在跑时**不排队**，直接返回 false：排队的后果是第一发刚写完、第二发又
     * clear + 重写一遍同样的数据，白等一轮 4.7 MB。返回 false 时界面不该念"开始更新目录"，
     * 正在跑的那一发自己会把进度发出来。
     */
    suspend fun syncNow(): Boolean {
        if (!running.tryLock()) return false
        try {
            syncUnlocked()
            return true
        } finally {
            running.unlock()
        }
    }

    private suspend fun syncUnlocked() {
        _state.value = CatalogSyncState.Downloading
        val previousEtag = settings.observeCatalogEtag().first()
        val response = runCatching { fetch(previousEtag) }.getOrElse { failure ->
            // 只记 reason：这一发没有凭据参与，但把整条异常堆进日志对诊断没有任何帮助。
            record(LogLevel.WARN, "catalog fetch failed: ${failure.message ?: "unknown"}")
            _state.value = CatalogSyncState.Failed(failure.message ?: "fetch failed")
            return
        }
        if (response.notModified) {
            val at = settings.observeCatalogLastSyncAt().first()
            record(LogLevel.INFO, "catalog not modified (304)")
            _state.value = CatalogSyncState.Synced(
                models = catalog.modelCount(),
                vendors = catalog.vendorCount(),
                atMillis = at,
                changed = false,
            )
            return
        }
        val text = response.body?.decodeToString()
        if (text == null || text.isBlank()) {
            record(LogLevel.WARN, "catalog response empty")
            _state.value = CatalogSyncState.Failed("empty response")
            return
        }
        // 解析单独 catch 一次：上游改结构时这道闸要给出"解不出来"而不是一个崩在 UI 线程上的异常。
        val snapshot = runCatching { CatalogParser.parse(CatalogParser.decode(text)) }.getOrElse { failure ->
            record(LogLevel.WARN, "catalog decode failed: ${failure.message ?: "unknown"}")
            _state.value = CatalogSyncState.Failed("decode failed")
            return
        }
        // 空目录不当一次成功：上游真出岔子回一个 `{}` 时，"同步成功，0 条"会把上一次
        // 那份好数据顶掉，用户 next 一次看到的就是所有模型都掉进未识别组。
        if (snapshot.models.isEmpty()) {
            record(LogLevel.WARN, "catalog snapshot empty")
            _state.value = CatalogSyncState.Failed("empty catalog")
            return
        }
        // text 解完就丢：后面三步（写目录、写厂商、回填模型）都只需要解析结果，
        // 让那串 4.7 MB 的字符继续活着等于在整个导入期间多占一份内存。
        val stamp = now()
        val written = catalog.replace(snapshot)
        catalog.rekeyAllModels()
        settings.setCatalogLastSyncAt(stamp)
        response.etag?.let { settings.setCatalogEtag(it) }
        record(LogLevel.INFO, "catalog synced: $written models, ${snapshot.vendors.size} vendors")
        _state.value = CatalogSyncState.Synced(
            models = written,
            vendors = snapshot.vendors.size,
            atMillis = stamp,
            changed = true,
        )
    }

    private suspend fun fetch(etag: String?): FetchOutcome {
        val response: HttpResponse = client.get(MODELS_DEV_URL) {
            if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
        }
        val status = response.status.value
        if (status == HTTP_NOT_MODIFIED) return FetchOutcome(notModified = true)
        if (status !in 200..299) {
            throw IllegalStateException("models.dev responded HTTP $status")
        }
        return FetchOutcome(
            notModified = false,
            body = readCapped(response),
            etag = response.headers[HttpHeaders.ETag],
        )
    }

    /**
     * 分块读响应体并封顶。
     *
     * 不一次 `ByteArray(CATALOG_MAX_DOWNLOAD_BYTES)` 起手：那是 8 MB 的预分配，
     * 而上游现在只有 4.7 MB——按块长出来，峰值就是实际大小加一块。
     */
    private suspend fun readCapped(response: HttpResponse): ByteArray {
        val channel = response.bodyAsChannel()
        val declared = response.contentLength()
        if (declared != null && declared > CATALOG_MAX_DOWNLOAD_BYTES) {
            channel.cancel(null)
            throw IllegalStateException("catalog is $declared bytes, over the ${CATALOG_MAX_DOWNLOAD_BYTES}B cap")
        }
        val chunks = ArrayList<ByteArray>()
        var total = 0L
        while (true) {
            val room = CATALOG_MAX_DOWNLOAD_BYTES + 1L - total
            if (room <= 0L) break
            val chunk = ByteArray(minOf(READ_CHUNK_BYTES.toLong(), room).toInt())
            val read = channel.readAvailable(chunk)
            if (read < 0) break
            if (read == 0) continue
            total += read
            chunks += if (read == chunk.size) chunk else chunk.copyOf(read)
        }
        // 超限要抛，不能截断：截断的 JSON 解出来要么整体失败、要么更糟——**少一批模型**，
        // 而界面上一切正常，只有用户发现某个模型怎么也搜不到。
        if (total > CATALOG_MAX_DOWNLOAD_BYTES) {
            throw IllegalStateException("catalog exceeded the ${CATALOG_MAX_DOWNLOAD_BYTES}B cap while reading")
        }
        val out = ByteArray(total.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    private suspend fun record(level: LogLevel, message: String) {
        runCatching { audit?.record(level = level, category = LogCategory.HTTP, message = message) }
    }

    private class FetchOutcome(
        val notModified: Boolean,
        val body: ByteArray? = null,
        val etag: String? = null,
    )

    companion object {
        /** 全量快照。实测 2026-09 是 4,705,665 字节、222 家厂商、7,864 行模型。 */
        const val MODELS_DEV_URL = "https://models.dev/api.json"

        /** 自动更新的节奏：7 天。上游一天内就会改价，但再快也不值得为它多烧一份 4.7 MB。 */
        const val REFRESH_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

        const val HTTP_NOT_MODIFIED = 304

        /** 读响应体的块长。 */
        const val READ_CHUNK_BYTES = 256 * 1024
    }
}
