package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.data.repo.ImportWriter
import com.lc33.tokenvault.importer.ParsedRecord
import com.lc33.tokenvault.importer.TextImporter
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.screens.manage.ImportPreview
import com.lc33.tokenvault.ui.shell.hostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文本导入（§11）：粘贴 → 预览 → 确认。
 *
 * 三步不是装饰：一段自由文本解析出来必然有猜的成分（`DeepSeek V4 Pro` 到底是模型 id 还是
 * 显示名），让用户在写库前看一眼比事后去改便宜得多。有问题的条目不阻止导入，只标出来。
 *
 * 明文只在两条路径上存在，都压到最短：
 * - [parse] 之后，`ParsedRecord` 里的密钥 / 令牌 / 账号密码是 `CharArray`，一直带到
 *   [confirm] 交给 [ImportWriter]（它自己擦），这里不转成 `String`。
 * - 剪贴板读出来的整段文本是 `String`（框架接口只给 String），那是"用户粘进来的那一份"，
 *   我们不去复制它。
 */
class ImportViewModel constructor(
    private val writer: ImportWriter,
    private val clipboard: SecureClipboard,
) : ViewModel() {

    private val _previews = MutableStateFlow<List<ImportPreview>>(emptyList())
    val previews: StateFlow<List<ImportPreview>> = _previews.asStateFlow()

    /** 有记录因为缺「供应商名称」而整条解析失败。只给条数，文案在界面拼。 */
    private val _parseErrorCount = MutableStateFlow(0)
    val parseErrorCount: StateFlow<Int> = _parseErrorCount.asStateFlow()

    /** 正在写库（确认后到完成前），界面据此禁用确认按钮。 */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 上一次确认写入了多少家供应商。0 表示还没写过。 */
    private val _importedCount = MutableStateFlow(0)
    val importedCount: StateFlow<Int> = _importedCount.asStateFlow()

    /** 解析结果按原顺序保存，确认时只取勾选的那几条。 */
    private var records: List<ParsedRecord> = emptyList()

    /** 读剪贴板文本，供界面「从剪贴板填充」。返回 null 表示剪贴板里没有文本。 */
    fun readClipboard(): String? = clipboard.read()

    fun parse(text: String) {
        records = emptyList()
        _previews.value = emptyList()
        _parseErrorCount.value = 0

        val result = TextImporter.parse(text)
        records = result.records
        _previews.value = result.records.map { it.toPreview() }
        _parseErrorCount.value = result.errors.size
    }

    fun toggle(index: Int) {
        val current = _previews.value
        if (index !in current.indices) return
        _previews.value = current.toMutableList().also {
            it[index] = it[index].copy(selected = !it[index].selected)
        }
    }

    /** 确认：把勾选的记录写库。明文在这一步交给仓库，仓库自己擦。 */
    fun confirm(onDone: (Int) -> Unit = {}) {
        if (_importing.value) return
        val selected = records.filterIndexed { i, _ -> _previews.value.getOrNull(i)?.selected == true }
        if (selected.isEmpty()) return

        _importing.value = true
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.Default) { writer.write(selected) }
                _importedCount.value = count
                // 写完后清空预览，避免"已经写进去了还留在预览里"造成的重复导入错觉
                records = emptyList()
                _previews.value = emptyList()
                onDone(count)
            } finally {
                _importing.value = false
            }
        }
    }

    private fun ParsedRecord.toPreview(): ImportPreview = ImportPreview(
        name = name,
        host = hostOf(apiBaseUrl ?: ""),
        keyCount = keys.size,
        modelCount = models.size,
        accountCount = accounts.size,
        protocols = supportedProtocols.map { it.wireName },
        issues = issues,
        selected = true,
    )
}
