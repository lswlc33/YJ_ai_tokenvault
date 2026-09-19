package com.lc33.tokenvault.domain.model

/**
 * 最近一次**成功**的备份（导出到本机文件，或上传 WebDAV）。
 *
 * 为什么要持久化：同步页那张状态卡以前只记在 ViewModel 内存里，退页即丢，于是
 * 「上次备份」永远是"还没有备份"，用户以为从来没成功过，而远端明明躺着一份包。
 * 存进 `app_settings` 后这一句才说得出口。
 *
 * 只存**时间戳与落点**，不存路径也不存文件名：SAF 的 uri 出了这一轮授权就读不到，
 * 记下来只会得到一个点开报错的假入口；落点两档足够回答"我上次备份到哪儿了"。
 *
 * @param atMillis 成功那一刻的墙钟毫秒（由调用方用 `nowMillis()` 传，红线 20）。
 * @param target 落点。
 */
data class LastBackup(val atMillis: Long, val target: BackupTarget)

/** 备份落在哪里。文案在 strings.xml，由页面映射（红线 19）。 */
enum class BackupTarget(val wireName: String) {
    /** 本机文件（SAF 导出）。 */
    LOCAL("local"),

    /** WebDAV 远端。 */
    WEBDAV("webdav"),
    ;

    companion object {
        /** 认不出来当 [LOCAL]：落点只是状态卡上的一句话，猜错不影响任何数据。 */
        fun fromWireName(value: String?): BackupTarget =
            entries.firstOrNull { it.wireName == value } ?: LOCAL
    }
}
