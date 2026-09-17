@file:OptIn(ExperimentalForeignApi::class)

package com.lc33.tokenvault.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFBridgingRelease
import platform.CoreFoundation.CFBridgingRetain
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSData
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
import platform.CoreFoundation.CFStringCreateWithCString

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
 */
class IosBiometricVault(
    private val session: VaultSession,
) : BiometricVault {

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
            // 借用而不是复制：Keychain 会拷贝一份进去，借来的引用随 lambda 结束即释放。
            session.withDek { dek -> store(dek) }
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
            status == errSecItemNotFound -> return BiometricUnlockOutcome.Invalidated
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
        val dict = baseQuery(context = null) ?: return
        try {
            SecItemDelete(dict)
        } finally {
            CFRelease(dict)
        }
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
    private fun store(dek: ByteArray): Boolean {
        disable()
        val dict = baseQuery(context = null) ?: return false
        var acl: CFTypeRef? = null
        var dataRef: CFTypeRef? = null
        try {
            val access = SecAccessControlCreateWithFlags(
                kCFAllocatorDefault,
                kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                kSecAccessControlBiometryCurrentSet,
                null,
            ) ?: return false
            acl = access
            CFDictionaryAddValue(dict, kSecAttrAccessControl, access)

            val data = dek.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = dek.size.toULong())
            }
            dataRef = CFBridgingRetain(data)
            CFDictionaryAddValue(dict, kSecValueData, dataRef)
            return SecItemAdd(dict, null) == errSecSuccess
        } finally {
            dataRef?.let { CFRelease(it) }
            acl?.let { CFRelease(it) }
            CFRelease(dict)
        }
    }

    /** 读取。[context] 必须是刚 `evaluatePolicy` 成功过的那个，否则会再弹一次验证框。 */
    private fun read(context: LAContext): Pair<OSStatus, ByteArray?> {
        val dict = baseQuery(context) ?: return errSecAuthFailed to null
        try {
            CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(dict, result.ptr)
                if (status != errSecSuccess) {
                    status to null
                } else {
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
            CFRelease(dict)
        }
    }

    /** 查询字典的公共部分：类、服务、账号；[context] 非空时带上它复用已验证的上下文。 */
    private fun baseQuery(context: LAContext?): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        putString(dict, kSecAttrService, SERVICE)
        putString(dict, kSecAttrAccount, ACCOUNT)
        if (context != null) {
            val ref = CFBridgingRetain(context)
            CFDictionaryAddValue(dict, kSecUseAuthenticationContext, ref)
            CFRelease(ref)
        }
        return dict
    }

    /** 造一个 CFString 放进字典。字典会 retain 它，所以这里马上释放自己那一份。 */
    private fun putString(dict: CFMutableDictionaryRef?, key: CFStringRef?, value: String) {
        val ref = memScoped {
            CFStringCreateWithCString(kCFAllocatorDefault, value.cstr, kCFStringEncodingUTF8)
        } ?: return
        CFDictionaryAddValue(dict, key, ref)
        CFRelease(ref)
    }

    private companion object {
        const val SERVICE = "com.lc33.tokenvault.biometric"
        const val ACCOUNT = "vault_bio"
    }
}
