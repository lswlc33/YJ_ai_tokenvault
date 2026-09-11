package com.lc33.tokenvault.screens.model

/** 供应商设置页的草稿。v3 起只包含合集信息，不包含任何请求行为配置。 */
data class ProviderDraft(
    val id: Long? = null,
    val name: String = "",
    val note: String = "",
    val website: String = "",
    val groupIndex: Int = 0,
    val colorIndex: Int = 0,
    val pinned: Boolean = false,
)
