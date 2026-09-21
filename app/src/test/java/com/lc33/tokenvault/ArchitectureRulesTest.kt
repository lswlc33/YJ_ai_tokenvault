package com.lc33.tokenvault

import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoRefreshPolicy
import com.lc33.tokenvault.domain.ClipboardClearPolicy
import com.lc33.tokenvault.domain.HttpConcurrencyPolicy
import com.lc33.tokenvault.domain.model.LogRetention
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import com.lc33.tokenvault.ui.shell.KEY_AUTH_STYLES
import com.lc33.tokenvault.ui.shell.KEY_BALANCE_KINDS
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode
import com.lc33.tokenvault.ui.theme.PROVIDER_COLOR_COUNT
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
 * 比如 MIUIX 的 `Window*` 弹层是独立系统窗口、与页面组合树脱钩，
 * 用它展示明文密钥会让弹层与页面的生命周期/状态不同步。
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

    // 两个源码根：app 是 Android 工程（UI 还在 app），shared 是阶段2 抽出的纯 Kotlin 层。
    // 纯 Kotlin 包（domain/endpoint/probe/balance/catalog/importer/backup/crypto）迁到
    // shared/src/commonMain 后，架构规则必须改扫 shared，否则"纯 Kotlin 层不依赖平台"
    // 这条防线就空转了（app 里这些包已是空目录）。
    private val appSourceRoot: File = sequenceOf(
        File("src/main/java/com/lc33/tokenvault"),
        File("app/src/main/java/com/lc33/tokenvault"),
    ).firstOrNull { it.isDirectory } ?: error("找不到 app 主源码目录")

    private val sharedSourceRoot: File = sequenceOf(
        File("shared/src/commonMain/kotlin/com/lc33/tokenvault"),
        File("../shared/src/commonMain/kotlin/com/lc33/tokenvault"),
    ).firstOrNull { it.isDirectory } ?: error("找不到 shared 主源码目录")

    private fun kotlinFilesUnder(root: File, vararg relativePaths: String): List<File> =
        relativePaths
            .map { root.resolve(it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun allKotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.relPathOf(root: File): String = relativeTo(root).path.replace('\\', '/')

    /** 两个根混扫时，按所属根算出带前缀的相对路径，便于定位违规文件。 */
    private fun File.displayPath(): String {
        return if (absolutePath.startsWith(sharedSourceRoot.absolutePath)) {
            "shared/" + relativeTo(sharedSourceRoot).path.replace('\\', '/')
        } else {
            relativeTo(appSourceRoot).path.replace('\\', '/')
        }
    }

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
        // 纯 Kotlin 包在阶段2 已迁到 shared/src/commonMain，这里扫 shared；同时扫 app 兜底，
        // 防止有人把平台依赖塞回 app 里残留的同名包。
        for (root in listOf(appSourceRoot, sharedSourceRoot)) {
            for (file in kotlinFilesUnder(root, *pureKotlinPackages.toTypedArray())) {
                val text = file.readText()
                for ((needle, why) in banned) {
                    if (text.contains(needle)) {
                        violations += "${file.relPathOf(root)} 出现 $needle —— $why"
                    }
                }
            }
        }
        fail("纯 Kotlin 的八个包里不允许出现平台依赖（计划.md 红线 20）：", violations)
    }

    @Test
    fun `只有 ui-miuix 能 import MIUIX`() {
        val violations = (allKotlinFiles(appSourceRoot) + allKotlinFiles(sharedSourceRoot))
            // displayPath() 对 shared 里的文件返回 "shared/ui/miuix/xxx.kt"，对 app 返回 "ui/miuix/xxx.kt"，
            // 都统一以 "ui/miuix/" 结尾段为界，用 contains("/ui/miuix/") 或 startsWith("ui/miuix/") 判断。
            .filter { !it.displayPath().let { p -> p.startsWith("ui/miuix/") || p.endsWith("/ui/miuix/") || p.contains("/ui/miuix/") } }
            .filter { it.readText().contains("import top.yukonga.miuix") }
            .map { "${it.displayPath()} 直接 import 了 MIUIX" }
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
        val violations = (allKotlinFiles(appSourceRoot) + allKotlinFiles(sharedSourceRoot))
            .mapNotNull { file ->
                bannedWindowComponents.find(file.readText())?.let { "${file.displayPath()} 用了 ${it.value}" }
            }
        fail(
            "那一族是独立系统窗口、与页面组合树脱钩；弹层统一用 Overlay*" +
                "（计划.md §13.2）：",
            violations,
        )
    }

    @Test
    fun `screens 里不出现 SQL 与 HTTP 构造`() {
        val sqlKeywords = Regex("""\b(SELECT\s|INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|CREATE\s+TABLE)""")
        val violations = mutableListOf<String>()
        // 阶段3 起 screens/ 全在 shared/src/commonMain，app 下那个目录已经不存在了：
        // 只扫 appSourceRoot 时 kotlinFilesUnder 拿到空列表，这条规则会**静默通过**，
        // 看起来是绿的、实际是空转。app 那一份保留作兜底，防止有人把页面搬回去。
        for (root in listOf(appSourceRoot, sharedSourceRoot)) {
            for (file in kotlinFilesUnder(root, "screens")) {
                val text = file.readText()
                sqlKeywords.find(text)?.let { violations += "${file.relPathOf(root)} 出现 SQL：${it.value.trim()}" }
                if (text.contains("Request.Builder")) {
                    violations += "${file.relPathOf(root)} 直接构造 HTTP 请求"
                }
            }
        }
        fail("页面层不碰 SQL，也不构造 HTTP 请求（计划.md §4.3）：", violations)
    }

    @Test
    fun `仓库里不出现真实密钥形态的字面量`() {
        val keyShaped = Regex("""sk-[A-Za-z0-9]{20,}""")
        val violations = (allKotlinFiles(appSourceRoot) + allKotlinFiles(sharedSourceRoot))
            .mapNotNull { file ->
                keyShaped.find(file.readText())?.let { "${file.displayPath()} 出现疑似真实密钥" }
            }
        fail("真实凭据永不入库（计划.md §0、§14.2）：", violations)
    }

    @Test
    fun `页面主体不允许出现文本按钮`() {
        // 按钮只允许在 OverlayDialog / OverlayBottomSheet 里；页面主体统一走行入口或卡片。
        val violations = (allKotlinFiles(appSourceRoot) + allKotlinFiles(sharedSourceRoot))
            .filter { file ->
                val path = file.displayPath()
                path.startsWith("screens/") || path.startsWith("shared/screens/")
            }
            .filter { it.readText().contains("AppTextButton") }
            .map { it.displayPath() }
        fail("页面主体不允许 import 或调用 AppTextButton（弹层按钮由 AppDialog / AppBottomSheet 提供）：", violations)
    }
    @Test
    fun `Compose 代码里没有中文字面量`() {
        val violations = mutableListOf<String>()
        for (file in allKotlinFiles(appSourceRoot) + allKotlinFiles(sharedSourceRoot)) {
            val offenders = chineseStringLiteralsIn(file.readText())
            if (offenders.isNotEmpty()) {
                violations += "${file.displayPath()}: " + offenders.joinToString(", ") { "\"$it\"" }
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
        fail(
            "配色下拉按下标取值，两边数量必须一致（红线 17 的同一条道理）：",
            arrayItemCountMismatches("color_scheme_modes", AppColorSchemeMode.entries.size, "AppColorSchemeMode"),
        )
    }

    @Test
    fun `返回动画下拉的选项数与枚举一致`() {
        // 这两个下拉同样按 ordinal/entries 下标取值；顺序错了会直接把用户选的样式换掉。
        fail(
            "返回动画下拉按下标取值，两边数量必须一致（红线 17 的同一条道理）：",
            arrayItemCountMismatches("predictive_back_styles", PredictiveBackStyle.entries.size, "PredictiveBackStyle"),
        )
    }

    @Test
    fun `返回方向下拉的选项数与枚举一致`() {
        fail(
            "返回方向下拉按下标取值，两边数量必须一致（红线 17 的同一条道理）：",
            arrayItemCountMismatches("predictive_back_exit_directions", PredictiveBackExitDirection.entries.size, "PredictiveBackExitDirection"),
        )
    }

    @Test
    fun `自动锁定下拉的选项数与策略表一致`() {
        // 同上，但后果更重：错位一格就是“选「立即」得到 5 分钟”，而这是个安全设置。
        // AutoLockPolicy.OPTIONS 末尾那一枚是「从不」，少一项就把它变成了「5 分钟」
        fail(
            "自动锁定下拉按下标取值，两边数量必须一致（§7.4）：",
            arrayItemCountMismatches("auto_lock_options", AutoLockPolicy.OPTIONS.size, "AutoLockPolicy.OPTIONS"),
        )
    }

    @Test
    fun `剪贴板清除下拉的选项数与策略表一致`() {
        // 同「自动锁定」：按下标取值，错位一格就是「选 30 秒得到 5 分钟」。
        fail(
            "剪贴板清除下拉按下标取值，两边数量必须一致（§7.5）：",
            arrayItemCountMismatches("clipboard_clear_options", ClipboardClearPolicy.OPTIONS.size, "ClipboardClearPolicy.OPTIONS"),
        )
    }

    /**
     * 自动刷新间隔那一枚下拉（§13.4 探测设置页）。
     *
     * 与「自动锁定」同一条风险：按下标取值，错位一格就是「选每 15 分钟得到 6 小时」，
     * 而这一项的后果是数据一直不新，用户只会觉得"这个自动刷新根本没生效"。
     */
    @Test
    fun `自动刷新间隔下拉的选项数与策略表一致`() {
        fail(
            "自动刷新间隔下拉按下标取值，两边数量必须一致（§13.4）：",
            arrayItemCountMismatches(
                "probe_auto_refresh_interval_options",
                AutoRefreshPolicy.OPTIONS.size,
                "AutoRefreshPolicy.OPTIONS",
            ),
        )
    }

    /**
     * 最大并发数那一枚下拉（§13.4 探测设置页）。
     *
     * 与「自动锁定」「自动刷新间隔」同一条风险，但后果更直白：按下标取值，错位一格就是
     * 「选 8 得到 32」，等于一次把四倍请求打给上游——红线 29 挡的正是这件事。
     */
    @Test
    fun `最大并发数下拉的选项数与策略表一致`() {
        fail(
            "最大并发数下拉按下标取值，两边数量必须一致（§13.4）：",
            arrayItemCountMismatches(
                "probe_max_concurrency_options",
                HttpConcurrencyPolicy.OPTIONS.size,
                "HttpConcurrencyPolicy.OPTIONS",
            ),
        )
    }

    /**
     * 三端 HTTP 引擎的并发配置必须与设置档位对得上。
     *
     * 为什么这条要进机器检查而不是留在 code review：设置里能选的最大值是 32，而引擎的
     * `maxRequests` 是构造时定死的。谁把它"顺手改回 8"，16/32 两档就悄悄变成假数——
     * 界面写着 32，实际 8，而且没有任何报错。这条断言就是让这种改动直接红。
     *
     * iOS 只断"引擎里不该出现全局 maxRequests"：Darwin 没有全局并发的概念（全局那一档由
     * commonMain 的 `net/ConcurrencyGate` 负责），在引擎里再写一个是第二套真相。
     */
    @Test
    fun `三端引擎的并发配置与设置档位对齐`() {
        val violations = mutableListOf<String>()
        // 文件名带上平台短后缀（`.android.kt` / `.jvm.kt`），所以下面这两对不能只按目录拼出来。
        val okHttpEngines = listOf(
            "androidMain" to "HttpEngine.android.kt",
            "jvmMain" to "HttpEngine.jvm.kt",
        )
        for ((sourceSet, fileName) in okHttpEngines) {
            val file = platformSourceRoot(sourceSet).resolve("net/$fileName")
            if (!file.isFile) {
                violations += "找不到 $file"
                continue
            }
            val text = file.readText()
            if (!text.contains("maxRequests = HttpConcurrencyPolicy.MAX")) {
                violations += "${file.name} 的 maxRequests 必须等于 HttpConcurrencyPolicy.MAX，" +
                    "否则最高两档只是把请求挪进 OkHttp 队列里排队"
            }
            if (!text.contains("maxRequestsPerHost = HttpConcurrencyPolicy.PER_HOST_MAX_REQUESTS")) {
                violations += "${file.name} 的 maxRequestsPerHost 必须走常量（红线 29 的每主机限流）"
            }
        }
        val ios = platformSourceRoot("iosMain").resolve("net/HttpEngine.ios.kt")
        if (!ios.isFile) {
            violations += "找不到 $ios"
        } else if (Regex("""maxRequests\s*=""").containsMatchIn(ios.readText())) {
            violations += "iosMain 不该出现 maxRequests：全局那一档归 commonMain 的 ConcurrencyGate，" +
                "引擎里再写一套就是两个真相互相不认识"
        }
        fail("HTTP 引擎的并发上限必须与「最大并发数」档位一致：", violations)
    }

    /** 平台源码根：与 [sharedSourceRoot] 同一套两种 cwd 兜底（IDE 跑与命令行跑工作目录不同）。 */
    private fun platformSourceRoot(sourceSet: String): File = sequenceOf(
        File("shared/src/$sourceSet/kotlin/com/lc33/tokenvault"),
        File("../shared/src/$sourceSet/kotlin/com/lc33/tokenvault"),
    ).firstOrNull { it.isDirectory } ?: error("找不到 shared/$sourceSet 主源码目录")

    /**
     * 密钥设置页那两个下拉。
     *
     * 它们和上面五个是同一类风险，但**漏在防线之外**很久了：`auth_styles` /
     * `balance_kinds` 都是按下标写进 `KeyDraft`（`authStyleIndex` / `balanceKindIndex`），
     * 存库时再按下标查 `KEY_AUTH_STYLES` / `KEY_BALANCE_KINDS`。少一项或顺序变了，
     * 表现是**把余额查询类型写错**——比如把 new-api 存成 DeepSeek，用户不会发现，
     * 只会看到"余额总是查不到"。所以补进这条机器检查里。
     */
    @Test
    fun `密钥设置下拉的选项数与代码表一致`() {
        fail(
            "密钥设置页的下拉按下标取值，两边数量必须一致：",
            arrayItemCountMismatches("auth_styles", KEY_AUTH_STYLES.size, "KEY_AUTH_STYLES") +
                arrayItemCountMismatches("balance_kinds", KEY_BALANCE_KINDS.size, "KEY_BALANCE_KINDS"),
        )
    }

    @Test
    fun `日志保留期下拉的选项数与枚举一致`() {
        // 同一类风险，且后果是数据：错位一格就是「选 7 天得到 30 天」，或者反过来
        // 把 7 天读成永久，日志再也不会被清理。
        fail(
            "日志保留期下拉按下标取值，两边数量必须一致：",
            arrayItemCountMismatches("log_retention_options", LogRetention.entries.size, "LogRetention"),
        )
    }

    @Test
    fun `供应商颜色下拉的选项数与调色板一致`() {
        // `providers.color` 存的是调色板下标，颜色名按同一下标取：少一个名字就是
        // 「选第 8 个颜色得到第 1 个」，而用户只会觉得"这个软件记错了我的选择"。
        fail(
            "供应商颜色下拉按下标取值，颜色名数量必须与调色板一致：",
            arrayItemCountMismatches("provider_colors", PROVIDER_COLOR_COUNT, "PROVIDER_COLOR_COUNT"),
        )
    }

    /** 比每一份 strings.xml 里某个 `string-array` 的条目数与代码侧那张表的长度。 */
    private fun arrayItemCountMismatches(arrayName: String, expected: Int, codeSide: String): List<String> {
        // 阶段3：string-array 已随 UI 迁到 shared 的 composeResources，不再在 app res。
        val resRoot = sequenceOf(
            File("shared/src/commonMain/composeResources"),
            File("../shared/src/commonMain/composeResources"),
        ).firstOrNull { it.isDirectory } ?: error("找不到 composeResources 目录")
        val stringFiles = resRoot.listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") }
            .map { it.resolve("strings.xml") }
            .filter { it.isFile }
        assertTrue("至少要比到一份 strings.xml", stringFiles.isNotEmpty())

        return stringFiles.mapNotNull { xml ->
            val array = xml.readText()
                .substringAfter("""<string-array name="$arrayName">""", "")
                .substringBefore("</string-array>")
            val count = Regex("<item>").findAll(array).count()
            val where = "${xml.parentFile!!.name}/strings.xml"
            when {
                array.isEmpty() -> "$where 里没有 $arrayName"
                count != expected -> "$where 有 $count 项，$codeSide 有 $expected 个"
                else -> null
            }
        }
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

    @Test
    fun `每条二级路由都有入口`() {
        // 整屏模型页曾经就是这条规则拦下的那个东西：Routes.kt 里声明了、VaultNavHost 里
        // 把 ViewModel 和回调全接好了、编译与 800 多条测试一起绿，但全仓没有一处
        // navigate(XRoute(...))，于是用户在界面上永远走不进去——"接了线没装开关"。
        // 顶栏那三个 tab 由 VaultNavDisplay 按索引切换（navigate 传的是变量），所以它们
        // 从 TopLevelRoutes 那份表里读出来放行，而不是写死在这。
        val routesSource = sharedSourceRoot.resolve("ui/shell/Routes.kt")
        assertTrue("找不到 ${routesSource.relPathOf(sharedSourceRoot)}", routesSource.isFile)
        val declared = Regex("^data (?:class|object) (\\w+Route)\\b", RegexOption.MULTILINE)
            .findAll(routesSource.readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("Routes.kt 里一条路由都没解析到，先修这条测试的正则", declared.isNotEmpty())

        val tabs = sharedSourceRoot.resolve("ui/shell/TopLevelPagerState.kt").readText()
            .let { source -> Regex("TopLevelRoutes[^=]*= listOf\\(([^)]*)\\)").find(source) }
            ?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        assertTrue("TopLevelPagerState.kt 里读不到 TopLevelRoutes，先修这条测试的正则", tabs.isNotEmpty())

        val sources = (allKotlinFiles(appSourceRoot) + allKotlinFiles(sharedSourceRoot))
            .filter { it.name != "Routes.kt" }
            .map { it.readText() }
        val unreachable = declared.filter { name ->
            name !in tabs && sources.none { text -> text.contains("navigate($name") }
        }
        fail(
            "每条二级路由都要有至少一处 navigate(该Route(...))，否则那个页面是进不去的死代码" +
                "（顶栏三个 tab 走索引切换，由 TopLevelRoutes 放行）：",
            unreachable.map { "$it 没有任何 navigate 调用点" },
        )
    }
}
