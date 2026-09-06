package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
import com.lc33.tokenvault.domain.repo.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 设置项。这张表里没有秘密，所以整个类不碰 DEK，锁定态也能读写（§6.1 推论 2）。
 *
 * 两个实现选择：
 *
 * - **订阅整张表再挑那一个键**，而不是给每个键加一条 `WHERE key = :key` 的查询。
 *   这张表只有几十行，多读几行的代价可以忽略；而按键分查会让"加一个设置项"变成
 *   "加一条 SQL"，DAO 会随着设置项一起膨胀。代价是任何一项设置写入都会让这条流再发
 *   一次，所以后面必须跟 [distinctUntilChanged]——不跟的表现是每改一次别的开关，
 *   `AutoLocker` 就被重设一次时限（同一个值，但那是巧合而不是保证）。
 * - **值存 `value`（TEXT）而不是 `valueBlob`**：秒数不是秘密，而 blob 是给加密项留的。
 */
@Singleton
class RoomSettingsRepository @Inject constructor(
    private val dao: AppSettingDao,
) : SettingsRepository {

    override fun observeAutoLockTimeout(): Flow<AutoLockTimeout> = dao.observeAll()
        .map { rows -> AutoLockPolicy.decode(rows.firstOrNull { it.key == KEY_AUTO_LOCK }?.value) }
        .distinctUntilChanged()

    override suspend fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        dao.put(AppSettingEntity(key = KEY_AUTO_LOCK, value = AutoLockPolicy.encode(timeout)))
    }

    private companion object {
        /**
         * 键名照 §7.4 里的写法。
         *
         * **存的是秒数，不是下拉的下标**：存下标的话，以后在中间插一档就会让所有已存的
         * 设置悄悄改变含义，而没有任何迁移能发现它（用户选的「立即」变成「30 秒」）。
         */
        const val KEY_AUTO_LOCK = "autoLockSeconds"
    }
}
