package com.lc33.tokenvault.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.platform.applyHandWrittenSchema
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据层仪器测试（§6.3）。
 *
 * 这些断言只能在**真实 SQLite** 上验证，JVM 单测的 FakeDao 测不了：
 * - 手写的部分唯一索引 `idx_keys_default`（Room 的 `@Index` 表达不了 `WHERE`）；
 * - 外键的 CASCADE / SET_NULL（`ForeignKey` 注解只是声明，真正生效靠 `PRAGMA foreign_keys`）。
 *
 * 每一处都对应 CLAUDE.md 里"改坏了不会立刻报错"的东西——删供应商该不该连带删 Key、
 * 删分组该不该把供应商顶成"未分组"，这些错了只在真机上用一段时间才炸出来。
 */
@RunWith(AndroidJUnit4::class)
class VaultDatabaseTest {

    private lateinit var db: VaultDatabase
    private lateinit var groupDao: GroupDao
    private lateinit var providerDao: ProviderDao
    private lateinit var keyDao: ApiKeyDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // 与 VaultModule.provideDatabase 保持一致：外键约束必须显式开，否则
                        // CASCADE/SET_NULL 全是摆设。测试就是要锁住"生产环境开了"这件事。
                        database.execSQL("PRAGMA foreign_keys = ON")
                        database.applyHandWrittenSchema()
                    }
                },
            )
            .allowMainThreadQueries()
            .build()
        groupDao = db.groupDao()
        providerDao = db.providerDao()
        keyDao = db.apiKeyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------ 部分唯一索引

    @Test
    fun idx_keys_default存在() = runBlocking {
        // 首次访问才真正建库并触发 onOpen → 手写索引被应用。
        val providerId = insertProvider()
        insertKey(providerId, isDefault = true)

        val indexes = db.openHelper.readableDatabase.query(
            "PRAGMA index_list(api_keys)",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue("手写索引 idx_keys_default 必须在库中存在：$indexes", indexes.contains("idx_keys_default"))
    }

    @Test
    fun 同一供应商两张默认Key被唯一索引拒绝() = runBlocking {
        val providerId = insertProvider()
        insertKey(providerId, isDefault = true)

        // insertRaw 绕过 DAO 的 insert 事务（那个会先 clearDefault），直接撞唯一索引。
        val second = ApiKeyEntity(
            providerId = providerId,
            label = "second",
            secretEnc = byteArrayOf(1, 2, 3),
            fingerprint = "fp-second",
            isDefault = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val threw = try {
            keyDao.insertRaw(second)
            false
        } catch (_: Throwable) {
            true
        }

        // 两个断言都查真实状态，不依赖异常消息措辞（SQLite 的 UNIQUE 冲突消息不保证含索引名）：
        // 1. 插入必须抛约束冲突；
        // 2. 且库里不能出现两张默认。
        val defaultCount = keyDao.findByProvider(providerId).count { it.isDefault }
        assertTrue("第二张默认 Key 必须被索引拒绝，而不是静默共存", threw)
        assertEquals(
            "库里不能出现两张默认 Key（索引要么挡住插入、要么挡住遗留）",
            1,
            defaultCount,
        )
    }

    // ------------------------------------------------------------------ 外键 CASCADE

    @Test
    fun 删供应商级联删密钥() = runBlocking {
        val providerId = insertProvider()
        insertKey(providerId, isDefault = false)

        providerDao.delete(providerId)

        assertTrue("删供应商后其密钥必须被 CASCADE 清掉", keyDao.findByProvider(providerId).isEmpty())
    }

    @Test
    fun 删供应商级联删账号() = runBlocking {
        val providerId = insertProvider()
        db.providerAccountDao().insert(
            ProviderAccountEntity(providerId = providerId, label = "a", createdAt = 1L, updatedAt = 1L),
        )

        providerDao.delete(providerId)

        assertTrue(
            "删供应商后其账号必须被 CASCADE 清掉",
            db.providerAccountDao().findAll().none { it.providerId == providerId },
        )
    }

    // ------------------------------------------------------------------ 外键 SET_NULL

    @Test
    fun 删分组把供应商顶成未分组() = runBlocking {
        val groupId = groupDao.insert(GroupEntity(name = "g", sortOrder = 0))
        val providerId = insertProvider(groupId = groupId)

        groupDao.delete(groupId)

        val provider = providerDao.findById(providerId)!!
        assertNull("删分组后 provider.groupId 必须被 SET_NULL 置空", provider.groupId)
    }

    @Test
    fun 删客户端预设把供应商顶成默认() = runBlocking {
        val profileId = db.clientProfileDao().insert(
            ClientProfileEntity(name = "p", userAgent = "ua"),
        )
        val providerId = insertProvider(clientProfileId = profileId)

        db.clientProfileDao().deleteCustom(profileId)

        val provider = providerDao.findById(providerId)!!
        assertNull("删预设后 provider.clientProfileId 必须被 SET_NULL 置空", provider.clientProfileId)
    }

    // ------------------------------------------------------------------ 帮手

    private suspend fun insertProvider(
        groupId: Long? = null,
        clientProfileId: Long? = null,
    ): Long = providerDao.insert(
        ProviderEntity(
            name = "p",
            apiBaseUrl = "https://example.com",
            apiRoot = "https://example.com/v1",
            groupId = groupId,
            clientProfileId = clientProfileId,
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )

    private suspend fun insertKey(providerId: Long, isDefault: Boolean): Long = keyDao.insertRaw(
        ApiKeyEntity(
            providerId = providerId,
            label = "k",
            secretEnc = byteArrayOf(1, 2, 3),
            fingerprint = "fp-$providerId",
            isDefault = isDefault,
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )
}
