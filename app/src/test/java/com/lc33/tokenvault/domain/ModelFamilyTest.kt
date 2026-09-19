package com.lc33.tokenvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 前缀归族（模型页分组的地基）。
 *
 * 用例全部来自真实模型 id 的形状，尤其是那两个会让"按 `-` 切第一段"出错的形式：
 * `Qwen3.5-397B-A17B`（第一段带数字）与 `moonshot/kimi-k2`（斜杠才是厂商分界）。
 * 分组错了不会崩，只会让中转站那几百个模型散成一堆单行小组，用户看到的是"这页没在分组"。
 */
class ModelFamilyTest {

    @Test
    fun `同一家前缀的模型归到一族`() {
        assertEquals("deepseek", ModelFamily.keyOf("deepseek-chat"))
        assertEquals("deepseek", ModelFamily.keyOf("deepseek-reasoner"))
        assertEquals("deepseek", ModelFamily.keyOf("DeepSeek-V4-Pro"))
        assertEquals("gpt", ModelFamily.keyOf("gpt-4o"))
        assertEquals("gpt", ModelFamily.keyOf("gpt-4.1-mini"))
        assertEquals("gpt", ModelFamily.keyOf("gpt-5.6-sol"))
        assertEquals("claude", ModelFamily.keyOf("claude-3-5-sonnet-latest"))
        assertEquals("glm", ModelFamily.keyOf("glm-5.2"))
        assertEquals("llama", ModelFamily.keyOf("llama-3.3-70b-instruct"))
        assertEquals("minimax", ModelFamily.keyOf("MiniMax-M2.5"))
    }

    @Test
    fun `数字紧跟字母时只取字母段`() {
        // 按 '-' 切第一段会得到 qwen3.5，于是同一家被拆成 qwen 与 qwen3.5 两组。
        assertEquals("qwen", ModelFamily.keyOf("Qwen3.5-397B-A17B"))
        assertEquals("qwen", ModelFamily.keyOf("qwen-plus"))
        assertEquals("kimi", ModelFamily.keyOf("kimi-k2"))
    }

    @Test
    fun `斜杠前缀整段保留，不削成首字母`() {
        assertEquals("moonshot", ModelFamily.keyOf("moonshot/kimi-k2"))
        assertEquals("deepseek", ModelFamily.keyOf("deepseek/deepseek-v3.2"))
        // 带横杠的厂商名不能被"取字母段"削成一个字母：真机实测里 `z-ai/...` 曾凑出
        // 一个 21 个模型的 "z" 组，把智谱和别的单字母前缀混在一起。
        assertEquals("z-ai", ModelFamily.keyOf("z-ai/glm-5.3-flash"))
        assertEquals("x-ai", ModelFamily.keyOf("x-ai/grok-4"))
        assertEquals("moonshot", ModelFamily.keyOf("moonshot/kimi-k2:free"))
    }

    @Test
    fun `字母段过短时退回第一段而不是一个字母一组`() {
        // o1 / o3 / o4-mini 的字母段只有 1 个字符，取字母段会得到 "o" 一个巨型组，
        // 把 OpenAI 的 o 系列与别的单字母前缀混在一起。退回第一段更准。
        assertEquals("o3", ModelFamily.keyOf("o3-mini"))
        assertEquals("o1", ModelFamily.keyOf("o1"))
        assertEquals("s1", ModelFamily.keyOf("s1-2025-01-15"))
    }

    @Test
    fun `能力后缀与空白不参与归族`() {
        assertEquals("deepseek", ModelFamily.keyOf("deepseek-chat:free"))
        assertEquals("deepseek", ModelFamily.keyOf("  deepseek-chat  "))
    }

    @Test
    fun `空与纯符号落到兜底键`() {
        assertEquals(ModelFamily.OTHERS_KEY, ModelFamily.keyOf(""))
        assertEquals(ModelFamily.OTHERS_KEY, ModelFamily.keyOf("   "))
        assertEquals(ModelFamily.OTHERS_KEY, ModelFamily.keyOf("/weird"))
    }

    @Test
    fun `有目录展示名时用它否则首字母大写`() {
        assertEquals("DeepSeek", ModelFamily.displayOf("deepseek-chat", "DeepSeek"))
        // 目录没匹配上时只能把前缀首字母大写，得到的是 `Deepseek` 而不是厂方写法 `DeepSeek`。
        // 这正是目录同步的价值所在：同步过一次，分组标题就从"看着像"变成"是那个名字"。
        assertEquals("Deepseek", ModelFamily.displayOf("deepseek-chat", null))
        assertEquals("Gpt", ModelFamily.displayOf("gpt-4o", "  "))
    }

    @Test
    fun `族键已经是归好的那一个时不再归第二次`() {
        // 分组循环里手上拿的就是 keyOf 的结果，再过 displayOf 会被归两次：
        // z-ai 第二遍只剩字母 "z"，21 个智谱模型会挂在写着「Z」的组下面。
        assertEquals("Z-ai", ModelFamily.displayOfKey("z-ai", null))
        assertEquals("Z.ai", ModelFamily.displayOfKey("z-ai", "Z.ai"))
        assertEquals("DeepSeek", ModelFamily.displayOfKey("deepseek", "DeepSeek"))
        // displayOf 收的是原始模型 id，两个入口不能混用。
        assertEquals("Z-ai", ModelFamily.displayOf("z-ai/glm-5.3-flash", null))
    }
}
