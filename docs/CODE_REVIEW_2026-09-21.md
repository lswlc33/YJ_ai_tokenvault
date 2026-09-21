# 全面复查报告 · 2026-09-21

范围：`master` 全仓（483 个跟踪文件，约 10.4 万行 Kotlin），起点 `44244a6`。
本轮 16 个提交（`0a66305`…`cb8e3b7`），45 个文件。

四个维度分开看：**UI 布局**、**代码逻辑**、**整洁性 / 一致性**、**用户操作路径**（模拟真实点击序列找没兜住的地方）。
凡是能自己判定对错的都直接改了；需要真机目测、需要产品决策、或改动面大于本轮可验证范围的，列在第三节，逐条写了触发路径与建议。

> 报告初稿写完之后又补了两个（原本列在第三节）：**探测轮次卡在「正在请求 N/M」** = `cb8e3b7`，
> **低额阈值没有出口** = `99ad87a`。第三节里对应两条已标注。

---

## 一、已修（每个提交都过了 pre-commit 的 `:app:compileDebugKotlin :app:testDebugUnitTest`）

### 布局错：用户会直接卡住

| 提交 | 现象 | 位置 |
|---|---|---|
| `0a66305` | 客户端关键词页两条行入口并排，第二条（**保存**）被整行宽度的 preference 行挤到屏幕外 → 这一页只看得见「添加」，关键词永远存不下来 | `ClientKeywordsScreen` |
| `0a66305` | 阈值页 / 关键词页 Room 首帧未到时 `?: return`，整页不画，没有顶栏也没有返回箭头，看着像卡死 | 同上两页 |
| `35eeab6` | `AppDialog` 内容不可滚：WebDAV 设置弹层四个输入框 + 开关行，小屏/大字号下「确定/取消」被顶出弹层 → 看得见输入框却提交不了也关不掉 | `AppComponents.AppDialog` |
| `35eeab6` | `AppBottomSheet` 同一毛病（分组选择器能到 15 组、账号编辑两张卡、许可证整段正文），修在包装层，顺手去掉某页为绕开它而手加的空转 `verticalScroll` | `AppComponents.AppBottomSheet` |
| `0a66305` | 管理页普通态也有 FAB，末尾留白却只在多选态让出药丸高度 → 最后一张供应商卡的余额/状态点/延迟被加号永久压住，滚不出来 | `ManageScreen` |
| `0a66305` | `BootCorrupt` 的 `AppCard` 漏 `fillMaxWidth`（全仓唯一一处），缩成文字宽度 | `BootCorruptScreen` |
| `24a2470` | 五处裸 MIUIX `Text` 仍按 `Clip` 切值，绕过 `AppText` 定下的省略号：Base URL、预设名、分段标签、chip 被齐边切掉而**看不出被切** | `AppComponents` |
| `1daa384` | 模型页行脚注三条（来源/最近探测/未匹配）没有 `maxLines` 也没有 `weight`，任一条换行就撑高整行；组头标题同理 | `KeyModelsScreen` |

### 逻辑错：说假话或算出错数

| 提交 | 现象 |
|---|---|
| `594c1d0` | new-api 站点回 `quota_per_unit: 0` → `quota / 0 = Infinity` 原样落进余额列 → 首页印成 `92233720368547758.07` 一笔"看着真实"的余额；同币种两个这种值相加还会溢出成**负数**。三道一起收：读取处 `takeIf { it > 0 }`、商非有限值报解析失败、解码处把库里已有的 Infinity 归到"查询失败"那一档（不计入合计、计入失败家数）。附 3 条单测 |
| `0ab9d88` | 用量报告：只有一个读数时"净变化/总消耗"塌成 `0.0`，界面印成 `区间净变化 ¥0.00`——那是断言"这个区间一分没动"。而余额历史从 v10 才开始攒，**报告上线头几天几乎每家都是这个形状**。改成可空 + 界面留破折号；迷你折线单点时改画一个点（原来是 72×32 空框，像渲染坏了）；折线图左槽按实际最宽刻度算（写死 46dp 时四位数金额的轴文字整条压在折线上面） |
| `11b2911` | 连点供应商编辑页的 ✓ 两下：`providers` 没有名字唯一索引 → 真插入两家同名供应商；且 `_saved` 发两次 → `back()` 两次 → 把用户多退一级（从管理掉到总览）。预设编辑页同洞（自定义预设 `builtinKey` 为 null，唯一索引管不到），保存与删除都会多发一次退页事件。按 `KeyEditorViewModel.saving` 那套补闸 |
| `11b2911` | 余额卡：提示在**发出的那一刻**就说「余额已刷新」，而 `refreshAll()` 是 `runCatching` 吞掉的 → 全挂也报已刷新、数字还是旧的。改成跑完由事件说话（成功/失败两句）；同时加一道闸，连点不再叠第二遍全量余额请求（每趟都要真打各家接口），灰掉的图标旁边写明「正在查询余额…」 |
| `ffe1084` | 删掉一家供应商（或从备份恢复把 id 全部重排）之后，从探测明细点它的旧行 → 永远停在"加载中"。根因是 `state == null` 同时表示"还没读到"和"读到了没这条"。两个详情 VM 各加 `rowGone`，占位页分开说两件事；五处 `LoadingState(fillMaxSize)` 画在任何 Scaffold 外面（无顶栏、无 inset、"加载中"压在状态栏底下、没有可点的返回入口）也一并换成有外壳的 `PagePlaceholder` |
| `1daa384` | 模型整屏页 `loading` 与空态分不开 → 进页面先闪一句「该密钥没有模型」再整屏跳出来。另外目录那次 `findByKeys` 没兜底，异常会从 `collect` 冒出去把整页带走（VM 协程没有父级接） |
| `5cca1c9` | 数据页「清空探测结果」只清了库，明细页读的是引擎内存里那份累计快照 → "已清空"之后明细页照旧列全部旧行，而同一时刻总览已变成「还没探测过」。加 `ProbeEngine.clearLastRound()`，顺序放在事务之后 |
| `168c752` | WebDAV `saveConfig` 五项写入分开提交：中途失败留下"地址换了、密码没换"的半份配置 → `hasCredentials` false、同步页三行一起变灰说不出原因、报出来的是一条通用失败。收进一个事务（审计留在事务外，日志写不进去不该回滚用户刚填的配置） |
| `e533825` | 同步页：灰掉那三行的是**六个**动作共用的一个 busy 位，而解释那句只挂在"检查连接"一档（2026-09 那条反馈只补了拉列表）→ 上传/恢复/删除远端跑起来时照样一片灰、一个字不说。补中性提示。<br>「立即备份」与本地「导出」在恢复进行中没有任何闸：OVERWRITE 恢复是"先清库再写回"，中途导出的是一份**写了一半的库**，而提示只会说"已导出"。两处按 busy 置灰<br>Key 编辑页提交的早退里带了 `protocols.isEmpty()`，把 VM 里同一条判断和它已经渲染的报错挡在调用之前 → 老备份恢复出来的空协议 Key 改个名字按 ✓ 什么都不发生 |
| `0a66305` | 阈值校验放过 `Infinity` / `NaN`（`toDoubleOrNull` 认这两个，老代码只判 `< 0.0`）：存成 NaN 后每次低额比较恒为 false，这一档永不报警；存成 Infinity 则全都报警。补 `isFinite`；报错也不再一路红到退页——逐格判断、改动输入即收回 |
| `cb8e3b7` | 探测轮次：`_progress` 从此在任何出口都被摘掉（不再永久「正在请求 N/M」），收尾那一段失败也一定 `finishRun`，进行中给「查看明细」入口（停止按钮只在那一页），`cancel()` 对已死轮次也清进度；`observeLatest` 不再把半截行显示成「上次探测：刚刚 · 共 0 项」 |
| `99ad87a` | 「余额低额阈值」补上唯一的出口：余额卡上那句「N 家余额低于提醒阈值」（数据早就在 `DashboardUiState.attention` 里，只是没有任何界面读它） |
| `dd04de0` | 供应商详情页 `items(state.keys.size)` 没给 key，而 `KeyCard` 的展开态是 `remember(row.id)` → 删一把密钥后每张卡继承前一张的展开/收起，某张不相干的卡突然摊开一屏模型 |

### 整洁性 / 一致性

- `be1012a`：796 条 plain string 里 **75 条没有任何引用**（`Res.string.<键>` 与 `import …generated.resources.<键>` 两种写法各扫一遍，array/plurals 单独排除，两侧语言文件同步删）。它们是被删掉的界面留下的：管理页四页签时代、总览"需要处理/健康度"两块、请求预览整组、导入问题清单、从没做过的引导。另清掉 5 条只 import 不用的残留。删后两侧键数 721/721 完全对齐
- `0a66305` 里同时把关键词页改成与阈值页同一条规矩（落库成功才退页），那条 `_saved` 之前没有收集者
- 全仓扫过：无行尾空白、无 tab 缩进、`) {` 断行异常仅一处（已修，`dd04de0`）；两侧资源键数在改动前后都严格一致

---

## 二、复核后**没有**改的（避免下一轮重复怀疑）

- `ManageRows.kt:137` 等 9 处"行尾列无界"（上一轮报告也提过）：机制成立（`Row` 先量无 weight 的孩子），但这些尾巴实际只会是 `USD 1234.50`、`800 ms`、`2026-08-20 14:33` 这种长度有限的串，最坏的那条（Infinity 撑出的 20 位金额）已经在 `594c1d0` 掐掉。真要动得先有能变长的数据源，否则是给界面加没用的 `widthIn`。**没改**
- `detail_key_label` / `dashboard_balance_*` 等直接用 `${balance.currency} ${balance.amount}` 的地方：查了类型，`UiMoney.amount` 已经是 `String`（上游经 `FormatMoney.centsToPlainString`），不是裸 Double，不存在 `42.099999999999994` 那类问题
- `ProviderDetailScreen.InfoCard` 官网那行**故意不设 maxLines**（注释说明是全文唯一完整展示域名的地方），不是漏了
- 余额卡上的 `更新于 %1$s`、供应商卡的 `2099-99-99` 会员倒计时：都是既定设计
- `AppText` 的 Ellipsis 默认值本身已生效，只有绕过它的裸 `Text` 需要补（已补）

---

## 三、没修的问题（按后果排序，每条含触发路径 / 为什么这轮没动 / 建议）

### P1 · 会造成数据丢失、不可逆后果或永久卡死

**1. 自动锁屏会把填了一半的编辑器整个吞掉**
- 触发：管理 → 新建供应商 → ＋新建密钥 → 粘好 cURL/密钥 → 息屏或切走超过 **60 秒**（`AutoLockPolicy` 默认）→ 解锁。
- 后果：草稿没了。`VaultShell.kt:118-122` 的注释自己写明"锁定会把导航栈连同这一层一起销毁"，解锁后栈重建成 `DashboardRoute`，只有顶层 tab 被记住（`AppRoot.kt:85`）。脏检查只保护返回手势（`KeyEditorScreen.kt:215-227`），保护不了锁屏。
- 为什么没动：这是"复制一串 Key 中途被电话打断"的日常路径，代价是用户白填一屏。修法要么把 `KeyDraft`/`ProviderDraft` 落到盘上（`rememberSaveable` 不够，进程被杀仍会没），要么锁定时保留整条栈——两种都是功能级改动，且需要真机验证锁屏/解锁时序。
- 建议：先定方向（推荐：草稿按路由存进 VM 之外的宿主级 slot + 落盘），再动。

**2. 锁定不清页面级 ViewModel 里的明文副本**
- 触发：查看密钥 / 打开账号明文 → 立即锁定（或后台 60s）→ 解锁。
- 后果：`KeyEditorViewModel._revealed`、`KeyDetailViewModel._revealed`、`ProviderDetailViewModel.revealedAccount` 里的明文 `CharArray`/`String` 仍在堆上。`VaultSession.lock()` 清了 DEK、`KnownSecrets`、剪贴板（都对），但触达不到页级 VM；`onCleared()` 也不会被调——per-entry 的 `ViewModelStore` 是随组合子树消失被**丢弃**，不是清理（`ui/miuix/navigation/VaultNavDisplay.kt:126`）。
- 为什么没动：需要一个把 `locked` 事件下发到三页的通道（`AppRoot` 已经在 `onLocked` 上挂着 `KnownSecrets.clear()`，缺的是往下那一截）。三页各自已有 `clearRevealed()` / `closeReveal()` / `clearRevealedAccount()`，接线不难但要真机验证"锁定后内存里读不到明文"。
- 建议：`AppRoot` 把 `autoLocker.locked` 通过 compositionLocal 传下去，三页 `LaunchedEffect(locked) { if (locked) clear…() }`。

**3. 撤销删除供应商会永久丢掉它的余额历史**
- 触发：长按选中 → 删除 → 提示条按「撤销」→ 显示"已恢复"。
- 后果：供应商/密钥/模型/账号都按原主键回来了，但 `balance_history.providerId` 是 FK `onDelete = CASCADE`，**在删除那一刻就被级联清掉了**（`SupportEntities.kt:291-297`），`UndoRestorer` 的快照集合里没有它。于是用量报告里那家的趋势线永久截断，而 `undo()` 返回 true、UI 报了成功。同文件注释（`:283-288`）恰好说明这份历史金贵到要为它做 dedup。
- 为什么没动：要给 `BalanceHistoryDao` 加一个按 provider 取行的查询，并在同一个撤销事务里重插（涉及主键与 dedup 语义）。可以测，但属于"再一轮独立改动"。
- 建议：快照集合必须与级联集合对齐——这条值得写成一条测试固定住。

**4. ~~探测轮次在已兜底的块之外抛异常 → 永久"正在请求 N/M"，还留一条假的完成记录~~ 已修 `cb8e3b7`**
- 位置：`ProbeEngine.kt:788-909`（`_progress` 置位之后、`try` 之前）与 `:1086-1121`（收尾之后的 `applyDiscovered` / `refreshModelsInner`）都在 `catch (Throwable)` 之外；`cancel()`（`:754-757`）根本不碰 `_progress`。引擎 scope 的 `ScopeCrashGuard` 会把冒出来的人吞掉（空 handler），所以现场是"界面停在进度上，日志安静"。
- 后果 A：`_progress` 永远非空 → 仪表盘一直显示「正在请求 N/M · host」，而且"查看明细"在 `lastRun != null && progress == null` 条件下被隐藏，用户连停止按钮都点不到；只有杀进程能清。
- 后果 B：抛在 `runRepository.insert` 之前 → `probe_runs` 留一条 `finishedAt = null`，`ProbeRun.toSummary()`（`UiMapping.kt:581-583`）把它映射成 `finishedAt = startedAt`、总数 0/成功 0/失败 0 →「上次探测：刚刚 · 共 0 项」，一轮从没跑过的"已完成"。
- 补一句当时没写全的：**这个死态不止在异常时才有**。「停止探测」这个按钮只在明细页里，而仪表盘进行中的那一轮恰恰不给进入口，顶栏刷新又不会取消正在跑的轮次——所以每一轮跑起来的一百多秒里用户都没有停止这条路。
- 修法：`runRound` 兜住所有出口（摘进度 + 记 ERROR），收尾那一段单独 `runCatching` 保证 `finishRun` 一定执行，进行中也给「查看明细」，`cancel()` 在没有活轮次时清进度，`observeLatest` 只回已结案的行。仍需真机验证：跑一轮中途强杀进程，看下次进来「上次探测」是不是上一轮而不是"刚刚 0 项"。

**5. `BootCorrupt` 唯一的出路仍是「清空重来」**
- 现状：`AppRoot.kt:74` 仍传 `onRestoreFromBackup = {}`、`LockCallbacks.restoreFromBackupEnabled` 默认 false，所以那一行是灰的——但已经会说明为什么灰（`boot_corrupt_restore_unavailable`），清空也有二次确认。
- 结构性问题没变：唯一的恢复入口在解锁闸**后面**（`VaultNavHost.kt:1381-1385`），而 BootCorrupt 关掉的正是那道门。带着可用备份也救不回来。
- 为什么没动：要在解锁失败的路径上开一条"用备份口令直接建库"的通道，属于安全边界改动，需要他亲自定口径。

### P2 · 会误导用户，但不毁数据

**6. ~~「余额低额阈值」这一整页设置目前没有任何可见效果~~ 已修 `99ad87a`**
- `attentionItemsOf(...)` 是唯一读 `observeBalanceThresholds()` 的地方，结果落在 `DashboardUiState.attention`，而**没有任何 composable 读 `.attention`**（`ui/common/HealthVisuals.kt:53-58` 那个 `messageOf` 四档文案也没人用）。设了 ¥30、余额掉到 ¥5，界面上不会有任何一处提到它。
- 上一轮删掉总览"需要处理"那块卡时，留下了入口和数据、拿走了出口。本轮 `0a66305` 先修了这一页本身的错（校验 + 报错残留），随后 `99ad87a` 补上出口：余额卡上多一句「N 家余额低于提醒阈值」，摆在已有的「N 个供应商的余额查询失败」旁边。不新开卡也不新开页。
- 仍然待定的两半：另外三档（密钥被拒 / 客户端被拦 / 配置错）现在还是没有出口，`messageOf` 也没人用；这一句只报数不报名，"是哪几家"要去管理页对着数字看。要不要把"需要处理"整块接回来，归他定。

**7. `https://user:pass@host/v1` 会被接受，口令进明文列**
- `EndpointNormalizer.kt:106-108`：`hostPort = afterScheme.substringBefore('/')` 会带上 `userinfo@` 前缀，而校验只查空/查询/fragment。
- 后果：从代理配置里粘来的地址带着口令也能存；`apiBaseUrl`/`apiRoot` 是 TEXT 列（口令**不加密**），会显示在详情页、被「复制 Base URL」送进剪贴板。另外 `ProbeEngine.hostOf`（`:727-728`）会剥 userinfo，而 `HttpEngine`/`UiMapping.hostOf`（`UiMapping.kt:143-146`）不剥 → 同一家站被节流器当成两个身份。
- 建议：authority 含 `@` 时拒绝（新增 `EndpointError.HasUserInfo` + 两份文案），或剥掉并给一次警告。顺带把两处 `hostOf` 合成一个权威。

**8. 大段 cURL 粘贴在主线程解析**
- `ImportViewModel.kt:60-97`：`viewModelScope.launch`（Main.immediate）里直接 `CurlImporter.parse(text)`（`:69`）和 `keys.fingerprintOf(...)`（`:84`），都没挪到 `Dispatchers.Default`。100KB 输入是几百毫秒冻结，低端机可能 ANR，而这一发没有任何进度提示（`_importing` 只管写库）。同类的 `ProviderDetailViewModel.kt:224`、`KeyModelsViewModel.kt:349` 都用了 `withContext(Dispatchers.Default)`。

**9. 探测结果里未列出的 HTTP 状态码被永久钉成灰色 CONFIG_ERROR**
- `ProbeClassifier` 固定 200/400/401/403/404/429（fixture `probe-matrix.json` 只钉了这六个），405/408/409/410/422/451 走默认档 → 长期灰着，而 `ClassificationReason` 到明细 UI 没有通路（`ProbeItemResult` 没有 `reason` 字段）。上一轮就是这个原因留下的，本轮仍未动：改默认档会影响所有供应商的状态点语义，需要先定"这些码到底说明什么"。

**10. `LogScreen` 用 `.padding(padding)` 而全仓约定是 `contentPadding`**
- `LogScreen.kt:196`：外层 `Column` 吃掉 Scaffold padding，列表只给横向 contentPadding。其余列表页（`ManageScreen.kt:401` 及其 `:206-213` 的注释、`KeyDetailScreen.kt:198`、`KeyModelsScreen.kt:195`、`UsageReportScreen.kt:111`）都把底部避让交给 `contentPadding`，理由写明是"列表要能滚进药丸底下，玻璃底栏才采样得到内容，否则发黑"。
- 没动的原因：这是纯视觉判断，Windows 上跑不出来；改了可能反而把状态栏 inset 弄错位。需要真机看一眼再定。

### P3 · 结构与技术债（需要有意识的取舍）

**11. 一整个分组被塞进 `LazyColumn` 的一个 item**
`KeyModelsScreen.kt:273-288` + `:540-553`：`groups.forEach { item { GroupCard } }`，而 `GroupCard` 内部 `rows.forEach { ModelRow }`。一把 Key 有 200 个模型时，第一个 item 里就有一次性组合 200 行（每行还带一个 `AppIconMenu`），远超视口高度。同类还有 `KeyDetailScreen.kt:404-432`、`KeyEditorScreen.kt:473-480`、`ProviderDetailScreen.kt:893-907`、`GroupsScreen.kt:359-368`。
拆成逐行 item 会破坏"一张卡是一个整体"的外观（要做首/尾圆角分段），渐进展开（先渲染前 N 行 +「还有 170 个」）是产品决定。本轮只做了不改外观的部分（脚注 maxLines/weight）。**需要他选**，也建议顺手在真机上量一次进入该页的耗时再定。

**12. 两个 `ModelRow`，同一实体两种长相**
`ManageRows.kt:323-371` 用 `AppBasicRow` + `endActions` + `maxLines = 1`；`KeyModelsScreen.kt:573-649` 自己拼 `Column { Row { … } }`（就是第 11 条那套无界尾巴的来源）。同一行模型在三个页面上高度、点击区、溢出行为都不一样。合并成一个组件是正确做法，但会同时动三个页面。

**13. `VaultNavHost` 的样板重复**（`PageContent` 本身没问题：定义一次、调用两次）
- `LoadingState(fillMaxSize)` 5 处（本轮已统一成 `PagePlaceholder`）
- `AppUndoFeedback(actionLabel/undoneMessage/failedMessage/undo())` 5 处逐字相同，各自前面还有同样的三个 `stringResource`
- `feedback = LocalAppFeedback.current` + `writeFailed` + `LaunchedEffect { … WriteFailed → post }` 10 处
- 破坏性确认的 `AppDialog(title, confirmText, dismissText = dialog_cancel, onDismiss)` 形状 13 处
建议：`rememberUndoFeedback(deletion, strings)` 一个 helper 吃掉 10 个块；`ui/miuix` 里加 `ConfirmDestructiveDialog(...)` 吃掉 13 个。

**14. 无断点、无字号缩放适配**
全仓没有 `WindowSizeClass`/`adaptiveBreakpoint`（grep 确认为 0）。具体后果：`AppTokens.screenPadding = 16.dp` 固定 → 平板/折叠屏上卡片拉满，一行字横跨 1000dp；`LineChart.kt:67` 的 10sp 与 `LiquidGlassNavigationBar.kt:326` 的 11sp 是全仓最小的两处文字，低于 12sp 惯例，且不受字号缩放影响（系统开大字号时它们不变）。`AppComponents` 里 `insideMargin = PaddingValues(16.dp)`、几处 10dp/6dp 圆角是 token 值的字面量复制。

**15. 上一轮就已明确推迟、本轮确认仍在的**（不重复展开）
`ProbeEngine` 无单测；迁移 2→3 只有 FK-off 分支被测；iOS 每主机并发与 Android 不一致（Darwin 没设 `httpMaximumConnectionsPerHost`）；自签 **https** WebDAV 在 iOS 仍不支持；`Info.plist` 声明 `zh-Hans` 但没有 `.lproj`、`NSFaceIDUsageDescription` 只有英文；`ReleaseMatcher` 认为 `1.0 < 1.0.0`；`RoomBackupStore.observeAll()` 把 `secretEnc` 一起搬 + `concatToString` 留下删不掉的明文副本；`TextImporter`/`TextExporter` 没有生产调用方。

---

## 四、本轮模拟过的用户路径，以及结论

| 路径 | 结论 |
|---|---|
| 空库首启：建首家 → 建首 Key → 探测 → 看模型 | 干净。没有 `list[0]`/裸 `first()`；空态有明确 CTA；模型列表空/筛空/搜空三种分开 |
| 连点：保存密钥、导入、同步、上传/恢复/删除 | **供应商与预设的保存有问题（已修）**；其余每步都有独立 per-operation 闸且 `finally` 收回（`ImportViewModel`、`KeyEditorViewModel`、`SyncViewModel` 六条、`SecurityViewModel`、`LockViewModel`、`ProbeEngine` 四个 `Job`、`AutoRefresher.roundInFlight`） |
| 删除级联：删有 Key/模型/历史的供应商 | schema 级联与 `UndoRestorer` 的重插集合基本对齐（按 FK 顺序、保持原主键，所以绑了 AAD 的密文还能打开；死掉的 `clientProfileId` 置 null 而不是让撤销失败）。**唯一缺口是 `balance_history`（P1-3）** |
| 返回键/推入动画期间的错误状态 | 两个编辑器都有脏检查 + 确认丢弃 + `PlatformBackHandler`；离开探测页不会杀轮次（独立 `SupervisorJob`），取消也在 `NonCancellable` 里落库。破坏性动作全部有确认，OVERWRITE 恢复还有第二次确认 |
| 锁屏打断 | **有 P1-1、P1-2 两个洞**。剪贴板这一侧是好的：`VaultSession.lock()` 会清，`AppTextFieldState.clear()` 连撤销历史一起清，秘密输入框故意不用 `rememberSaveable`，编辑页两处明文 30s 自动遮回 |
| 输入畸形：unclosed `$'…'`、`?a=1` 查询串、尾随换行、`≤0`/非整数、纯空白名 | `CurlParser` 线性且总体不抛（历史上崩过的 `substring(2,1)` 已有 `length >= 3` 保护）；没有异常能从点击处理器逃出去；写入边界一律 trim |
| 网络失败：限流、空模型列表、恢复各阶段失败 | 四种 `SkipReason` 都有说法；限流在两处守（`HostGate.acquire` + `awaitWireSpacing`）并吃 `Retry-After`；可疑的空 `/models` 拒绝用于删列表；`RestoreFailure` 分成口令错/版本太新/未授权/传输/写库五种文案 |
| 设置中途改：并发数、每主机间隔、自动锁、余额阈值 | 一个值只有一个权威、都是派生而非缓存（`HttpConcurrencyApplier`、`AutoRefresh` 只影响下一次等待、`AutoLocker.timeout` 从原后台时刻重算、长任务暂停引用计数）；改并发数不会抢占在飞的请求 |
| 给用户的数字 | 「失败」与「0」在类型上就是两回事，一路到 UI；总额故意不含失败读数并单独计数；多币种不做汇率换算且明说；三页共用同一套 `aggregateHealth`/`effectiveHealth`；模型数在 SQL 与 UI 两侧同一套去重。**唯一算错的是单点趋势（已修）与阈值页的 Infinity/NaN（已修）** |
| 恢复备份之后 | 所有列表/详情页都由 Room flow 驱动（`SharingStarted.Lazily`），恢复会自然落到屏上，不需要手动刷新。残留问题只在引擎的内存轮次快照（P1-4 与 `ffe1084`、`5cca1c9` 覆盖了其中可见的三条） |

---

## 五、验证口径与这轮没能验到的

- 每个提交都独立过了 `:app:compileDebugKotlin :app:testDebugUnitTest`（pre-commit 钩子），本轮另跑 `:shared:jvmTest`（`DiGraphSmokeTest` 能抓 Koin 图问题，新增的 `ProbeEngine` 构造参数就是靠它兜的）、`:app:lint`（通过）、`:shared:compileCommonMainKotlinMetadata`（iOS 缺口的本地代理，只剩已知的 Room `VaultDatabaseConstructor` 那一条，说明新代码没有再引入 JVM-only API）。
- 新增单测 7 条：阈值 `Infinity`/`NaN` 校验 3 条（`BalanceThresholdParseTest`）、new-api 换算比与商溢出 3 条（`BalanceAdapterTest`）、报告单点不报变化 1 条（`UsageReportAggregatorTest`）。
- CI（GitHub Actions）在报告提交 `dd4fd45` 上三个 job 全绿：`build`、`ios`、`release`。这一条尤其有意义，因为它是 `compileCommonMainKotlinMetadata` 那个本地代理的确认——本轮新增的 commonMain 代码在 iOS 目标上真编得过。最后两个提交（`cb8e3b7`、`99ad87a`）的 CI 在写这份报告时还在跑。
- **没有做的验证**：真机/模拟器目测。所以第三节的 P2-10（LogScreen 底栏发黑）、P3-11（进模型页的实际卡顿程度）都只到"读代码可判定"这一步；上面所有布局类修复的视觉效果同样需要他上手看一眼——尤其是关键词页那两个并排按钮、弹层可滚之后的矮弹层外观、以及对话框按钮从"被顶出屏幕"变成"钉在底部"。
- `cb8e3b7` 特别需要一次真机验证：跑一轮探测，中途强杀进程，再进来对一眼「上次探测」是不是上一轮而不是"刚刚 · 共 0 项"；以及进行中点「查看明细」→「停止探测」是不是真能把那一行字清掉。`ProbeEngine` 依然没有单测（第三节 P3 那条已知欠账），这四处改动是靠读代码 + 全绿回归推出来的，不是被测出来的。
- 上一轮的两条有意保留项仍然有效（本轮不动）：自动轮次每把 Key 仍发 protocols+1 个 GET；v8 迁移仍按最小 `id` 选存活行。
