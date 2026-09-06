# 元记 · AI Token Vault — 执行计划（精简版）

> **本文件的定位**：只回答三件事——做完了什么、还差什么、下一步怎么走。
> 设计细节、推导过程、完整红线条文仍在 `old_plan.md`；架构不变量与 36 条红线以
> `CLAUDE.md` 为准（**改代码前必须读**）。本文件不替代它们，也不与它们冲突；
> 若出现冲突，以 `CLAUDE.md` 为准。
>
> 状态截至 2026-09-06。代码基线：提交 `8cf715d`（M0–M10 功能层已全部落地并提交，
> 其后 12 个提交是 CI 收尾）。剩余工作见 §3——全是"要设备 / 要额度 / 要硬件"的验收，
> 加上两个可砍项。

---

## 1. 里程碑状态总览

| 里程碑 | 状态 | 缺口（一句话） |
| --- | --- | --- |
| M0 仓库与骨架 | ✅ | 无（真机视觉验收已在 M1/M3 顺带完成） |
| M0.5 协议踩点 | ✅ | 额度耗尽的真实响应未采到（§6.2） |
| M0.8 界面骨架 | ✅ | 无（18 个路由均有真实页面） |
| M1 安全底座 | ✅ | 生物识别无设备可验（当前设备无指纹硬件）；**前台空闲锁定 + 屏幕关闭即锁定已落地**（`app_settings` 可编辑，探测/备份进行中自动挂起空闲锁定） |
| M2 数据层 | ✅ | 仪器测试已补（索引/级联，6 用例）；外键约束 bug 已修 |
| M3 接真数据 | ✅ | 无（管理四页 + 仪表盘六卡均吃真数据） |
| M4 文本导入 | ✅ | ADB 端到端已跑（设备上输入单家块 → 解析预览 → 确认 → 落库 → 详情页解出明文；含明文弹层截屏全黑）；剩 M5/M6/M7 探测与余额（要花额度） |
| M5 探测引擎 | ✅ | 明细页已接真数据（`ProbeRunViewModel`）；仅剩设备端到端未跑 |
| M6 客户端伪装 | ✅ | 仅剩设备端到端（Agent Router 三路径 / TLS 指纹终止结论）；**拦截关键词设置已落地**（`app_settings` 可编辑） |
| M7 余额 | ✅ | 仅剩设备端到端（真实数字与后台对得上）+ 适配器真实响应样例；**余额阈值设置已落地**（`app_settings` 可编辑，USD/CNY）；**手动代理设置已落地**（`app_settings` 可编辑，`host:port`，支持 IPv6 方括号） |
| M8 模型元数据 | 🟡 纯逻辑层已做 | WorkManager 拉取 `api.json` 未做（可砍） |
| M9 备份与同步 | ✅ | 第二台设备恢复验证未做；WebDAV 半边可砍 |
| M10 打磨与发布 | 🟡 发布链路已落地 | 宽屏双栏、TalkBack、更新页 API 未做；**搜索/排序/批量已落地** |
| M11 可选增强 | ➖ | 逐项独立评估 |

**功能层（M0–M10 的代码、测试、UI 接线）已全部落地并提交**。剩余的都是"要设备 / 要额度 /
要硬件"的验收（ADB 端到端、生物识别、额度耗尽采集、仪器测试），以及两个可砍项
（M8 的 WorkManager 拉取、M10 的宽屏双栏），不再是功能缺失。

**数据层已无迁移负担**：10 张表（`groups` / `providers` / `api_keys` /
`provider_accounts` / `client_profiles` / `models` / `model_catalog` /
`probe_runs` / `audit_log` / `app_settings`）与 v1 schema 已建，M6–M10 需要的列
（`authStyle` / `clientProfileId` / `balance*` / `quotaCalibrated` / `probeEnabled`）
也已在位。**后续里程碑原则上不加表、不加列**，除非实施中发现确实缺字段（届时走
v2 显式迁移，红线 9）。

---

## 2. 已完成清单

### M0 / M0.5 / M0.8（骨架 · 踩点 · 界面）
- Gradle 9.5 + 版本目录 + Hilt + KSP；MIUIX 0.9.3 主题与包装层；分层 Scaffold Shell。
- `ArchitectureRulesTest` + `ci.yml` 五条 grep 双重守架构；`.githooks`（pre-commit 编译+单测，commit-msg 中文）。
- M0.5 打过三家真实中转站，五个答案 + 四条衍生事实写入 `old_plan.md` §16，脱敏 fixture 在 `app/src/test/resources/fixtures/`。
- 18 个路由全部真实页面；仪表盘六卡、管理页（只列供应商 + 分组筛选）、设置四块导航。
- 顺带提前完成：`endpoint/EndpointNormalizer`（10 用例）、`ui/common/RelativeTime` 分档（6 用例）。

### M1（安全底座，2026-09-06 设备验证通过）
- `crypto/`：Argon2id 包裹 DEK（改 PIN 是 O(1)）、AES-256-GCM 字段级加密、HKDF 派生、恢复密钥（`normalize` 容错）。
- `FileBootStore` 原子写 + `BootCorrupt` 态；`VaultSession` 阶段机；`UnlockBackoff` 30/60/300/900/3600s。
- `AutoLocker` 走进程级生命周期；`FLAG_SECURE` 已验证（`screencap` 全黑）。
- 逐屏验证：改 PIN、退避计数、恢复密钥轮换与解锁、切后台 66 秒自动锁定（同 PID）。
- **未验项**：生物识别（当前设备报无硬件，安全页那一行是禁用态）。

### M2（数据层，基本完成）
- 10 张表 + 10 个 DAO + v1 schema JSON + `data/mapper` 列编解码 + 实体↔领域映射器。
- `domain/repo/` 五个接口 + `data/repo/` 实现（分组 / 供应商 / 密钥 / 账号 / 设置 / 模型，后两个是 M4 建的最小版）。
- **缺口**：仪器测试（迁移、级联删除、`BootStore` 原子写）一条没写；`ProfileSeeder` 未做；`SettingsRepository` 目前只有 `autoLockSeconds` 一项。

### M3（管理 + 仪表盘接真数据，2026-09-06 设备验证通过）
- 管理页 / 供应商详情 / 供应商编辑 / 分组管理四页吃真数据；密钥能存、能看明文、能复制。
- 两步写（AAD 绑主键，红线 24）、插入前算指纹、遮蔽串不入库按 `updatedAt` 缓存。
- 仪表盘六卡不再有假数字：三卡接真数据、三卡给空态；聚合统一走 `ui/shell/UiMapping.kt` 纯函数。
- 自动锁定时限落 `app_settings.autoLockSeconds`（存秒数不存下标，"从不"是显式一档）。

### M4（文本导入，2026-09-06）
- `importer/TextImporter` 纯函数（8 用例全绿），块字段五条终止规则全部落地。
- `data/repo/ImportWriter` 落库编排（单事务），`importer/TextExporter` 反向导出（往返断言）。
- `SecureClipboard.read()` + `AppTextFieldState.setText()` 支持"从剪贴板填充"。
- **缺口**：ADB 端到端（本机无设备）；SAF 导出入口（可砍，§4.7）。

### M5（探测引擎核心，2026-09-06）
- `endpoint/`：三协议请求构造（MockWebServer 验真实报文，测试 2 全绿）。
- `probe/ProbeClassifier`（测试 3，读 `fixtures/probe-matrix.json` 16 条真实响应）、`ModelMerger`（测试 12）、`ProbeOrchestrator`（测试 14，虚拟时间）。
- `net/`：`OkHttpEngine` + `HostGate`（host 间隔默认 800ms，429 加倍封顶 8s）。
- 单测 328 个全绿；`lint` 0 error；`assembleDebug` 通过。
- **接线已落地**：`engine/ProbeEngine`（`@Singleton`）、`probe/ProbePlan.kt` 任务生成、Hilt 绑定、`probe_runs` DAO 与保留策略、`DashboardViewModel.startProbe` 已接（`canProbe = true`）、仪表盘「上次探测」摘要卡已接真数据（`ProbeRunSummary` 改存时间戳而不是拼好的文案，红线 19）。
- **工作区未提交的那一批**是摘要卡相关改动，先提交掉再往下做。

### M6（客户端伪装，2026-09-06：纯函数层 + 预设 UI + 自动嗅探）
- `domain/repo/ClientProfileRepository` + `data/repo/RoomClientProfileRepository` + `data/dao/ClientProfileDao`。
- `data/seed/BuiltinPresets` + `ProfileSeeder`：幂等种入 8 个内置预设，按 `builtinRev` 刷新未改过的条目。
- `endpoint/HeaderAssembler`（测试 10）：基础头 → 预设头按序覆盖 → 鉴权头最后加且不可覆盖 → 占位符展开；`mergeBodyPatch` 按 RFC 7386。
- `importer/CurlParser`（测试 9）：续行 / 引号 / `-H` / `-A` / `--data-raw`，自动剔除敏感头并回传清单。
- 预设 UI（列表 / 编辑 / cURL 导入）接真数据；供应商编辑页「客户端预设」下拉接真数据并落库。
- 自动嗅探（`probe/SniffPlan` + `engine/ProbeEngine.trySniff`）：先换鉴权头 → 最多试 4 个预设（匹配协议排前、不匹配也试）→ 命中写回 `clientProfileId`/`authStyle` → 429 熔断（红线 29）。
- 单测 381 个全绿；`lint` 0 error / 24 warning；`assembleDebug` 通过。
- **缺口**：设备端到端（Agent Router 三路径 / TLS 指纹终止结论），要插设备且花真实额度。

### M7（余额，2026-09-06：纯逻辑层 + 引擎 + UI 接线）
- `balance/` 八个文件：`BalanceAdapter` 接口 + `BalanceSnapshot` + `FormatMoney`（先舍入再相加）；
  六个内置适配器（new-api / DeepSeek / OpenRouter / SiliconFlow / Moonshot / 自定义 JSON）+ `none`。
- `NewApiAdapter` 先读 `/api/status` 校准 `quota_per_unit`，校准失败不致命（`quotaCalibrated=0` 标记）。
- `engine/BalanceEngine`（`@Singleton`）单家点一下查一次；失败也落 `error`（区分"查询失败"与"余额 0"）。
- `DashboardViewModel.refreshBalance` + 余额明细页三态分组；测试 `BalanceAdapterTest` 13 用例。
- **缺口**：设备端到端（真实数字与后台对得上）+ 每个适配器贴真实响应样例。

### M8（模型元数据，可砍：纯逻辑层已做）
- `catalog/`：`ModelCatalogMatcher` 三级匹配（全走索引）+ `CatalogNormalize`；`ModelCatalogDao`
  的 `findByKey`/`findByModelId`/`findByNormId`/`upsertAll`/`clear`；测试 9 用例。
- **缺口**：WorkManager 拉取 `api.json`（分块解析 + 每 200 行事务 + TTL 7 天）——`TokenVaultApp`
  注释明确"现在还没有 Worker"，`DataScreen` 的"同步元数据"是空实现。可砍。

### M9（备份与同步，2026-09-06：编解码 + 引擎 + SAF 接线）
- `backup/BackupCodec`（AAD 绑 header）+ `engine/BackupEngine`（643 行）导出/恢复编排；
  跨表引用用自然键（红线 27）、不搬探测结果（红线 28）、恢复三模式默认合并、单事务。
- SAF 接线（`VaultNavHost.SyncRouteContent`）+ `appSettings` 白名单含 `themeMode`/`localeTag`；
  顺带填掉 M4 的 SAF 导出入口。
- **缺口**：第二台设备恢复验证；WebDAV 半边（可砍，`onWebDav = {}`）。

### M10（打磨与发布，2026-09-06：发布链路已落地）
- `audit_log` 持久化 + 日志页（条数 + 天数双重上限）；README（如实写 6 位 PIN 挡不住离线穷举）。
- 发布链路：ci.yml 单测+lint+仪器编译+`assembleNightly` 一次 gradlew；keystore 走 secret；
  nightly 预发布走 `nightly-build` tag（仓库规则禁建 `nightly` tag）。
- **缺口**：搜索/排序/批量；宽屏双栏（可砍）；TalkBack 走通主路径；更新页接 GitHub Releases API。

---

## 3. 未完成清单（按优先级）

**功能层已全部落地**。下表剩余项分为两类：要设备/额度/硬件的验收，与两个可砍项。

| 优先级 | 项 | 说明 | 粗估 |
| --- | --- | --- | --- |
| **P1** | ADB 端到端（M5/M6/M7） | M5 探测、M6 嗅探、M7 余额——插设备花真实额度。M4 粘贴导入已在设备上跑通（含 FLAG_SECURE 验证） | 需要设备 + 真实额度 |
| **P1** | 仪器测试 | Room 迁移 / 级联删除 / `BootStore` 原子写已补（`VaultDatabaseTest` 6 用例 + `FileBootStoreTest` JVM 可测）；`FLAG_SECURE` 的 connectedTest 已补（`SecureFlagTest` 2 用例，真机跑通） | ✅ 完成 |
| **P2** | 生物识别设备验证 | 换一台有指纹的机器（当前设备无硬件） | 有设备时 |
| **P2** | 额度耗尽真实响应 | DeepSeek CNY 0.89 耗尽后补一次 `ProtocolSpike` 进 fixture | 余额自然耗尽时 |
| **P2** | M8 WorkManager 拉取 | 可砍，不影响核心四件事 | 2 人日 |
| **P2** | M10 收尾 | ~~搜索/排序/批量~~（✅ 已落地）、宽屏双栏（可砍）、TalkBack、更新页 API | 1 人日 |
| **P3** | M11 可选增强 | 供应商预设库 / Deep Link / SQLCipher / 桌面端 | 逐项评估 |

> 粗估只用于排序，**不是承诺**。要花钱的探测（M5/M6）与余额（M7）在设备端到端里一次跑完。

---

## 4. 实施路径

> **状态说明（2026-09-06）**：§4.1–§4.6 的**实现步骤（非设备验收）已全部完成**——
> M5 接线、M6 伪装、M7 余额、M8 纯逻辑、M9 备份、M10 发布链路都已落地并提交（见 §2 各节）。
> 剩下的只有各节标注的「设备端到端 / 真实额度 / 硬件」验收项，归入 §3 的 P1/P2。
> 下文的步骤表保留作为历史记录，验收项以 §3 为准。

### 4.1 M5 收尾：让探测真的跑起来并证明它是对的 ✅（实现完成，设备验收待做）

**目标**：`ProbeEngine` 从"代码写完了"变成"在设备上跑过、结论可信"。

| 步骤 | 内容 |
| --- | --- |
| 1 | 跑通门禁（编译 + 单测 + lint），**先把工作区那批摘要卡改动提交掉** |
| 2 | 补 `ProbeEngine` 单测：`VaultSession` 锁定 → `onLock()` 真的取消（用假 session）；逐项落库用假 DAO 断言 `health` 只在判定性响应时被改（红线 11） |
| 3 | **探测明细页 `ProbeRunScreen` 接真实数据**——当前 `VaultNavHost` 仍传 `lastRun = null` + 空列表，用户刚探测完点「查看明细」却看到空态，这是必须补的断点：`ProbeItemResult` 补 target 上下文（供应商名 / host）、行内 outcome 字符串枚举化（红线 19）、新增 `ProbeRunViewModel` 聚合结果流 |
| 4 | **探测进行中暂停前台空闲锁定计时**（§7.4）——✅ 已落地：`ProbeEngine` 与 `BackupEngine` 用 `try/finally` 包 `pauseIdleLock()/resumeIdleLock()`；顺带把前台空闲锁定 + 屏幕关闭即锁定两个开关从 `SettingsDraft` 迁到 `app_settings` 并接通 `AutoLocker`（详见 CLAUDE.md M1 后的小节） |
| 5 | 删掉 `DashboardUiState.canProbe` / `canRefreshBalance` 占位字段（已恒为 true）——✅ 已删（2026-09-06） |
| 6 | 探测触发点补齐：详情页「探测这一家」、Key 行长按「单 Key 探测」——✅ 已落地（2026-09-06，`ProbeEngine.probeProvider`/`probeKey` 复用 `startScoped` 二级过滤，零成本 L1/L2）；**模型三路合并落库**（`ModelMerger` 已实现，写入侧还没接）——依赖 L3 模型探测（要钱、红线 36 仅手动），L3 本身未实现，写入侧无触发源不能单独做，归「要设备/额度验收」 |
| 7 | 设备验收：飞行模式全量探测 → 全部"网络不可达"且健康结论保留；一次 429 不把可用 Key 标坏；中点取消请求立刻停；探测中切后台被锁定后已完成结果仍在 |

**验收**：`old_plan.md` §17「探测与客户端伪装」前 8 条；§14.3 测试 2/3/12/14 全绿。

**注意**：真实探测**花钱**。验收用 `示例数据.md` 时，每轮只勾一个供应商，且 L3 / 升级版 L2 一律手动（红线 36）。**不要为了验证重跑 `ProtocolSpike`**（一轮约 30 个请求）。

---

### 4.2 M6 客户端伪装：好 Key 不能因为 UA 被判死 ✅（实现完成，设备验收待做）

> **已完成**（2026-09-06）：步骤 1–7 全部落地，测试 9/10 与 `SniffPlanTest`（嗅探顺序）全绿。
> **仅剩验收里的设备端到端**：Agent Router 三条路径 + JustDoWork 空 body + TLS 指纹终止结论，
> 需插设备、花真实额度（归入 P2「遗留补做」）。

**目标**：Agent Router（401，只看 UA）能用 `claude_code` 预设过闸；整条链路没有硬编码 UA（红线 22）。

| 步骤 | 内容 |
| --- | --- |
| 1 | `ClientProfile` 领域模型（表已有）+ DAO + 仓库（最小集：`observeAll` / `findByBuiltinKey` / `add` / `update` / `delete`） |
| 2 | **`ProfileSeeder` 幂等种入 8 个内置预设**（M2 遗留项，预设内容见 `old_plan.md` §8.2 表；`zcode` 留空位） |
| 3 | `endpoint/HeaderAssembler`（纯函数）：预设头按序覆盖 → **鉴权头最后加且不可被覆盖** → 占位符展开 → `bodyPatch` 按 RFC 7386 合并 |
| 4 | `importer/CurlParser`（纯函数）：续行 / 引号 / `-H` / `-A` / `--data-raw`；自动剔除 `Authorization` / `cookie` / `content-length` / `host` 并在预览页告知 |
| 5 | 预设 UI：设置 → 客户端预设（列表 / 编辑 / cURL 导入）；**供应商编辑页的「客户端预设」下拉接真数据并落库**（现在只有一项且不落库） |
| 6 | 自动嗅探：`CLIENT_BLOCKED` 时**先换鉴权头**（Bearer ↔ x-api-key）→ 再按顺序最多试 4 个预设（匹配本协议的排前、其余也要试）；命中写回 `clientProfileId` / `authStyle`；**本轮该 host 出现过 429 立即停止嗅探**（红线 29） |
| 7 | 扩展测试 10（头部组装）与测试 9（cURL 解析），并把嗅探顺序与熔断加进测试 14 |

**验收**：§14.3 测试 9、10 全绿；Agent Router 三条路径全部走通——默认 UA 被 401 拦 → 换 `claude_code` 预设转可用 → 换 `x-api-key` 也能绕过；JustDoWork（403 + 空 body）给出"上游没说原因"而不是编造理由；所有预设试过仍被拦时给"按 TLS 指纹识别，无法伪装"的终止结论。

---

### 4.3 M7 余额：数字必须和后台页面对得上 ✅（实现完成，设备验收 + 真实样例待做）

**目标**：余额从"空态"变成真数字，且浮点尾数、币种、失败与 0 的区分都对。

| 步骤 | 内容 |
| --- | --- |
| 1 | `balance/` 纯 Kotlin 包：`BalanceAdapter` 接口 + `BalanceSnapshot` + `formatMoney`（`BigDecimal.setScale(2, HALF_UP)`，**先舍入再相加**） |
| 2 | 6 个内置适配器 + `customJson` + `none`。`newapi` 要**先读 `/api/status` 的 `quota_per_unit` 校准且绝不截断响应体**（Agent Router 那份 5.4 KB，字段排在公告之后）；`quotaCalibrated = 0` 时 UI 带"换算比未校准"标记 |
| 3 | 落库与刷新：`BalanceRepository` + 仪表盘刷新图标 + 详情页「查余额」；删掉 `canRefreshBalance` 占位 |
| 4 | 展示：首页按币种分组求和（不做汇率换算）、阈值（默认 USD 5 / CNY 30）、`balanceRaw` 折叠区、**「查询失败」与「余额为 0」必须可区分** |
| 5 | 测试 4、5 全绿（含两条 M0.5 回归：>4 KB 的 `/api/status`、缺 `quota_display_type` 不整条失败） |
| 6 | **每个适配器在 PR 里贴一次真实响应样例，贴不出来就删掉该预设**——留一个恒返回 0 的预设比没有更糟 |

**注意**：`/api/user/self` 会回显 `access_token`、邮箱、用户名，余额响应体**绝不原样进日志**，异常消息里也不许带原始 body（红线 32）。

---

### 4.4 M8 模型元数据（可砍） 🟡（纯逻辑层完成，WorkManager 拉取未做）

| 步骤 | 内容 |
| --- | --- |
| 1 | WorkManager 拉取 `api.json`（4.46 MB），**按厂商分块解析**（`JsonReader` 切顶层片段逐个 `decodeFromString`），每 200 行一个事务分批写 `model_catalog` |
| 2 | 三级匹配 `matchCatalog(modelId, vendorHint)` 全部走索引；结果写回 `models.catalogKey`，**只在列表变化或元数据更新后重算**，不在渲染时算 |
| 3 | 缓存 TTL 7 天；仅在过期且 `UNMETERED` 时自动更新；首次不自动下 4 MB，改为第一次需要时询问 |
| 4 | 详情页展示上下文 / 输出 / 价格 / 模态 / 推理 / 工具调用；匹配不上只显示模型 id；提供"手动指定元数据" |

**验收**：测试 13 全绿；**实测耗时与内存峰值贴进 PR** 再决定要不要退回整树解析（不许凭感觉选）。锁定态同步照常跑（推论 2）。

---

### 4.5 M9 备份与同步 ✅（实现完成，第二台设备恢复 + WebDAV 待做）

| 步骤 | 内容 |
| --- | --- |
| 1 | 备份包编解码：`magic ‖ headerLen ‖ header(明文 JSON) ‖ payload(AES-256-GCM)`；AAD 绑 header 原始字节 |
| 2 | **跨表引用一律自然键**（分组名、`builtinKey`、指纹在导入端重算）；**不搬探测结果**（恢复后一律未探测）；预设只备份用户改过的 |
| 3 | `appSettings` 白名单必须显式含 `themeMode` / `localeTag`（权威存储在 boot，而 boot 不进备份） |
| 4 | SAF 导入导出（`CreateDocument` / `OpenDocument`）；恢复前用**当前 DEK** 生成本地快照（私有目录留 3 份） |
| 5 | 三种恢复模式（覆盖 / 合并 / 仅新增，默认合并），整过程单事务失败回滚 |
| 6 | 填掉 M4 留下的 SAF 导出入口（`TextExporter` 已就绪，只差文件写入） |
| 7 | **WebDAV（可砍）**：四动词手写、`https://` 强制、`PeriodicWorkRequest` 周期备份（默认关）、冲突判据用 `header.createdAt` 而非 `dataRevision` |
| 8 | 测试 11 全绿 + **第二台设备真机恢复验证**（分组与客户端预设必须对得上，红线 27） |

---

### 4.6 M10 打磨与发布 🟡（发布链路完成，搜索/批量/TalkBack/更新页待做）

| 步骤 | 内容 |
| --- | --- |
| 1 | `audit_log` 持久化 + 脱敏 + 日志页（表已建）；日志条数与天数上限 |
| 2 | 搜索 / 排序 / 批量操作 ✅（2026-09-06 已落地）；**宽屏双栏（可砍）** |
| 3 | 无障碍（TalkBack 走通主路径）；strings 双份键名对齐 |
| 4 | release 签名（四个环境变量）；nightly + tag 两条 workflow；release 包全流程回归 |
| 5 | 更新页接 GitHub Releases API（自动检查默认关；无网与 GitHub 不可达要给出可区分文案） |
| 6 | `README.md`：功能、隐私声明、**如实写明 6 位 PIN 挡不住离线穷举**（7.6） |

---

### 4.7 P2 · 遗留补做

| 项 | 步骤 | 何时做 |
| --- | --- | --- |
| **仪器测试** | `androidTest/` 现有 `VaultDatabaseTest`（6 用例：部分唯一索引 `idx_keys_default`、外键 CASCADE/SET_NULL）+ `SecureFlagTest`（2 用例：FLAG_SECURE 挂载置位/卸载清除/引用计数）。**已修掉外键未开启的 bug**（`PRAGMA foreign_keys` 默认 OFF，CASCADE/SET_NULL 全是摆设）。还差：Room 迁移测试（升 v2 时补） | 设备上 connectedTest 已跑通 |
| **生物识别设备验证** | 换一台有指纹的机器，走：开启 → 解锁 → 改指纹后自动失效回退 PIN → 关闭后多次 PIN 解锁不悄悄打开 | 有设备时 |
| **ADB 端到端** | 插设备后跑 `AGENTS.md` 里的流程：粘贴 `示例数据.md` → 全量探测 → 余额 → 快速复制 | 需要设备 + 真实额度 |
| **额度耗尽真实响应** | DeepSeek 那 CNY 0.89 用完后补一次 `ProtocolSpike`，把响应加进 `fixtures/probe-matrix.json` | 余额自然耗尽时 |
| ~~SAF 导出入口~~ | ✅ 已在 M9 一并填掉 | 已完成 |
| ~~README / 文档~~ | ✅ 已在 M10 完成（含 6 位 PIN 安全边界） | 已完成 |

---

## 5. 每次提交的门禁

```powershell
.\gradlew.bat :app:testDebugUnitTest     # 主力，几秒
.\gradlew.bat :app:lint                  # 0 error
.\gradlew.bat :app:assembleDebug
```

`pre-commit` 已自动跑 `compileDebugKotlin` + `testDebugUnitTest`，`commit-msg` 要求中文提交信息。
**改架构规则的时刻**：`ArchitectureRulesTest` 与 `ci.yml` 的五条 grep 是同一批规则的两道闸，改一处必须改另一处。

**每个里程碑收尾的通用检查**（缺一项就不算完成）：
1. 能构建、能跑测试、能装到设备上看到东西（`old_plan.md` §16 开头）。
2. 该里程碑用到的持久化字段**都有 UI 入口或明确的产生路径**（红线 16）。
3. 新增的用户可见文本进两份 `strings.xml`，键名一致；Compose 里不留中文字面量（红线 19）。
4. `CLAUDE.md` 的「当前进度」同步更新——它的价值就在于记录**为什么**这么做，以及哪些东西是"看起来完成了其实没有"。

---

## 6. 待确认与未实测

| 项 | 状态 | 需要谁 |
| --- | --- | --- |
| `zcode` 及其它常用客户端的真实请求头 | 内置预设留空位；M0.5 已证明 `claude_code` 真实有用，优先级降低 | 需一次抓包，或做完 M6 后用 cURL 导入自补 |
| 包名 `com.lc33.tokenvault` | 未收到异议即定稿 | 发布后改不了，现在提 |
| 额度耗尽的真实响应 | §8.4 第 4 行来自公开资料，无本地样本 | 见 §4.7，等 DeepSeek 余额耗尽 |
| 生物识别的设备验证 | 当前设备无指纹硬件 | 换机器 |

---

## 7. 关键约束索引

**改代码前必读 `CLAUDE.md` 的这几块**，它们是"改坏了不会立刻报错"的东西：

| 主题 | 出处 |
| --- | --- |
| 分层规则（八个纯 Kotlin 包 / `ui/miuix` 唯一 MIUIX 入口 / `Window*` 禁用） | `CLAUDE.md` 分层 + 弹层与截屏 |
| 36 条红线 | `CLAUDE.md`「36 条红线」，推导见 `old_plan.md` §3 |
| 数据层六条推论（Room 单例 / 锁定不关库 / DAO 不解密 / boot 原子写） | `CLAUDE.md`「六条推论」 |
| 加密边界（哪些列加密、哪些明文） | `CLAUDE.md`「加密边界」+ `old_plan.md` §4.4 |
| `KeyHealth` 与 `ProbeOutcome` 不是一个东西 | `CLAUDE.md`「两个状态列」 |
| 五条 CI grep 规则 | `old_plan.md` §14.4 |
| 完整验收清单（第 3 节每条红线的落地证明） | `old_plan.md` §17 |

**最容易踩的三条**（每次动相关代码默念一遍）：
- 瞬时失败**绝不**改写 `health`（红线 11）。
- 字段级密文的 AAD 必须绑行身份；新增密钥是**两步写**（AAD 依赖 AUTOINCREMENT 分配的 id）。
- 要花钱的探测**只能用户手动点**（红线 36），自动路径一分不花。
