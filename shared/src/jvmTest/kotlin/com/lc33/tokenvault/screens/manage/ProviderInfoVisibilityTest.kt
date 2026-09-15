package com.lc33.tokenvault.screens.manage

import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiProviderRow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 供应商预览页"信息卡要不要画"的判定。
 *
 * 这条规则来自一次实测：新建一家、什么都没填时，首屏会留一张**空卡片**——
 * 备注、官网、协议三样都没有，卡片里一个字都没有，只剩一圈圆角边框占着位置。
 */
class ProviderInfoVisibilityTest {

    @Test
    fun `备注、官网、协议都没有时不画信息卡`() {
        assertFalse(hasProviderInfo(row(note = null, website = null, protocols = emptyList())))
    }

    @Test
    fun `有备注就画`() {
        assertTrue(hasProviderInfo(row(note = "公司账号", website = null, protocols = emptyList())))
    }

    @Test
    fun `有官网就画`() {
        assertTrue(
            hasProviderInfo(row(note = null, website = "https://api.example.com", protocols = emptyList())),
        )
    }

    @Test
    fun `有协议就画`() {
        assertTrue(hasProviderInfo(row(note = null, website = null, protocols = listOf("chat"))))
    }

    @Test
    fun `空白字符串不算有内容`() {
        // 输入框里删干净之后存的是空串/空白，不是 null——那种情况同样不该画空卡片。
        assertFalse(hasProviderInfo(row(note = "   ", website = "", protocols = emptyList())))
    }

    private fun row(
        note: String?,
        website: String?,
        protocols: List<String>,
    ) = UiProviderRow(
        id = 1,
        name = "p",
        note = note,
        websiteUrl = website,
        host = "",
        protocols = protocols,
        colorIndex = 0,
        pinned = false,
        groupId = null,
        keyCount = 0,
        okKeyCount = 0,
        modelCount = 0,
        accountCount = 0,
        balance = null,
        health = UiHealth.Unknown,
    )
}
