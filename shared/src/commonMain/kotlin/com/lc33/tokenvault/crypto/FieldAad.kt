package com.lc33.tokenvault.crypto

import kotlin.jvm.JvmInline

/**
 * 字段级密文的绑定信息（AAD）。
 *
 * **红线 24**：每条字段级密文的 AAD 必须绑定行身份 `"表名:主键"`，否则密文可被跨行覆盖——
 * 把 A 供应商的 `secretEnc` 整块拷到 B 供应商那一行，解密照样成功，于是 B 就"变成"了 A。
 * 有 AAD 之后同样的搬运会在认证阶段失败（[DecryptionFailedException]）。
 *
 * 做成值类而不是裸 `String`，是为了让"忘记传 AAD"变成编译错误而不是运行时的安全洞。
 */
@JvmInline
value class FieldAad private constructor(val value: String) {

    fun bytes(): ByteArray = value.encodeToByteArray()

    override fun toString(): String = value

    companion object {
        /**
         * @param table 表名，用 DDL 里的原名（`api_keys` / `provider_accounts` / `providers`…）。
         * @param primaryKey 行主键。
         * @param column 列名。同一行里有多列密文时（`provider_accounts` 的用户名与密码）
         *   必须再区分，否则用户名的密文能被搬到密码列上。
         */
        fun of(table: String, primaryKey: Long, column: String): FieldAad {
            require(table.isNotBlank()) { "table name must not be blank" }
            require(column.isNotBlank()) { "column name must not be blank" }
            return FieldAad("$table:$primaryKey:$column")
        }

        /**
         * 字符串主键的设置行。WebDAV 凭据不在业务表里，但同样必须绑定列身份，
         * 否则用户名密文与密码密文可以互换。
         */
        fun ofSetting(key: String, column: String): FieldAad {
            require(key.isNotBlank()) { "setting key must not be blank" }
            require(column.isNotBlank()) { "column name must not be blank" }
            return FieldAad("app_settings:$key:$column")
        }

        /**
         * 备份包内层用的 AAD。备份里没有行主键（自然键引用，红线 27），
         * 所以绑的是"这是哪个包的哪一段"。
         */
        fun ofBackup(deviceId: String, section: String): FieldAad =
            FieldAad("backup:$deviceId:$section")

        /**
         * DEK 包裹槽位用的 AAD。
         *
         * 绑槽位是必要的：三条包裹路径包的是**同一个 DEK**（红线 25），三份密文长度也一样，
         * 所以不绑槽位的话，把 `dekWrappedByPin` 拷进 `dekWrappedByBiometric` 是可以解开的。
         * 后果不是泄密（还是同一个 DEK），而是"关掉生物识别"这个动作被悄悄绕过——
         * 用户以为关了，实际上 Keystore 那条路仍然能解出 DEK（红线 5 想禁的正是这个）。
         */
        fun ofDekSlot(slot: String): FieldAad {
            require(slot.isNotBlank()) { "slot name must not be blank" }
            return FieldAad("boot:dek:$slot")
        }
    }
}
