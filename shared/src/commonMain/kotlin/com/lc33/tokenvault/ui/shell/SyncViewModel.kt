package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.domain.repo.WebDavSettingsRepository
import com.lc33.tokenvault.engine.BackupEngine
import com.lc33.tokenvault.engine.RestoreMode
import com.lc33.tokenvault.engine.WebDavEngine
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.screens.model.BackupStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 同步页（本地备份 / 恢复 / WebDAV）的 ViewModel。
 *
 * SAF 与 WebDAV 都是用户手动触发的动作；自动备份没有 Worker 消费方，所以这里
 * 不暴露任何“自动”状态，避免设置页画出没有实效的开关。
 *
 * 结果走一次性事件：Snackbar 不该在 StateFlow 里用状态表达，否则第二次进页面
 * 还会提示一次。口令与凭据不缓存：进入协程前先复制一份 owned CharArray，
 * 协程结束后擦掉；调用方可以立刻清空输入框，不会和异步任务抢同一块内存。
 */
class SyncViewModel constructor(
    private val engine: BackupEngine,
    private val webDavEngine: WebDavEngine,
    private val webDavSettings: WebDavSettingsRepository,
    private val session: VaultSession,
) : ViewModel() {

    private val _backup = MutableStateFlow(BackupStatus())
    val backup: StateFlow<BackupStatus> = _backup.asStateFlow()

    private val _webDavBusy = MutableStateFlow(false)
    val webDavBusy: StateFlow<Boolean> = _webDavBusy.asStateFlow()

    val webDavConfig: StateFlow<WebDavConfig> = webDavSettings.observeConfig()
        .stateIn(viewModelScope, SharingStarted.Eagerly, WebDavConfig())

    private val _events = Channel<SyncEvent>(Channel.BUFFERED)
    val events: Flow<SyncEvent> = _events.receiveAsFlow()

    val isUnlocked: Boolean get() = session.isUnlocked

    /** 导出加密备份包到 SAF。 */
    fun export(password: CharArray, sink: suspend (ByteArray) -> Unit) {
        val owned = password.copyOf()
        viewModelScope.launch {
            try {
                runCatching { engine.export(owned) }
                    .onSuccess { bytes ->
                        runCatching { sink(bytes) }
                            .onSuccess {
                                _backup.value = _backup.value.copy(lastBackupAgo = "just now")
                                _events.send(SyncEvent.ExportSucceeded)
                            }
                            .onFailure { _events.send(SyncEvent.ExportFailed(it.message)) }
                    }
                    .onFailure { _events.send(SyncEvent.ExportFailed(it.message)) }
            } finally {
                owned.zeroize()
            }
        }
    }

    /** 从 SAF 选中的备份包恢复。 */
    fun restore(bytes: ByteArray, password: CharArray, mode: RestoreMode) {
        val owned = password.copyOf()
        viewModelScope.launch {
            try {
                runCatching { engine.restore(bytes, owned, mode) }
                    .onSuccess { result -> _events.send(SyncEvent.RestoreSucceeded(result.importedProviders)) }
                    .onFailure { _events.send(SyncEvent.RestoreFailed(it.message)) }
            } finally {
                owned.zeroize()
            }
        }
    }

    /** 保存 WebDAV 地址 / 目录 / 安全开关；凭据为 null 时保留已存值。 */
    fun saveWebDavConfig(
        url: String,
        remoteDirectory: String,
        allowInsecure: Boolean,
        username: CharArray?,
        password: CharArray?,
    ) {
        val ownedUsername = username?.copyOf()
        val ownedPassword = password?.copyOf()
        viewModelScope.launch {
            _webDavBusy.value = true
            try {
                webDavSettings.saveConfig(
                    WebDavConfig(
                        url = url,
                        remoteDirectory = remoteDirectory,
                        allowInsecure = allowInsecure,
                    ),
                    username = ownedUsername,
                    password = ownedPassword,
                )
                _events.send(SyncEvent.WebDavConfigSaved)
            } catch (t: Throwable) {
                _events.send(SyncEvent.WebDavFailed(t.message))
            } finally {
                ownedUsername?.zeroize()
                ownedPassword?.zeroize()
                _webDavBusy.value = false
            }
        }
    }

    fun listWebDavBackups() {
        viewModelScope.launch {
            _webDavBusy.value = true
            try {
                _events.send(SyncEvent.WebDavListSucceeded(webDavEngine.listRemoteBackups()))
            } catch (t: Throwable) {
                _events.send(SyncEvent.WebDavFailed(t.message))
            } finally {
                _webDavBusy.value = false
            }
        }
    }

    fun uploadToWebDav(password: CharArray) {
        val owned = password.copyOf()
        viewModelScope.launch {
            _webDavBusy.value = true
            try {
                val result = webDavEngine.upload(owned)
                _backup.value = _backup.value.copy(
                    lastBackupAgo = "just now",
                    targetLabel = "WebDAV",
                )
                _events.send(SyncEvent.WebDavUploadSucceeded(result.fileName, result.prunedCount))
            } catch (t: Throwable) {
                _events.send(SyncEvent.WebDavFailed(t.message))
            } finally {
                owned.zeroize()
                _webDavBusy.value = false
            }
        }
    }

    fun restoreLatestFromWebDav(password: CharArray, mode: RestoreMode) {
        val owned = password.copyOf()
        viewModelScope.launch {
            _webDavBusy.value = true
            try {
                val result = webDavEngine.restoreLatest(owned, mode)
                _events.send(SyncEvent.RestoreSucceeded(result.importedProviders))
            } catch (t: Throwable) {
                _events.send(SyncEvent.RestoreFailed(t.message))
            } finally {
                owned.zeroize()
                _webDavBusy.value = false
            }
        }
    }
}

/** 同步页的一次性事件。 */
sealed interface SyncEvent {
    data object ExportSucceeded : SyncEvent
    data class ExportFailed(val message: String?) : SyncEvent
    data class RestoreSucceeded(val importedProviders: Int) : SyncEvent
    data class RestoreFailed(val message: String?) : SyncEvent
    data object WebDavConfigSaved : SyncEvent
    data class WebDavListSucceeded(val names: List<String>) : SyncEvent
    data class WebDavUploadSucceeded(val fileName: String, val prunedCount: Int) : SyncEvent
    data class WebDavFailed(val message: String?) : SyncEvent
}