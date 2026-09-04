# fixtures —— M0.5 协议踩点的实测响应

采集时间 **2026-09-04**，采集工具 `app/src/test/java/com/lc33/tokenvault/spike/ProtocolSpike.kt`
（默认跳过，要 `-DvaultSpike=true` 且仓库根有 `示例数据.md` 才跑）。
源站是 `示例数据.md` 里那三家真实中转站：Agent Router、JustDoWork、DeepSeek。

这里的文件是**脱敏后**的。原始响应在 `.local/spike/`（`.gitignore` 写死），永不入库——
上游会把密钥后 4 位回显在错误消息里，new-api 的 `/api/user/self` 还会回显访问令牌、
邮箱与第三方账号 id。

## 文件

| 文件 | 内容 | 谁用 |
| --- | --- | --- |
| `probe-matrix.json` | 去重后的 16 种响应形态，每条带 `expectOutcome` / `expectHealth` 与「证明了什么」 | M5 的 `classify()` 单测，一条 case 一个断言 |
| `balance/newapi-user-self-*.json` | 两家 new-api 的 `/api/user/self` | M7 `newapi` 适配器 |
| `balance/newapi-status-*.json` | 两家 new-api 的 `/api/status`（`quota_per_unit` 在这里） | M7 `newapi` 适配器的换算比校准 |
| `balance/deepseek-user-balance.json` | DeepSeek 官方 `/user/balance` | M7 `deepseek` 适配器 |
| `models/newapi-models-*.json` | new-api 的模型列表，带 `supported_endpoint_types` | M5 的模型三路合并 |
| `models/deepseek-models.json` | DeepSeek 的模型列表（**没有**端点类型字段） | 同上 |

## 脱敏做了什么

按红线 32 的两道：**先按已知明文值替换**（密钥、访问令牌及其前 8 / 后 4 字符），
**正则只作兜底**（`sk-…`、`Bearer …`、以及 `access_token` / `email` / `username` /
`display_name` / `github_id` 这一族 JSON 字段）。
数值型的账号 id 手工换成了 `100001` / `100002` / `100003`。
`/api/status` 里的公告与 FAQ 正文、站点自己的 OAuth client id 与 turnstile site key 已删。

`probe-matrix.json` 里 Anthropic 响应的 `signature` 是约 700 字节的 base64，已截断标注——
它不是我们的秘密，只是噪声。

## 几条不要改回去的结论

1. **客户端校验可能返回 401 而不是 403**，且发生在鉴权之前（Agent Router）。
2. **模型不存在可能返回 400 而不是 404**（DeepSeek），也可能返回 **403 + 空 body**（JustDoWork）。
3. **Cloudflare 1015 有两种响应体形态**，纯文本与 RFC 7807 JSON；body 里的 `retry_after`
   可能比响应头 `Retry-After` 更大，退避要取较大值。
4. **200 不代表拿到了内容**：`max_tokens=16` 在推理模型上会得到空 `content` /
   `status: "incomplete"`，SUCCESS 的判据不能要求文本非空。
5. `quota_per_unit` 两家都是 **500000**，与 §9.2 的默认值一致；但它排在几 KB 的公告之后，
   适配器不能先截断响应体再找它。

## 重跑

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ProtocolSpike*" -DvaultSpike=true
```

会花掉少量真实额度（本次两轮合计约 $0.5 的 Agent Router 免费额度，DeepSeek 与
JustDoWork 可忽略）。重跑后原始与脱敏两份 JSON 都落在 `.local/spike/`，
**入库前必须自己再核一遍脱敏结果**，别直接 copy。
