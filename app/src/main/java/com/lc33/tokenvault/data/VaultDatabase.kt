package com.lc33.tokenvault.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.dao.AuditLogDao
import com.lc33.tokenvault.data.dao.ClientProfileDao
import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.dao.ModelCatalogDao
import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.data.dao.ProviderAccountDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.data.entity.AuditLogEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.ModelCatalogEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity

/**
 * 数据库。
 *
 * **跑在系统自带 SQLite 上，不上 SQLCipher**（§4.4）：真正的秘密已经字段级加密，
 * SQLCipher 额外保护的只是元数据，代价是每 ABI 多 1–2 MB 原生库、且库只能解锁后打开。
 * 代价必须诚实写出来——**元数据在应用私有目录里是明文的**，这句话进了"关于"页。
 *
 * 实例是**应用级单例，启动即建**（§6.1 推论 1）。锁定 = 清零 DEK + 跳锁屏，**不关库**。
 * 于是只碰公开数据的后台任务（models.dev 同步、日志清理）在锁定态也能跑。
 *
 * `exportSchema = true` + Room Gradle 插件把 schema JSON 提交进仓库：没有基线，
 * 迁移测试就无从写起。
 */
@Database(
    entities = [
        GroupEntity::class,
        ProviderEntity::class,
        ApiKeyEntity::class,
        ProviderAccountEntity::class,
        ClientProfileEntity::class,
        ModelEntity::class,
        ModelCatalogEntity::class,
        ProbeRunEntity::class,
        AuditLogEntity::class,
        AppSettingEntity::class,
    ],
    version = VaultDatabase.VERSION,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun groupDao(): GroupDao
    abstract fun providerDao(): ProviderDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun providerAccountDao(): ProviderAccountDao
    abstract fun clientProfileDao(): ClientProfileDao
    abstract fun modelDao(): ModelDao
    abstract fun modelCatalogDao(): ModelCatalogDao
    abstract fun probeRunDao(): ProbeRunDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "vault.db"

        /**
         * "每个供应商至多一张默认 Key"，在**数据库层面**保证（§6.3）。
         *
         * Room 的 `@Index` 不支持 `WHERE` 子句，所以这条部分唯一索引只能手写。
         * 它的名字刻意**不以 `index_` 开头**——Room 校验 schema 时只读它自己创建的那些
         * （前缀 `index_`），所以 `idx_` 前缀的索引不会被当成"多出来的索引"而报不匹配。
         *
         * 为什么值得费这个劲：应用层的"设默认时清掉其它的"是在一个事务里做的，但如果
         * 将来某处漏了一句 `clearDefault`，两张默认会**静默**共存，而余额适配器会随机拿到
         * 其中一张。有了这条索引，那个 bug 会在写入时立刻炸出来。
         */
        const val PARTIAL_INDEX_KEYS_DEFAULT =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_keys_default " +
                "ON api_keys(providerId) WHERE isDefault = 1"

        /**
         * 在 `onOpen` 而不是只在 `onCreate` 里建索引。
         *
         * `IF NOT EXISTS` 让它幂等，代价是每次开库多一条 DDL（几乎为零）。收益是：
         * 万一某个版本的 `onCreate` 漏了它，用户升级上来时会自动补上，
         * 而不是带着一个缺索引的库一直跑下去。
         */
        fun applyHandWrittenSchema(db: SupportSQLiteDatabase) {
            db.execSQL(PARTIAL_INDEX_KEYS_DEFAULT)
        }
    }
}
