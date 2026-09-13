package com.lc33.tokenvault.domain.repo

/**
 * 一次已完成的删除，以及把它撤回来的能力。
 *
 * **为什么是"先删、再补偿"而不是"延迟删除"**：延迟删除（删了先不落库、等超时再真删）
 * 看起来更省事，但库里那些行会继续被探测、余额查询和备份看到——"已经删掉的密钥仍然
 * 出现在下一次探测里"与项目现有的不变量直接冲突。所以删除立刻落库，撤销是一次补偿写入。
 *
 * 代价是撤销只在内存里有效：提示消失或进程被杀之后就真的没了。这与改动前（完全
 * 不可撤销）相比是纯增量，不是回退。
 *
 * 实现方（仓库）持有删除前的完整快照，并在 [undo] 里按**原主键**写回。原主键是必须
 * 保留的：字段级密文的 AAD 绑定 `表:主键:列`（红线 24），换一个 id 写回会让
 * `secretEnc` / `balanceTokenEnc` / `usernameEnc` 全部无法解密。
 */
fun interface UndoableDeletion {
    /**
     * 把这次删除撤销掉。
     *
     * @return true 表示数据已按原样恢复；false 表示恢复不了（例如用户删除后又新建了
     *   同名/同指纹的行，唯一索引会冲突）。**实现不应抛异常**——撤销失败是正常结果，
     *   调用方只会把它显示成一句"恢复失败"，不需要崩溃。
     */
    suspend fun undo(): Boolean
}

/**
 * 把多次删除合成一次撤销。批量删除供应商之后弹的那条提示要对应用户刚才删掉的那一批。
 */
fun List<UndoableDeletion>.combined(): UndoableDeletion = UndoableDeletion {
    // 全部成功才算成功：有一部分没恢复回来时报"恢复失败"，比谎报成功好。
    var allOk = true
    for (deletion in this) {
        if (!deletion.undo()) allOk = false
    }
    allOk
}
