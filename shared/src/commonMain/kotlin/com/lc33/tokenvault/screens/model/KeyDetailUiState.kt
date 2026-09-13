package com.lc33.tokenvault.screens.model

data class KeyDetailUiState(
    val key: UiKeyRow,
    val models: List<UiModelRow>,
    val nowMs: Long = 0,
    /**
     * 这一把 Key 是否还能在排序里上移 / 下移。
     *
     * 顶栏的「更多」菜单用它把两项置灰：排序第一的 Key 再上移是空操作，
     * 菜单里留一个点了没反应的条目，比置灰更难解释。
     */
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
)
