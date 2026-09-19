package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.domain.model.WebDavCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WebDavClientTest {

    @Test
    fun `远端 URL 会编码目录与文件名并保留基路径`() {
        val config = WebDavConfig(url = "https://dav.example.com/dav/", remoteDirectory = "/Yuan Ji")
        assertEquals(
            "https://dav.example.com/dav/Yuan%20Ji/backup-1.yjv",
            WebDavClient.remoteFileUrl(config, "backup-1.yjv"),
        )
        assertEquals(
            "https://dav.example.com/dav/Yuan%20Ji",
            WebDavClient.remoteDirectoryUrl(config),
        )
    }

    @Test
    fun `明文 HTTP 没有显式放行时直接拒绝`() {
        val config = WebDavConfig(url = "http://192.168.1.2/dav", remoteDirectory = "/YuanJi")
        assertFailsWith<IllegalArgumentException> {
            WebDavClient.remoteDirectoryUrl(config)
        }
        WebDavClient.remoteDirectoryUrl(config.copy(allowInsecure = true))
    }

    @Test
    fun `PROPFIND 只取 yjv 文件名并按名称排序`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/YuanJi/</D:href></D:response>
              <D:response><D:href>/dav/YuanJi/yuanji-backup-2.yjv</D:href></D:response>
              <D:response><D:href>/dav/YuanJi/yuanji-backup-1.yjv</D:href></D:response>
              <D:response><D:href>/dav/YuanJi/readme.txt</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals(
            listOf("yuanji-backup-1.yjv", "yuanji-backup-2.yjv"),
            WebDavClient.parseBackupNames(xml),
        )
    }

    /**
     * 部分 Nextcloud 反代把文件名里的 `/` 编码成 `%2F` 写进 href。顺序必须是
     * **先按原始 href 取末段、再解码**：先解码会让那个 `/` 变成真分隔符，
     * `substringAfterLast('/')` 只截到后半截，列出的名字对不上实际文件。
     */
    @Test
    fun `href 里的编码斜杠先取末段再解码`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/YuanJi/meta%2Fbackup.yjv</D:href></D:response>
              <D:response><D:href>/dav/YuanJi/space%20name.yjv</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        val names = WebDavClient.parseBackupNames(xml)
        assertTrue(names.any { it == "meta/backup.yjv" }, "meta%2Fbackup 应保留完整文件名，实际 $names")
        assertTrue(names.any { it == "space name.yjv" }, "编码空格应还原为字符本身，实际 $names")
    }

    @Test
    fun `四动词带 Basic 认证并处理成功状态`() {
        val config = WebDavConfig(
            url = "https://dav.example.com/dav",
            remoteDirectory = "/YuanJi",
        )
        val credentials = WebDavCredentials("user".toCharArray(), "pass".toCharArray())
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            methods += request.method.value
            assertEquals("Basic dXNlcjpwYXNz", request.headers["Authorization"])
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = """<D:multistatus><D:response><D:href>/dav/YuanJi/a.yjv</D:href></D:response></D:multistatus>""",
                    status = HttpStatusCode(207, "Multi-Status"),
                )
                "PUT" -> respond(ByteArray(0), HttpStatusCode.Created)
                "GET" -> respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
                "DELETE" -> respond(ByteArray(0), HttpStatusCode.NoContent)
                else -> error("unexpected method ${request.method.value}")
            }
        }
        val client = WebDavClient(HttpClient(engine))

        runBlocking {
            assertEquals(listOf("a.yjv"), client.listBackups(config, credentials))
            client.put(config, credentials, "a.yjv", byteArrayOf(4))
            assertContentEquals(byteArrayOf(1, 2, 3), client.get(config, credentials, "a.yjv"))
            client.delete(config, credentials, "a.yjv")
        }

        assertEquals(listOf("PROPFIND", "PUT", "GET", "DELETE"), methods)
        credentials.zeroize()
        assertTrue(credentials.username.all { it == Char(0) })
    }
    @Test
    fun `四个动词各留一条 HTTP 日志，且不含凭据`() {
        val config = WebDavConfig(url = "https://dav.example.com/dav", remoteDirectory = "/YuanJi")
        val credentials = WebDavCredentials("user".toCharArray(), "pass".toCharArray())
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = """<D:multistatus><D:response><D:href>/dav/YuanJi/a.yjv</D:href></D:response></D:multistatus>""",
                    status = HttpStatusCode(207, "Multi-Status"),
                )
                "PUT" -> respond(ByteArray(0), HttpStatusCode.Created)
                "GET" -> respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
                "DELETE" -> respond(ByteArray(0), HttpStatusCode.NoContent)
                else -> error("unexpected method ${request.method.value}")
            }
        }
        val audit = RecordingAuditLog()
        val client = WebDavClient(HttpClient(engine), audit)

        runBlocking {
            client.listBackups(config, credentials)
            client.put(config, credentials, "a.yjv", byteArrayOf(4))
            client.get(config, credentials, "a.yjv")
            client.delete(config, credentials, "a.yjv")
        }

        assertEquals(
            listOf(
                "webdav PROPFIND https://dav.example.com/dav/YuanJi -> 207",
                "webdav PUT https://dav.example.com/dav/YuanJi/a.yjv -> 201",
                "webdav GET https://dav.example.com/dav/YuanJi/a.yjv -> 200",
                "webdav DELETE https://dav.example.com/dav/YuanJi/a.yjv -> 204",
            ),
            audit.records.map { it.message },
        )
        assertTrue(audit.records.all { it.level == LogLevel.INFO && it.category == LogCategory.HTTP })
        // Basic 凭据只进请求头：日志里不能出现用户名或口令。
        assertTrue(
            audit.records.none { record ->
                val text = record.message + record.detail.orEmpty()
                text.contains("user") || text.contains("pass")
            },
        )
        credentials.zeroize()
    }

    /**
     * 探测那一侧刻意关掉重定向（`HttpEngine.buildClient`），而 WebDAV 的 3xx 是真实配置差异：
     * 反代要求补尾斜杠、http→https。客户端自己**同动词**跟一次，PUT 不能变成 GET——
     * 降级之后备份字节压根没发出去，却还会报"已上传"。
     */
    @Test
    fun `3xx 用同一动词跟一次相对 Location`() {
        val config = WebDavConfig(url = "https://dav.example.com/dav", remoteDirectory = "/YuanJi")
        val credentials = WebDavCredentials("user".toCharArray(), "pass".toCharArray())
        val seen = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            seen += request.method.value to request.url.toString()
            if (request.url.toString().endsWith("a.yjv")) {
                respond(ByteArray(0), HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/dav/YuanJi/a.yjv/"))
            } else {
                respond(ByteArray(0), HttpStatusCode.Created)
            }
        }
        // 与生产同一个配置：Ktor 的自动跟随会按 RFC 把非 GET 降级，这里要验的是我们自己那套
        val client = WebDavClient(HttpClient(engine) { followRedirects = false })

        runBlocking { client.put(config, credentials, "a.yjv", byteArrayOf(4)) }

        assertEquals(
            listOf(
                "PUT" to "https://dav.example.com/dav/YuanJi/a.yjv",
                "PUT" to "https://dav.example.com/dav/YuanJi/a.yjv/",
            ),
            seen,
        )
        credentials.zeroize()
    }

    @Test
    fun `只跟一次，还回 3xx 就报出去`() {
        // 自环的 302 不能一直转；第二次的状态交给 ensureSuccess 定性。
        val config = WebDavConfig(url = "https://dav.example.com/dav", remoteDirectory = "/YuanJi")
        val credentials = WebDavCredentials("user".toCharArray(), "pass".toCharArray())
        var hits = 0
        val engine = MockEngine { request ->
            hits += 1
            assertEquals("PUT", request.method.value)
            respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://dav.example.com/dav/loop"))
        }
        val client = WebDavClient(HttpClient(engine) { followRedirects = false })

        val e = assertFailsWith<WebDavHttpException> {
            runBlocking { client.put(config, credentials, "a.yjv", byteArrayOf(4)) }
        }
        assertEquals(302, e.status)
        assertEquals(2, hits)
        credentials.zeroize()
    }
}