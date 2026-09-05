package com.lc33.tokenvault.platform

import com.lc33.tokenvault.crypto.KdfParams
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * boot 存储（§14.3 测试 6 的 boot 那一半，红线 26）。
 *
 * 这一层最需要被测的不是"能存能读"，而是**该落 Corrupt 的时候真的落 Corrupt**：
 * 把损坏当成全新安装是这个项目里最不可挽回的 bug——应用会高高兴兴让用户重设 PIN，
 * 而库里所有密文从此永久解不开，用户会以为"应用把我的数据删了"，而且他确实没救了。
 */
class FileBootStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var store: FileBootStore

    private fun sampleRecord() = BootRecord(
        deviceId = "device-under-test",
        onboarded = true,
        pinKdf = KdfParams(salt = ByteArray(KdfParams.SALT_BYTES) { 1 }),
        recoveryKdf = KdfParams(salt = ByteArray(KdfParams.SALT_BYTES) { 2 }),
        dekWrappedByPin = ByteArray(60) { it.toByte() },
        dekWrappedByRecovery = ByteArray(60) { (it + 1).toByte() },
        pinFailCount = 3,
        pinLockUntil = 1_700_000_000_000L,
        biometricEnabled = false,
        themeMode = "Dark",
        localeTag = "zh-CN",
    )

    @Before
    fun setUp() {
        file = File(temp.newFolder("files"), FileBootStore.FILE_NAME)
        store = FileBootStore(file) { "generated-device-id" }
    }

    @Test
    fun `全新安装读到 Missing 而不是 Corrupt`() {
        assertEquals(BootState.Missing, store.read())
    }

    @Test
    fun `往返`() {
        val record = sampleRecord()
        store.write(record)
        val state = store.read()
        assertTrue("$state", state is BootState.Ok)
        assertEquals(record, (state as BootState.Ok).record)
    }

    @Test
    fun `密文按 base64 存、不是数字数组`() {
        store.write(sampleRecord())
        val text = file.readText()
        // 数字数组会让 boot 文件没法用眼睛检查，而它是出问题时唯一的诊断手段
        assertFalse("不该出现 JSON 数字数组", text.contains("[\n"))
        assertTrue("应当是 base64 字符串", Regex(""""dekWrappedByPin"\s*:\s*"[A-Za-z0-9+/=]+"""").containsMatchIn(text))
    }

    @Test
    fun `不打印密文与盐到 toString`() {
        val text = sampleRecord().toString()
        assertTrue(text.contains("wraps=[pin/recovery]"))
        assertFalse("盐不该进日志", text.contains("salt"))
        assertFalse("密文不该进日志", text.contains("dekWrappedByPin="))
    }

    // ---------------------------------------------------------------- 损坏检测

    @Test
    fun `空文件是 Corrupt 而不是 Missing`() {
        file.parentFile?.mkdirs()
        file.writeText("")
        // 撕裂写入最常见的残留形态。当成 Missing 就会引导用户重设 PIN，
        // 而库里的密文从此永久解不开。
        assertTrue(store.read() is BootState.Corrupt)
    }

    @Test
    fun `截断的 JSON 是 Corrupt`() {
        store.write(sampleRecord())
        val text = file.readText()
        file.writeText(text.substring(0, text.length / 2))
        assertTrue(store.read() is BootState.Corrupt)
    }

    @Test
    fun `不认识的格式版本是 Corrupt、不猜也不静默升级`() {
        store.write(sampleRecord())
        file.writeText(file.readText().replace("\"format\": 1", "\"format\": 99"))
        val state = store.read()
        assertTrue(state is BootState.Corrupt)
        assertTrue((state as BootState.Corrupt).reason.contains("99"))
    }

    @Test
    fun `多出不认识的键也是 Corrupt`() {
        store.write(sampleRecord())
        file.writeText(file.readText().replaceFirst("{", "{\n  \"fromNewerVersion\": true,"))
        // 说明这份文件来自更新的版本。猜着读会读出错的语义，落 Corrupt 让用户去恢复备份
        assertTrue(store.read() is BootState.Corrupt)
    }

    @Test
    fun `声称已引导但缺 PIN 包裹是 Corrupt`() {
        val inconsistent = sampleRecord().copy(dekWrappedByPin = null)
        // 绕过 write 的校验直接落盘：模拟"某个旧版本写坏了"
        file.parentFile?.mkdirs()
        store.write(inconsistent.copy(onboarded = false))
        file.writeText(file.readText().replace("\"onboarded\": false", "\"onboarded\": true"))

        val state = store.read()
        assertTrue("$state", state is BootState.Corrupt)
    }

    // ---------------------------------------------------------------- 原子写与 update

    @Test
    fun `写完不留临时文件`() {
        store.write(sampleRecord())
        val leftovers = file.parentFile!!.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("临时文件必须被 rename 掉：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `覆盖写之后内容是新的、且仍只有一个文件`() {
        store.write(sampleRecord())
        store.write(sampleRecord().copy(pinFailCount = 9))
        assertEquals(1, file.parentFile!!.listFiles()!!.size)
        assertEquals(9, (store.read() as BootState.Ok).record.pinFailCount)
    }

    @Test
    fun `update 在全新安装时生成 deviceId`() {
        val updated = store.update { it.copy(themeMode = "Dark") }
        assertEquals("generated-device-id", updated.deviceId)
        assertEquals("Dark", (store.read() as BootState.Ok).record.themeMode)
    }

    @Test
    fun `全新安装上的空改动不落盘`() {
        store.update { it }
        // 一份只有 deviceId、没有任何包裹的 boot 文件对调用方来说与 Missing 等价，
        // 写它只是白担一次撕裂风险
        assertEquals(BootState.Missing, store.read())
    }

    @Test
    fun `update 没变就不写`() {
        store.write(sampleRecord())
        val before = file.lastModified()
        // 把时间戳往前挪，这样"有没有重写"才看得出来
        file.setLastModified(before - 10_000)
        val stamped = file.lastModified()

        store.update { it }
        assertEquals("内容没变不该重写：每次写都是一次撕裂风险", stamped, file.lastModified())
    }

    @Test
    fun `update 变了就写`() {
        store.write(sampleRecord())
        val result = store.update { it.copy(pinFailCount = it.pinFailCount + 1) }
        assertEquals(4, result.pinFailCount)
        assertEquals(4, (store.read() as BootState.Ok).record.pinFailCount)
    }

    @Test
    fun `拒绝在损坏文件上做增量修改`() {
        file.parentFile?.mkdirs()
        file.writeText("{ broken")
        // 允许的话，一次"累加失败计数"就会把损坏文件覆盖成一份看起来正常、
        // 实际丢了所有密文的新文件
        assertThrows(IllegalStateException::class.java) {
            store.update { it.copy(pinFailCount = 1) }
        }
        assertTrue("损坏文件必须原样留着", store.read() is BootState.Corrupt)
    }

    @Test
    fun `clear 连临时文件一起删`() {
        store.write(sampleRecord())
        File(file.parentFile, FileBootStore.FILE_NAME + ".tmp").writeText("leftover")
        store.clear()
        assertEquals(BootState.Missing, store.read())
        assertEquals(0, file.parentFile!!.listFiles()!!.size)
    }

    // ---------------------------------------------------------------- 派生判断

    @Test
    fun `生物识别开关与密文都在才算能用`() {
        val record = sampleRecord()
        assertFalse("开关关着就不算能用（红线 5）", record.biometricUsable)
        assertFalse(record.copy(biometricEnabled = true).biometricUsable)
        assertTrue(
            record.copy(biometricEnabled = true, dekWrappedByBiometric = ByteArray(60)).biometricUsable,
        )
    }

    @Test
    fun `恢复密钥那条路的存在性`() {
        assertTrue(sampleRecord().hasRecoveryWrap)
        assertFalse(sampleRecord().copy(dekWrappedByRecovery = null).hasRecoveryWrap)
    }

    @Test
    fun `KDF 参数原样读回、不被常量纠正`() {
        val custom = KdfParams(
            memoryKib = KdfParams.MIN_MEMORY_KIB,
            iterations = 1,
            parallelism = 1,
            salt = ByteArray(KdfParams.SALT_BYTES) { 7 },
        )
        store.write(sampleRecord().copy(pinKdf = custom))
        val readBack = (store.read() as BootState.Ok).record.pinKdf
        assertNotNull(readBack)
        // 红线 3：一旦某个版本拿编译期常量去"纠正"存储值，旧包裹立刻永久解不开
        assertEquals(custom, readBack)
    }
}
