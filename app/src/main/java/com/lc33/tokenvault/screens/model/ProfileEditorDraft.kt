package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.domain.Protocol

/**
 * 客户端预设编辑页的草稿——已经是**解析好、准备落库**的形状（headers 是 `List<Pair>` 而不是
 * 编辑页里那坨多行文本）。多行文本 → 有序对的解析发生在页面层，这里只收成品。
 */
data class ProfileEditorDraft(
    val name: String,
    val userAgent: String,
    val headers: List<Pair<String, String>>,
    val bodyPatch: String,
    val protocols: Set<Protocol>,
)
