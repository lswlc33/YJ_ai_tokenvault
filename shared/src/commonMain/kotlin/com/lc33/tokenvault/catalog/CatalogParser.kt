package com.lc33.tokenvault.catalog

import com.lc33.tokenvault.domain.model.CatalogModel
import com.lc33.tokenvault.domain.model.CatalogVendor
import kotlinx.serialization.decodeFromString

/**
 * models.dev 快照 → 目录行的**纯函数**。
 *
 * 不碰网络、不碰 DAO、不碰 Room：解码是 `engine/CatalogSync` 的事，落库是
 * `data/repo/RoomModelCatalogRepository` 的事。整条链路里唯一"看懂上游"的就是这个对象，
 * 所以它必须能在 JVM 上拿一段截断的 JSON 直接测——上游一改格式，先红在这里，
 * 而不是红在用户手机上那次同步失败。
 *
 * ## 四个 id 是怎么拼出来的
 *
 * 上游结构是 `slug -> { models: { mapKey -> 模型 } }`，而 **mapKey 有两种形态**：
 * 官方厂商名下是裸 id（`openai` 的 `gpt-4o`），聚合站名下带真厂商前缀
 * （`tokengo` 的 `deepseek/deepseek-v3.2`）。实测 7,864 行里 4,508 行带斜杠，
 * 其中 **4,419 行的前缀不等于外层 slug**。据此：
 *
 * | 字段 | 规则 | `tokengo` + `deepseek/deepseek-chat` | `openai` + `gpt-4o` |
 * |---|---|---|---|
 * | [CatalogModel.key] | `providerSlug/mapKey` | `tokengo/deepseek/deepseek-chat` | `openai/gpt-4o` |
 * | [CatalogModel.modelId] | mapKey 原样 | `deepseek/deepseek-chat` | `gpt-4o` |
 * | [CatalogModel.vendor] | mapKey 的斜杠前缀，无斜杠时是外层 slug | `deepseek` | `openai` |
 * | [CatalogModel.qualifiedId] | `vendor/裸 id` | `deepseek/deepseek-chat` | `openai/gpt-4o` |
 * | [CatalogModel.normId] | 归一化后的裸 id | `deepseek-chat` | `gpt-4o` |
 * | [CatalogModel.canonical] | `vendor == providerSlug` | false | true |
 *
 * 三条规则各自挡一个真实的坑，都不是为了好看：
 *
 * 1. **主键必须带外层 slug**。同一个 mapKey 会被十几家聚合站重复挂出、各带一份转售价，
 *    用 `vendor/model` 当主键就会互相覆盖，最后留下哪一家的价格**全看 JSON 里的顺序**。
 *    分开存之后"重复"反而是好事：多条候选，由 [CatalogModel.canonical] 挑原创那条。
 * 2. **modelId 不剥前缀**。中转站列表里写的就是 `deepseek/deepseek-chat`，剥掉前缀
 *    反而让第二级精确匹配对不上，含斜杠输入的第一级匹配也整段失效。
 * 3. **厂商不用 `family`**。GPT 系列的 family 是 `gpt`，DeepSeek 的 flash/thinking 分身
 *    各有各的 family（`deepseek-flash`、`deepseek-thinking`），拿它分组会得到
 *    「一个 DeepSeek 拆成四组、GPT 变成 gpt 组」这种结果。
 */
object CatalogParser {

    /** 一次解析的产物。两份一起返回，是为了让落库那侧能在同一个事务里替换两张表。 */
    data class Result(
        val models: List<CatalogModel>,
        val vendors: List<CatalogVendor>,
    )

    /** 顶层解码：`Map<外层 slug, 厂商>`。 */
    fun decode(jsonText: String): Map<String, ModelsDevProvider> =
        ModelsDevSchema.json.decodeFromString<Map<String, ModelsDevProvider>>(jsonText)

    fun parse(snapshot: Map<String, ModelsDevProvider>): Result {
        val vendors = snapshot.map { (slug, provider) ->
            CatalogVendor(
                slug = slug,
                // 上游 name 实测恒在；真给空串就退回 slug，免得界面上出现一个空分组标题。
                name = provider.name.ifBlank { slug },
                apiUrl = provider.api?.ifBlank { null },
                docUrl = provider.doc?.ifBlank { null },
            )
        }
        // 展示名按 slug 查，而不是顺手用外层 name：聚合站条目上的厂商是前缀那个
        // （`deepseek`），它的外层是 `tokengo`，用外层名会把 DeepSeek 的模型标成 TokenGo。
        val nameBySlug = vendors.associate { it.slug to it.name }

        val models = buildList {
            snapshot.forEach { (providerSlug, provider) ->
                provider.models.forEach { (mapKey, model) ->
                    add(toCatalogModel(providerSlug, mapKey, model, nameBySlug))
                }
            }
        }
        return Result(models = models, vendors = vendors)
    }

    /**
     * 单条转换。露出来是为了让测试能直接喂一个 `(providerSlug, mapKey)` 进去断言那四个 id，
     * 不必为了一条规则铺一整个快照。
     */
    fun toCatalogModel(
        providerSlug: String,
        mapKey: String,
        model: ModelsDevModel,
        vendorNamesBySlug: Map<String, String> = emptyMap(),
    ): CatalogModel {
        val bare = mapKey.substringAfterLast('/')
        val vendor = if (mapKey.contains('/')) mapKey.substringBeforeLast('/') else providerSlug
        val qualifiedId = "$vendor/$bare"
        return CatalogModel(
            key = "$providerSlug/$mapKey",
            providerSlug = providerSlug,
            vendor = vendor,
            vendorName = vendorNamesBySlug[vendor],
            modelId = mapKey,
            qualifiedId = qualifiedId,
            normId = CatalogNormalize.normalize(bare),
            canonical = vendor == providerSlug,
            name = model.name.ifBlank { null },
            description = model.description?.ifBlank { null },
            family = model.family?.ifBlank { null },
            // Double 收进来再转 Int：上游写 `1000000.0` 时不该让整次同步炸掉。
            contextLimit = model.limit?.context?.toInt(),
            outputLimit = model.limit?.output?.toInt(),
            costInput = model.cost?.input,
            costOutput = model.cost?.output,
            costCacheRead = model.cost?.cacheRead,
            costCacheWrite = model.cost?.cacheWrite,
            inputModalities = model.modalities?.input.orEmpty(),
            outputModalities = model.modalities?.output.orEmpty(),
            reasoning = model.reasoning,
            toolCall = model.toolCall,
            attachment = model.attachment,
            structuredOutput = model.structuredOutput,
            openWeights = model.openWeights,
            releaseDate = model.releaseDate?.ifBlank { null },
            lastUpdated = model.lastUpdated?.ifBlank { null },
            status = model.status?.ifBlank { null },
            knowledgeCutoff = model.knowledge?.ifBlank { null },
        )
    }
}
