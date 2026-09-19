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
import platform.CoreFoundation.CFErrorRefVar
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
import platform.Foundation.NSError
import platform.Foundation.create
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecAccessControlRef
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecAuthFailed
import platform.Security.errSecInteractionNotAllowed
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
 *
 * **所有失败都要带原因出去**：`evaluatePolicy` 的 `NSError`、`SecAccessControlCreateWithFlags`
 * 的 `CFErrorRef*` 出参、`SecItemAdd` / `SecItemCopyMatching` 的 OSStatus 一律不许丢——
 * 丢了之后界面只能说"失败"，而这一项失败的常见原因（没设锁屏密码、刚录了新指纹、
 * 连续按错被系统锁住）各自要做的下一件事完全不同。
 */
class IosBiometricVault(
    private val session: VaultSession,
) : BiometricVault {

    /** 服务名与账号是常驻常量，随本对象活到进程结束，所以这几份 CFString 不释放。 */
    private val service: CFStringRef? =
        CFStringCreateWithCString(kCFAllocatorDefault, SERVICE, kCFStringEncodingUTF8)

    private val account: CFStringRef? =
        CFStringCreateWithCString(kCFAllocatorDefault, ACCOUNT, kCFStringEncodingUTF8)

    /**
     * 写入时用的第二个账号（staging）。
     *
     * Keychain 用 (class, service, account) 认唯一性，同一个账号上没法"先加新的再删旧的"，
     * 所以换一份 DEK 只能落在另一个账号上、提交成功后再把正式账号换过来。
     */
    private val stagingAccount: CFStringRef? =
        CFStringCreateWithCString(kCFAllocatorDefault, ACCOUNT_STAGING, kCFStringEncodingUTF8)

    override fun isAvailable(): Boolean =
        LAContext().canEvaluatePolicy(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            // 这里传 null 是有意的：这一问只关心"能不能"，而"为什么不能"（没录指纹 /
            // 没设锁屏密码 / 硬件不可用）在界面上都收敛成同一句"这台设备现在用不了"。
            error = null,
        )

    override suspend fun enable(prompt: BiometricPromptText): BiometricEnableOutcome {
        if (!isAvailable()) return BiometricEnableOutcome.Unavailable
        val context = LAContext().apply { localizedCancelTitle = prompt.cancel }
        // 启用前先验证一次：既确认这台设备的生物识别真的可用，也拿到用户当下的明确同意。
        when (val auth = authenticate(context, prompt)) {
            IosAuth.Success -> Unit
            IosAuth.Cancelled -> return BiometricEnableOutcome.Cancelled
            IosAuth.LockedOut -> return BiometricEnableOutcome.LockedOut
            // 函数开头 `isAvailable()` 已经挡过一次，走到这里只可能是两步之间用户把指纹删光了。
            // 报"这台设备现在用不了"，而不是"失败了"：没有哪一步出错，只是没东西可验了。
            IosAuth.NotEnrolled -> return BiometricEnableOutcome.Unavailable
            is IosAuth.Failed -> return BiometricEnableOutcome.Error(auth.reason)
        }
        // 借用而不是复制：Keychain 会拷一份进去，借来的引用随 lambda 结束即释放。
        return try {
            session.withDek { dek ->
                when (val stored = store(dek, context)) {
                    StoreOutcome.Stored -> BiometricEnableOutcome.Success(null)
                    is StoreOutcome.Failed -> BiometricEnableOutcome.Error(stored.reason)
                }
            }
        } catch (_: Exception) {
            BiometricEnableOutcome.Error("vault is locked")
        }
    }

    override suspend fun unlock(blob: ByteArray?, prompt: BiometricPromptText): BiometricUnlockOutcome {
        val context = LAContext().apply { localizedCancelTitle = prompt.cancel }
        when (val auth = authenticate(context, prompt)) {
            IosAuth.Success -> Unit
            IosAuth.Cancelled -> return BiometricUnlockOutcome.Cancelled
            // 锁住（连续失败太多次）时**不碰 boot**：Keychain 那一份完全正常，
            // 判成 Invalidated 会把一个没坏的凭据关掉，用户还得用 PIN 重开一次。
            IosAuth.LockedOut -> return BiometricUnlockOutcome.LockedOut
            // 一个指纹/人脸都没录了：这一项绑的是 `BiometryCurrentSet`，那份 Keychain
            // 已经被系统判死，**再也不会读出来**。所以这是真失效，交给调用方关开关清包裹
            // （留着它只会让开关一直"开着"，而每一次都注定解不开）。
            IosAuth.NotEnrolled -> return BiometricUnlockOutcome.Invalidated
            is IosAuth.Failed -> return BiometricUnlockOutcome.Error(auth.reason)
        }
        val (status, bytes) = read(context)
        if (status == errSecItemNotFound || status == errSecAuthFailed) {
            return BiometricUnlockOutcome.Invalidated
        }
        if (status == errSecUserCanceled) return BiometricUnlockOutcome.Cancelled
        if (status == errSecInteractionNotAllowed) {
            // 设备正处于锁屏 / 生物识别临时不可用：凭据没坏，只是现在读不出来。
            return BiometricUnlockOutcome.LockedOut
        }
        val dek = bytes ?: return BiometricUnlockOutcome.Error("keychain read failed: OSStatus $status")
        val result = session.unlockWithDek(dek)
        if (result is UnlockResult.Success) return BiometricUnlockOutcome.Success
        // 身份校验没过（或长度 / boot 不对）：这条路拿回来的东西不是本库的那把 DEK，
        // 留着它只会让下一次又"解锁成功但数据解不开"。删掉平台凭据，开关由调用方关掉。
        disable()
        return BiometricUnlockOutcome.Error(
            (result as? UnlockResult.Unavailable)?.reason ?: "platform DEK rejected",
        )
    }

    override fun disable() {
        // 两个账号都要清：`store` 是"先写 staging、再换到正式账号"，中途失败会留下
        // staging 那一份。留着它 = 一份没人读的 DEK 躺在 Keychain 里。
        withQuery(accountRef = account) { query -> SecItemDelete(query) }
        withQuery(accountRef = stagingAccount) { query -> SecItemDelete(query) }
    }

    /**
     * 系统验证框的结果。
     *
     * **[IosAuth.Failed] 必须存在**：旧实现把 `reply` 里那个 `NSError?` 直接丢掉，
     * 于是"用户按了取消""Touch ID 被锁住""系统弹不出来"三种情况在调用方看起来一模一样，
     * 用户按了没反应、也永远不知道下一步该做什么。
     */
    private sealed interface IosAuth {
        data object Success : IosAuth
        data object Cancelled : IosAuth
        data object LockedOut : IosAuth

        /** 这台设备现在**没有**任何已录入的生物识别（用户删光了指纹/人脸）。 */
        data object NotEnrolled : IosAuth
        data class Failed(val reason: String) : IosAuth
    }

    /**
     * 弹系统验证框。
     *
     * **`localizedReason` 传 title，不传 subtitle**：iOS 那个框只有**一行**说明文字的位置
     * （`LAContext` 上除 `localizedReason` 外只有 `localizedCancelTitle` /
     * `localizedFallbackTitle`，没有 Android `PromptInfo.setSubtitle` 的对应物），
     * 而 Android 端这一格用的正是 `setTitle(prompt.title)`。之前送出去的是 subtitle，
     * 于是 iOS 用户看到的是"验证一下是你，才能解开数据密钥"这一串目的描述，
     * 而框上从来没写过"这是要解锁金库"还是"这是要开启生物识别"——两件事在启用与解锁
     * 两个场景里恰好是不同的 title，混成一句就分不出来了。
     *
     * 底部那个按钮已经由 `localizedCancelTitle` 接走（调用点设好）。
     * `localizedFallbackTitle` 刻意不设：`...WithBiometrics` 这条策略没有"改用设备密码"的
     * 退路，设了也不会出现，而设成"改用 PIN"更是假话（系统不会拿它去解本应用的 PIN）。
     */
    private suspend fun authenticate(context: LAContext, prompt: BiometricPromptText): IosAuth =
        suspendCancellableCoroutine { continuation ->
            context.evaluatePolicy(
                LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                prompt.title,
            ) { success, error ->
                if (!continuation.isActive) return@evaluatePolicy
                continuation.resume(
                    when {
                        success -> IosAuth.Success
                        // 取消是用户的选择，不是故障：这一档不能进 [IosAuth.Failed]，
                        // 否则每次按取消都会冒一条系统错误。
                        error?.code == LAErrorUserCancel || error?.code == LAErrorAppCancel ||
                            error?.code == LAErrorSystemCancel || error?.code == LAErrorUserFallback ->
                            IosAuth.Cancelled

                        error?.code == LAErrorBiometryLockout -> IosAuth.LockedOut

                        // 没录入任何生物识别 ≠ 暂时锁住：这一档"等一会儿"不会变好，
                        // 说"稍后再试"是把人往错的方向推。它归到"这条路本身没有了"，
                        // 由调用方按失效善后（关掉开关、清掉那份再也读不出来的 Keychain 项）。
                        error?.code == LAErrorBiometryNotEnrolled -> IosAuth.NotEnrolled

                        else -> IosAuth.Failed(error?.localizedDescription ?: "biometry unavailable")
                    },
                )
            }
        }

    /** [store] 的结果：要么已经落在正式账号上，要么带着原因失败（此时正式那一份没被动过）。 */
    private sealed interface StoreOutcome {
        data object Stored : StoreOutcome
        data class Failed(val reason: String) : StoreOutcome
    }

    /** 访问控制的创建结果。失败那一份带的是 `CFError` 里的人话，不是错误码。 */
    private sealed interface AccessControl {
        data class Ok(val ref: SecAccessControlRef) : AccessControl
        data class Failed(val reason: String) : AccessControl
    }

    /**
     * 写入：**先 add 到 staging，成功后才删正式项、再把同一份 add 到正式账号**。
     *
     * 旧实现是"先 disable() 删掉旧的、再 SecItemAdd 新的"，那个顺序在 add 失败时
     * （访问控制建不出来、权限、磁盘异常）会把用户**本来能用**的凭据删了个干净：
     * 开关还开着，但生物识别从此再也解不开，只能回 PIN 重开一次。
     * 现在 add 失败时正式那一份原地不动；提交那一步万一失败，staging 里还留着新的一份，
     * 由下一次 [enable] 或 [disable] 收尾，而调用方拿到的是一句具体原因。
     */
    private fun store(dek: ByteArray, context: LAContext): StoreOutcome {
        val access = when (val created = createAccessControl()) {
            is AccessControl.Ok -> created.ref
            is AccessControl.Failed -> return StoreOutcome.Failed(created.reason)
        }
        // CFData 与 NSData 是 toll-free bridged，桥接之后 keychain 就收得了。
        val dataRef = CFBridgingRetain(dek.toNSData())
        val contextRef = CFBridgingRetain(context)
        try {
            // 每写一次都要带上刚验证过的上下文：新建时若也要用户在场，系统不会再弹一次。
            val entries: List<Pair<CFStringRef?, CFTypeRef?>> = listOf(
                kSecAttrAccessControl to access,
                kSecValueData to dataRef,
                kSecUseAuthenticationContext to contextRef,
            )
            // 先扫掉上一次可能留下的 staging（中途被杀留下的半成品）。
            withQuery(accountRef = stagingAccount) { query -> SecItemDelete(query) }
            val staged = withQuery(entries, accountRef = stagingAccount) { query -> SecItemAdd(query, null) }
            if (staged != errSecSuccess) {
                return StoreOutcome.Failed("keychain add failed: OSStatus $staged")
            }
            withQuery(accountRef = account) { query -> SecItemDelete(query) }
            val committed = withQuery(entries, accountRef = account) { query -> SecItemAdd(query, null) }
            if (committed != errSecSuccess) {
                return StoreOutcome.Failed("keychain commit failed: OSStatus $committed")
            }
            withQuery(accountRef = stagingAccount) { query -> SecItemDelete(query) }
            return StoreOutcome.Stored
        } finally {
            CFRelease(access)
            dataRef?.let { CFRelease(it) }
            contextRef?.let { CFRelease(it) }
        }
    }

    /**
     * 建生物识别访问控制。**错误出参必须接**：`SecAccessControlCreateWithFlags` 返回 null
     * 时旧实现只知道"失败了"，而真正的原因（设备没设锁屏密码、生物识别被限制）都在
     * 那个 `CFErrorRef*` 里——不接就等于把唯一一句能给用户的话扔掉了。
     *
     * 返回 null 时那个 out 参数**不由我们负责释放**（Core Foundation 的"Get 规则"：
     * 没取得所有权），所以这里只做一次 toll-free 桥接读文案，不 CFRelease。
     */
    private fun createAccessControl(): AccessControl = memScoped {
        val error = alloc<CFErrorRefVar>()
        val ref = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            kSecAccessControlBiometryCurrentSet,
            error.ptr,
        )
        if (ref != null) {
            AccessControl.Ok(ref)
        } else {
            val reason = (error.value?.let { CFBridgingRelease(it) as? NSError }?.localizedDescription
                ?: "access control unavailable")
            AccessControl.Failed(reason)
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
        extra: List<Pair<CFStringRef?, CFTypeRef?>> = emptyList(),
        accountRef: CFStringRef? = account,
        block: (CFDictionaryRef?) -> T,
    ): T = memScoped {
        val pairs = listOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            // 账号可换：`store` 那份"先写 staging 再提交到正式账号"的写法要用到第二个账号，
            // 而两个账号才是这一层"写失败不丢旧凭据"的全部本钱。
            kSecAttrAccount to accountRef,
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

        /** 写入过程中用的账号，见 [store] 的"先 staging 再提交"。 */
        const val ACCOUNT_STAGING = "vault_bio.staging"
    }
}

/** ByteArray → NSData。空数组直接给空 NSData：`addressOf(0)` 对空数组会越界。 */
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    } ?: NSData()
}
