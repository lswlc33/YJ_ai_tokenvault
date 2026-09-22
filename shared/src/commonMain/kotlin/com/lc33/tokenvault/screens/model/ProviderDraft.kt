package com.lc33.tokenvault.screens.model

/** 供应商设置页的草稿。v3 起只包含合集信息，不包含任何请求行为配置。 */
data class ProviderDraft(
    val id: Long? = null,
    val name: String = "",
    val note: String = "",
    val website: String = "",
    /**
     * 允许 ping 官网检测连通性。**默认关**：这会真的向那家发一次请求，
     * 得用户在设置页里明确打开（供应商设置页那个开关）。
     */
    val checkWebsite: Boolean = false,
    val groupIndex: Int = 0,
    /** 手选颜色下标；**null = 「自动」**，用按 id 生成的身份色（见 `ProviderPalette.colorFor`）。 */
    val colorIndex: Int? = null,
    val pinned: Boolean = false,
)
