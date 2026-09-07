package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.engine.BackupEngine
import com.lc33.tokenvault.engine.RestoreMode
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.screens.model.BackupStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 同步页（备份 / 恢复）的 ViewModel（§12.1）。
 *
 * 职责：把 [BackupEngine] 的导出 / 恢复编排接到 UI。SAF 文件读写（`CreateDocument` /
 * `OpenDocument`）发生在 Composable 层（那里才拿得到 `ActivityResultContracts`），
 * 这里只负责"给一段字节，加密成包"与"拿到包字节，解包并落库"。
 *
 * 结果走一次性事件（[SyncEvent]）：Snackbar 与"恢复成功跳走"都不该在 `StateFlow` 里
 * 用状态表达——事件发完就没了，状态留着会变成"第二次进来还提示一次"。
 *
 * 备份口令**不缓存**：默认沿用 PIN，但 PIN 在解锁后就不在内存里了（红线 1），
 * 所以口令由调用方每次传入、用完即擦。
 */
class SyncViewModel constructor(
    private val engine: BackupEngine,
    private val session: VaultSession,
) : ViewModel() {

    private val _backup = MutableStateFlow(BackupStatus())
    val backup: StateFlow<BackupStatus> = _backup.asStateFlow()

    private val _events = Channel<SyncEvent>(Channel.BUFFERED)
    val events: Flow<SyncEvent> = _events.receiveAsFlow()

    val isUnlocked: Boolean get() = session.isUnlocked

    /**
     * 导出加密备份包。
     *
     * @param password 备份口令（明文，调用方用完必须擦）。
     * @param sink 拿到包字节之后怎么写出去（SAF 流写入），在 Dispatchers.IO 上执行。
     */
    fun export(password: CharArray, sink: suspend (ByteArray) -> Unit) {
        viewModelScope.launch {
            runCatching { engine.export(password) }
                .onSuccess { bytes ->
                    runCatching { sink(bytes) }
                        .onSuccess {
                            _backup.value = _backup.value.copy(lastBackupAgo = "just now")
                            _events.send(SyncEvent.ExportSucceeded)
                        }
                        .onFailure { _events.send(SyncEvent.ExportFailed(it.message)) }
                }
                .onFailure { _events.send(SyncEvent.ExportFailed(it.message)) }
        }
    }

    /**
     * 从备份包恢复。
     *
     * @param bytes 包字节。
     * @param password 备份口令。
     * @param mode 覆盖 / 合并 / 仅新增。
     */
    fun restore(bytes: ByteArray, password: CharArray, mode: RestoreMode) {
        viewModelScope.launch {
            runCatching { engine.restore(bytes, password, mode) }
                .onSuccess { result ->
                    _events.send(SyncEvent.RestoreSucceeded(result.importedProviders))
                }
                .onFailure { _events.send(SyncEvent.RestoreFailed(it.message)) }
        }
    }
}

/** 同步页的一次性事件。 */
sealed interface SyncEvent {
    data object ExportSucceeded : SyncEvent
    data class ExportFailed(val message: String?) : SyncEvent
    data class RestoreSucceeded(val importedProviders: Int) : SyncEvent
    data class RestoreFailed(val message: String?) : SyncEvent
}
