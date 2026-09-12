package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.endpoint.EndpointError
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl
import com.lc33.tokenvault.screens.model.KeyDraft
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

/** Key 设置页：编辑一把 Key 的名称、备注、行为配置与探测权限。 */
class KeyEditorViewModel constructor(
    private val keys: ApiKeyRepository,
    private val profilesRepository: ClientProfileRepository,
    private val keyId: Long,
) : ViewModel() {

    val profiles = profilesRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _draft = MutableStateFlow(KeyDraft())
    val draft: StateFlow<KeyDraft> = _draft.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

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
            val key = keys.find(keyId)
            if (key != null) {
                currentKey = key
                _draft.value = key.toDraft(profileList)
            }
            _loaded.value = true
        }
    }

    fun onChange(next: KeyDraft) {
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
        val override = if (draft.pathOverrideAnthropic.isBlank()) {
            emptyMap()
        } else {
            mapOf(com.lc33.tokenvault.domain.Protocol.ANTHROPIC to draft.pathOverrideAnthropic)
        }
        val normalized = normalizeBaseUrl(draft.baseUrl, override)
        if (normalized !is NormalizeResult.Ok) {
            secret?.zeroize()
            balanceToken?.zeroize()
            _urlError.value = (normalized as NormalizeResult.Err).error
            return
        }
        _urlError.value = null

        viewModelScope.launch {
            try {
                val key = currentKey ?: return@launch
                keys.updateMeta(
                    key.copy(
                        label = draft.label.trim(),
                        note = draft.note.trim(),
                        enabled = draft.enabled,
                        sortOrder = draft.sortOrder,
                    ),
                )
                keys.updateSettings(
                    id = key.id,
                    settings = draft.toSettings(key.settings, normalized, profiles.value),
                    balanceToken = balanceToken,
                )
                if (secret != null && secret.isNotEmpty()) {
                    keys.replaceSecret(key.id, secret)
                }
                _saved.tryEmit(Unit)
            } finally {
                secret?.zeroize()
                balanceToken?.zeroize()
            }
        }
    }
}
