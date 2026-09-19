package com.lc33.tokenvault.engine

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lc33.tokenvault.catalog.CatalogSyncState
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.data.repo.RoomModelCatalogRepository
import com.lc33.tokenvault.data.repo.RoomSettingsRepository
import com.lc33.tokenvault.data.repo.RoomTransactionRunner
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 目录同步引擎的链路测试（MockEngine 挡掉真网络，Room 用真库）。
 *
 * 盯的是四个"错法很安静"的点：
 * - **304 不能重写整表**：重写会把 `models.catalogKey` 一起重算一遍，白闪一次界面；
 * - **空快照不能当成一次成功**：上游抽风回 `{}` 时若照写，用户所有模型会集体掉进"其他"；
 * - **条件请求要真把 `If-None-Match` 发出去**：没发就是每 7 天白下一份 4.7 MB；
 * - **闸门要挡得住**：自动更新关着、或离上次不到 7 天时，一发都不该发出去。
 *
 * 没有用 fake 仓库：这一层的意义恰恰是"下载→解析→落库→回填"四段接起来对不对，
 * 换成 fake 就只剩四段各自的契约，而那是别的测试已经在管的事。
 */
class CatalogSyncTest {

    private lateinit var dir: File
    private lateinit var database: VaultDatabase
    private lateinit var settings: RoomSettingsRepository
    private lateinit var catalog: RoomModelCatalogRepository

    private var requestedHeaders: HttpRequestData? = null
    private var requestCount = 0

    /** 上游真实形态的截断版：一家厂商两个模型，其中一个是聚合站转售。 */
    private val snapshotJson = """
        {
          "openai": {
            "id": "openai", "name": "OpenAI", "doc": "https://platform.openai.com/docs",
            "models": {
              "gpt-4o": {"id": "gpt-4o", "name": "GPT-4o", "family": "gpt", "tool_call": true,
                         "last_updated": "2026-03-01", "limit": {"context": 128000, "output": 16384}},
              "o3-mini": {"id": "o3-mini", "name": "o3-mini", "family": "o",
                          "last_updated": "2026-02-01", "limit": {"context": 200000}}
            }
          },
          "tokengo": {
            "id": "tokengo", "name": "TokenGo",
            "models": {"openai/gpt-4o": {"id": "openai/gpt-4o", "last_updated": "2026-08-01"}}
          }
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory(prefix = "vault-catalog-sync-").toFile()
        database = Room.databaseBuilder<VaultDatabase>(name = File(dir, "vault.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        settings = RoomSettingsRepository(database.appSettingDao())
        catalog = RoomModelCatalogRepository(
            catalogDao = database.modelCatalogDao(),
            vendorDao = database.modelVendorDao(),
            modelDao = database.modelDao(),
            providerDao = database.providerDao(),
            transactions = RoomTransactionRunner(database),
        )
        requestedHeaders = null
        requestCount = 0
    }

    @AfterTest
    fun tearDown() {
        database.close()
        dir.deleteRecursively()
    }

    private fun sync(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        etag: String? = TEST_ETAG,
        now: () -> Long = { NOW_MILLIS },
    ): CatalogSync {
        val mock = MockEngine { data: HttpRequestData ->
            requestCount++
            requestedHeaders = data
            val headers = if (etag != null) headersOf(HttpHeaders.ETag, etag) else headersOf()
            respond(body, status, headers)
        }
        return CatalogSync(
            client = HttpClient(mock),
            catalog = catalog,
            settings = settings,
            now = now,
        )
    }

    private suspend fun seedModel(modelId: String): Long {
        val providerId = database.providerDao().insert(
            ProviderEntity(name = "OpenAI 官方", createdAt = 1L, updatedAt = 1L),
        )
        val keyId = database.apiKeyDao().insertRaw(
            ApiKeyEntity(
                providerId = providerId,
                secretEnc = byteArrayOf(0),
                fingerprint = "fp",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val id = database.modelDao().insertIgnoring(
            com.lc33.tokenvault.data.entity.ModelEntity(
                providerId = providerId,
                keyId = keyId,
                modelId = modelId,
                protocol = "chat",
                source = "discovered",
                discoveredVia = "chat",
                firstSeenAt = 1L,
            ),
        )
        return id
    }

    @Test
    fun `首次同步落库并回填模型后记下时刻与 etag`() = runBlocking {
        seedModel("gpt-4o")
        val engine = sync(snapshotJson)

        assertTrue(engine.syncIfNeeded(force = false))
        assertEquals(3, catalog.modelCount())
        assertEquals(2, catalog.vendorCount())
        assertNotNull(database.modelDao().findById(1L)?.catalogKey)
        assertEquals(NOW_MILLIS, settings.observeCatalogLastSyncAt().first())
        assertEquals(TEST_ETAG, settings.observeCatalogEtag().first())
        val state = engine.state.value
        assertTrue(state is CatalogSyncState.Synced && state.changed && state.models == 3, "$state")
    }

    @Test
    fun `从没同步过时进页面那一发就是全量拉取`() = runBlocking {
        // lastSyncAt 为 0 时"距今不到 7 天"这个判断没有意义，必须拉。
        val engine = sync(snapshotJson)
        assertTrue(engine.syncIfNeeded(force = false))
        assertEquals(1, requestCount)
    }

    @Test
    fun `上游回 304 时不重写目录也不改时刻`() = runBlocking {
        settings.setCatalogLastSyncAt(EIGHT_DAYS_AGO)
        settings.setCatalogEtag(TEST_ETAG)
        val first = sync(snapshotJson)
        first.syncNow()
        val writtenAt = settings.observeCatalogLastSyncAt().first()
        val countBefore = catalog.modelCount()

        val second = sync(body = "", status = HttpStatusCode.NotModified)
        assertTrue(second.syncIfNeeded(force = true))

        assertEquals(TEST_ETAG, requestedHeaders?.headers?.get(HttpHeaders.IfNoneMatch), "条件请求没发出去就是白下 4.7 MB")
        assertEquals(countBefore, catalog.modelCount())
        assertEquals(writtenAt, settings.observeCatalogLastSyncAt().first())
        val state = second.state.value
        assertTrue(state is CatalogSyncState.Synced && !state.changed, "304 要说「已是最新」，不能说「已更新」")
    }

    @Test
    fun `自动更新关着时不自己发起`() = runBlocking {
        settings.setCatalogAutoUpdate(false)
        val engine = sync(snapshotJson)
        assertFalse(engine.syncIfNeeded(force = false))
        assertEquals(0, requestCount)
        // 手动那一发不受开关管。
        assertTrue(engine.syncNow())
        assertEquals(1, requestCount)
    }

    @Test
    fun `离上次不到七天时按下去不重发`() = runBlocking {
        settings.setCatalogLastSyncAt(NOW_MILLIS - 60_000L)
        val engine = sync(snapshotJson)
        assertFalse(engine.syncIfNeeded(force = false))
        assertEquals(0, requestCount)
        // 过了 7 天就该发。
        settings.setCatalogLastSyncAt(NOW_MILLIS - CatalogSync.REFRESH_INTERVAL_MILLIS - 1_000L)
        assertTrue(engine.syncIfNeeded(force = false))
        assertEquals(1, requestCount)
    }

    @Test
    fun `上游回空对象时保留原有目录而不是抹平成空库`() = runBlocking {
        val first = sync(snapshotJson)
        first.syncNow()
        val countBefore = catalog.modelCount()

        val broken = sync("{}")
        broken.syncNow()
        assertFalse(broken.state.value is CatalogSyncState.Synced)
        assertEquals(countBefore, catalog.modelCount(), "一次抽风的上游响应不该毁掉整份目录")
        assertTrue(broken.state.value is CatalogSyncState.Failed)
    }

    @Test
    fun `上游报 5xx 时状态是可重试的失败`() = runBlocking {
        val engine = sync("gateway", status = HttpStatusCode.BadGateway)
        assertTrue(engine.syncNow())
        val state = engine.state.value
        assertTrue(state is CatalogSyncState.Failed, "$state")
        assertTrue(state.reason.contains("502"), state.reason)
        assertEquals(0, catalog.modelCount())
    }

    private companion object {
        const val TEST_ETAG = "\"b35eb98437d2e2679b535ab9b757e223\""
        const val NOW_MILLIS = 1_800_000_000_000L
        const val EIGHT_DAYS_AGO = NOW_MILLIS - 8L * 24 * 60 * 60 * 1000
    }
}
