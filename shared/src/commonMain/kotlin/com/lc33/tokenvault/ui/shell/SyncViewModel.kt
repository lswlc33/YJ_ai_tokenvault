package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.model.BackupTarget
import com.lc33.tokenvault.domain.model.LastBackup
import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.domain.model.WebDavCredentials
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.domain.repo.WebDavSettingsRepository
import com.lc33.tokenvault.engine.BackupEngine
import com.lc33.tokenvault.engine.RestoreMode
import com.lc33.tokenvault.engine.WebDavEngine
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.model.UiBackupTarget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    private val settings: SettingsRepository,
    private val session: VaultSession,
) : ViewModel() {

    /**
     * 状态卡上那句"上次备份"。权威值是 `app_settings` 里那两个键，不是内存：
     * 以前它只活在 ViewModel 里，退页即丢，第二次进这一页永远写着"还没有备份"，
     * 而远端其实躺着一份包。相对时间与落点文案都由页面用资源现算（红线 19），
     * 这里不再往 UiState 里塞 "just now" / "WebDAV" 这类英文字面量。
     */
    val backup: StateFlow<BackupStatus> = settings.observeLastBackup()
        .map { last ->
            last?.let { BackupStatus(lastBackupAtMs = it.atMillis, target = it.target.toUi()) }
                ?: BackupStatus()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackupStatus())

    private val _webDavBusy = MutableStateFlow(false)
    val webDavBusy: StateFlow<Boolean> = _webDavBusy.asStateFlow()

    /**
     * 「检查连接」这件事单独一个状态，而不是让页面用 busy 反推：拉列表是只读的
     * PROPFIND，灰掉三行的是它，但上传/恢复同样会灰掉那三行——只给一个 busy，
     * 上传途中就会显示"正在检查连接"，那是句假话。
     */
    private val _webDavChecking = MutableStateFlow(false)
    val webDavChecking: StateFlow<Boolean> = _webDavChecking.asStateFlow()

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
                                markBackupDone(BackupTarget.LOCAL)
                                _events.send(SyncEvent.ExportSucceeded)
                            }
                            .onFailure { _events.send(SyncEvent.ExportFailed) }
                    }
                    .onFailure { _events.send(SyncEvent.ExportFailed) }
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
                    .onFailure { _events.send(SyncEvent.RestoreFailed) }
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
                _events.send(SyncEvent.WebDavFailed)
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
            _webDavChecking.value = true
            try {
                _events.send(SyncEvent.WebDavListSucceeded(webDavEngine.listRemoteBackups()))
            } catch (t: Throwable) {
                _events.send(SyncEvent.WebDavFailed)
            } finally {
                _webDavChecking.value = false
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
                // 上传成功才记"上次备份"：状态卡那句"什么时候备份的、备份到哪"必须
                // 跟真的落点对得上，所以这一步写在 try 里、失败就不记。
                markBackupDone(BackupTarget.WEBDAV)
                _events.send(SyncEvent.WebDavUploadSucceeded(result.fileName, result.prunedCount))
            } catch (t: Throwable) {
                _events.send(SyncEvent.WebDavFailed)
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
                _events.send(SyncEvent.RestoreFailed)
            } finally {
                owned.zeroize()
                _webDavBusy.value = false
            }
        }
    }

    /**
     * 从 WebDAV 恢复**列表里被选中的那一份**。
     *
     * 与 [restoreLatestFromWebDav] 分两条路是刻意的：走列表时用户已经看到了文件名与日期，
     * 恢复的就该是他点的那一条，而不是"再列一次取最大"——两次 PROPFIND 之间别人传了新备份，
     * 后一种写法就会给他恢复成另一份。
     */
    fun restoreFromWebDav(fileName: String, password: CharArray, mode: RestoreMode) {
        val owned = password.copyOf()
        viewModelScope.launch {
            _webDavBusy.value = true
            try {
                val result = webDavEngine.restore(fileName, owned, mode)
                _events.send(SyncEvent.RestoreSucceeded(result.importedProviders))
            } catch (t: Throwable) {
                _events.send(SyncEvent.RestoreFailed)
            } finally {
                owned.zeroize()
                _webDavBusy.value = false
            }
        }
    }

    /**
     * 删掉远端那一份备份，成功后立刻重新拉一次列表。
     *
     * 刷列表失败不外抛、也不报"同步失败"：包已经删掉了，这时候提示失败会让用户以为
     * 没删成而去点第二次。列表停在旧的那一条上，下一次进页面或手动刷新时自然纠正。
     */
    fun deleteWebDavBackup(fileName: String) {
        viewModelScope.launch {
            _webDavBusy.value = true
            try {
                runCatching { webDavEngine.delete(fileName) }
                    .onSuccess {
                        _events.send(SyncEvent.WebDavDeleted)
                        runCatching { webDavEngine.listRemoteBackups() }
                            .onSuccess { names -> _events.send(SyncEvent.WebDavListSucceeded(names)) }
                    }
                    .onFailure { _events.send(SyncEvent.WebDavFailed) }
            } finally {
                _webDavBusy.value = false
            }
        }
    }

    /**
     * 解出已存的 WebDAV 凭据，给设置弹层回填（2026-09 反馈：改一次配置要重输一遍，
     * 忘了密码在应用里根本看不到）。**调用方负责用完 zeroize**——这里不擦是因为
     * 拷进输入框之前擦掉就没有意义了。
     */
    suspend fun storedCredentials(): WebDavCredentials? =
        runCatching { webDavSettings.credentials() }.getOrNull()

    /**
     * 记一次成功备份（时间 + 落点）到 `app_settings`。
     *
     * 记失败不外抛：备份包本身已经落好了，这一条只是状态卡上的一句话；
     * 为它弹一条"失败"反而像是备份没做成。
     */
    private suspend fun markBackupDone(target: BackupTarget) {
        runCatching { settings.setLastBackup(LastBackup(atMillis = nowMillis(), target = target)) }
    }

    private fun BackupTarget.toUi(): UiBackupTarget = when (this) {
        BackupTarget.LOCAL -> UiBackupTarget.Local
        BackupTarget.WEBDAV -> UiBackupTarget.WebDav
    }
}

/**
 * 同步页的一次性事件。
 *
 * **失败事件不带异常原文**：原文里常有 WebDAV 地址、文件名甚至凭据片段，投到 Snackbar
 * 等于把日志内容摊在屏幕上（还可能被截图）。失败原因由引擎自己写进 audit_log，
 * 提示只说"失败了，去看日志"。
 */
sealed interface SyncEvent {
    data object ExportSucceeded : SyncEvent
    data object ExportFailed : SyncEvent
    data class RestoreSucceeded(val importedProviders: Int) : SyncEvent
    data object RestoreFailed : SyncEvent
    data object WebDavConfigSaved : SyncEvent
    data class WebDavListSucceeded(val names: List<String>) : SyncEvent
    data class WebDavUploadSucceeded(val fileName: String, val prunedCount: Int) : SyncEvent
    /** 远端某一份已删除。不带文件名：提示只说"已删除"，文件名在列表里自己会消失。 */
    data object WebDavDeleted : SyncEvent
    data object WebDavFailed : SyncEvent
}