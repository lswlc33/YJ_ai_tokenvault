package com.lc33.tokenvault.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 备份文件名的两种读法（§12.2）。
 *
 * 列表那一行要把 `yuanji-backup-<秒>.yjv` 翻成人能读的日期，读不出就得退回显示文件名——
 * 远端目录是用户自己的地盘，里面出现什么名字都有可能，解析这一侧不许抛。
 */
class WebDavEngineTest {

    @Test
    fun `文件名与落盘时刻可逆`() {
        val at = 1_758_000_000_000L
        val name = WebDavEngine.backupFileName(at)
        // 文件名只到秒，所以回到毫秒时末三位被抹掉——列表按这个排序与显示，够用。
        assertEquals(name, "yuanji-backup-1758000000.yjv")
        assertEquals(at / 1000 * 1000, WebDavEngine.backupEpochMillis(name))
    }

    @Test
    fun `不是本应用命名的文件读不出时刻也不抛`() {
        assertNull(WebDavEngine.backupEpochMillis("readme.txt"))
        assertNull(WebDavEngine.backupEpochMillis("yuanji-backup-.yjv"))
        assertNull(WebDavEngine.backupEpochMillis("yuanji-backup-abc.yjv"))
        assertNull(WebDavEngine.backupEpochMillis("yuanji-backup-1.7.yjv"))
        // 0 与超出 2100 年的值都判 null：后者再乘 1000 就溢出成负数，
        // 界面会画出一个 1970 年前的日期，那比"没有日期"更误导人。
        assertNull(WebDavEngine.backupEpochMillis("yuanji-backup-0.yjv"))
        assertNull(WebDavEngine.backupEpochMillis("yuanji-backup-99999999999999.yjv"))
    }
}
