# CLAUDE.md

架构与不变量。这里写的都是**改坏了不会立刻报错**的东西——编译通过、测试也可能通过，
但会在某个时刻造成不可挽回的后果。动到相关代码时先读这一页。

怎么动手在 `AGENTS.md`，完整设计与每条规则的推导在 `计划.md`。

## 分层

```
domain/ endpoint/ probe/ balance/ catalog/ importer/ backup/ crypto/
    纯 Kotlin。零 android/androidx/okhttp3 import，零 Clock.System。
    平台能力（含当前时间）以接口或 lambda 注入，所以这八个包在 JVM 单测里全覆盖。
net/        OkHttp 实现层：HttpEngine 的实现 + 脱敏拦截器
data/       Room 实体 / DAO / 迁移 / 映射器 / 仓库实现
platform/   Keystore、BiometricPrompt、剪贴板、SAF、WorkManager、窗口安全
di/         Hilt Module
ui/theme    AppTokens / StatusPalette / AppColorSchemeMode（无 MIUIX 依赖）
ui/miuix    **唯一允许 import top.yukonga.miuix 的包**，含 AppTheme
ui/common   StatusDot / SecretText / RelativeTime / EmptyState …
ui/shell    AppRoot / LockGate / VaultShell / NavHost / Routes
screens/    页面。不出现 SQL，不构造 HTTP 请求，不直接接触 crypto/
```

## 三个一级页的分工

底栏三项：**仪表盘 · 管理 · 设置**。分工是一条硬规则——改坏了不会报错，但会长出两套真相：

- **仪表盘只读**。不提供任何编辑入口，每一行的点击结果都是"跳到管理页的某个详情"。
- **管理负责改**。**只列供应商**，按用户自定义分组筛选（一排可横滑的 chip）；密钥 / 模型 /
  平台账号都在供应商详情里看和改——它们没有独立于供应商的意义。
  管理页**不放汇总卡**——同一个数字在两个页面各算一遍，迟早对不上。
- **设置只管软件自己**：设置（外观 / 安全 / 探测 / 客户端预设 / 数据）、同步、关于、更新。

"探测"**不是**一级页：它是动作不是内容。入口在仪表盘（一个按钮），逐项结果在
`ProbeRunRoute` 二级页，"去改"永远只有一个去处——管理页。详见 `计划.md` §13.1、§13.4。

- 仓库接口定义在 `domain/repo/`，实现在 `data/`。ViewModel 只依赖接口。
- Room 实体只存在于 `data/`，与 `domain/` 的模型是两套类，中间有显式映射器。
- 这几条由 `ArchitectureRulesTest`（JVM 单测，pre-commit 会跑）与 `ci.yml` 的 grep
  双重守住。

## 六条推论（数据层）

1. **Room 实例是应用级单例**，启动即建。锁定 = 清零 DEK + 跳锁屏，**不关库**。
   需要明文秘密的调用在锁定态抛 `VaultLockedException`。
2. 因此只碰公开数据的后台任务（models.dev 同步、`dataRevision` 检查、日志清理）
   **在锁定状态下也能跑**；只有需要加解密的任务才要求已解锁。
3. **DAO 与映射器一律不解密。** `Flow<List<Entity>>` → 领域对象只搬密文 `ByteArray`，
   明文只出现在明确借用 DEK 的 UseCase 里。否则列表页在锁定瞬间会在 Flow 内部抛异常，
   把整条订阅打断。遮蔽串是解密后现算的，所以它只出现在详情页。
4. **`BootStore` 的写入必须原子**（临时文件 → fsync → rename），解析失败落到
   `LockPhase.BootCorrupt`，引导用户去恢复备份，绝不静默重建。
5. **同一个配置项只能有一个权威存储。** `boot` 只存解锁前必须可读的那几项
   （KDF 参数、包裹后的 DEK、退避计数、`themeMode` / `localeTag` / `onboarded`），
   `app_settings` 不得重复这些键。备份的 `appSettings` 白名单要显式包含它们。
6. **默认 Key 恰好一张。** 新增第一张自动设默认；设默认时同事务清掉其它；
   删除默认那张后自动把 `sortOrder` 最小的启用 Key 顶上——否则四个余额适配器会静默失效。

## 加密边界（"关于"页必须与此一致）

| 加密（字段级 AES-256-GCM） | 明文 |
| --- | --- |
| `api_keys.secretEnc` | 供应商名称、备注、官网、端点、协议、路径覆盖 |
| `provider_accounts.usernameEnc` / `passwordEnc` | 模型 id、显示名、探测状态、延迟 |
| `providers.balanceTokenEnc` | 余额金额、币种、原始文本 |
| `app_settings.valueBlob`（WebDAV 凭据） | 分组、颜色、排序、时间戳、日志、客户端预设 |

数据库跑在系统自带 SQLite 上，**不上 SQLCipher**：真正的秘密已经字段级加密，
SQLCipher 额外保护的只是元数据，代价是每 ABI 多 1–2 MB 原生库、库只能解锁后打开。
代价必须诚实写出来：**元数据在应用私有目录里是明文的**。宣传语因此只能说
"密钥与账号密码经 AES-256-GCM 加密存储"，不能说"整库加密"。

## 36 条红线（一句话版，推导见 `计划.md` §3）

**密钥与加密**

1. 明文密钥只允许存在于内存的 `ByteArray`/`CharArray` 和 HTTP 请求头里。
2. DEK 随机生成，PIN 只用来包裹它；**改 PIN 必须是 O(1)**，不允许全库重加密。
3. KDF 参数与盐随密文一起存；"发现与编译期常量不一致就改写存储值"是永久锁库的定时炸弹。
4. 生物识别必须由 Keystore 硬件密钥保护且 `setUserAuthenticationRequired(true)`。
5. 生物识别开关由独立持久化偏好决定，关闭后任何解锁路径都不得悄悄打开。
6. 锁定时 DEK 与派生子密钥置零；DEK 只由唯一会话对象持有，他人短暂借用。
24. 每条字段级密文的 AAD **必须绑定行身份**（`"表名:主键"`），否则密文可被跨行覆盖。
25. DEK 可以有多种包裹（PIN / 生物识别 / **恢复密钥**），但明文 DEK 只有一份。

**数据完整性**

7. 备份包必须自包含：内含 KDF 参数、盐与**明文密钥**（整包已加密），绝不搬运旧设备密文。
8. 解密失败必须报错并中断，不允许静默降级成 `null`。
9. 任何跨版本恢复都走显式迁移函数，不允许把旧结构的行直接插入新表。
10. 数据库是唯一数据源，UI 通过 Flow 观察，不允许"写完手动通知刷新"。
26. boot 存储每次写入必须原子，并且必须存在显式的 `BootCorrupt` 状态。
27. 备份包里的跨表引用一律用自然键（分组名、`builtinKey`、指纹重算），绝不搬自增主键。
28. 备份包**不搬运探测结果**，恢复后一律重置为未探测。

**探测正确性**

11. **瞬时失败绝不修改健康结论。** 网络失败 / 429 / 5xx 只写 `lastOutcome`，
    `health` 保留上一次的持久结论。
12. `GET /v1/models` 返回 200 不等于 Key 有效；反过来，除 401/403 之外的结构化响应
    都证明鉴权已通过，400 只说明请求参数不被接受。
13. 自动同步时手动录入的条目永不被改；上游消失的条目标停用而非删除。
14. 余额是"可用额度"，减法只写在明确提供两个量的那个适配器内部。
15. 余额必须带币种；不硬编码货币符号，不硬编码阈值。
29. **探测不允许把自己探成失败**：每 host 每轮有请求上限，撞 429 后停发所有可选请求；
    并发上限之外还要有 host 级**最小间隔**——挂 Cloudflare 的站三个并发请求就够撞 1015。
30. 发现来的模型必须记住来自哪个协议的列表；"上游消失即停用"只在该协议内生效。
33. **客户端校验可能返回 401，而且拦在鉴权之前**（M0.5 实测）。所以客户端关键词判定
    必须排在 401 分支之前，否则"换个 UA 就能用"会被判成"密钥无效"。
34. **200 不代表拿到了内容。** `max_tokens=16` 在推理模型上给出空 `content` 或
    `status: "incomplete"`。判 SUCCESS 只能要求"200 + 是 JSON + 没有 error 字段"。
35. **非 2xx 的响应体经常不是 JSON，甚至完全是空的**（纯文本 `error code: 1015`、
    403/404 + 空 body）。body 是可选输入，解析失败不许升级成 `CONFIG_ERROR`。
36. **要花钱的探测只能用户手动点，开关按供应商单独存。** L3 逐模型、以及 models 路由
    不鉴权时的升级版 L2，一概不许出现在自动路径（自动探测 / 定时 / 解锁后 / 重试失败项）
    上——碰到就标 `SKIPPED` 并说明。四级开关 + 一个总闸都在 `providers` 行上，
    设置里的同名项只是"新建时的默认值"。每家站规则不同，有的 ToS 就不允许探测。

**产品完整性**

16. 每个持久化字段都必须有 UI 入口或明确的产生路径。
17. 同一状态全应用只有一套文案和一套颜色；状态必须同时用颜色和文字表达。
18. 协议属于**模型**，能力集合属于**供应商**，二者不能互相推导。
19. 用户可见文本一律进两份 strings.xml，Compose 代码里不留中文字面量。
20. 领域逻辑是不依赖 Android 的纯 Kotlin，**当前时间也算平台能力**，必须注入 `Clock`。
31. 同一个配置项只能有一个权威存储（见上面推论 5）。

**凭据边界与可解释性**

21. 平台账号的用户名与密码与 API 密钥同等对待：同一套加密、遮蔽、回遮、脱敏、剪贴板策略。
22. 客户端伪装预设是**数据不是代码**：探测代码里不允许硬编码任何 `User-Agent` 或特征头。
23. 明确记录哪些列加密、哪些明文，并在"关于"页说明。
32. 落库前的脱敏**以已知明文值替换为主，正则只作兜底**——正则挡不住 base64 形态的访问令牌。
    替换要覆盖明文的 **≥8 字符前缀与 ≥4 字符后缀**：上游会只回显后 4 位（`****alid`）。

## 两个状态列，不是一个

`KeyHealth`（持久结论，只被判定性响应改写）与 `ProbeOutcome`（最近一次探测发生了什么，
每次都写）分成两列，是红线 11 在类型层面的表达。`KeyHealth` 里**没有**
`NETWORK_ERROR` / `RATE_LIMITED` / `UPSTREAM_ERROR`：它们是瞬时状况，留在那里迟早
有人写进去。`models` 表同理。

## 弹层与截屏

只用 MIUIX 的 `Overlay*`：它画在 `Scaffold` 内的同一窗口里，继承 `FLAG_SECURE`。
`Window*` 系列是独立系统窗口、**不继承**，而本项目的弹层里就有展示明文密钥和密码的。
Compose 原生的 `Dialog` 也是独立窗口，用它必须单独设 `SecureFlagPolicy.SecureOn`。

## 当前进度

M0（仓库与骨架）已完成：Gradle + 版本目录 + Hilt + MIUIX 主题 + 分层 Scaffold Shell +
三个一级页占位 + 类型安全路由 + `AppTokens`/`StatusPalette` + `.githooks` + `ci.yml` +
`ArchitectureRulesTest`。**唯一没做的 M0 验收项是真机/模拟器上的视觉验收**（本机没有连接
设备，也没有任何 AVD 与 system image），要么插真机跑 `installDebug`，要么先征得同意再下
system image。

M0.5（协议踩点）已完成，2026-09-04。`ProtocolSpike.kt` 打了三家真实中转站，五个答案与
四条顺带撞出来的事实都写进了 `计划.md` §16「M0.5 实测结论」，脱敏 fixture 在
`app/src/test/resources/fixtures/`（`probe-matrix.json` 是 16 条去重响应形态，
M5 的 `classify()` 单测直接读它）。红线 33–35 就是这一轮补的。
唯一没拿到的是**额度耗尽的真实响应**（三个账号都还有余额），记在 §18。

M0.8（界面骨架）**已完成**。底栏是仪表盘 · 管理 · 设置，全部 18 个路由都有真实页面，
没有占位壳了：

- **仪表盘**六块卡（余额 / 内容计数 / 健康分布 / 需要处理 / 上次探测 / 备份）+ 两个二级页
  （探测明细、余额明细）。
- **管理**只列供应商 + 用户分组的横滑筛选条；二级页有供应商详情、供应商编辑、
  分组管理、粘贴导入。编辑页的**端点实时预览**用的是真的 `normalizeBaseUrl`。
- **设置**四块导航 + 七个二级页（外观 / 安全 / 探测 / 客户端预设 / 数据 / 同步 / 更新）。

`ui/miuix` 有 `AppTabRow` / `AppChip` / `AppFilterChip` / `AppSearchField` / `AppTextField` /
`AppFab` / `AppBottomSheet` / `AppLinearProgress` / `AppSwitchRow` / `AppDropdownRow` /
`AppRefreshBox`；`ui/common` 有 `StatusDot` / `SegmentedBar` / `StatTile` / `HealthVisuals` /
`SecretText` / `RelativeTime` / `ColorSwatchRow` / `EmptyState`。
样例数据在 `screens/sample/`（M3 删掉）。

**顺手提前做完的两件 M3 / M5 的事**（都是纯 Kotlin + 单测，不是脚手架）：
`endpoint/EndpointNormalizer.kt`（§5.2 的 URL 规范化，§14.3 测试 1 全绿，10 个用例）与
`ui/common/RelativeTime.kt` 的分档函数（6 个用例）。两者都不读当前时间，`now` 是参数
（红线 20），所以测试不是时间敏感的。

验收：模拟器上 18 个页面都能进能回；深浅色各一轮、中英文各一轮；`OverlayDialog` 与
`OverlayBottomSheet` 在二级页里都能弹；单测 22 个全绿（1 个 spike 按设计跳过）；
`lint` 0 issue；strings 两份键名脚本对齐（各 314 项）。
**还没做**：宽屏双栏（在 M10）、`SecretText` 接真明文与剪贴板（要 DEK，在 M3）。

`AppTabRow` **没有用 MIUIX 的 `TabRow`**：它给所有分段算同一个固定宽度再加内边距，
四个英文标签在 360dp 宽的屏上必然被截断，而 `minWidth` / `maxWidth` 都改不动
（真正的上限是"可用宽度 ÷ 分段数"）。现在是自己用 `Surface` + `weight` 拼的。
它当前没有使用者，留给 M3 的详情页按协议给模型分组。

**strings.xml 里不要写 Markdown**：`**加粗**` 会原样显示成星号。强调靠断句和词序。

M1（安全底座）**已完成**，2026-09-06。引导 → 设 PIN → 抄恢复密钥 → 锁定 → 解锁 →
自动锁定，整条路在设备（Android 15，adb `127.0.0.1:7555`）上逐屏走通。
**生物识别那一条只有单测，没有设备验证**：这台设备报"没有生物识别硬件"，
安全页那一行因此是禁用态。要验它得换一台有指纹的机器。

- `crypto/`：Argon2id 包裹 DEK（红线 2 的 O(1) 改 PIN）、AES-256-GCM 字段级加解密、
  HKDF 派生子密钥、`RecoveryKey`（32 个 hex 字符 = 128 位熵，`normalize` 吃掉全部空白、
  连字符与大小写差异）。
- `platform/VaultSession` 是**唯一持有明文 DEK 的对象**（红线 6/25），阶段机
  `Loading | Onboarding | Locked | Unlocked | BootCorrupt`；`FileBootStore` 原子写
  （临时文件 → fsync → rename，红线 26），`Missing` 与 `Corrupt` 是两个状态。
- `UnlockBackoff`：前 4 次不罚，之后 30/60/300/900/3600 秒。**改 PIN 页验旧 PIN 走
  `unlockWithPin` 而不是另做一个不计次的 `verifyPin`**——后者等于给那一页开一个绕过
  §7.2 的入口。
- `platform/AutoLocker` 接 `ProcessLifecycleOwner` 的**进程级** ON_STOP/ON_START，
  不用 Activity 的生命周期（那个在转屏和弹系统框时也走 onStop，于是每次转屏都锁一次）。
  切后台到点在后台真的锁掉，不等用户回来补锁。时间用注入的 `elapsedRealtime`（红线 20）。
- `BootStore.revision` 是个落盘计数：观察者从数据源知道该重读了，而不是靠每个写入点
  顺手通知一声（红线 10 的精神）。**生物识别开关与配色模式都从它派生，不自己记一份**——
  `AppRoot` 与外观页各自 `hiltViewModel()`，后者的宿主是导航栈里的一个 entry，
  两处拿到的是两个实例；而 `BiometricUnlocker` 在密钥失效时会自己关掉开关，那条路
  不经过任何 ViewModel。自己记一份的表现是"设置页画着已开启、锁屏页照旧弹指纹框"。
- `SecureClipboard` / `BiometricUnlocker` 都是接口 + 实现，为的是能在 JVM 单测里换掉：
  真实的生物识别实现连"构造出来但不调用"都做不到（`KeyStore.getInstance("AndroidKeyStore")`
  在 JVM 上直接抛）。

**M1 的设备验证清单**（2026-09-06，逐屏 `uiautomator dump` 核对）：改 PIN 三步 → 旧 PIN
当场失效、新 PIN 能解锁（红线 2 的 O(1) 重包裹在设备上成立）；错一次 PIN 后提示
"还能再错 3 次"、解锁成功后计数清零（退避计数确实落 boot）；恢复密钥轮换 → 换掉旧的那把、
`screencap` 出来整屏全黑（`FLAG_SECURE` 生效）、离开那一页再进去只剩"这个库有恢复密钥"
（明文与展示串都丢了）；恢复密钥**大写带空格**照样解锁（`normalize`）；
自动锁定切后台 66 秒后回到前台是锁屏，而且 `pidof` 前后同一个 PID——所以是**那个活着的
进程自己锁的**，不是进程被杀之后的假阳性。

**已知缺口：安全设置里"离开应用后锁定"那个下拉还没接上。** 五个选项
（立即 / 30 秒 / 1 分钟 / 5 分钟 / 从不）只改内存里的 `SettingsDraft`，
`AutoLocker.timeoutSeconds` 始终是默认的 60 秒。刻意没做半截接线：这一项的权威存储
应该是 `app_settings`（红线 31），而那要等 M3 的 `SettingsRepository`；
接在 boot 上是错的权威，将来还得再迁一次。选"立即"却仍然等 60 秒是"设置项撒谎"，
所以它排在 M3 的第一批。

M2（数据层）**部分完成**：10 张表 + 10 个 DAO + v1 schema JSON 已提交，
`data/mapper` 的列编解码（CSV 与 JSON 列往返）17 个单测全绿。
**仓库层还没有**——`domain/repo/` 接口与 `data/` 实现都不存在，10 个 DAO 目前零注入方。

下一步 M3（接真数据）：`domain/repo/` 接口 + `data/` 实现，然后把管理页从
`screens/sample/` 换成真数据。**在这一步做完之前，这个 app 存不下任何一个真密钥。**
里程碑表见 `计划.md` §16。

单测 200 个全绿（1 个 spike 按设计跳过），17 个 suite；`lint` 0 error / 23 warning
（12 个 UnusedResources 是 M0.8 留下的死文案，M3 会用上或删掉；剩下的是 6 个
PluralsCandidate、2 个依赖有新版、2 个 Modifier 工厂命名、1 个拼写）；
strings 两份各 408 条 string + 7 条 string-array，键名零差异。
**仪器测试仍然是空的**（只有 `HiltTestRunner.kt`），所以 `计划.md` §14.3 里需要设备的三项
——Keystore 往返、boot 原子写杀进程、`FLAG_SECURE` + 剪贴板——目前只有手工验证，
没有自动化覆盖。
