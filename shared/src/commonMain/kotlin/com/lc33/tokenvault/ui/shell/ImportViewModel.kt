package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.SecretMask
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.DuplicateApiKeyException
import com.lc33.tokenvault.domain.repo.ImportWriter
import com.lc33.tokenvault.importer.CurlImporter
import com.lc33.tokenvault.importer.ParsedRecord
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.screens.manage.CurlImportError
import com.lc33.tokenvault.screens.manage.CurlImportPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 单条 cURL 导入：识别 → 展示脱敏结果 → 写入当前供应商。
 *
 * 明文只存在于 [ParsedRecord] 的 CharArray 中，预览只给遮蔽串；确认或离开时统一擦除。
 */
class ImportViewModel constructor(
    private val writer: ImportWriter,
    private val keys: ApiKeyRepository,
    private val clipboard: SecureClipboard,
    private val providerId: Long,
) : ViewModel() {

    private val _preview = MutableStateFlow<CurlImportPreview?>(null)
    val preview: StateFlow<CurlImportPreview?> = _preview.asStateFlow()

    private val _error = MutableStateFlow<CurlImportError?>(null)
    val error: StateFlow<CurlImportError?> = _error.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _duplicatePrompt = MutableStateFlow(false)
    val duplicatePrompt: StateFlow<Boolean> = _duplicatePrompt.asStateFlow()

    /** 导入时是否开启自动同步模型。默认开——导入后用户大概率想立刻看到模型列表。 */
    private val _probeModels = MutableStateFlow(true)
    val probeModels: StateFlow<Boolean> = _probeModels.asStateFlow()

    fun setProbeModels(value: Boolean) {
        _probeModels.value = value
    }

    private var pendingRecord: ParsedRecord? = null
    private var pendingFingerprint: String? = null

    /** 用户已经在重复提示上点过"仍要导入"：这一次冲突不再回摆提示，见 [import]。 */
    private var forcedDuplicate = false

    fun readClipboard(): String? = clipboard.read()

    fun parse(text: String) {
        clearPending()
        _preview.value = null
        _error.value = null
        _duplicatePrompt.value = false

        viewModelScope.launch {
            // 解析与指纹都过 runCatching：这两处都在协程里，异常冒出去就是崩应用，
            // 而用户看到的画面与"我粘的东西不对"没区别。失败要说得出"这一条没能处理"。
            val result = runCatching { CurlImporter.parse(text) }.getOrElse {
                _error.value = CurlImportError.Failed
                return@launch
            }
            when {
                result.records.isEmpty() -> _error.value = CurlImportError.NoCommand
                result.records.size > 1 -> _error.value = CurlImportError.MultipleCommands
                else -> {
                    val record = result.records.first()
                    val key = record.keys.firstOrNull()
                    if (key == null) {
                        _error.value = CurlImportError.MissingKey
                        return@launch
                    }
                    pendingRecord = record
                    pendingFingerprint = runCatching { keys.fingerprintOf(key.secret) }.getOrElse {
                        _error.value = CurlImportError.Failed
                        return@launch
                    }
                    _preview.value = CurlImportPreview(
                        baseUrl = record.apiBaseUrl.orEmpty(),
                        protocols = record.supportedProtocols.map { it.wireName },
                        maskedKey = SecretMask.of(key.secret),
                        models = record.models.map { it.modelId },
                    )
                }
            }
        }
    }

    fun confirm(onDone: () -> Unit) {
        val record = pendingRecord ?: return
        val fingerprint = pendingFingerprint
        viewModelScope.launch {
            // 预检本身也会写读库（它查的是指纹唯一索引）。查不动时**当作不重复继续导入**：
            // 真重复了仓库那条唯一索引会挡住并回到重复提示，比卡在这里什么都不做要好。
            val duplicate = fingerprint != null &&
                runCatching { keys.existsFingerprint(providerId, fingerprint) }.getOrDefault(false)
            if (duplicate) {
                _duplicatePrompt.value = true
            } else {
                import(record, onDone)
            }
        }
    }

    fun confirmDuplicate(onDone: () -> Unit) {
        val record = pendingRecord ?: return
        _duplicatePrompt.value = false
        forcedDuplicate = true
        viewModelScope.launch { import(record, onDone) }
    }

    fun dismissDuplicate() {
        _duplicatePrompt.value = false
    }

    private suspend fun import(record: ParsedRecord, onDone: () -> Unit) {
        if (_importing.value) return
        _importing.value = true
        try {
            writer.writeKeyToProvider(providerId, record, probeModels = _probeModels.value)
            clearPending()
            _preview.value = null
            onDone()
        } catch (_: DuplicateApiKeyException) {
            // 仓库层的指纹预检（唯一索引 `api_keys(providerId, fingerprint)`）挡住了这一条。
            //
            // 两条路都要给用户一个看得懂的收尾，而不是让异常从协程里冒出去：
            // - 正常确认（没点过"仍要导入"）：把重复提示摆出来，那正是"这把已经在了"的意思。
            // - 用户已经点过"仍要导入"（[forcedDuplicate]）：库里**已经存在**同一把密钥，
            //   他要的结果其实早就达成了——再写一次数据库也不会接受。于是按"已导入"收尾
            //   关掉这页；悄悄失败或反复弹同一个提示都比这个更难理解。
            if (forcedDuplicate) {
                clearPending()
                _preview.value = null
                _duplicatePrompt.value = false
                onDone()
            } else {
                _duplicatePrompt.value = true
            }
        } catch (_: Throwable) {
            // 其余失败（写设置那一行炸了、库正忙、明文解不开）必须落在页面上：以前只有
            // 一个 finally，异常直接冒出协程 = 崩应用，而用户刚按的是"导入"。
            // 预览留着不清，改两下就能再按一次，不用重贴整条 cURL。
            _error.value = CurlImportError.Failed
        } finally {
            _importing.value = false
            forcedDuplicate = false
        }
    }

    private fun clearPending() {
        pendingRecord?.keys?.forEach { it.secret.zeroize() }
        pendingRecord?.balanceToken?.zeroize()
        pendingRecord = null
        pendingFingerprint = null
        forcedDuplicate = false
    }

    override fun onCleared() {
        clearPending()
    }
}
