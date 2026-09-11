package com.lc33.tokenvault.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.KeySettingsEntity
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

@RunWith(AndroidJUnit4::class)
class VaultDatabaseTest {

    private lateinit var db: VaultDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                        database.execSQL("PRAGMA foreign_keys = ON")
                        database.applyHandWrittenSchema()
                    }
                },
            )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `默认Key索引已经取消`() = runBlocking {
        val providerId = insertProvider()
        insertKey(providerId)

        val indexes = db.openHelper.readableDatabase.query("PRAGMA index_list(api_keys)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertFalse(indexes.contains("idx_keys_default"))
    }

    @Test
    fun `删供应商级联删密钥与Key设置`() = runBlocking {
        val providerId = insertProvider()
        val keyId = insertKey(providerId)
        db.keySettingsDao().insert(
            KeySettingsEntity(
                keyId = keyId,
                apiBaseUrl = "https://example.com/v1",
                apiRoot = "https://example.com",
                updatedAt = 1L,
            ),
        )

        db.providerDao().delete(providerId)

        assertTrue(db.apiKeyDao().findByProvider(providerId).isEmpty())
        assertTrue(db.keySettingsDao().findAll().isEmpty())
    }

    @Test
    fun `删供应商级联删账号`() = runBlocking {
        val providerId = insertProvider()
        db.providerAccountDao().insert(
            ProviderAccountEntity(providerId = providerId, label = "a", createdAt = 1L, updatedAt = 1L),
        )

        db.providerDao().delete(providerId)

        assertTrue(
            db.providerAccountDao().findAll().none { it.providerId == providerId },
        )
    }

    @Test
    fun `删分组把供应商顶成未分组`() = runBlocking {
        val groupId = db.groupDao().insert(GroupEntity(name = "g", sortOrder = 0))
        val providerId = insertProvider(groupId = groupId)

        db.groupDao().delete(groupId)

        assertNull(db.providerDao().findById(providerId)?.groupId)
    }

    @Test
    fun `删客户端预设把Key设置顶成默认`() = runBlocking {
        val profileId = db.clientProfileDao().insert(
            ClientProfileEntity(name = "p", userAgent = "ua"),
        )
        val providerId = insertProvider()
        val keyId = insertKey(providerId)
        db.keySettingsDao().insert(
            KeySettingsEntity(
                keyId = keyId,
                apiBaseUrl = "https://example.com/v1",
                apiRoot = "https://example.com",
                clientProfileId = profileId,
                updatedAt = 1L,
            ),
        )

        db.clientProfileDao().deleteCustom(profileId)

        assertNull(db.keySettingsDao().findByKey(keyId)?.clientProfileId)
    }

    @Test
    fun `Key排序就是优先级`() = runBlocking {
        val providerId = insertProvider()
        val first = insertKey(providerId, label = "a", sortOrder = 1)
        val second = insertKey(providerId, label = "b", sortOrder = 0)

        val ordered = db.apiKeyDao().findByProvider(providerId).map { it.key.id }
        assertEquals(listOf(second, first), ordered)
    }

    private suspend fun insertProvider(groupId: Long? = null): Long =
        db.providerDao().insert(
            ProviderEntity(
                name = "p",
                websiteUrl = "https://example.com",
                groupId = groupId,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

    private suspend fun insertKey(
        providerId: Long,
        label: String = "k",
        sortOrder: Int = 0,
    ): Long = db.apiKeyDao().insertRaw(
        ApiKeyEntity(
            providerId = providerId,
            label = label,
            note = "",
            secretEnc = byteArrayOf(1, 2, 3),
            fingerprint = "fp-$providerId-$label",
            sortOrder = sortOrder,
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )
}
