package com.lc33.tokenvault.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lc33.tokenvault.catalog.CatalogParser
import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.data.repo.RoomModelCatalogRepository
import com.lc33.tokenvault.data.repo.RoomTransactionRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * models.dev 目录的落库与 `models.catalogKey` 回填（真 Room，JVM 上跑）。
 *
 * 用真库而不是 fake DAO 是有意的：这一层的全部风险都在 SQL 与匹配落库的接缝上——
 * `qualifiedId` 那条查询有没有排 canonical、批量 updateAll 有没有把没变的行也重写一遍、
 * 别名后缀能不能靠 normId 兜住。fake 会把同样的错误照抄一遍，测不出东西。
 *
 * 每个用例都从 [CatalogParser] 的真实输出出发（不是手搓目录行），否则测的就只是
 * "我给的数据能查回来"，而不是"上游那份数据会被挂成什么"。
 */
class ModelCatalogRepositoryTest {

    private lateinit var dir: File
    private lateinit var database: VaultDatabase
    private lateinit var repository: RoomModelCatalogRepository

    private val fixture = """
        {
          "deepseek": {
            "id": "deepseek", "name": "DeepSeek", "doc": "https://api-docs.deepseek.com",
            "models": {
              "deepseek-chat": {
                "id": "deepseek-chat", "name": "DeepSeek Chat", "family": "deepseek",
                "reasoning": false, "tool_call": true, "attachment": false,
                "open_weights": false, "last_updated": "2026-01-01",
                "limit": {"context": 65536, "output": 8192},
                "cost": {"input": 0.27, "output": 1.1}
              }
            }
          },
          "openai": {
            "id": "openai", "name": "OpenAI",
            "models": {
              "gpt-4o": {
                "id": "gpt-4o", "name": "GPT-4o", "family": "gpt",
                "tool_call": true, "attachment": true, "open_weights": false,
                "last_updated": "2026-03-01",
                "limit": {"context": 128000, "output": 16384},
                "cost": {"input": 2.5, "output": 10.0}
              }
            }
          },
          "tokengo": {
            "id": "tokengo", "name": "TokenGo",
            "models": {
              "deepseek/deepseek-chat": {
                "id": "deepseek/deepseek-chat", "name": "DeepSeek Chat (resale)",
                "tool_call": true, "open_weights": false, "last_updated": "2026-08-01",
                "limit": {"context": 65536, "output": 8192},
                "cost": {"input": 1.4, "output": 4.4}
              },
              "gpt-4o-2024-08-06": {
                "id": "gpt-4o-2024-08-06", "name": "GPT-4o dated",
                "open_weights": false, "last_updated": "2026-07-01"
              }
            }
          }
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory(prefix = "vault-catalog-").toFile()
        database = Room.databaseBuilder<VaultDatabase>(name = File(dir, "vault.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        repository = RoomModelCatalogRepository(
            catalogDao = database.modelCatalogDao(),
            vendorDao = database.modelVendorDao(),
            modelDao = database.modelDao(),
            providerDao = database.providerDao(),
            transactions = RoomTransactionRunner(database),
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
        dir.deleteRecursively()
    }

    private fun parsed() = CatalogParser.parse(CatalogParser.decode(fixture))

    /**
     * 造一家供应商**并给它配一把 Key**。
     *
     * 只插 provider 不够：`models.keyId` 上有指向 `api_keys` 的外键，模型行插进去就会撞
     * FOREIGN KEY constraint failed。两个都是自增，所以每个用例里 provider 与 key 的 id
     * 都是 1，[seed] 那头的 `keyId = 1L` 默认值才对得上。
     */
    private suspend fun seedProvider(name: String): Long {
        val providerId = database.providerDao().insert(
            ProviderEntity(name = name, createdAt = 1L, updatedAt = 1L),
        )
        database.apiKeyDao().insertRaw(
            ApiKeyEntity(
                providerId = providerId,
                secretEnc = byteArrayOf(0),
                fingerprint = "fp",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        return providerId
    }

    private suspend fun ModelDao.seed(vararg modelIds: String, keyId: Long = 1L, providerId: Long) {
        modelIds.forEachIndexed { index, id ->
            insertIgnoring(
                ModelEntity(
                    providerId = providerId,
                    keyId = keyId,
                    modelId = id,
                    protocol = "chat",
                    source = "discovered",
                    discoveredVia = "chat",
                    firstSeenAt = 1L,
                    sortOrder = index,
                ),
            )
        }
    }

    @Test
    fun `整表替换把目录与厂商一起落库`() = runBlocking {
        assertEquals(0, repository.modelCount())
        val written = repository.replace(parsed())

        assertEquals(4, written)
        assertEquals(4, repository.modelCount())
        assertEquals(3, repository.vendorCount())
    }

    @Test
    fun `替换是整表覆盖，上游撤掉的模型不会留下幽灵行`() = runBlocking {
        repository.replace(parsed())
        val smaller = CatalogParser.Result(
            models = parsed().models.filter { it.providerSlug == "openai" },
            vendors = parsed().vendors.filter { it.slug == "openai" },
        )
        repository.replace(smaller)

        assertEquals(1, repository.modelCount(), "旧快照里那三家不该还在")
        assertEquals(1, repository.vendorCount())
    }

    @Test
    fun `裸 id 挂到原创那条而不是转售条目`() = runBlocking {
        val providerId = seedProvider("DeepSeek 官方")
        database.modelDao().seed("deepseek-chat", providerId = providerId)
        repository.replace(parsed())

        assertEquals(1, repository.rekeyAllModels())
        val row = database.modelDao().findByProvider(providerId).single()
        // 两条候选：官方的 `deepseek/deepseek-chat` 与 tokengo 的
        // `tokengo/deepseek/deepseek-chat`（后者 last_updated 更新，但它是转售条目，
        // 那份上下文与能力位是聚合站自己截过的）。
        assertEquals("deepseek/deepseek-chat", row.catalogKey)
        val matched = repository.findByKeys(listOf(row.catalogKey!!)).values.single()
        assertTrue(matched.canonical, "挂上的必须是厂方自己那一条")
        assertEquals(65_536, matched.contextLimit)
    }

    @Test
    fun `批量查目录一次拿全，不存在的键不占位`() = runBlocking {
        repository.replace(parsed())
        val keys = database.modelCatalogDao().findAll().map { it.key }
        assertTrue(keys.size >= 2, "夹具里至少要有两条目录，否则这条测试没测到批量")

        val found = repository.findByKeys(keys + "no-such-vendor/no-such-model")
        assertEquals(keys.size, found.size, "查不到的键不该在结果里占一个位置")
        assertTrue(keys.all { it in found })
        // 空集合不发查询：Room 展开出来的 `IN ()` 是非法 SQL。
        assertTrue(repository.findByKeys(emptyList()).isEmpty())
    }

    @Test
    fun `中转站给的带前缀 id 也挂到原创那条`() = runBlocking {
        val providerId = seedProvider("某中转站")
        database.modelDao().seed("deepseek/deepseek-chat", providerId = providerId)
        repository.replace(parsed())

        repository.rekeyAllModels()
        val row = database.modelDao().findByProvider(providerId).single()
        assertEquals(
            "deepseek/deepseek-chat",
            row.catalogKey,
            "qualifiedId 那一级要能把带前缀的输入也归到厂方条目",
        )
    }

    @Test
    fun `带日期后缀的别名靠归一化挂上`() = runBlocking {
        val providerId = seedProvider("某中转站")
        // 上游的 mapKey 是裸 `gpt-4o`，这家给的是 `gpt-4o-2024-08-06`。
        // 精确两级都对不上，只剩 normId 这一级。
        database.modelDao().seed("gpt-4o-2024-08-06", providerId = providerId)
        repository.replace(parsed())

        assertEquals(1, repository.rekeyAllModels())
        val row = database.modelDao().findByProvider(providerId).single()
        assertEquals("tokengo/gpt-4o-2024-08-06", row.catalogKey)
    }

    @Test
    fun `挂不上的保持 null 而不是随便认一个`() = runBlocking {
        val providerId = seedProvider("某中转站")
        database.modelDao().seed("my-private-sql-gateway-v9", providerId = providerId)
        repository.replace(parsed())

        assertEquals(0, repository.rekeyAllModels())
        assertNull(database.modelDao().findByProvider(providerId).single().catalogKey)
    }

    @Test
    fun `重复回填不重写没有任何变化的行`() = runBlocking {
        val providerId = seedProvider("DeepSeek 官方")
        database.modelDao().seed("deepseek-chat", "gpt-4o", providerId = providerId)
        repository.replace(parsed())

        assertEquals(2, repository.rekeyAllModels())
        // 第二次一行都不该变。这条守的是"每次同步都别把整个列表闪一遍"——
        // 重写没变的行会让 observeAll 的流重发，界面上所有模型列表一起刷新。
        assertEquals(0, repository.rekeyAllModels())
    }

    @Test
    fun `上游撤掉厂方条目后回填改挂到还留着的那条`() = runBlocking {
        val providerId = seedProvider("DeepSeek 官方")
        database.modelDao().seed("deepseek-chat", providerId = providerId)
        repository.replace(parsed())
        assertEquals(1, repository.rekeyAllModels())
        assertEquals("deepseek/deepseek-chat", database.modelDao().findByProvider(providerId).single().catalogKey)

        // 下一次同步里上游不再提供 deepseek 那一家了，但 tokengo 还挂着同一个模型的转售条目。
        repository.replace(
            CatalogParser.Result(
                models = parsed().models.filterNot { it.providerSlug == "deepseek" },
                vendors = parsed().vendors.filterNot { it.slug == "deepseek" },
            ),
        )
        assertEquals(1, repository.rekeyAllModels())
        assertEquals(
            "tokengo/deepseek/deepseek-chat",
            database.modelDao().findByProvider(providerId).single().catalogKey,
            "只补空不摘旧的话，行会一直指着一行已经不存在的目录；撤了厂方条目就该退到还留着的那条",
        )
    }

    @Test
    fun `目录里彻底没有的模型才掉回未识别`() = runBlocking {
        val providerId = seedProvider("某中转站")
        database.modelDao().seed("deepseek-chat", providerId = providerId)
        repository.replace(parsed())
        assertEquals(1, repository.rekeyAllModels())

        repository.replace(
            CatalogParser.Result(
                models = parsed().models.filter { it.providerSlug == "openai" },
                vendors = parsed().vendors.filter { it.slug == "openai" },
            ),
        )
        assertEquals(0, repository.rekeyAllModels(), "这一行从挂上变成摘掉，摘掉不算「补上」")
        assertNull(
            database.modelDao().findByProvider(providerId).single().catalogKey,
            "所有候选都没了就得挂 null，让模型页把它归进「其他」那一组",
        )
    }

    @Test
    fun `增量回填只扫没挂上的行`() = runBlocking {
        val providerId = seedProvider("DeepSeek 官方")
        database.modelDao().seed("deepseek-chat", "my-private-sql-gateway-v9", providerId = providerId)
        repository.replace(parsed())

        assertEquals(1, repository.rekeyUnkeyedModelsOfKey(providerId, keyId = 1L))
        val rows = database.modelDao().findByProvider(providerId)
        assertEquals(
            mapOf("deepseek-chat" to "deepseek/deepseek-chat", "my-private-sql-gateway-v9" to null),
            rows.associate { it.modelId to it.catalogKey },
        )
        // 再来一次：没有未挂上的行了，应该是 0 而不是把已挂上的重算一遍。
        assertEquals(0, repository.rekeyUnkeyedModelsOfKey(providerId, keyId = 1L))
    }

    @Test
    fun `消歧用的是供应商名提示`() = runBlocking {
        // 两个厂商各有一个同名不同实体的 `claude-x`，两条都是原创，而 openai 那条更新。
        // 没有提示的话 lastUpdated 会赢、把模型标成 OpenAI 的；用户明说这把 Key 是
        // Anthropic 官方给的，提示必须压过新旧比较。
        val providerId = seedProvider("Anthropic 官方")
        database.modelDao().seed("claude-x", providerId = providerId)
        repository.replace(
            CatalogParser.parse(
                CatalogParser.decode(
                    """
                    {
                      "openai": {"id":"openai","name":"OpenAI","models":{"claude-x":{"id":"claude-x","last_updated":"2026-09-01"}}},
                      "anthropic": {"id":"anthropic","name":"Anthropic","models":{"claude-x":{"id":"claude-x","last_updated":"2026-01-01"}}}
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(1, repository.rekeyAllModels())
        val row = database.modelDao().findByProvider(providerId).single()
        assertEquals("anthropic/claude-x", row.catalogKey, "用户说这把 Key 是 Anthropic 的，就该按那家显示")
    }
}
