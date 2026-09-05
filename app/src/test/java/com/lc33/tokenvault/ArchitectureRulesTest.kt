package com.lc33.tokenvault

import com.lc33.tokenvault.ui.theme.AppColorSchemeMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分层规则的机器检查（计划.md §4.3、§14.4 的五条 CI grep）。
 *
 * 放在 JVM 单测里而不是只放在 CI 里，是因为 `.githooks/pre-commit` 会跑
 * `:app:testDebugUnitTest`：违规在提交前就被拦住，而不是推上去等 CI 一分钟后再说。
 * CI 里那几条 grep 保留，作为"有人绕过 hook"时的第二道闸。
 *
 * 这些规则违反了不会立刻崩，但会在某个时刻造成不可挽回的后果——
 * 比如 MIUIX 的 `Window*` 弹层是独立系统窗口、`FLAG_SECURE` 不继承，
 * 用它展示明文密钥就是能被截屏的。
 */
class ArchitectureRulesTest {

    /** 八个纯 Kotlin 包：零 Android、零 Room、零 OkHttp，且不允许直接读当前时间。 */
    private val pureKotlinPackages = listOf(
        "domain",
        "endpoint",
        "probe",
        "balance",
        "catalog",
        "importer",
        "backup",
        "crypto",
    )

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/lc33/tokenvault"),
        File("app/src/main/java/com/lc33/tokenvault"),
    ).firstOrNull { it.isDirectory } ?: error("找不到主源码目录")

    private fun kotlinFilesUnder(vararg relativePaths: String): List<File> =
        relativePaths
            .map { sourceRoot.resolve(it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private val allKotlinFiles: List<File>
        get() = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.relative(): String = relativeTo(sourceRoot).path.replace('\\', '/')

    private fun fail(rule: String, violations: List<String>) {
        assertTrue(
            "$rule\n" + violations.joinToString("\n") { "  - $it" },
            violations.isEmpty(),
        )
    }

    private companion object {
        /** 见 [hasExemptionMarker]。拼出来而不是写成一整个字面量，否则这份文件自己会命中自己。 */
        const val I18N_EXEMPT = "i18n" + "-exempt"
    }

    @Test
    fun `纯 Kotlin 层不依赖平台`() {
        val banned = listOf(
            "import android." to "Android 类型",
            "import androidx." to "AndroidX 类型（含 Room）",
            "import okhttp3." to "OkHttp 类型",
            "Clock.System" to "直接读当前时间（必须注入 Clock，否则测试变成时间敏感的）",
        )
        val violations = mutableListOf<String>()
        for (file in kotlinFilesUnder(*pureKotlinPackages.toTypedArray())) {
            val text = file.readText()
            for ((needle, why) in banned) {
                if (text.contains(needle)) {
                    violations += "${file.relative()} 出现 $needle —— $why"
                }
            }
        }
        fail("纯 Kotlin 的八个包里不允许出现平台依赖（计划.md 红线 20）：", violations)
    }

    @Test
    fun `只有 ui-miuix 能 import MIUIX`() {
        val violations = allKotlinFiles
            .filter { !it.relative().startsWith("ui/miuix/") }
            .filter { it.readText().contains("import top.yukonga.miuix") }
            .map { "${it.relative()} 直接 import 了 MIUIX" }
        fail(
            "MIUIX 是实验期库，API 可能无预告变更，所以只允许 ui/miuix/ 这一层直接引用它" +
                "（计划.md §4.3）：",
            violations,
        )
    }

    @Test
    fun `禁用 MIUIX 的 Window 系列弹层`() {
        // 用正则拼出被禁的名字，这样本文件里不出现那些字面量，仓库级 grep 不会自己撞上自己。
        val bannedWindowComponents =
            Regex("""\bWindow(?:Dialog|BottomSheet|[A-Za-z]*Popup|[A-Za-z]*Menu|[A-Za-z]*Preference)\b""")
        val violations = allKotlinFiles
            .mapNotNull { file ->
                bannedWindowComponents.find(file.readText())?.let { "${file.relative()} 用了 ${it.value}" }
            }
        fail(
            "那一族是独立系统窗口，FLAG_SECURE 不继承；展示明文密钥的弹层必须用 Overlay*" +
                "（计划.md §7.5、§13.2）：",
            violations,
        )
    }

    @Test
    fun `screens 里不出现 SQL 与 HTTP 构造`() {
        val sqlKeywords = Regex("""\b(SELECT\s|INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|CREATE\s+TABLE)""")
        val violations = mutableListOf<String>()
        for (file in kotlinFilesUnder("screens")) {
            val text = file.readText()
            sqlKeywords.find(text)?.let { violations += "${file.relative()} 出现 SQL：${it.value.trim()}" }
            if (text.contains("Request.Builder")) {
                violations += "${file.relative()} 直接构造 HTTP 请求"
            }
        }
        fail("页面层不碰 SQL，也不构造 HTTP 请求（计划.md §4.3）：", violations)
    }

    @Test
    fun `仓库里不出现真实密钥形态的字面量`() {
        val keyShaped = Regex("""sk-[A-Za-z0-9]{20,}""")
        val violations = allKotlinFiles
            .mapNotNull { file ->
                keyShaped.find(file.readText())?.let { "${file.relative()} 出现疑似真实密钥" }
            }
        fail("真实凭据永不入库（计划.md §0、§14.2）：", violations)
    }

    @Test
    fun `Compose 代码里没有中文字面量`() {
        val violations = mutableListOf<String>()
        for (file in allKotlinFiles) {
            val offenders = chineseStringLiteralsIn(file.readText())
            if (offenders.isNotEmpty()) {
                violations += "${file.relative()}: " + offenders.joinToString(", ") { "\"$it\"" }
            }
        }
        fail(
            "用户可见文本一律进 values/strings.xml 与 values-zh-rCN/strings.xml，" +
                "代码里不留中文字面量（计划.md 红线 19）。" +
                "确实是解析用的模式而不是 UI 文案时，在那一行加 $I18N_EXEMPT 注释并写明理由：",
            violations,
        )
    }

    @Test
    fun `配色下拉的选项数与枚举一致`() {
        // 下拉是按**下标**选的（AppDropdownRow 的 API 就是 selectedIndex），所以
        // R.array.color_scheme_modes 的条目数与 AppColorSchemeMode 的常量数必须相等。
        // 不等的表现不是崩溃而是错位：加了一个枚举值忘了加文案，用户选"深色"得到壁纸取色；
        // 反过来 entries[index] 会越界。两种都编译得过。
        val enumCount = AppColorSchemeMode.entries.size

        val resRoot = sequenceOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory } ?: error("找不到 res 目录")
        val stringFiles = resRoot.listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") }
            .map { it.resolve("strings.xml") }
            .filter { it.isFile }
        assertTrue("至少要比到一份 strings.xml", stringFiles.isNotEmpty())

        val violations = stringFiles.mapNotNull { xml ->
            val array = xml.readText()
                .substringAfter("""<string-array name="color_scheme_modes">""", "")
                .substringBefore("</string-array>")
            val count = Regex("<item>").findAll(array).count()
            val where = "${xml.parentFile!!.name}/strings.xml"
            when {
                array.isEmpty() -> "$where 里没有 color_scheme_modes"
                count != enumCount -> "$where 有 $count 项，AppColorSchemeMode 有 $enumCount 个"
                else -> null
            }
        }
        fail("配色下拉按下标取值，两边数量必须一致（红线 17 的同一条道理）：", violations)
    }

    /**
     * 逐行豁免标记。
     *
     * 为什么需要它：本项目有一类中文**必须**留在代码里——它们是拿来**匹配用户粘贴进来的
     * 中文**的解析模式，不是给用户看的文案。例如 §11.1 协议别名里的全角括号与"原生"、
     * §8.4 第 4 行的额度关键词（`该令牌额度已用尽`）、§8.2 客户端校验关键词里的"客户端"。
     *
     * 把它们搬进 strings.xml 是**错的**：那样它们会跟着界面语言变，于是"手机设成英文的
     * 用户粘贴一段中文数据"就解析不出来了——而这恰恰是最常见的用法。
     *
     * 标记刻意做成**逐行**而不是逐文件：逐文件等于给整个文件开后门，而 review 时
     * 没人会逐行去确认那个后门有没有被滥用。
     */
    private fun hasExemptionMarker(source: String, index: Int): Boolean {
        val lineStart = source.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
        val lineEnd = source.indexOf('\n', index).let { if (it < 0) source.length else it }
        return source.substring(lineStart, lineEnd).contains(I18N_EXEMPT)
    }

    /**
     * 找出源码里含中日韩字符的**字符串字面量**。注释里的中文是允许的，
     * 所以要先把注释剥掉，不能一把正则了事。
     */
    private fun chineseStringLiteralsIn(source: String): List<String> {
        val cjk = Regex("""[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]""")
        val found = mutableListOf<String>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                // 行注释
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    while (i < n && source[i] != '\n') i++
                }
                // 块注释（含 KDoc）
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i = minOf(i + 2, n)
                }
                // 原始字符串
                c == '"' && source.startsWith("\"\"\"", i) -> {
                    // 结束符不能取"第一个 \"\"\""：Kotlin 允许原始字符串的内容以引号结尾，
                    // 此时源码里会出现连着的四个引号（例如正则 "[^"]*" 写成原始字符串）。
                    // 取第一个就会把结束符定在内容中间，之后整个扫描错位，把后面的注释
                    // 当成字符串——曾经因此把 crypto/Redactor.kt 误报成"有中文字面量"。
                    // 正确规则：连续引号里**最后三个**才是结束符。
                    val hit = source.indexOf("\"\"\"", i + 3)
                    if (hit < 0) {
                        val body = source.substring(i + 3)
                        if (cjk.containsMatchIn(body) && !hasExemptionMarker(source, i)) {
                            found += body.take(30)
                        }
                        i = n
                    } else {
                        var runEnd = hit
                        while (runEnd < n && source[runEnd] == '"') runEnd++
                        val body = source.substring(i + 3, runEnd - 3)
                        if (cjk.containsMatchIn(body) && !hasExemptionMarker(source, i)) {
                            found += body.take(30)
                        }
                        i = runEnd
                    }
                }
                // 普通字符串
                c == '"' -> {
                    val start = i
                    val sb = StringBuilder()
                    i++
                    while (i < n && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < n) {
                            sb.append(source[i]).append(source[i + 1])
                            i += 2
                        } else {
                            sb.append(source[i])
                            i++
                        }
                    }
                    i++
                    val body = sb.toString()
                    if (cjk.containsMatchIn(body) && !hasExemptionMarker(source, start)) {
                        found += body.take(30)
                    }
                }
                // 字符字面量
                c == '\'' -> {
                    i++
                    while (i < n && source[i] != '\'') {
                        i += if (source[i] == '\\') 2 else 1
                    }
                    i++
                }
                else -> i++
            }
        }
        return found
    }
}
