package com.lc33.tokenvault.screens.model

data class KeyDetailUiState(
    val key: UiKeyRow,
    val models: List<UiModelRow>,
    val nowMs: Long = 0,
)
