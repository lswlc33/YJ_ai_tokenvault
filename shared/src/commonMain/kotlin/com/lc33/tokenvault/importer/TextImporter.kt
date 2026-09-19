package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol

/**
 * 文本导入解析器（§11.1）。
 *
 * 纯函数：`parse(text)` 不碰任何 Android / 网络 / 时间，输入 `String` 输出结构化结果。
 * 单测直接喂脱敏 fixture，所以这一层在 JVM 上全覆盖。
 *
 * 格式约束（与 `示例数据.md` 一致）：
 * - 记录之间用单独一行 `---` 分隔，最后一条可以没有 `---`（EOF 也是合法终止）。
 * - 字段名与值用空白分隔。
 * - 块字段（`支持端点类型` / `模型列表`）的内容在字段名之后的连续行里。
 * - `API Key` 的值可以再带 label：`API Key 主号 sk-xxx`（[TextExporter] 写这种），
 *   也可以只写密钥 `API Key sk-xxx`（旧样本写法，label 由顺序分配）。
 * - 余额那一组字段（`余额查询类型` / `请求地址` / `访问令牌` / `用户ID` / `换算比` /
 *   `余额配置`）与 `路径覆盖` 是 [TextExporter] 的往返字段：导出写什么，这里就能读回什么。
 *
 * 块字段的终止规则（§11.1 第 1–5 条，**必须写死**）：
 * 1. 遇到块字段名后进入块模式。
 * 2. 块内**跳过空行**，不把空行当终止符。
 * 3. 块在遇到以下任一情况时结束：下一行匹配任何已知字段名（含 `字段名 值` 与
 *    "字段名单独成行"两种形态）、单独一行 `---`、文件结束。
 * 4. **已知字段名优先于块内容**：`模型列表` 之后出现的 `余额查询类型 NewAPI` 是字段不是模型。
 * 5. 与"字段名单独成行、值在下一行"冲突时同样是**已知字段名优先**：先判定这一行是不是字段名。
 */
object TextImporter {

    /** 记录分隔符。 */
    private const val SEPARATOR = "---"

    /**
     * 全部已知字段名，**含**会单独成行、值在下一行的那些。
     * 块终止判定与"字段名优先"都靠它。
     *
     * 按**长度降序**排：`API Key`（含空格）与 `API请求地址` 都要在 `API` 被误判前先匹配。
     */
    private val FIELD_NAMES = listOf(
        "供应商名称", "支持端点类型", "API请求地址", "余额查询类型", "客户端预设", // i18n-exempt: 导入格式的字段名
        "官网链接", "模型列表", "请求地址", "访问令牌", "平台账号", "平台密码", // i18n-exempt: 导入格式的字段名
        "账号备注", "登录方式", "路径覆盖", "登录地址", "换算比", "用户ID", // i18n-exempt: 导入格式的字段名
        "余额配置", "API Key", "备注", // i18n-exempt: 导入格式的字段名
    )

    /** 登录方式支持逗号、空格与 `/` 分隔；未知值跳过而不是让整份导入失败。 */
    private fun parseLoginMethods(raw: String?): Set<LoginMethod> =
        raw?.split(',', '/', ' ', '，')
            ?.mapNotNull { rawMethod ->
                when (rawMethod.trim().lowercase()) {
                    "github" -> LoginMethod.GITHUB
                    "linuxdo", "linux.do", "linux do" -> LoginMethod.LINUX_DO
                    else -> null
                }
            }
            ?.toCollection(LinkedHashSet())
            ?: emptySet()

    /** 长字段优先，避免「登录方式」被「登录」这类潜在短字段截走。 */
    private val FIELD_NAMES_BY_LENGTH = FIELD_NAMES.sortedByDescending(String::length)

    /** 块条目：`- 内容`。 */
    private val LIST_ITEM = Regex("""^\s*[-•]\s*(.*)$""")

    /** 值内部的空白分隔（`API Key 主号 sk-xxx`、`路径覆盖 chat /v1/x`）。 */
    private val WHITESPACE = Regex("""\s+""")

    /**
     * `无` / `—` / 空 都表示"没有"（§11.1 备注那行）。
     *
     * **不含 `-`**：单个连字符太常见，真实备注就写"-"（"待补"的速记）时会整条被吞成 null，
     * 而导出侧的空值一律写 `无`，去掉它不影响任何往返。
     */
    private val EMPTY_VALUES = setOf("无", "—", "") // i18n-exempt: 匹配粘贴数据的空值写法

    fun parse(text: String): ParseResult {
        val records = mutableListOf<ParsedRecord>()
        val errors = mutableListOf<ParseError>()

        // 先按 `---` 切成原始段。空段（首尾 / 连续 `---`）直接跳过。
        val rawBlocks = text.split(Regex("""(?m)^\s*---\s*$"""))
        for (rawBlock in rawBlocks) {
            val body = rawBlock.trim()
            if (body.isEmpty()) continue
            val record = parseRecord(body)
            if (record != null) {
                records += record
            } else {
                errors += ParseError(nameHint = body.lineSequence().firstOrNull(), message = "no name")
            }
        }

        return ParseResult(records = records, errors = errors)
    }

    /**
     * 解析一条记录的主体（已被 `---` 切开、去头尾空白的文本）。
     * 没有「供应商名称」时返回 null——那是 [ParseError] 的职责。
     */
    private fun parseRecord(body: String): ParsedRecord? {
        val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null

        // 逐行状态机
        var name: String? = null
        var note: String? = null
        var websiteUrl: String? = null
        var apiBaseUrl: String? = null
        var balanceKind = BalanceKind.NONE
        /** 「余额查询类型」是「官方接口」时先记这个标志，收尾时有了 apiBaseUrl 再按 host 推断。 */
        var balanceIsOfficial = false
        var balanceBaseUrl: String? = null
        var balanceToken: CharArray? = null
        var balanceUserId: String? = null
        var clientProfileRef: String? = null

        /** new-api 系"多少 quota == 1 单位货币"的换算比，缺失时用适配器默认。 */
        var quotaPerUnit: Double? = null

        /** customJson 的原始配置串（`{...}`），交给 [com.lc33.tokenvault.balance.CustomJsonAdapter] 解读。 */
        var balanceConfig: String? = null

        /** 协议 → 完整路径覆盖（`路径覆盖 chat /v1/chat/completions`）。 */
        val pathOverrides = linkedMapOf<Protocol, String>()

        val protocols = linkedSetOf<Protocol>()
        val keys = mutableListOf<ParsedKey>()
        val models = mutableListOf<ParsedModel>()
        val accountDrafts = mutableListOf<MutableAccount>()
        val issues = mutableListOf<ImportIssue>()

        // 当前块字段；null 表示不在块内
        var inBlock: BlockKind? = null

        // 当前正在填充的账号组（`平台账号` 开新组）
        var currentAccount: MutableAccount? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // ---- 块模式：先看这一行是不是块终止 ----
            val block = inBlock
            if (block != null) {
                if (isSeparatorLine(line)) {
                    inBlock = null
                    i++
                    continue
                }
                val fieldName = fieldNameOf(line)
                if (fieldName != null) {
                    // 已知字段名优先于块内容（规则 4/5）
                    inBlock = null
                    // 不 i++，落到下面的字段处理
                } else {
                    // 块内容：跳过空行，条目进对应的块
                    val item = line.trim()
                    if (item.isNotEmpty()) {
                        when (block) {
                            BlockKind.PROTOCOLS -> parseProtocolItem(item)?.let { protocols += it }
                            BlockKind.MODELS -> parseModelItem(item, protocols)?.let {
                                models += it
                                if (it.needsReview) issues += ImportIssue.MODEL_NAME_REVIEW
                            }
                        }
                    }
                    i++
                    continue
                }
            }

            // ---- 字段行 ----
            val field = parseFieldLine(line)
            if (field == null) {
                // 既不是块内容也不是已知字段：忽略（可能是空行、注释、游离文本）
                i++
                continue
            }

            val (fName, fValue) = field
            when (fName) {
                "供应商名称" -> name = fValue // i18n-exempt: 导入格式的字段名
                "备注" -> note = if (fValue in EMPTY_VALUES) null else fValue // i18n-exempt: 导入格式的字段名
                "官网链接" -> websiteUrl = fValue?.trimEnd('/')?.ifEmpty { null } // i18n-exempt: 导入格式的字段名
                "API Key" -> {
                    // 值按空白再切一刀：**最后一个 token 是密钥，中间的都是 label**。
                    // 密钥（OpenAI / Anthropic / new-api 都是 base64 字符集）不含空白，
                    // 所以这个切法无歧义；反过来"secret 在前 label 在后"会让 label 里
                    // 的空格没法表达。只有一段时它就是密钥，label 按顺序自动分配——
                    // 这正是 `示例数据.md` 与旧样本的写法，向后兼容不用迁移。
                    val parts = fValue?.split(WHITESPACE)?.filter { it.isNotBlank() }.orEmpty()
                    val secret = parts.lastOrNull()
                    if (secret != null) {
                        val label = parts.dropLast(1).joinToString(" ").takeIf { it.isNotBlank() }
                            ?: keyLabel(keys.size)
                        keys += ParsedKey(label = label, secret = secret.toCharArray())
                    }
                }
                "API请求地址" -> apiBaseUrl = fValue // i18n-exempt: 导入格式的字段名
                "支持端点类型" -> inBlock = BlockKind.PROTOCOLS // i18n-exempt: 导入格式的字段名
                "模型列表" -> inBlock = BlockKind.MODELS // i18n-exempt: 导入格式的字段名
                "余额查询类型" -> { // i18n-exempt: 导入格式的字段名
                    balanceIsOfficial = isOfficialKind(fValue)
                    balanceKind = if (balanceIsOfficial) BalanceKind.NONE else parseBalanceKind(fValue)
                }
                "请求地址" -> { // i18n-exempt: 导入格式的字段名
                    // 只有出现在「余额查询类型」之后才算余额地址（§11.1）
                    if (balanceKind != BalanceKind.NONE) {
                        balanceBaseUrl = parseBalanceUrl(fValue)
                    }
                }
                "访问令牌" -> { // i18n-exempt: 导入格式的字段名
                    // 值可能在下一行（字段名单独成行）
                    val value = fValue ?: peekNextValue(lines, i)
                    if (value != null && value.isNotBlank()) {
                        balanceToken = value.toCharArray()
                        if (fValue == null) i++ // 消费了下一行
                    }
                }
                "用户ID" -> balanceUserId = fValue // i18n-exempt: 导入格式的字段名
                "换算比" -> { // i18n-exempt: 导入格式的字段名
                    // 只收正数：0 / 负数会把余额除成 Infinity 或负余额，宁可不存用默认值。
                    quotaPerUnit = fValue?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }
                }
                "余额配置" -> { // i18n-exempt: 导入格式的字段名
                    // customJson 的整份配置（method/path/headers/valuePath/usedPath/currency）。
                    // 这里**不校验 JSON**：形状对不对由 CustomJsonAdapter 在查询时报，
                    // 导入阶段把用户写的东西原样搬过去比自作聪明地丢掉更好。
                    balanceConfig = fValue?.trim()?.takeIf { it.isNotEmpty() }
                }
                "路径覆盖" -> { // i18n-exempt: 导入格式的字段名
                    // `<协议别名> <完整路径>`。别名认不出就整行跳过——存一条挂错协议的路径
                    // 比没有覆盖更糟（请求会安静地发到另一个端点上）。
                    val parts = fValue?.split(WHITESPACE)?.filter { it.isNotBlank() }.orEmpty()
                    val protocol = parts.firstOrNull()?.let { Protocol.fromAlias(it) }
                    val path = parts.drop(1).joinToString(" ").trim().takeIf { it.isNotEmpty() }
                    if (protocol != null && path != null) pathOverrides[protocol] = path
                }
                "客户端预设" -> clientProfileRef = fValue // i18n-exempt: 导入格式的字段名
                "平台账号" -> { // i18n-exempt: 导入格式的字段名
                    // 遇到下一个「平台账号」即开新组（§11.1 账号分组规则）
                    val acc = MutableAccount(username = fValue?.toCharArray())
                    currentAccount = acc
                    accountDrafts += acc
                }
                "平台密码" -> { // i18n-exempt: 导入格式的字段名
                    val acc = currentAccount ?: MutableAccount().also { accountDrafts += it; currentAccount = it }
                    acc.password = fValue?.toCharArray()
                }
                "账号备注" -> { // i18n-exempt: 导入格式的字段名
                    val acc = currentAccount ?: MutableAccount().also { accountDrafts += it; currentAccount = it }
                    acc.label = fValue ?: acc.label
                }
                "登录方式" -> { // i18n-exempt: 导入格式的字段名
                    val acc = currentAccount ?: MutableAccount().also { accountDrafts += it; currentAccount = it }
                    acc.loginMethods = parseLoginMethods(fValue)
                }
                "登录地址" -> { // i18n-exempt: 导入格式的字段名
                    val acc = currentAccount ?: MutableAccount().also { accountDrafts += it; currentAccount = it }
                    acc.loginUrl = fValue
                }
            }
            i++
        }

        // ---- 记录收尾：校验与派生 ----
        if (name.isNullOrBlank()) return null

        // 官方余额接口按 host 推断（§11.1：`api.deepseek.com` → `deepseek`，推不出则 customJson）。
        // customJson 本身就是"待配置"的语义——用户看到它就知道要手动填请求地址与路径。
        if (balanceIsOfficial) {
            balanceKind = inferOfficialKind(apiBaseUrl, balanceBaseUrl)
        }

        // 模型协议不在 supportedProtocols 里 → 自动补进去并提示（§11.2）
        for (m in models) {
            if (m.protocol !in protocols) {
                protocols += m.protocol
                if (ImportIssue.MODEL_PROTOCOL_ADDED !in issues) issues += ImportIssue.MODEL_PROTOCOL_ADDED
            }
        }

        // newapi 但缺访问令牌或用户 ID（§11.2）
        if (balanceKind == BalanceKind.NEWAPI && (balanceToken == null || balanceUserId.isNullOrBlank())) {
            issues += ImportIssue.BALANCE_MISSING_CREDENTIAL
        }

        // http:// 地址（§11.2）
        if (apiBaseUrl?.startsWith("http://") == true) {
            issues += ImportIssue.INSECURE_ENDPOINT
        }

        // 账号完整性（§11.2：只记一半合法，但提示）
        // 账号重复（同一条记录内相同平台账号，后一条跳过）——这里先标 issue，落库时跳过
        val seenUsernames = mutableSetOf<String>()
        val accounts = mutableListOf<ParsedAccount>()
        for (acc in accountDrafts) {
            val username = acc.username?.concatToString()
            if (username != null && !seenUsernames.add(username)) {
                issues += ImportIssue.ACCOUNT_DUPLICATE
                continue // 后一条跳过
            }
            if ((acc.username == null) != (acc.password == null)) {
                if (ImportIssue.ACCOUNT_INCOMPLETE !in issues) issues += ImportIssue.ACCOUNT_INCOMPLETE
            }
            accounts += ParsedAccount(
                label = acc.label,
                username = acc.username,
                password = acc.password,
                loginUrl = acc.loginUrl,
                // 漏传过一次的回归：`登录方式` 解析进了 acc.loginMethods，但这里没带上，
                // 于是导入后的账号永远没有登录方式——字段解析得再对，收尾漏一步就是零。
                loginMethods = acc.loginMethods,
            )
        }

        return ParsedRecord(
            name = name,
            note = note,
            websiteUrl = websiteUrl,
            apiBaseUrl = apiBaseUrl,
            supportedProtocols = protocols,
            pathOverrides = pathOverrides.toMap(),
            balanceKind = balanceKind,
            balanceBaseUrl = balanceBaseUrl,
            balanceToken = balanceToken,
            balanceUserId = balanceUserId,
            quotaPerUnit = quotaPerUnit,
            balanceConfig = balanceConfig,
            clientProfileRef = clientProfileRef,
            keys = keys,
            models = models.map { ParsedModel(it.modelId, it.protocol, it.needsReview) },
            accounts = accounts,
            issues = issues.distinct(),
        )
    }

    // ------------------------------------------------------------------ 工具

    private enum class BlockKind { PROTOCOLS, MODELS }

    /** 块填充中的账号草稿。 */
    private class MutableAccount(
        var label: String = "账号", // i18n-exempt: 账号 label 的落库缺省值（§11.1）
        var username: CharArray? = null,
        var password: CharArray? = null,
        var loginUrl: String? = null,
        var loginMethods: Set<LoginMethod> = emptySet(),
    )

    /** 是单独一行 `---`（块终止判定用）。 */
    private fun isSeparatorLine(line: String): Boolean =
        line.trim() == SEPARATOR

    /**
     * 取这一行的字段名（不取值）。
     *
     * 用显式的前缀匹配而不是正则：字段名里有空格（`API Key`），且括号说明可能是半角
     * `(…)` 也可能是全角 `（…）`。前缀匹配把"字段名后紧跟空白 / 括号 / 行尾"作为边界，
     * 两种括号和空格都能正确处理。
     *
     * 匹配不到已知字段名时返回 null——这是"已知字段名优先"的判定点（规则 4/5）。
     */
    private fun fieldNameOf(line: String): String? {
        val trimmed = line.trimStart()
        for (name in FIELD_NAMES_BY_LENGTH) {
            if (trimmed.startsWith(name) && isFieldNameBoundary(trimmed, name)) {
                return name
            }
        }
        return null
    }

    /**
     * 字段名之后必须是空白、括号（半角或全角）或行尾，否则不是字段行。
     * 例如 `API请求地址foo` 不该被当成 `API请求地址` + 值 `foo`。
     */
    private fun isFieldNameBoundary(line: String, name: String): Boolean {
        val rest = line.substring(name.length)
        return rest.isEmpty() ||
            rest[0].isWhitespace() ||
            rest[0] == '(' || rest[0] == '（'
    }

    /**
     * 解析一行字段，返回 (字段名, 值)。值可能为 null（字段名单独成行，值在下一行）。
     * 不是已知字段行时返回 null。
     *
     * 值部分会剥掉字段名后可能跟的括号说明（`访问令牌(在个人安全设置里获取)`），
     * 再按空白切出真正的值。
     */
    private fun parseFieldLine(line: String): Pair<String, String?>? {
        val name = fieldNameOf(line) ?: return null
        var rest = line.trimStart().substring(name.length).trimStart()

        // 剥掉紧跟的括号说明（半角或全角）
        if (rest.startsWith('(') || rest.startsWith('（')) {
            val open = rest[0]
            val close = if (open == '(') ')' else '）'
            val closeIdx = rest.indexOf(close)
            if (closeIdx >= 0) {
                rest = rest.substring(closeIdx + 1).trimStart()
            }
        }

        // 剩下的就是值（可能为空 → 字段名单独成行）
        return name to rest.trim().ifEmpty { null }
    }

    /** 块里 `- Chat Completions` 这种条目 → 协议别名。 */
    private fun parseProtocolItem(item: String): Protocol? {
        val content = LIST_ITEM.matchEntire(item)?.groupValues?.get(1) ?: item
        return Protocol.fromAlias(content)
    }

    /** 块里 `<modelId> <协议>` 这种条目。按最后一个空白切分。 */
    private fun parseModelItem(item: String, protocols: Set<Protocol>): ParsedModel? {
        val content = item.trim()
        if (content.isEmpty()) return null

        // 按最后一个空白切分；尾段命中协议别名则为协议，否则整行是 id 且协议取第一个
        val lastSpace = content.lastIndexOf(' ')
        if (lastSpace > 0) {
            val idPart = content.substring(0, lastSpace).trim()
            val protoPart = content.substring(lastSpace + 1).trim()
            val protocol = Protocol.fromAlias(protoPart)
            if (protocol != null) {
                return ParsedModel(
                    modelId = idPart,
                    protocol = protocol,
                    needsReview = needsReview(idPart),
                )
            }
            // 尾段不是协议：整行是 id，协议取 supportedProtocols 第一个
            return ParsedModel(
                modelId = content,
                protocol = protocols.firstOrNull() ?: Protocol.CHAT,
                needsReview = needsReview(content),
            )
        }

        // 没有空白：整行是 id
        return ParsedModel(
            modelId = content,
            protocol = protocols.firstOrNull() ?: Protocol.CHAT,
            needsReview = needsReview(content),
        )
    }

    /** 模型 id 含空格或大写字母 → 疑似显示名（§11.2）。 */
    private fun needsReview(modelId: String): Boolean =
        modelId.any { it.isWhitespace() || it.isUpperCase() }

    /** 「官方接口」判定：命中即由收尾阶段的 host 推断决定最终 kind。 */
    private fun isOfficialKind(value: String?): Boolean {
        val v = value?.trim()?.lowercase() ?: return false
        return v.contains("官方") || v.contains("official") // i18n-exempt: 匹配粘贴数据的"官方接口"写法
    }

    /**
     * 余额查询类型 → [BalanceKind]（非官方的那几档）。
     *
     * 按 [BalanceKind.wireName] **忽略大小写**认，所以导出侧写什么、这里就能读回什么
     * （往返对称）。大小写不敏感不是宽容：`customJson` 这一档的 wireName 本身带大写，
     * 而值先被 lowercase() 过，精确比较会让它永远认不出来——导出再导入一次，
     * 用户手配的任意站余额就静默变成"不查"。
     * `new-api` 这种带连字符的历史写法单独留着——旧样本里出现过，认不出来等于把用户
     * 已经配好的余额查询静默降级成"不查"。
     */
    private fun parseBalanceKind(value: String?): BalanceKind {
        val v = value?.trim()?.lowercase() ?: return BalanceKind.NONE
        if (v == "new-api") return BalanceKind.NEWAPI
        return BalanceKind.entries.firstOrNull { it.wireName.equals(v, ignoreCase = true) } ?: BalanceKind.NONE
    }

    /**
     * 剥离行尾说明文字（如"不填默认是端点域名"）与结尾斜杠。
     *
     * 取第一个空白前的部分当地址，空串则存 null。
     * **刻意不做"等于 API请求地址就存 null"的归一**：用户显式写了这一行就该照原样留下
     * （`TextImporterTest` 里那条"请求地址剥离行尾说明文字"就是它的契约），而"没填就用端点
     * 域名"这层兜底本来就由适配器写在 `balanceBaseUrl ?: apiRoot` 上，两处都归一反而看不出
     * 用户到底填没填。
     */
    private fun parseBalanceUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        // 取第一个空白前的部分作为地址
        val url = value.trim().substringBefore(' ').trimEnd('/')
        return url.ifEmpty { null }
    }

    /** 取下一行的值（`访问令牌` 字段名单独成行时）。 */
    private fun peekNextValue(lines: List<String>, current: Int): String? {
        if (current + 1 >= lines.size) return null
        val next = lines[current + 1].trim()
        // 下一行若是字段名，则说明确实没有值（而不是把字段名当值）
        if (fieldNameOf(next) != null) return null
        return next.ifEmpty { null }
    }

    /** 密钥 label：第一张 `主号`，之后 `备用 N`。 */
    private fun keyLabel(index: Int): String =
        if (index == 0) "主号" else "备用 ${index + 1}" // i18n-exempt: 导入格式规定的密钥 label（§11.1）

    /**
     * 官方余额接口按 host 推断（§11.1：`api.deepseek.com` → `deepseek`）。
     * 推不出则 [BalanceKind.CUSTOM_JSON]（"待配置"的语义，用户看到它就知道要手动填）。
     */
    private fun inferOfficialKind(apiBaseUrl: String?, balanceBaseUrl: String?): BalanceKind {
        val host = balanceBaseUrl?.substringAfter("://")?.substringBefore('/')
            ?: apiBaseUrl?.substringAfter("://")?.substringBefore('/')
        return when (host?.lowercase()) {
            "api.deepseek.com" -> BalanceKind.DEEPSEEK
            else -> BalanceKind.CUSTOM_JSON
        }
    }
}

/** 解析的完整结果。 */
data class ParseResult(
    val records: List<ParsedRecord>,
    val errors: List<ParseError>,
) {
    val isEmpty: Boolean get() = records.isEmpty() && errors.isEmpty()
}
