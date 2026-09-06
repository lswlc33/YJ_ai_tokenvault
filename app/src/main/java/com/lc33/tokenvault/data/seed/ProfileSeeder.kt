package com.lc33.tokenvault.data.seed

import com.lc33.tokenvault.data.dao.ClientProfileDao
import com.lc33.tokenvault.data.mapper.toEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 种入 8 个内置客户端伪装预设（M2 遗留项，计划.md §8.2）。
 *
 * 幂等由 [ClientProfileDao.seedBuiltin] 保证：按 `builtinKey` 找，不存在就插，
 * 存在且 `userEdited = 0` 且 `builtinRev` 更旧才更新。所以这个方法在每次启动时跑都是安全的——
 * 既能把新装用户的预设种出来，也能随版本把"没改过"的内置条目刷新成新指纹（升 [BuiltinPresets.REV]）。
 *
 * 只碰公开数据（预设里没有秘密），所以锁定态也能跑（§6.1 推论 2）。
 */
@Singleton
class ProfileSeeder @Inject constructor(
    private val dao: ClientProfileDao,
) {

    /** 种入全部内置预设。每次启动调用，幂等。 */
    suspend fun seed() {
        BuiltinPresets.BUILTIN_PRESETS.forEach { preset ->
            dao.seedBuiltin(preset.toEntity())
        }
    }
}
