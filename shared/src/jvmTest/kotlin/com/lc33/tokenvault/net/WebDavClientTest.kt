package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.domain.model.WebDavCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
}