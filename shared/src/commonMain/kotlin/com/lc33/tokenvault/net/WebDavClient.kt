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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.decodeURLPart
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
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
            val response = requestDav("PROPFIND", remoteDirectoryUrl(config), credentials) {
                header(HttpHeaders.Depth, "1")
                contentType(ContentType.Application.Xml)
                setBody(PROP_REQUEST_BODY)
            }
            ensureSuccess(response.status.value, "PROPFIND")
            response.status.value to parseBackupNames(response.readBytesCapped().decodeToString())
        }

    suspend fun put(config: WebDavConfig, credentials: WebDavCredentials, fileName: String, bytes: ByteArray) {
        logged("PUT", remoteFileUrl(config, fileName)) {
            val response = requestDav("PUT", remoteFileUrl(config, fileName), credentials) {
                setBody(bytes)
            }
            ensureSuccess(response.status.value, "PUT")
            response.status.value to Unit
        }
    }

    suspend fun get(config: WebDavConfig, credentials: WebDavCredentials, fileName: String): ByteArray =
        logged("GET", remoteFileUrl(config, fileName)) {
            val response = requestDav("GET", remoteFileUrl(config, fileName), credentials)
            ensureSuccess(response.status.value, "GET")
            response.status.value to response.readBytesCapped()
        }

    suspend fun delete(config: WebDavConfig, credentials: WebDavCredentials, fileName: String) {
        logged("DELETE", remoteFileUrl(config, fileName)) {
            val response = requestDav("DELETE", remoteFileUrl(config, fileName), credentials)
            ensureSuccess(response.status.value, "DELETE")
            response.status.value to Unit
        }
    }

    /**
     * 按块读响应体并封顶。与 [HttpEngine] 里那份 `readBodyCapped` 同一个道理，只是上限换成
     * 备份包的量级（那边 512KB 是给模型列表用的，套到这里会把正常备份判成非法）。
     *
     * `bodyAsBytes()` 会把上游给的全部读进内存，而上游给多少完全不看我们的脸色：
     * `remoteDirectory` 填错指到一个视频、或网盘目录里有一个同名的大文件，几百 MB 先进内存、
     * 再整份 gunzip（[com.lc33.tokenvault.engine.BackupEngine]）——低端机直接 OOM 闪退。
     * 整库导出是"明文 JSON + gzip + AES"，几百把 Key 也就几 MB，32MB 封顶不误伤正常包。
     */
    private suspend fun HttpResponse.readBytesCapped(): ByteArray {
        val channel = bodyAsChannel()
        val buffer = ByteArray(MAX_DOWNLOAD_BYTES)
        var total = 0
        while (total < buffer.size) {
            val read = channel.readAvailable(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        // 下一字节要用独立的单字节数组探：读进 buffer[0] 会把已经拿到的头一个字节覆盖掉。
        val probe = ByteArray(1)
        if (total == buffer.size && channel.readAvailable(probe, 0, 1) > 0) {
            channel.cancel(null)
            // 抛异常而不是截断：截断会让下面那道 gzip/AES 拿到半份文件，报出来的就是
            // "口令错或包损坏"，把真原因（文件大得离谱）盖掉了。恢复失败这条路由
            // `SyncEvent.RestoreFailed` 说成资源文案，所以这里只写给日志与诊断看。
            throw IllegalStateException(
                "remote file is larger than ${MAX_DOWNLOAD_BYTES / (1024 * 1024)}MB, download refused",
            )
        }
        return buffer.copyOf(total)
    }

    private suspend fun recordHttp(
        level: LogLevel,
        message: String,
        detail: String? = null,
    ) {
        runCatching { audit?.record(level = level, category = LogCategory.HTTP, message = message, detail = detail) }
    }

    /**
     * 发一次 DAV 请求；上游回 3xx 时**同动词、同请求体**跟着 `Location` 再发一次，只跟一次。
     *
     * 为什么要在这里自己跟：探测那一侧刻意关掉了重定向（见 `HttpEngine.buildClient`——3xx 对
     * 探活没有意义，跟过去只会把"上游在跳转"这件事藏起来），但 WebDAV 的 3xx 是真实的配置差异：
     * 尾斜杠、http→https、目录被反代挪走。不处理的表现是"某天备份同步全红"，而用户在自己
     * 那一侧改不动上游那个斜杠。
     *
     * 为什么不用 Ktor 的 `HttpRedirect` 插件：它按 RFC 把 301/302/303 上的非 GET 降级成 GET，
     * 于是 PUT 的备份字节压根没发出去、我们却报"已上传"——静默丢数据比报错糟得多。
     * 只跟一次：还回 3xx 就交给 [ensureSuccess] 报出去（自环的 302 不能一直转）。
     */
    private suspend fun requestDav(
        verb: String,
        url: String,
        credentials: WebDavCredentials,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val first = client.request(url) {
            method = HttpMethod(verb)
            applyAuth(credentials)
            configure()
        }
        if (first.status.value !in 300..399) return first
        // 没有 Location 的 3xx 无处可跟，原样交出去让 ensureSuccess 定性。
        val location = first.headers[HttpHeaders.Location]?.let { resolve(url, it) } ?: return first
        return client.request(location) {
            method = HttpMethod(verb)
            applyAuth(credentials)
            configure()
        }
    }

    /** `Location` 允许是绝对地址，也可能是相对当前 URL 的一段（尾斜杠那类跳转常见）。 */
    private fun resolve(base: String, location: String): String =
        URLBuilder(base).apply { takeFrom(location) }.buildString()

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

    /**
     * 日志里的目标地址：去 query / fragment，并**剥掉 userinfo**。
     *
     * 与 `HttpEngine.safeTarget` 同一条规矩：有人会把凭据直接写进 WebDAV 地址
     * （`https://user:pass@dav.example.com/…`），而日志会被复制、截图、分享出去。
     */
    private fun safeTarget(url: String): String {
        val base = url.substringBefore('?').substringBefore('#')
        val schemeEnd = base.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val pathStart = base.indexOf('/', startIndex = schemeEnd).let { if (it < 0) base.length else it }
        return base.substring(0, schemeEnd) +
            base.substring(schemeEnd, pathStart).substringAfterLast('@') +
            base.substring(pathStart)
    }

    private fun HttpRequestBuilder.applyAuth(credentials: WebDavCredentials) {
        header(HttpHeaders.Authorization, basic(credentials.username, credentials.password))
        header(HttpHeaders.UserAgent, USER_AGENT)
    }

    companion object {
        private const val USER_AGENT = "YuanJi-WebDAV/1.0"

        /** [readBytesCapped] 的上限：32 MB。备份包量级见那里的说明。 */
        const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024
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
         *
         * **先按原始 href 取末段、再解码**，顺序不能倒。有些服务端（Nextcloud 的部分
         * 反代配置）会把文件名里的 `/` 编码成 `%2F` 写进 href：先 `decodeURLPart()` 就
         * 把它变回一个真斜杠，于是 `substringAfterLast('/')` 只截到文件名的后半截，
         * 列出来的名字对不上实际文件，下载与删除都指向一个不存在的文件名。
         * 反过来（先取末段）永远不会多切一刀，编码字符留到解码那一步再还原。
         */
        internal fun parseBackupNames(xml: String): List<String> {
            val hrefRegex = Regex("""<(?:[A-Za-z0-9_.-]+:)?href>(.*?)</(?:[A-Za-z0-9_.-]+:)?href>""")
            return hrefRegex.findAll(xml)
                .mapNotNull { match ->
                    val name = match.groupValues[1].trim().substringAfterLast('/').decodeURLPart()
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
