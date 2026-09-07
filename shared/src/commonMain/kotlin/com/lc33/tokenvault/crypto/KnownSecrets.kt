package com.lc33.tokenvault.crypto

import kotlin.concurrent.Volatile

/**
 * 会话级「已知明文秘密」追踪器（红线 32 的第一道）。
 *
 * [Redactor] 的第一道按「这一台设备上已经见过的明文值」替换，而"见过"的时机是：
 * 用户在详情页展开了某把密钥、某条账号（[com.lc33.tokenvault.ui.shell.ProviderDetailViewModel]
 * 是唯一解密密钥的页面）。这个类就是那份"见过"清单的单一权威存储。
 *
 * 生命周期与解锁会话对齐：
 * - 展开 / 解密出明文时 [add]，随用户越看越多而增长。
 * - 锁定时 [clear]——锁定意味着所有明文都不该再活着（红线 6 的精神延伸），
 *   这份清单也一并清零，否则"锁上了，但脱敏器还记着上一把密钥的明文"。
 *
 * 为什么是独立类而不是塞进 [VaultSession]：
 * - `VaultSession` 是"唯一持有明文 DEK"的阶段机，职责是解锁/锁定，不该再背一份脱敏关注点。
 * - 明文清单的消费者是 `crypto/` 的 [Redactor]，生产者是 `ui/` 的 ViewModel，
 *   夹在中间的是 `platform/` 的锁定事件——三者共享同一个注入实例，比让某一家"顺带管理"更清晰。
 *
 * 为什么存 [CharArray] 而不是 `String`：
 * 红线 1——明文秘密一律走 [CharArray] 传递，`String` 不可变、擦不掉。这里存副本，
 * 调用方自己那份照样擦，互不影响。[snapshot] 里为脱敏临时构造的 `String` 擦不掉，
 * 这是 [Redactor] 做 `replace` 的固有代价（已知限制），但清单本体是可擦的。
 */
class KnownSecrets {

    /** 副本一份份持有，`add` 后调用方擦自己那份不影响这里。 */
    @Volatile
    private var secrets: List<CharArray> = emptyList()

    /**
     * 登记一份已知明文。
     *
     * 存的是**副本**：调用方（ViewModel）随后会 zeroize 自己那一份，这里得留得住。
     *
     * 并发策略：不用 `synchronized`（JVM 专属，会挡住 iOS 编译），而是每次修改都
     * 用不可变列表快照替换 [secrets]，配合 `@Volatile` 保证跨线程可见性。
     * 这里只有「append」和「整表清空」两种操作，没有读-改-写竞争，快照替换足够。
     */
    fun add(secret: CharArray) {
        if (secret.isEmpty()) return
        // 去重：同一把密钥可能被反复展开，重复登记只会让脱敏多做几轮无用替换。
        if (secrets.any { it.contentEquals(secret) }) return
        secrets = secrets + secret.copyOf()
    }

    /** 清空并逐一置零。锁定（[VaultSession.lock]）时调用。 */
    fun clear() {
        val old = secrets
        secrets = emptyList()
        old.forEach { it.zeroize() }
    }

    /**
     * 给 [Redactor] 的第一道用。每次现读、不持快照——与
     * [Redactor.knownSecrets] 参数"做成 lambda 而非集合"的意图一致：
     * 清单会随用户继续展开而增长，脱敏器不该持有一份过期副本。
     */
    fun snapshot(): List<String> = secrets.map { it.concatToString() }
}
