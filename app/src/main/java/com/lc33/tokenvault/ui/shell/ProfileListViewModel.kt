package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 客户端预设列表页。
 *
 * 只读仓库那份 `Flow`：增删改都发生在预设编辑页，这里不需要任何写操作。显示名（内置
 * `default` 要本地化）由页面层用 `displayName` + `stringResource` 现算，ViewModel 不碰资源
 * （红线 19）。
 */
class ProfileListViewModel constructor(
    profiles: ClientProfileRepository,
) : ViewModel() {

    val profiles: StateFlow<List<ClientProfile>> = profiles.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
