package com.lc33.tokenvault.net

import com.lc33.tokenvault.crypto.toUtf8
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.domain.model.WebDavCredentials
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CancellationException
import com.lc33.tokenvault.crypto.zeroize
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * WebDAV 四动词客户端（§12.2）：PROPFIND / PUT / GET / DELETE。
 *
 * 不引入 WebDAV 库，也不自动创建目录：远程目录必须预先存在。这样一次误配置
 * 不会在用户网盘根目录悄悄建出一串目录；目录不存在时错误信息直说。
 */
class WebDavClient constructor(
    private val client: HttpClient,
    private val audit: AuditLogRepository? = null,
) {

    suspend fun listBackups(config: WebDavConfig, credentials: WebDavCredentials): List<String> =
        logged("PROPFIND", remoteDirectoryUrl(config)) {
            val response = client.request(remoteDirectoryUrl(config)) {
                method = HttpMethod("PROPFIND")
                applyAuth(credentials)
                header(HttpHeaders.Depth, "1")
                contentType(ContentType.Application.Xml)
                setBody(PROP_REQUEST_BODY)
            }
            ensureSuccess(response.status.value, "PROPFIND")
            response.status.value to parseBackupNames(response.bodyAsText())
        }

    suspend fun put(config: WebDavConfig, credentials: WebDavCredentials, fileName: String, bytes: ByteArray) {
        logged("PUT", remoteFileUrl(config, fileName)) {
            val response = client.request(remoteFileUrl(config, fileName)) {
                method = HttpMethod.Put
                applyAuth(credentials)
                setBody(bytes)
            }
            ensureSuccess(response.status.value, "PUT")
            response.status.value to Unit
        }
    }

    suspend fun get(config: WebDavConfig, credentials: WebDavCredentials, fileName: String): ByteArray =
        logged("GET", remoteFileUrl(config, fileName)) {
            val response = client.request(remoteFileUrl(config, fileName)) {
                method = HttpMethod.Get
                applyAuth(credentials)
            }
            ensureSuccess(response.status.value, "GET")
            response.status.value to response.bodyAsBytes()
        }

    suspend fun delete(config: WebDavConfig, credentials: WebDavCredentials, fileName: String) {
        logged("DELETE", remoteFileUrl(config, fileName)) {
            val response = client.request(remoteFileUrl(config, fileName)) {
                method = HttpMethod.Delete
                applyAuth(credentials)
            }
            ensureSuccess(response.status.value, "DELETE")
            response.status.value to Unit
        }
    }

    private suspend fun recordHttp(
        level: LogLevel,
        message: String,
        detail: String? = null,
    ) {
        runCatching { audit?.record(level = level, category = LogCategory.HTTP, message = message, detail = detail) }
    }

    private suspend fun <T> logged(verb: String, url: String, block: suspend () -> Pair<Int, T>): T =
        try {
            val (status, result) = block()
            recordHttp(
                level = LogLevel.INFO,
                message = "webdav $verb ${safeTarget(url)} -> $status",
            )
            result
        } catch (cancelled: CancellationException) {
            // 取消不是失败：用户自己中断的传输不该在日志里留下一条 ERROR，
            // 与 HttpEngine 的处理保持一致。
            throw cancelled
        } catch (t: Throwable) {
            recordHttp(
                level = LogLevel.ERROR,
                message = "webdav $verb ${safeTarget(url)} failed",
                detail = t::class.simpleName,
            )
            throw t
        }

    private fun safeTarget(url: String): String = url.substringBefore('?').substringBefore('#')

    private fun HttpRequestBuilder.applyAuth(credentials: WebDavCredentials) {
        header(HttpHeaders.Authorization, basic(credentials.username, credentials.password))
        header(HttpHeaders.UserAgent, USER_AGENT)
    }

    companion object {
        private const val USER_AGENT = "YuanJi-WebDAV/1.0"
        private const val PROP_REQUEST_BODY =
            """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""

        /** 远端目录的完整 URL；不追加备份文件名。 */
        fun remoteDirectoryUrl(config: WebDavConfig): String =
            remoteUrl(config, emptyList())

        /** 远端备份文件的完整 URL；每个路径段单独 percent-encode。 */
        fun remoteFileUrl(config: WebDavConfig, fileName: String): String {
            require(fileName.isNotBlank()) { "file name must not be blank" }
            require('/' !in fileName) { "file name must not contain '/'" }
            return remoteUrl(config, listOf(fileName))
        }

        /**
         * 从 PROPFIND 响应里取 `.yjv` 文件名。只要 href，不解析资源类型：
         * 本项目只按固定后缀过滤，目录没有 `.yjv` 后缀，天然不会混进来。
         */
        internal fun parseBackupNames(xml: String): List<String> {
            val hrefRegex = Regex("""<(?:[A-Za-z0-9_.-]+:)?href>(.*?)</(?:[A-Za-z0-9_.-]+:)?href>""")
            return hrefRegex.findAll(xml)
                .mapNotNull { match ->
                    val href = match.groupValues[1].trim().decodeURLPart()
                    val name = href.substringAfterLast('/')
                    name.takeIf { it.endsWith(BACKUP_EXTENSION) }
                }
                .distinct()
                .sorted()
                .toList()
        }

        private fun remoteUrl(config: WebDavConfig, fileName: List<String>): String {
            val base = config.url.trim()
            require(base.startsWith("https://") || (base.startsWith("http://") && config.allowInsecure)) {
                "WebDAV endpoint must use https:// (or explicitly allow insecure http://)"
            }
            require(base.none { it.isWhitespace() }) { "WebDAV endpoint must not contain whitespace" }
            require('?' !in base && '#' !in base) { "WebDAV endpoint must not contain query or fragment" }

            val directory = config.remoteDirectory
                .split('/')
                .mapNotNull { segment -> segment.takeIf { it.isNotBlank() } }
                .map(::encodeSegment)
            val encodedFile = fileName.map(::encodeSegment)
            return buildString {
                append(base.trimEnd('/'))
                (directory + encodedFile).forEach { segment ->
                    append('/')
                    append(segment)
                }
            }
        }

        private fun encodeSegment(raw: String): String = buildString {
            raw.forEach { char ->
                if (char.isUnreserved()) append(char) else {
                    char.toString().encodeToByteArray().forEach { byte ->
                        append('%')
                        append(byte.toInt().and(0xff).toString(16).uppercase().padStart(2, '0'))
                    }
                }
            }
        }

        private fun Char.isUnreserved(): Boolean =
            this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '.' || this == '_' || this == '~'

        @OptIn(ExperimentalEncodingApi::class)
        private fun basic(username: CharArray, password: CharArray): String {
            // 不用 String 拼 “user:pass”：凭据已经只能活在 CharArray 里，
            // 中间再落一个不可擦的 String 就抵消了这条规矩。
            val user = username.toUtf8()
            val pass = password.toUtf8()
            val raw = ByteArray(user.size + 1 + pass.size)
            try {
                user.copyInto(raw)
                raw[user.size] = ':'.code.toByte()
                pass.copyInto(raw, user.size + 1)
                return "Basic " + Base64.encode(raw)
            } finally {
                user.zeroize()
                pass.zeroize()
                raw.zeroize()
            }
        }

        private fun ensureSuccess(status: Int, verb: String) {
            when {
                status in 200..299 -> Unit
                status == 401 || status == 403 -> throw WebDavUnauthorizedException(status, verb)
                status == 404 -> throw WebDavNotFoundException(verb)
                else -> throw WebDavHttpException(status, verb)
            }
        }

        internal const val BACKUP_EXTENSION = ".yjv"
    }
}

class WebDavHttpException(val status: Int, val verb: String) :
    Exception("WebDAV $verb failed with HTTP $status")

class WebDavUnauthorizedException(val status: Int, val verb: String) :
    Exception("WebDAV $verb authentication failed (HTTP $status)")

class WebDavNotFoundException(val verb: String) :
    Exception("WebDAV $verb target not found")
