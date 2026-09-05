package com.lc33.tokenvault.data.mapper

import com.lc33.tokenvault.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV / JSON 列的往返。
 *
 * 值得单独测的理由：这是最容易**静默**损坏数据的地方。存进去与读出来差一个字符，
 * 表现是"某个供应商的协议莫名少了一个"，而没有任何报错、也没有崩溃。
 */
class ColumnCodecsTest {

    // ---------------------------------------------------------------- 协议 CSV

    @Test
    fun `协议集合往返`() {
        val protocols = linkedSetOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC)
        assertEquals("chat,responses,anthropic", protocols.toCsv())
        assertEquals(protocols, protocols.toCsv().toProtocolSet())
    }

    @Test
    fun `协议 CSV 保持顺序`() {
        // 协议在 UI 上是按顺序显示的 chips，每次读出来顺序不同会让列表看起来在闪
        val reversed = linkedSetOf(Protocol.ANTHROPIC, Protocol.CHAT)
        assertEquals(listOf(Protocol.ANTHROPIC, Protocol.CHAT), reversed.toCsv().toProtocolSet().toList())
    }

    @Test
    fun `空 CSV 得到空集合`() {
        assertTrue("".toProtocolSet().isEmpty())
        assertTrue("   ".toProtocolSet().isEmpty())
        assertTrue(",,".toProtocolSet().isEmpty())
    }

    @Test
    fun `不认识的协议被跳过而不是抛异常`() {
        // 抛的后果是整个列表页打不开（一行坏数据毁掉全部），跳过的后果是那一行少一个协议
        val parsed = "chat,gemini,anthropic".toProtocolSet()
        assertEquals(setOf(Protocol.CHAT, Protocol.ANTHROPIC), parsed)
    }

    @Test
    fun `CSV 容忍空格`() {
        assertEquals(setOf(Protocol.CHAT, Protocol.ANTHROPIC), " chat , anthropic ".toProtocolSet())
    }

    // ---------------------------------------------------------------- 路径覆盖 JSON

    @Test
    fun `路径覆盖往返`() {
        val overrides = mapOf(Protocol.ANTHROPIC to "/anthropic/v1/messages")
        val encoded = overrides.pathOverridesToJson()
        assertEquals("""{"anthropic":"/anthropic/v1/messages"}""", encoded)
        assertEquals(overrides, encoded.toPathOverrides())
    }

    @Test
    fun `路径覆盖的序列化结果稳定`() {
        // 同一份数据每次序列化出的字符串必须一致，否则"没变就不写"这条优化会失效
        val a = mapOf(Protocol.ANTHROPIC to "/x", Protocol.CHAT to "/y")
        val b = mapOf(Protocol.CHAT to "/y", Protocol.ANTHROPIC to "/x")
        assertEquals(a.pathOverridesToJson(), b.pathOverridesToJson())
    }

    @Test
    fun `空覆盖表往返`() {
        assertEquals("{}", emptyMap<Protocol, String>().pathOverridesToJson())
        assertTrue("{}".toPathOverrides().isEmpty())
    }

    @Test
    fun `坏掉的 JSON 得到空表而不是崩溃`() {
        assertTrue("not json".toPathOverrides().isEmpty())
        assertTrue("[1,2,3]".toPathOverrides().isEmpty())
    }

    @Test
    fun `不认识的协议键被跳过`() {
        assertEquals(
            mapOf(Protocol.CHAT to "/a"),
            """{"chat":"/a","gemini":"/b"}""".toPathOverrides(),
        )
    }

    // ---------------------------------------------------------------- 请求头 JSON

    @Test
    fun `请求头往返并保持顺序`() {
        val headers = listOf(
            "x-app" to "cli",
            "x-stainless-lang" to "js",
            "x-stainless-os" to "MacOS",
        )
        val encoded = headers.headersToJson()
        // 是数组不是对象：部分上游会看请求头顺序，而 JSON 对象顺序不保证
        assertTrue(encoded.startsWith("["))
        assertEquals(headers, encoded.toHeaderList())
    }

    @Test
    fun `同名头可以出现两次`() {
        // Map 会把它折叠成一条，而有的上游确实要求重复头
        val headers = listOf("accept" to "a", "accept" to "b")
        assertEquals(headers, headers.headersToJson().toHeaderList())
    }

    @Test
    fun `空请求头往返`() {
        assertEquals("[]", emptyList<Pair<String, String>>().headersToJson())
        assertTrue("[]".toHeaderList().isEmpty())
    }

    @Test
    fun `坏掉的请求头 JSON 得到空表`() {
        assertTrue("{}".toHeaderList().isEmpty())
        assertTrue("nonsense".toHeaderList().isEmpty())
        // 长度不是 2 的项被跳过，其余保留
        assertEquals(listOf("a" to "b"), """[["a","b"],["c"]]""".toHeaderList())
    }

    @Test
    fun `请求头值里的引号与反斜杠不破坏编码`() {
        val headers = listOf("x-quote" to """he said "hi" \ ok""")
        assertEquals(headers, headers.headersToJson().toHeaderList())
    }

    // ---------------------------------------------------------------- 模态 CSV

    @Test
    fun `模态 CSV 往返`() {
        val modalities = listOf("text", "image")
        assertEquals("text,image", modalities.toCsv())
        assertEquals(modalities, "text,image".csvToList())
    }

    @Test
    fun `null 与空串得到空列表`() {
        assertTrue(null.csvToList().isEmpty())
        assertTrue("".csvToList().isEmpty())
    }
}
