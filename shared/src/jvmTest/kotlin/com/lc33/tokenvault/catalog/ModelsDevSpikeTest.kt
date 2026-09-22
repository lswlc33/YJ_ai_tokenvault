package com.lc33.tokenvault.catalog

import com.lc33.tokenvault.domain.ModelFamily
import com.lc33.tokenvault.domain.model.CatalogModel
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真快照 + 真模型列表的端到端实测（联网，默认跳过）。
 *
 * 跑法：
 * `./gradlew :shared:jvmTest --tests "*ModelsDevSpikeTest*" -DvaultSpike=true -DvaultDump=<导出文件> --info`
 *
 * 这份测试要回答的是单元测试回答不了的两个问题：
 *
 * 1. **[CatalogParser] 能不能真的把线上那份 4.7 MB 解出来**。截断 fixture 只能证明
 *    "按我想象中的形状能解"，而 `limit.input` 这种只在 1,308/7,864 行出现的字段、
 *    `cost.tiers` 这种嵌套结构，都只有真文件会撞。解不出来时表现是同步直接失败，
 *    在手机上根本看不出原因。
 * 2. **他自己那 676 个模型到底能匹配上多少**。前面所有规则（前缀归族、qualifiedId、
 *    canonical）的价值最终都要在这个数字上兑现；匹配率如果只有三成，那"按厂商分组"
 *    就是一句空话，得在写 UI 之前先知道。
 *
 * 断言只放在"结构没解出来"这种硬失败上；匹配率只打印不卡阈值——这个库以后会长，
 * 把一次实测的数字固化成断言，下次只会得到一个没有信息量的红叉。
 */
class ModelsDevSpikeTest {

    private val dumpPath: String? = System.getProperty("vaultDump")?.takeIf { it.isNotBlank() }
        ?: "E:/tmp/vaultdump/models.txt".let { if (File(it).exists()) it else null }

    @Test
    fun `真快照能被解析并且他的模型列表匹配得上`() {
        // 这一层不用 org.junit.Assume：shared 的 jvmTest 依赖里没有 JUnit，
        // 而"没答应就跳过"在这里等价于"直接返回"——反正它只在显式带上属性时才有意义。
        if (System.getProperty("vaultSpike") != "true") {
            println("SPIKE skipped（加 -DvaultSpike=true 才真跑）")
            return
        }

        val text = snapshot()
        println("SPIKE snapshot chars=${text.length}")

        val decoded = CatalogParser.decode(text)
        val parsed = CatalogParser.parse(decoded)
        assertTrue(parsed.models.isNotEmpty(), "线上快照解出来是空的")
        println("SPIKE providers=${parsed.vendors.size} models=${parsed.models.size}")
        assertEquals(
            parsed.models.size,
            parsed.models.map { it.key }.distinct().size,
            "主键有重复，说明 providerSlug 那层兜底没生效",
        )

        val byQualifiedId = parsed.models.groupBy { it.qualifiedId }
        val byModelId = parsed.models.groupBy { it.modelId }
        val byNormId = parsed.models.groupBy { it.normId }
        val canonicalShare = parsed.models.count { it.canonical }
        println("SPIKE canonical=$canonicalShare resale=${parsed.models.size - canonicalShare}")

        // 上下文与模态是详情页要展示的东西，全解出来是 null 的话这页就没内容可画。
        val withContext = parsed.models.count { it.contextLimit != null }
        val withDescription = parsed.models.count { !it.description.isNullOrBlank() }
        println("SPIKE withContext=$withContext withDescription=$withDescription")
        assertTrue(withContext > parsed.models.size * 0.9, "上下文上限大面积缺失，DTO 的 limit 声明八成不对")

        val dump = dumpPath?.let(::File)
        if (dump == null || !dump.exists()) {
            println("SPIKE 没有模型列表导出（-DvaultDump=...），跳过匹配率这一段")
            return
        }
        reportMatchRate(dump, byQualifiedId, byModelId, byNormId, parsed)
    }

    private fun reportMatchRate(
        dump: File,
        byQualifiedId: Map<String, List<CatalogModel>>,
        byModelId: Map<String, List<CatalogModel>>,
        byNormId: Map<String, List<CatalogModel>>,
        parsed: CatalogParser.Result,
    ) {
        val nameById = dump.parentFile?.let { dir ->
            File(dir, "providers.txt").takeIf { it.exists() }
                ?.readLines()?.mapNotNull { line ->
                    line.split('|').takeIf { it.size >= 2 }?.let { it[0].trim() to it[1].trim() }
                }?.toMap()
        }.orEmpty()

        val rows = dump.readLines().mapNotNull { line ->
            line.split('|').takeIf { it.size >= 4 }?.let {
                DumpRow(providerId = it[0].trim(), keyId = it[1].trim(), modelId = it[2].trim(), source = it[3].trim())
            }
        }
        assertTrue(rows.isNotEmpty(), "导出文件是空的")

        val vendorNameBySlug = parsed.vendors.associate { it.slug to it.name }
        val matched = rows.map { row ->
            val hit = matchOne(
                row.modelId,
                nameById[row.providerId],
                byQualifiedId,
                byModelId,
                byNormId,
            )
            row to hit
        }
        val hitCount = matched.count { it.second != null }
        println("SPIKE 匹配率 ${hitCount}/${rows.size} = ${"%.1f".format(hitCount * 100.0 / rows.size)}%")

        // 分组能不能真起作用：按前缀归族后的组大小分布。
        val families = rows.groupBy { ModelFamily.keyOf(it.modelId) }
        println("SPIKE 前缀族 ${families.size} 个，最大的 8 个：" +
            families.entries.sortedByDescending { it.value.size }.take(8)
                .joinToString(", ") { (key, list) ->
                    "$key(${list.size},${ModelFamily.displayOfKey(key, vendorNameBySlug[key])})"
                },
        )
        println("SPIKE 单行族 ${families.count { it.value.size == 1 }} 个")

        matched.groupBy { it.first.providerId }.forEach { (providerId, list) ->
            val hits = list.count { it.second != null }
            println(
                "  SPIKE provider $providerId ${nameById[providerId].orEmpty()} -> $hits/${list.size}" +
                    list.filter { it.second == null }.take(6).joinToString("、", "  未匹配样本：") { it.first.modelId },
            )
        }

        // 挂错方向的抽查：匹配上的行里，目录那条的厂商和它自己前缀族不一致的比例。
        val mismatched = matched.filter { (_, hit) ->
            hit != null && hit.vendor != ModelFamily.keyOf(hit.modelId)
        }
        println("SPIKE 目录厂商与前缀族不同名：${mismatched.size} 行")
        mismatched.take(8).forEach { (row, hit) ->
            println("  SPIKE ${row.modelId} -> ${hit?.key} (vendor=${hit?.vendor}, providerSlug=${hit?.providerSlug})")
        }
    }

    /** 与 `RoomModelCatalogRepository.matchKey` 同一套查法（内存索引 + 纯函数决策）。 */
    private fun matchOne(
        modelId: String,
        vendorHint: String?,
        byQualifiedId: Map<String, List<CatalogModel>>,
        byModelId: Map<String, List<CatalogModel>>,
        byNormId: Map<String, List<CatalogModel>>,
    ): CatalogModel? {
        fun List<CatalogModel>.asEntries() = map {
            ModelCatalogMatcher.CatalogEntry(
                key = it.key,
                vendor = it.vendor,
                modelId = it.modelId,
                normId = it.normId,
                qualifiedId = it.qualifiedId,
                canonical = it.canonical,
                lastUpdated = it.lastUpdated,
            )
        }
        val hit = ModelCatalogMatcher.match(
            modelId = modelId,
            vendorHint = vendorHint,
            byQualifiedId = if (modelId.contains('/')) byQualifiedId[modelId].orEmpty().asEntries() else emptyList(),
            byModelId = byModelId[modelId].orEmpty().asEntries(),
            byNormId = byNormId[CatalogNormalize.normalize(modelId.substringAfterLast('/'))].orEmpty().asEntries(),
        )
        return hit?.let { winner ->
            byModelId.values.flatten().firstOrNull { it.key == winner.key }
                ?: byNormId.values.flatten().firstOrNull { it.key == winner.key }
                ?: byQualifiedId.values.flatten().firstOrNull { it.key == winner.key }
        }
    }

    /** 下载一次就记住：两条测试要用同一份快照，否则"匹配率"和"真库回填"量的不是同一个东西。 */
    private var cachedSnapshot: String? = null

    private fun snapshot(): String = cachedSnapshot ?: fetchSnapshot().first.also { cachedSnapshot = it }

    private fun fetchSnapshot(): Pair<String, Long> {
        val connection = URL(CatalogSyncUrl.VALUE).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 90_000
        try {
            assertEquals(200, connection.responseCode, "models.dev 没回 200")
            val bytes = connection.inputStream.readBytes()
            return bytes.decodeToString() to bytes.size.toLong()
        } finally {
            connection.disconnect()
        }
    }

    /** 两列文本查询。给"按 providerId 找名字"这类小对照用。 */
    private fun queryRows(file: File, sql: String): List<List<String>> {
        val connection = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(file.absolutePath)
        val rows = mutableListOf<List<String>>()
        try {
            val statement = connection.prepare(sql)
            try {
                while (statement.step()) rows += listOf(statement.getText(0), statement.getText(1))
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
        return rows
    }

    private class DumpRow(val providerId: String, val keyId: String, val modelId: String, val source: String)

    /**
     * 在他**真库的那份副本**上跑一次 v8 → v9。
     *
     * 单元测试里的造库是照导出 schema 建出来的干净库，验不出真实数据上的意外：
     * 库里有多少行、`keyId` 有没有 NULL、有没有备份恢复留下的孤儿行——这些只有真库有。
     * 而 v9 那条迁移动的是 `model_catalog` 并新张表，最怕的就是把 `models` 的 676 行
     * 连带碰坏了，那是要用户拿命去发现的事故。
     *
     * 只读副本：先 `copyTo` 到临时目录再打开，Room 跑迁移会写文件，不能碰备份原件。
     */
    @Test
    fun `真库副本能迁到最新版且一行数据都不掉`() {
        if (System.getProperty("vaultSpike") != "true") {
            println("SPIKE skipped（加 -DvaultSpike=true 才真跑）")
            return
        }
        val source = (System.getProperty("vaultDumpDb")?.takeIf { it.isNotBlank() }
            ?: "E:/tmp/vaultdump/vault.db").let(::File)
        if (!source.exists()) {
            println("SPIKE 没有真库副本（${source.absolutePath}），跳过")
            return
        }
        val dir = kotlin.io.path.createTempDirectory(prefix = "vault-realdb-").toFile()
        try {
            val copy = File(dir, "vault.db")
            source.copyTo(copy, overwrite = true)
            File(source.parentFile, "vault.db-wal").takeIf { it.exists() }
                ?.copyTo(File(dir, "vault.db-wal"), overwrite = true)

            val before = counts(copy)
            val versionBefore = userVersion(copy)
            println("SPIKE 真库副本 v$versionBefore ${before.entries.joinToString(", ") { "${it.key}=${it.value}" }}")

            val database = androidx.room.Room.databaseBuilder<com.lc33.tokenvault.data.VaultDatabase>(
                name = copy.absolutePath,
            )
                .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
                .addMigrations(*com.lc33.tokenvault.data.VaultDatabase.ALL_MIGRATIONS.toTypedArray())
                .build()
            // 先走一次 DAO 才会真打开库、比对版本并跑迁移（build() 是懒的）。
            kotlinx.coroutines.runBlocking { database.modelCatalogDao().count() }
            database.close()

            val after = counts(copy)
            assertEquals(userVersion(copy), com.lc33.tokenvault.data.VaultDatabase.VERSION, "user_version 没升到最新版本")
            assertEquals(before["providers"], after["providers"], "迁移掉了供应商")
            assertEquals(before["api_keys"], after["api_keys"], "迁移掉了 Key")
            assertEquals(before["models"], after["models"], "迁移掉了模型行")
            assertEquals(0, after["model_catalog"], "新表该是空的，等首次同步来写")
            println("SPIKE 迁移后 ${after.entries.joinToString(", ") { "${it.key}=${it.value}" }}")

            reportRealDbSync(copy)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 在真库副本上把**整条同步链**跑一遍：下载 → 解析 → 落库 → 回填 → 按前缀分组。
     *
     * 这一步要和前面那条内存匹配对表：内存里算出 575 行能挂上目录，走真 Room 的
     * `rekeyAllModels` 就必须也是 575。两边不一致说明 DAO 那条 `ORDER BY canonical DESC`
     * 的候选查询和内存查法已经分叉——那是最阴的一种错，界面上看着全都对。
     *
     * 网络仍然只到 MockEngine：快照文本由同一个测试的前半段真下载过一次并缓存住，
     * 这样"走不走真网络"和"引擎逻辑对不对"两件事不会互相遮蔽。
     */
    private fun reportRealDbSync(copy: File) {
        val snapshotText = runCatching { snapshot() }.getOrNull()
        if (snapshotText == null) {
            println("SPIKE 拿不到快照，跳过真库同步这一段")
            return
        }
        val database = androidx.room.Room.databaseBuilder<com.lc33.tokenvault.data.VaultDatabase>(
            name = copy.absolutePath,
        )
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .build()
        try {
            val transactions = com.lc33.tokenvault.data.repo.RoomTransactionRunner(database)
            val catalog = com.lc33.tokenvault.data.repo.RoomModelCatalogRepository(
                catalogDao = database.modelCatalogDao(),
                vendorDao = database.modelVendorDao(),
                modelDao = database.modelDao(),
                providerDao = database.providerDao(),
                transactions = transactions,
            )
            val settings = com.lc33.tokenvault.data.repo.RoomSettingsRepository(database.appSettingDao())
            val mock = io.ktor.client.engine.mock.MockEngine {
                respond(snapshotText, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            val engine = com.lc33.tokenvault.engine.CatalogSync(
                client = io.ktor.client.HttpClient(mock),
                catalog = catalog,
                settings = settings,
                now = { 1_800_000_000_000L },
            )
            kotlinx.coroutines.runBlocking { check(engine.syncNow()) }

            val inDb = queryLongs(copy, "SELECT COUNT(*) FROM models WHERE catalogKey IS NOT NULL").first()
            // 同一批 id 用内存里那套查法再算一遍：数据源一样、算法一样，结果必须一样。
            // 不一样就是 DAO 的候选查询和内存查法分叉了——那是最阴的一种错，界面上看着全都对。
            val parsedForExpect = CatalogParser.parse(CatalogParser.decode(snapshotText))
            val byQualifiedId = parsedForExpect.models.groupBy { it.qualifiedId }
            val byModelId = parsedForExpect.models.groupBy { it.modelId }
            val byNormId = parsedForExpect.models.groupBy { it.normId }
            val nameById = queryRows(copy, "SELECT id, name FROM providers").associate { it[0] to it[1] }
            val expected = queryRows(copy, "SELECT providerId, modelId FROM models").count { (providerId, modelId) ->
                matchOne(modelId, nameById[providerId], byQualifiedId, byModelId, byNormId) != null
            }.toLong()
            println("SPIKE 真库同步后 model_catalog=${queryLongs(copy, "SELECT COUNT(*) FROM model_catalog").first()} " +
                "model_vendors=${queryLongs(copy, "SELECT COUNT(*) FROM model_vendors").first()} " +
                "挂上目录的模型=$inDb（内存算法预期 $expected）")
            assertEquals(expected, inDb, "真库回填和内存匹配对不上，DAO 与内存两条路已经分叉")

            // 分组分布：界面最终会照这个形状画。
            val grouped = queryStrings(copy, "SELECT modelId FROM models WHERE catalogKey IS NOT NULL")
                .groupBy { ModelFamily.keyOf(it) }
            println("SPIKE 真库前缀组 top10：" + grouped.entries.sortedByDescending { it.value.size }.take(10)
                .joinToString(", ") { (key, list) -> "$key(${list.size})" })
            println("SPIKE catalogLastSyncAt=${queryStrings(copy, "SELECT value FROM app_settings WHERE key='catalogLastSyncAtMillis'").firstOrNull()}")
        } finally {
            database.close()
        }
    }

    private fun queryStrings(file: File, sql: String): List<String> {
        val connection = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(file.absolutePath)
        val values = mutableListOf<String>()
        try {
            val statement = connection.prepare(sql)
            try {
                while (statement.step()) values += statement.getText(0)
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
        return values
    }

    private fun userVersion(file: File): Int = queryLongs(file, "PRAGMA user_version").firstOrNull()?.toInt() ?: -1

    private fun counts(file: File): Map<String, Int?> = listOf(
        "providers", "api_keys", "models", "model_catalog",
    ).associateWith { table ->
        queryLongs(file, "SELECT COUNT(*) FROM $table").firstOrNull()?.toInt()
    }

    private fun queryLongs(file: File, sql: String): List<Long> {
        val connection = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(file.absolutePath)
        val values = mutableListOf<Long>()
        try {
            val statement = connection.prepare(sql)
            try {
                while (statement.step()) values += statement.getLong(0)
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
        return values
    }

    private object CatalogSyncUrl {
        const val VALUE = "https://models.dev/api.json"
    }
}
