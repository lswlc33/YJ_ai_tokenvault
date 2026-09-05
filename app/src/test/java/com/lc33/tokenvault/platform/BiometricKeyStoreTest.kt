package com.lc33.tokenvault.platform

import androidx.biometric.BiometricManager
import com.lc33.tokenvault.domain.BiometricAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生物识别里两块**能在 JVM 上测**的逻辑：返回码分流与 `{iv, ct}` 编码。
 *
 * 其余部分（Keystore 生成、BiometricPrompt 交互）整体依赖框架与真实指纹，只能真机验收，
 * 验收项见 §17「改指纹后生物识别自动失效并回退 PIN」。
 */
class BiometricKeyStoreTest {

    @Test
    fun `七档返回码各有归属`() {
        assertEquals(
            BiometricAvailability.AVAILABLE,
            biometricAvailabilityOf(BiometricManager.BIOMETRIC_SUCCESS),
        )
        assertEquals(
            BiometricAvailability.NO_HARDWARE,
            biometricAvailabilityOf(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            BiometricAvailability.HARDWARE_UNAVAILABLE,
            biometricAvailabilityOf(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            BiometricAvailability.NONE_ENROLLED,
            biometricAvailabilityOf(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            BiometricAvailability.SECURITY_UPDATE_REQUIRED,
            biometricAvailabilityOf(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
        assertEquals(
            BiometricAvailability.UNSUPPORTED,
            biometricAvailabilityOf(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED),
        )
    }

    @Test
    fun `状态未知归到暂时不可用`() {
        // 它的语义是"这个版本判断不了"，归到"暂时不可用、稍后再试"比归到"不支持"更接近事实，
        // 也不会让用户白跑一趟系统设置
        assertEquals(
            BiometricAvailability.HARDWARE_UNAVAILABLE,
            biometricAvailabilityOf(BiometricManager.BIOMETRIC_STATUS_UNKNOWN),
        )
    }

    @Test
    fun `没见过的返回码保守归到不支持`() {
        assertEquals(BiometricAvailability.UNSUPPORTED, biometricAvailabilityOf(9999))
    }

    @Test
    fun `分流之后只有 AVAILABLE 会显示入口`() {
        val usable = BiometricAvailability.entries.filter { it.usable }
        assertEquals(listOf(BiometricAvailability.AVAILABLE), usable)
    }

    // ---------------------------------------------------------------- {iv, ct} 编码

    @Test
    fun `编码与解码往返`() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(48) { (it + 100).toByte() }
        val wrapped = BiometricKeyStore.encodeWrapped(iv, ct)

        assertEquals(1 + iv.size + ct.size, wrapped.size)
        assertEquals(iv.toList(), BiometricKeyStore.decodeIv(wrapped)!!.toList())
        assertEquals(ct.toList(), BiometricKeyStore.decodeCiphertext(wrapped)!!.toList())
    }

    @Test
    fun `IV 长度写进头部、不假定 12 字节`() {
        // 假定长度错了的表现是解密失败，与"数据坏了"分不开
        val iv = ByteArray(16) { 7 }
        val wrapped = BiometricKeyStore.encodeWrapped(iv, ByteArray(32))
        assertEquals(16, wrapped[0].toInt())
        assertEquals(16, BiometricKeyStore.decodeIv(wrapped)!!.size)
    }

    @Test
    fun `坏掉的编码解出 null 而不是越界`() {
        assertNull(BiometricKeyStore.decodeIv(ByteArray(0)))
        assertNull(BiometricKeyStore.decodeCiphertext(ByteArray(0)))
        // 声称 IV 有 12 字节，实际只剩 3 字节
        val truncated = byteArrayOf(12, 1, 2, 3)
        assertNull(BiometricKeyStore.decodeIv(truncated))
        assertNull(BiometricKeyStore.decodeCiphertext(truncated))
        // IV 长度为 0 是非法的
        assertNull(BiometricKeyStore.decodeIv(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun `没有密文体时解出 null`() {
        val ivOnly = BiometricKeyStore.encodeWrapped(ByteArray(12), ByteArray(0))
        assertNull("只有 IV 没有密文，说明存坏了", BiometricKeyStore.decodeCiphertext(ivOnly))
    }

    @Test
    fun `IV 长度越界时构造就失败`() {
        val tooLong = ByteArray(256)
        val error = runCatching { BiometricKeyStore.encodeWrapped(tooLong, ByteArray(1)) }
        assertTrue(error.isFailure)
    }
}
