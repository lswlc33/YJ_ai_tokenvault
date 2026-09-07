package com.lc33.tokenvault.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
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
 *
 * **阶段4 KMP 化**：实体与 DAO 零平台依赖，整个数据层进 commonMain。
 * 跨平台的 `Room.databaseBuilder<T>()` 不能反射拿 `VaultDatabase_Impl`，要靠
 * `@ConstructedBy` + expect object 由 KSP 在**每个 target** 生成 actual
 * （Room KMP 的官方模式，见 room-kmp 文档）。Android 端仍走
 * `Room.databaseBuilder(Context, ...)` 的老路径，不经过这个构造器对象。
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
@ConstructedBy(VaultDatabaseConstructor::class)
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
         *
         * 手写 DDL 的执行时机归各平台：Android 在 `RoomDatabase.Callback.onOpen`
         * 里跑（见 shared androidMain 的 applyHandWrittenSchema 扩展），iOS 包在
         * SQLiteDriver 包装层里跑——commonMain 摸不到平台的连接对象。
         */
        const val PARTIAL_INDEX_KEYS_DEFAULT =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_keys_default " +
                "ON api_keys(providerId) WHERE isDefault = 1"
    }
}

/**
 * Room KMP 的构造器桥。KSP 会在每个 target 的生成代码里补上 actual；
 * common 编译时还没有 actual，靠 suppress 放行（Room 官方模式）。
 */
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object VaultDatabaseConstructor : RoomDatabaseConstructor<VaultDatabase>
