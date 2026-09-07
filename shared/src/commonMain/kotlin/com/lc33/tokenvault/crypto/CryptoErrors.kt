package com.lc33.tokenvault.crypto

/**
 * 加密层的异常。
 *
 * 红线 8：**解密失败必须报错并中断，不允许静默降级成 `null`**。所以这一层没有任何
 * 返回可空值的解密函数——拿不到明文就抛，由调用方决定怎么呈现。
 * 一个返回 `null` 的解密 API 迟早会被写成 `?: ""`，然后一张坏掉的密钥看起来像"空密钥"。
 *
 * **消息一律用英文**，不进 strings.xml：它们是给开发者与日志看的诊断信息，不是 UI 文案
 * （红线 19 不允许 Kotlin 里出现中文字面量，而 `crypto/` 是纯 Kotlin 层、读不到资源）。
 * 用户看到的"已锁定""PIN 错误"由 UI 层按异常类型自己出（§6.1 推论 1）。
 */
sealed class CryptoException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * 认证失败：口令不对、密文被改、或者 **AAD 不匹配**（密文被搬到了别的行上，红线 24）。
 *
 * 刻意不区分这三种原因：区分了就等于告诉攻击者"口令对了但行号不对"。
 * 需要区分的地方（解锁页要显示"PIN 错误"）由调用点根据上下文自己决定文案。
 */
class DecryptionFailedException(
    where: String,
    cause: Throwable? = null,
) : CryptoException("decryption failed at $where", cause)

/** 封套的版本或算法标识不认识。跨版本恢复必须走显式迁移（红线 9），不猜。 */
class UnsupportedEnvelopeException(message: String) : CryptoException(message)

/** KDF 参数不合法或超过封顶值。见 §7.2 与 §12.1：导入端只能照着 header 算，所以要有上限。 */
class InvalidKdfParamsException(message: String) : CryptoException(message)

/** 金库已锁定，DEK 不在内存里。UI 层统一转成"已锁定"（§6.1 推论 1）。 */
class VaultLockedException : CryptoException("vault is locked")
