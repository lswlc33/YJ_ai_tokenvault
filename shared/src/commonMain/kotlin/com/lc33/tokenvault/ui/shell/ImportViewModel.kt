package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.SecretMask
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
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

    private var pendingRecord: ParsedRecord? = null
    private var pendingFingerprint: String? = null

    fun readClipboard(): String? = clipboard.read()

    fun parse(text: String) {
        clearPending()
        _preview.value = null
        _error.value = null
        _duplicatePrompt.value = false

        viewModelScope.launch {
            val result = CurlImporter.parse(text)
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
                    pendingFingerprint = keys.fingerprintOf(key.secret)
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
            if (fingerprint != null && keys.existsFingerprint(providerId, fingerprint)) {
                _duplicatePrompt.value = true
            } else {
                import(record, onDone)
            }
        }
    }

    fun confirmDuplicate(onDone: () -> Unit) {
        val record = pendingRecord ?: return
        _duplicatePrompt.value = false
        viewModelScope.launch { import(record, onDone) }
    }

    fun dismissDuplicate() {
        _duplicatePrompt.value = false
    }

    private suspend fun import(record: ParsedRecord, onDone: () -> Unit) {
        if (_importing.value) return
        _importing.value = true
        try {
            writer.writeKeyToProvider(providerId, record)
            clearPending()
            _preview.value = null
            onDone()
        } finally {
            _importing.value = false
        }
    }

    private fun clearPending() {
        pendingRecord?.keys?.forEach { it.secret.zeroize() }
        pendingRecord?.balanceToken?.zeroize()
        pendingRecord = null
        pendingFingerprint = null
    }

    override fun onCleared() {
        clearPending()
    }
}
