@file:OptIn(ExperimentalForeignApi::class)

package com.lc33.tokenvault.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecAuthFailed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData
import platform.darwin.OSStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 生物识别解锁（iOS 端，§7.3）：LocalAuthentication 验证 + 带访问控制的 Keychain 项保管 DEK。
 *
 * 与 Android 端的形状对齐，但凭据的落点不同：
 * - Android 把 Keystore 包裹的密文写进 `boot.dekWrappedByBiometric`；
 * - iOS 直接把 DEK 存进 Keychain，项的访问控制是 `kSecAccessControlBiometryCurrentSet`——
 *   验证由系统在**读密钥这一步**强制执行，软件层拿不到未验证的 DEK。所以
 *   `boot.dekWrappedByBiometric` 在 iOS 上恒为 null，开关仍以 `boot.biometricEnabled` 为准。
 *
 * `kSecAccessControlBiometryCurrentSet`（而不是 `...BiometryAny`）：用户新增或删除指纹后
 * 这一项自动失效，"换一个手指头就能解锁"不可能发生。代价是换指纹后要用 PIN 重新启用一次。
 *
 * 解锁时**先 `evaluatePolicy` 再带同一个 LAContext 读 Keychain**：前者负责画验证框、
 * 由我们控制文案；后者因为上下文已经验证过，不会再弹第二次。
 *
 * 两个跨 API 的注意点（都是编译期就撞过的）：
 * - `CFBridgingRetain` / `CFBridgingRelease` 声明在 **Foundation** 的 NSObject.h 里，
 *   不在 CoreFoundation 模块，import 必须写 `platform.Foundation.*`；
 * - `CFDictionaryCreate` 的键值数组用 `null` 回调创建，所以**字典不 retain 内容**，
 *   每次调用的值（包裹的密文、访问控制、LAContext）都必须在调用结束前自己持有并释放。
 */
class IosBiometricVault(
    private val session: VaultSession,
) : BiometricVault {

    /** 服务名与账号是常驻常量，随本对象活到进程结束，所以这两份 CFString 不释放。 */
    private val service: CFStringRef? =
        CFStringCreateWithCString(kCFAllocatorDefault, SERVICE, kCFStringEncodingUTF8)

    private val account: CFStringRef? =
        CFStringCreateWithCString(kCFAllocatorDefault, ACCOUNT, kCFStringEncodingUTF8)

    override fun isAvailable(): Boolean =
        LAContext().canEvaluatePolicy(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            error = null,
        )

    override suspend fun enable(prompt: BiometricPromptText): BiometricEnableOutcome {
        if (!isAvailable()) return BiometricEnableOutcome.Unavailable
        val context = LAContext().apply { localizedCancelTitle = prompt.cancel }
        // 启用前先验证一次：既确认这台设备的生物识别真的可用，也拿到用户当下的明确同意。
        if (!authenticate(context, prompt.subtitle)) return BiometricEnableOutcome.Cancelled
        val stored = try {
            // 借用而不是复制：Keychain 会拷一份进去，借来的引用随 lambda 结束即释放。
            session.withDek { dek -> store(dek, context) }
        } catch (_: Exception) {
            false
        }
        return if (stored) {
            BiometricEnableOutcome.Success(null)
        } else {
            BiometricEnableOutcome.Error("keychain store failed")
        }
    }

    override suspend fun unlock(blob: ByteArray?, prompt: BiometricPromptText): BiometricUnlockOutcome {
        val context = LAContext().apply { localizedCancelTitle = prompt.cancel }
        if (!authenticate(context, prompt.subtitle)) return BiometricUnlockOutcome.Cancelled
        val (status, bytes) = read(context)
        val dek = when {
            status == errSecItemNotFound || status == errSecAuthFailed ->
                return BiometricUnlockOutcome.Invalidated

            status == errSecUserCanceled -> return BiometricUnlockOutcome.Cancelled
            bytes == null -> return BiometricUnlockOutcome.Error("keychain read failed: $status")
            else -> bytes
        }
        return when (session.unlockWithDek(dek)) {
            is UnlockResult.Success -> BiometricUnlockOutcome.Success
            // unlockWithDek 在失败时已经擦掉 dek；凭据对不上就当它失效，让用户重新启用。
            else -> BiometricUnlockOutcome.Invalidated
        }
    }

    override fun disable() {
        withQuery(emptyList()) { query -> SecItemDelete(query) }
    }

    private suspend fun authenticate(context: LAContext, reason: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            context.evaluatePolicy(
                LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                reason,
            ) { success, _ ->
                if (continuation.isActive) continuation.resume(success)
            }
        }

    /** 写入：先删旧项，再带生物识别访问控制加一条新的。 */
    private fun store(dek: ByteArray, context: LAContext): Boolean {
        disable()
        val access = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            kSecAccessControlBiometryCurrentSet,
            null,
        ) ?: return false
        // CFData 与 NSData 是 toll-free bridged，桥接之后 keychain 就收得了。
        val dataRef = CFBridgingRetain(dek.toNSData())
        val contextRef = CFBridgingRetain(context)
        try {
            val status = withQuery(
                listOf(
                    kSecAttrAccessControl to access,
                    kSecValueData to dataRef,
                    // 带上刚验证过的上下文：新建时若也要用户在场，系统不会再弹一次。
                    kSecUseAuthenticationContext to contextRef,
                ),
            ) { query -> SecItemAdd(query, null) }
            return status == errSecSuccess
        } finally {
            CFRelease(access)
            dataRef?.let { CFRelease(it) }
            contextRef?.let { CFRelease(it) }
        }
    }

    /** 读取。[context] 必须是刚 `evaluatePolicy` 成功过的那个，否则会再弹一次验证框。 */
    private fun read(context: LAContext): Pair<OSStatus, ByteArray?> {
        val contextRef = CFBridgingRetain(context)
        try {
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = withQuery(
                    listOf(
                        kSecReturnData to kCFBooleanTrue,
                        kSecUseAuthenticationContext to contextRef,
                    ),
                ) { query -> SecItemCopyMatching(query, result.ptr) }
                if (status != errSecSuccess) {
                    status to null
                } else {
                    // CFDataRef 转回 NSData 之后由 ARC 接管这一份引用。
                    val data = CFBridgingRelease(result.value) as? NSData
                    val length = data?.length?.toInt() ?: 0
                    if (data == null || length <= 0) {
                        errSecAuthFailed to null
                    } else {
                        errSecSuccess to data.bytes?.readBytes(length)
                    }
                }
            }
        } finally {
            contextRef?.let { CFRelease(it) }
        }
    }

    /**
     * 建一个查询字典并把 [block] 跑掉。字典与键值数组都随本次调用消失。
     *
     * 字典可能建不出来（内存不足），那时 [block] 收到 null，Security 框架会给出错误码——
     * 比抛异常好：调用方（锁定流程）不该因为一次查询没建起来就崩。
     *
     * `CFDictionaryCreate` 的两个回调参数都是 null，所以字典**不持有**内容——
     * 这也是为什么值一律在本函数调用期间由调用方持有。
     */
    private inline fun <T> withQuery(
        extra: List<Pair<CFStringRef?, CFTypeRef?>>,
        block: (CFDictionaryRef?) -> T,
    ): T = memScoped {
        val pairs = listOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to account,
        ) + extra
        val keys = allocArrayOf(*pairs.map { it.first }.toTypedArray())
        val values = allocArrayOf(*pairs.map { it.second }.toTypedArray())
        val dict = CFDictionaryCreate(
            kCFAllocatorDefault,
            keys.reinterpret(),
            values.reinterpret(),
            pairs.size.convert(),
            null,
            null,
        )
        try {
            block(dict)
        } finally {
            CFRelease(dict)
        }
    }

    private companion object {
        const val SERVICE = "com.lc33.tokenvault.biometric"
        const val ACCOUNT = "vault_bio"
    }
}

/** ByteArray → NSData。空数组直接给空 NSData：`addressOf(0)` 对空数组会越界。 */
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    } ?: NSData()
}
