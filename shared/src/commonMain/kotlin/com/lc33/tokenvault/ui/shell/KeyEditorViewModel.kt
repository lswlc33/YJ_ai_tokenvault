package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.endpoint.EndpointError
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.screens.model.KeyDraft
import com.lc33.tokenvault.screens.model.UiModelRow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Key 设置页：编辑一把 Key 的名称、备注、行为配置、模型与探测权限。 */
class KeyEditorViewModel constructor(
    private val keys: ApiKeyRepository,
    private val modelsRepository: ModelRepository,
    private val profilesRepository: ClientProfileRepository,
    private val probeEngine: ProbeEngine,
    private val providerId: Long,
    private val keyId: Long,
) : ViewModel() {

    enum class SaveError {
        MissingSecret,
        InvalidTimeout,
        NoProtocols,
        SaveFailed,
    }

    val profiles = profilesRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _draft = MutableStateFlow(KeyDraft(providerId = providerId))
    val draft: StateFlow<KeyDraft> = _draft.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _models = MutableStateFlow<List<UiModelRow>>(emptyList())
    val models: StateFlow<List<UiModelRow>> = _models.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saveError = MutableStateFlow<SaveError?>(null)
    val saveError: StateFlow<SaveError?> = _saveError.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private val _urlError = MutableStateFlow<EndpointError?>(null)
    val urlError: StateFlow<EndpointError?> = _urlError.asStateFlow()

    private var currentKey: ApiKey? = null

    init {
        viewModelScope.launch {
            val profileList = profilesRepository.observeAll().first()
            val key = keyId.takeIf { it != 0L }?.let { keys.find(it) }
            currentKey = key
            _draft.value = key?.toDraft(profileList) ?: KeyDraft(providerId = providerId)
            _loaded.value = true

            val observedProviderId = key?.providerId ?: providerId
            modelsRepository.observeByProvider(observedProviderId).collect { list ->
                _models.value = if (keyId == 0L) {
                    emptyList()
                } else {
                    list.filter { it.keyId == keyId }.map { it.toRow() }
                }
            }
        }
    }

    fun onChange(next: KeyDraft) {
        _saveError.value = null
        _draft.value = next
    }

    fun clearUrlError() {
        _urlError.value = null
    }

    fun save(
        draft: KeyDraft,
        secret: CharArray?,
        balanceToken: CharArray?,
    ) {
        if (draft.protocols.isEmpty()) {
            secret?.zeroize()
            balanceToken?.zeroize()
            _saveError.value = SaveError.NoProtocols
            return
        }
        if (keyId == 0L && (secret == null || secret.isEmpty())) {
            secret?.zeroize()
            balanceToken?.zeroize()
            _saveError.value = SaveError.MissingSecret
            return
        }
        val timeoutText = draft.timeoutSeconds.trim()
        if (timeoutText.isNotEmpty() && (timeoutText.toIntOrNull() == null || timeoutText.toInt() <= 0)) {
            secret?.zeroize()
            balanceToken?.zeroize()
            _saveError.value = SaveError.InvalidTimeout
            return
        }

        val override = if (draft.pathOverrideAnthropic.isBlank()) {
            emptyMap()
        } else {
            mapOf(Protocol.ANTHROPIC to draft.pathOverrideAnthropic)
        }
        val normalized = normalizeBaseUrl(draft.baseUrl, override)
        if (normalized !is NormalizeResult.Ok) {
            secret?.zeroize()
            balanceToken?.zeroize()
            _urlError.value = (normalized as NormalizeResult.Err).error
            return
        }
        _urlError.value = null
        _saveError.value = null
        _saving.value = true

        viewModelScope.launch {
            try {
                val existing = currentKey
                if (existing == null) {
                    val addedId = keys.add(
                        providerId = providerId,
                        label = draft.label.trim(),
                        note = draft.note.trim(),
                        secret = requireNotNull(secret),
                        settings = draft.toSettings(null, normalized, profiles.value),
                        balanceToken = balanceToken,
                    )
                    currentKey = keys.find(addedId)
                } else {
                    keys.updateMeta(
                        existing.copy(
                            label = draft.label.trim(),
                            note = draft.note.trim(),
                            enabled = draft.enabled,
                            sortOrder = draft.sortOrder,
                        ),
                    )
                    keys.updateSettings(
                        id = existing.id,
                        settings = draft.toSettings(existing.settings, normalized, profiles.value),
                        balanceToken = balanceToken,
                    )
                    if (secret != null && secret.isNotEmpty()) {
                        keys.replaceSecret(existing.id, secret)
                    }
                }
                _saved.tryEmit(Unit)
            } catch (_: Exception) {
                _saveError.value = SaveError.SaveFailed
            } finally {
                secret?.zeroize()
                balanceToken?.zeroize()
                _saving.value = false
            }
        }
    }

    fun addModel(modelId: String, protocol: Protocol) {
        val key = currentKey ?: return
        viewModelScope.launch {
            runCatching {
                modelsRepository.add(
                    providerId = key.providerId,
                    keyId = key.id,
                    modelId = modelId.trim(),
                    protocol = protocol,
                    needsReview = modelId.any { it.isWhitespace() || it.isUpperCase() },
                )
            }
        }
    }

    fun updateModel(
        id: Long,
        modelId: String,
        protocol: Protocol,
        displayName: String?,
        enabled: Boolean,
    ) {
        val key = currentKey ?: return
        viewModelScope.launch {
            val existing = modelsRepository.observeByProvider(key.providerId).first()
                .firstOrNull { it.id == id } ?: return@launch
            runCatching {
                modelsRepository.update(
                    existing.copy(
                        modelId = modelId.trim(),
                        protocol = protocol,
                        displayName = displayName?.trim()?.ifEmpty { null },
                        enabled = enabled,
                    ),
                )
            }
        }
    }

    fun deleteModel(id: Long) {
        viewModelScope.launch { runCatching { modelsRepository.delete(id) } }
    }

    fun refreshModels() {
        val key = currentKey ?: return
        probeEngine.refreshModels(key.providerId, key.id)
    }
}
