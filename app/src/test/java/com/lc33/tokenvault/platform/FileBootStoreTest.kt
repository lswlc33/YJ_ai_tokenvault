package com.lc33.tokenvault.platform

import com.lc33.tokenvault.crypto.KdfParams
import java.io.File
import java.io.IOException
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

    /**
     * 一份"看起来是真的"记录。
     *
     * 包裹那两个字节不是随手填的：`BaseBootStore` 现在会先看封套头（版本 + 算法标识），
     * 认不出就整份判损坏——那正是"合法 JSON 但来自更新的版本"不该被交给解锁路径的原因。
     * 所以这份夹具给的是 v1/AES-256-GCM 的头，后面才是无意义的填充字节。
     */
    private fun envelopeLike(bytes: Int): ByteArray = ByteArray(bytes) { (it % 200).toByte() }
        .also {
            it[0] = com.lc33.tokenvault.crypto.SECRET_BOX_FORMAT_VERSION
            it[1] = com.lc33.tokenvault.crypto.SECRET_BOX_CIPHER_AES_256_GCM
        }

    private fun sampleRecord() = BootRecord(
        deviceId = "device-under-test",
        onboarded = true,
        pinKdf = KdfParams(salt = ByteArray(KdfParams.SALT_BYTES) { 1 }),
        dekWrappedByPin = envelopeLike(60),
        pinFailCount = 3,
        pinLockUntil = 1_700_000_000_000L,
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
        assertTrue(text.contains("wraps=[pin]"))
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
        val e = assertThrows(BootCorruptException::class.java) {
            store.update { it.copy(pinFailCount = 1) }
        }
        assertTrue("原因要能原样交给恢复页说：${e.reason}", e.reason.isNotBlank())
        assertTrue("损坏文件必须原样留着", store.read() is BootState.Corrupt)
    }

    @Test
    fun `损坏判定也能按 IllegalStateException 兜住`() {
        // 既有调用点（锁屏的失效善后、设置页的开关）用的是 runCatching / 按 ISE 兜的 catch。
        // 专用类型必须仍然是它的子类，否则那两处会在"文件坏了"时直接崩在页面之外。
        file.parentFile?.mkdirs()
        file.writeText("{ broken")
        assertThrows(IllegalStateException::class.java) { store.update { it.copy(pinFailCount = 1) } }
    }

    @Test
    fun `并发的读-改-写不吞掉对方的改动`() {
        // 锁在存储层而不是会话层：`update` 的调用点有三类，后两类（锁屏善后、设置页开关）
        // 根本拿不到会话那把锁。丢写的表现最阴——两份内容都是合法 JSON，事后从文件里看不出来。
        store.write(sampleRecord().copy(pinFailCount = 0, pinLockUntil = null))
        val incrementsPerThread = 10
        val failures = mutableListOf<Throwable>()
        val threads = (1..2).map {
            Thread {
                repeat(incrementsPerThread) {
                    runCatching { store.update { it.copy(pinFailCount = it.pinFailCount + 1) } }
                        .onFailure { synchronized(failures) { failures += it } }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("健康的 boot 文件上不该有任何一次写失败：$failures", failures.isEmpty())
        assertEquals(
            "两次 × $incrementsPerThread 次自增一次都不能丢",
            incrementsPerThread * 2,
            (store.read() as BootState.Ok).record.pinFailCount,
        )
    }

    @Test
    fun `clear 连临时文件一起删`() {
        store.write(sampleRecord())
        File(file.parentFile, FileBootStore.FILE_NAME + ".tmp").writeText("leftover")
        store.clear()
        assertEquals(BootState.Missing, store.read())
        assertEquals(0, file.parentFile!!.listFiles()!!.size)
    }

    // ---------------------------------------------------------------- rename 失败

    @Test
    fun `rename 失败时不删正式文件`() {
        store.write(sampleRecord())
        val before = file.readText()
        // rename 失败（目标被占用 / 只读目录 / 跨设备）在 CI 上造不出来，只能把它换出来：
        // 这一条要证明的恰恰是"失败了不许为了重试而先删掉正式文件"。
        store.renameOverride = { _, _ -> false }

        assertThrows(IOException::class.java) { store.write(sampleRecord().copy(pinFailCount = 42)) }

        assertTrue("正式文件必须还在原位", file.isFile)
        assertEquals("内容必须是上一份，不是半新半旧", before, file.readText())
        val state = store.read()
        assertTrue("$state", state is BootState.Ok)
        assertEquals(3, (state as BootState.Ok).record.pinFailCount)
    }

    @Test
    fun `正式文件没了但临时残留合法时从残留恢复`() {
        store.write(sampleRecord().copy(pinFailCount = 7))
        // 制造现场：rename 失败 = 新内容已经落到 .tmp 并 fsync 过，而正式文件没换上；
        // 之后再发生"正式文件不见了"（进程被杀在两次写之间、文件系统把 rename 整个丢了），
        // 那一份 .tmp 就是唯一真相。
        store.renameOverride = { _, _ -> false }
        runCatching { store.write(sampleRecord().copy(pinFailCount = 8)) }
        val temp = File(file.parentFile, FileBootStore.FILE_NAME + ".tmp")
        assertTrue("失败必须留下临时残留", temp.isFile)
        file.delete()

        val state = store.read()
        assertTrue("$state", state is BootState.Ok)
        assertEquals("该读回残留里那份新内容", 8, (state as BootState.Ok).record.pinFailCount)
        assertNotNull(store.lastTempRecovery)
    }

    @Test
    fun `残留里那份不是合法记录时判损坏而不是当成全新安装`() {
        store.write(sampleRecord())
        file.delete()
        File(file.parentFile, FileBootStore.FILE_NAME + ".tmp").writeText("{ 半份 JSON")
        // Missing 会引导用户去重设 PIN，而库里的密文从此永久解不开；判不出来就照实说判不出来。
        assertTrue(store.read() is BootState.Corrupt)
    }

    @Test
    fun `封套版本不认识时读的时候就判损坏、不留给解锁去崩`() {
        // 合法 JSON、字段齐全，但封套头是"更新的版本写出来的"（版本字节不认识）。
        // 存储层放过它，解锁路径就会抛 UnsupportedEnvelopeException——而那是锁屏页。
        val unknownEnvelope = ByteArray(60) { (it % 200).toByte() }.also {
            it[0] = 99
            it[1] = com.lc33.tokenvault.crypto.SECRET_BOX_CIPHER_AES_256_GCM
        }
        store.write(sampleRecord().copy(dekWrappedByPin = unknownEnvelope))

        val state = store.read()
        assertTrue("$state", state is BootState.Corrupt)
        assertTrue(
            "原因要点名封套：${(state as BootState.Corrupt).reason}",
            state.reason.contains("envelope"),
        )
    }

    // ---------------------------------------------------------------- 派生判断

    @Test
    fun `KDF 参数原样读回、不被常量纠正`() {
        val custom = KdfParams(
            iterations = KdfParams.MIN_ITERATIONS,
            salt = ByteArray(KdfParams.SALT_BYTES) { 7 },
        )
        store.write(sampleRecord().copy(pinKdf = custom))
        val readBack = (store.read() as BootState.Ok).record.pinKdf
        assertNotNull(readBack)
        // 红线 3：一旦某个版本拿编译期常量去"纠正"存储值，旧包裹立刻永久解不开
        assertEquals(custom, readBack)
    }
}
