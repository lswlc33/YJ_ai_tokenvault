# CLAUDE.md

架构与不变量。这里写的都是**改坏了不会立刻报错**的东西——编译通过、测试也可能通过，
但会在某个时刻造成不可挽回的后果。动到相关代码时先读这一页。

怎么动手在 `AGENTS.md`，完整设计与每条规则的推导在 `old_plan.md`。

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
`ProbeRunRoute` 二级页，"去改"永远只有一个去处——管理页。详见 `old_plan.md` §13.1、§13.4。

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
7. **外键约束必须显式开**（`onOpen` 里 `PRAGMA foreign_keys = ON`）。SQLite 默认 OFF，
   不开的话实体上的 `ForeignKey.CASCADE`（删供应商连带删 Key/账号/模型）与 `SET_NULL`
   （删分组置空 `provider.groupId`）**全是摆设**——删掉父行后子表里留下孤儿行，而编译
   与 JVM 单测都发现不了（FakeDao 没有 SQLite）。这条 2026-09-06 修掉，用仪器测试锁住。

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

## 36 条红线（一句话版，推导见 `old_plan.md` §3）

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
四条顺带撞出来的事实都写进了 `old_plan.md` §16「M0.5 实测结论」，脱敏 fixture 在
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

**“离开应用后锁定”那个下拉已经接上了**（见下面 M3 第三步）。当时刻意没做半截接线是对的：
这一项的权威存储是 `app_settings`（红线 31），接在 boot 上将来还得再迁一次。

**前台空闲锁定 + 屏幕关闭即锁定**（§7.4 收尾，2026-09-06 落地）：
- 两个开关从 `SettingsDraft` 迁到 `app_settings`（键 `idleLockSeconds` / `lockOnScreenOff`，
  默认都关）；`AutoLocker` 加 `idleLock` / `lockOnScreenOff` 两个 `@Volatile` 字段，由
  `TokenVaultApp` 里两条进程级订阅写入（同 `timeout` 的路子）。
- 空闲计时：`MainActivity.onUserInteraction()`（触屏与按键都会回调的规范钩子，非 androidx
  受限 API）调 `AutoLocker.onUserInteraction()` 重起一个 30 秒计时（`AutoLockPolicy.IDLE_LOCK_SECONDS`，
  固定时长、不给用户调）。计时用 `scope.launch { delay(...) }`，JVM 单测虚拟时间可推。
- 屏幕关闭：`MainActivity` 注册 `ACTION_SCREEN_OFF` 广播，收到调 `onScreenOff()`，开关开着
  就当场 `lockIfUnlocked()`。随 Activity 注销。
- **长任务挂起**（红线 28）：`ProbeEngine.runRound` 与 `BackupEngine.export/restore` 用
  `try/finally` 包 `autoLocker.pauseIdleLock()/resumeIdleLock()`，否则探测一轮（预算 120 秒）
  会被 30 秒空闲锁定自己打断。挂起用计数而非布尔，探测与备份重叠时只解一次不恢复。
- **Hilt 循环依赖**：`AutoLocker` 需要 `ProbeEngine`（锁定时停引擎），引擎又需要 `AutoLocker`
  （挂起空闲锁定）。`provideAutoLocker` 改收 `Provider<ProbeEngine>`，`onLock` 回调里才 `get()`。

M2（数据层）**基本完成**：10 张表 + 10 个 DAO + v1 schema JSON、`data/mapper` 的列编解码
（CSV 与 JSON 列往返）、实体↔领域映射器、`domain/repo/` 三个接口 + `data/repo/` 三个实现
（分组 / 供应商 / 密钥）都已提交。

M3（接真数据）**管理那一支已完成**，2026-09-06。管理页、供应商详情、供应商编辑、
分组管理四页吃真数据；**这个 app 现在存得下真密钥了**。设备上走通的一条路：
新建供应商 → 添加密钥 → 展开看明文 → `am force-stop` → 用 PIN 解锁 → 遮蔽串仍然算得出来
（这一步证明 DEK 从 boot 重新派生后 AAD 仍然对得上）。

- **新增密钥必须分两步写。** `secretEnc` 的 AAD 是 `api_keys:{id}:secretEnc`（红线 24），
  而 id 是 AUTOINCREMENT 分配的——加密时还不知道它。所以先插入（密文列先空着）拿到 id、
  再用真 AAD 加密回填，两步包在一个事务里。`providers.balanceTokenEnc` 同理。
  **这条路依赖 AUTOINCREMENT 不复用 id**：换成普通 `INTEGER PRIMARY KEY` 之后，
  删掉最后一行再插入会拿到同一个 id，于是旧密文能被搬进新行且 AAD 照样匹配。
- **指纹在插入之前算**（它不依赖 id），所以 `(providerId, fingerprint)` 唯一索引在插入
  那一刻就能挡住重复录入；顺带的好处是锁定态调 `add` 会抛在算指纹那一步，库里一行不留。
- **遮蔽串不入库**，是详情页解密后现算的（推论 3），按 `updatedAt` 缓存——只按 id 缓存的
  表现是"换了密钥但遮蔽串还是旧的那一段"。详情页因此挂 `SecureScreen()`。
- `ProviderRepository.save` 的令牌参数是**三档**：null 不动 / 空数组清掉 / 非空换新的。
  两档不够——编辑页手上没有已存的密文，"输入框是空的"不能当成"要清掉令牌"。
- **仓库单测用假 DAO**（`app/src/test/.../data/repo/FakeDaos.kt`）：没有 Robolectric，
  Room 在 JVM 上起不来。所以 SQL 层面的东西仍然没有自动化覆盖——外键 CASCADE、
  部分唯一索引 `idx_keys_default`、`(providerId, fingerprint)` 唯一约束。
  假 DAO 的行表**不能用 `MutableStateFlow<List<Entity>>`**：实体的 `equals` 是刻意残缺的
  （给 Room 用），而 `value` 的 setter 会用 `equals` 判"变了没"，于是
  `copy(isDefault = true)` 这种只动了未参与比较字段的写入会被静默丢掉。

**还吃 `screens/sample/` 的只剩两处**：粘贴导入的预览（解析器在 M4）与客户端预设列表
（`ProfileSeeder` 在 M5）。详情页的模型与平台账号**已接真数据**（2026-09-06 补，见下）。
编辑页的「客户端预设」下拉只有一项且不落库——内置预设是数据不是代码（红线 22）。

### M3 第三步：仪表盘接真数据 + 自动锁定时限落库（2026-09-06）

两件事，共同点是**它们不是功能缺失，是软件在对用户撒谎**：首屏的假数字，以及一个
选了「立即」却等 60 秒的安全开关。

**仪表盘六块卡不再有假数字。** 三块接真数据（余额 / 内容计数 / 健康分布），另三块给
**空态**（需要处理算得出来但目前必然为空；上次探测在 M5；备份在 M9）。余额明细页也接上了。

- **聚合全部走 `ui/shell/UiMapping.kt` 里的纯函数**（`contentCountsOf` / `healthBreakdownOf` /
  `balanceSummaryOf` / `attentionItemsOf`），仪表盘与管理页用同一批。两处各算一遍的代价很具体：
  首屏说 10 把、管理页加起来是 9 把，而两个数字都「看起来对」。
- **健康分布只算已启用的密钥**，因为计数卡那条聚合 SQL 带了 `enabled = 1`。不一致的表现是
  同一屏里「密钥 1」与「2 张全部可用」同时出现。
- **余额有三种状态，不是两种**（§9.3）：有金额（含 0）/ 试过但失败 / 压根没配置。
  `UiProviderRow` 因此除了 `balance: UiMoney?` 还有 `balanceFailed`——只看前者为 null 的话，
  一个刚建好、没开余额查询的供应商会被列到「查询失败」下面（这个错真的在设备上出现过一次，
  是逐屏核对时抓到的）。
- **需要处理只读 `health`**（红线 11），而且「额度不足」与「余额低于阈值」去重成一行：
  两条路径指向同一件事。全部 `UNKNOWN` 时这一卡是空的——「还没探测过」不是要处理的问题。
- **没实现的动作不画按钮**：`DashboardUiState.canProbe` / `canRefreshBalance` 当时是 false，
  于是「开始探测」与余额刷新图标根本不渲染。一个点下去什么都不会发生的按钮和假数字是同一类
  问题。探测引擎（M5）与余额引擎（M7）接入后这两个字段恒为 true，**已于 2026-09-06 连同
  `BalanceCard.canRefresh` 参数一起删掉**（当时注释就写着"两个都为 true 之后一起删"）。
  副作用：`ProbeRunRoute` 目前从界面上进不来（入口只在有过一轮探测时才画），所以那一页
  顺手加了空态。
- **需要本地化的东西不在 UiState 里拼好**（红线 19）：余额的「更新于」给时间戳，
  「需要处理」给 `AttentionKind` 枚举，文案由 `ui/common` 的 `messageOf` 统一给（红线 17）。
- 顺手补的两个洞：`relativeLabel(now, then)` 现在是相对时间的**唯一入口**，以前直接调
  `relativeTimeLabel` 而不传 `absoluteLabel`，于是超过 30 天的时间静静地渲染成空串；
  `ApiKeyRepository.observeAll()` 一条订阅取代了 `ManageViewModel` 里那个按家 combine 的 N+1。

**自动锁定时限落库了**，`SettingsRepository` 因此落地——**但它只有这一项**。
七个设置页其余那些仍然是内存态 `SettingsDraft`：它们大半还没有消费方，先落库只会得到
一批「存下来了但没人读」的键（红线 16）。后来（§7.4 收尾）前台空闲锁定与屏幕关闭即锁定
两项也有了消费方、从 `SettingsDraft` 迁到 `app_settings`，见下方 M1 之后的「前台空闲 / 屏幕
关闭锁定」小节。

- **存的是秒数，不是下拉的下标**（`app_settings.autoLockSeconds`）。存下标的代价是
  「以后在中间插一档」会让所有已存的设置悄悄改变含义，而没有任何迁移能发现它。
- **「从不」是显式的一档**（`AutoLockTimeout.Never`），不用 `null` 也不用 `-1`：
  哨兵值在算术里会静默变成「0 秒后锁」或者「永不锁」，而这是个安全设置。
  解不出来的存储值一律回默认档（失败往安全那一侧倒）。
- **订阅在 `TokenVaultApp` 里，不在设置页的 ViewModel 里。** 那个 ViewModel 只在用户站在
  那一页时活着，接在它上面的表现是「设成立即 → 退出设置 → 切后台」又退回 60 秒。
  `AutoLocker.timeout` 因此是个 `@Volatile` 字段，写入方只有那一条进程级订阅。
- `RoomSettingsRepository` 订阅**整张表**再挑那一个键，所以后面必须跟 `distinctUntilChanged`：
  不跟的表现是每改一次别的开关，`AutoLocker` 就被重设一次时限。

**这一步的设备验证**（2026-09-06，逐屏 `uiautomator dump`）：仪表盘读出 1 家 / 1 把 /
0 模型 / 0 账号，与管理页的「0 / 1 张可用」对得上；余额明细页给的是空态而不是「查询失败」；
下拉选一轮后库里依次是 `30` / `0` / `never` / `300` / `60`（秒数，不是下标）；选「立即」后
按 HOME、3 秒后回到前台是锁屏；选「从不」后后台 **75 秒**回来仍然解锁（旧行为会在 60 秒处
锁掉，所以这一条才是决定性的）；两次前后 `pidof` 同一个 PID，所以不是进程被杀之后的假阳性；
选 5 分钟后 `am force-stop` 再进去，下拉仍是 5 分钟。

下一步：M5 探测引擎的**接线半拉**（`ProbeEngine` @Singleton 宿主 + VaultSession 取消桥接 +
逐项落库 + Hilt 绑定 + 仪表盘触发），然后是 M6 客户端伪装（L1+L2 已就绪，M6 补嗅探与预设）。
里程碑表见 `old_plan.md` §16。

**详情页模型与平台账号接真数据（2026-09-06 补）**：M4 文本导入会写模型与账号（`ImportWriter`
已接 `ModelRepository` / `ProviderAccountRepository`），但详情页此前硬编码 `emptyList()`——
用户导入的模型 / 账号在详情页看不到，是"存进去了但没地方看"（红线 16）。补法：
- `ProviderAccountRepository` 加 `revealUsername(id): CharArray?`（`RoomProviderAccountRepository`
  实现，`cipher.open(usernameEnc, aadUsername(id))`，null = 没记用户名）。账号用户名也加密
  （红线 21），遮蔽串要解密现算，与密钥同一套逻辑。
- `ProviderDetailViewModel` 注入 `ModelRepository` + `ProviderAccountRepository`，`state` 从
  3 流 combine 扩成 6 流——超 `kotlinx.coroutines` 的 5 流类型化上限，拆成内层 4 流（provider /
  keys / models / accounts）`combine` 成 `DetailData`，外层再接 `masks` / `accountMasks` 两个
  遮蔽串缓存（§9.2 同款拆法）。账号遮蔽串走独立 `accountMasks` StateFlow + 重算协程。
- `UiMapping.kt` 加 `AiModel.toRow()`（`probeState → UiHealth`：NOT_FOUND→Error、NO_ACCESS /
  ERROR→Warn）与 `ProviderAccount.toRow(maskedUsername)`。
- 模型行 / 账号行的 `onClick` 仍是空实现——那是 M5 模型管理（L3 试一下/预热，要钱）与 M6
  账号管理（展开看密码、编辑、删除）的范围，本补丁只解决"数据可见"，不碰增删改。

### M4：文本导入（2026-09-06）

粘贴 → 预览 → 确认，整条路通了。解析器在 `importer/`（纯 Kotlin，测试 8 全绿），
落库编排在 `data/repo/ImportWriter.kt`，UI 在 `ImportViewModel` + `ImportScreen`。

- **解析器 `TextImporter` 是纯函数**，`parse(text) → ParseResult(records, errors)`。
  输出 [ParsedRecord]（含 `keys` / `accounts` / `models` 子项 + `issues`），
  秘密字段（API Key / 访问令牌 / 账号 / 密码）是 `CharArray`（红线 1），非秘密用 `String`。
  单测喂 `app/src/test/resources/sample_import.txt`（脱敏 fixture，与真实 `示例数据.md`
  逐字节等价）。
- **块字段终止规则**（§11.1 第 1–5 条）写死在状态机里：`支持端点类型` 后面隔空行、
  `模型列表` 紧跟字段名、最后一条无 `---` 结尾（EOF 是合法终止）三条都有用例。
  字段名识别用**前缀匹配**（`fieldNameOf`）而不是正则——字段名里有空格（`API Key`），
  括号说明可能是半角 `(…)` 也可能是全角 `（…）`。
- **字段名是导入格式的协议，不是 UI 文案**，所以 `TextImporter` / `TextExporter` 里的
  中文字面量（`供应商名称` / `备注` / `官方接口` / 密钥 label `主号` / `备用 N`）都逐行
  打了 `// i18n-exempt` 标记（与 `Protocol.fromAlias` 的 `ALIAS_NOISE` 同一个理由：把它们
  搬进 strings.xml 会跟着界面语言变，于是"英文用户粘贴中文数据"就解析不出来）。
- **落库编排 `ImportWriter`** 把一条记录写成：供应商 → 密钥（第一张默认，靠
  `ApiKeyRepository.add` 的不变量）→ 账号 → 模型，整批在 `TransactionRunner` 里。
  账号的明文走 `ProviderAccountRepository.add`，密钥走 `ApiKeyRepository.add`，
  各自内部都有"两步写"（AAD 绑主键，红线 24）。
- **新增两个仓库接口**：`ProviderAccountRepository` / `ModelRepository`（domain 层），
  各自只有导入需要的 `add` + `observeByProvider`。它们本属 M6 / M5，这里为了导入落地
  先建了最小版。`ProviderAccountDao` 因此补了 `setUsername` / `setPassword` 两个回填方法
  （两步写，不复用 `@Update` 整行替换）。
- **反向导出 `TextExporter`**（§11.3）是纯函数，`exportProvider(...)` 拼出同格式文本；
  遮蔽 / 明文的选择由调用方决定（调用方负责 `reveal` + `SecretMask.of`）。测试断言**往返**：
  导出再导入能解析回同样的东西。明文导出时用 `PLAINTEXT_WARNING` 插警告行。
  **UI 的 SAF 保存入口还没做**（§11.3 是可砍项，§14.4），核心验收"粘贴导入"已闭环。
- `SecureClipboard` 补了 `read()`（「从剪贴板填充」用），`AppTextFieldState` 补了
  `setText()`（把剪贴板文本填进输入框，走 `setTextAndPlaceCursorAtEnd` 而非重建 state）。

单测 290 个全绿（1 个 spike 按设计跳过）；`lint` 0 error / 23 warning；`assembleDebug` 通过。
**ADB 端到端还没跑**（本机当前没有连接设备）：`old_plan.md` §16 里 M4 的验收是"ADB 流程里把
真实 `示例数据.md` 一次粘贴成功"，这一步要插设备。数据层的正确性已经由 290 个单测覆盖
（含 ImportWriter 用真 `VaultSession` + `SecretBox` 的端到端加密往返）。

### M5：探测引擎（2026-09-06，只做 L1+L2）

验收 = 测试 2 / 3 / 12 / 14 全绿，**四项全绿**。核心是三个纯 Kotlin 包 + 一个 net 层：

- **`endpoint/` 三协议请求构造**（测试 2）：`ProbeRequest` / `ProbeResponse` 纯数据，
  `ProbeRequestBuilder` 纯函数出 CHAT / RESPONSES / ANTHROPIC 的 method / URL / 头 / body。
  `max_tokens = 16`（§5.2，只保证被接受、不保证拿到内容，红线 34）；CHAT/RESPONSES 用
  `Authorization: Bearer`，ANTHROPIC 用 `x-api-key` + `anthropic-version`（红线 22 后半句）。
  无密钥时基线检测用 `Bearer yj-probe-invalid`（不是空头）。`MockWebServer` 验证真实报文。
- **`probe/ProbeClassifier`**（测试 3）：`classify(status, body, error, level)` 单函数，
  严格按 §8.4 矩阵短路。四条原则都落了地：额度关键词优先于状态码、客户端关键词排在 401
  之前（红线 33）、L2 遇 400 判 OK 而非 CONFIG_ERROR、body 空/纯文本不因解析失败升级
  （红线 35）。用例直接读 `fixtures/probe-matrix.json` 16 条真实响应。
  **关键词匹配在"小写 + 去空白"后的文本上做，关键词本身也要去空白**——否则
  `unauthorized client`（含空格）在去空白的 body 里永远匹配不上（这是个实测会踩的坑）。
- **`probe/ModelMerger`**（测试 12）：三路合并纯函数，返回 `ModelMergePlan`（insert/touch/
  disable 三个动作列表）。`discoveredVia == 本协议` 才停用（红线 30），manual 行永不动（红线 13）。
- **`probe/ProbeOrchestrator`**（测试 14）：编排纯逻辑，依赖 `ProbeTransport`（发请求抽象）。
  逐项推流 `Flow<ProbeItemResult>`、取消即停、总预算超时、每 host 请求预算（`12 + 密钥数 +
  模型数`）、429 后停该 host 后续请求（红线 29）、host 间隔。`runTest` 虚拟时间全绿。
- **`net/OkHttpEngine` + `HostGate`**：单例 OkHttpClient（connect 8s / read 20s / call 35s /
  dispatcher 8 & per-host 3），host 门闸默认 800ms、429 加倍封顶 8s。`http://` 必须
  `allowInsecure` 才放行（§7.5）。首字节延迟用 per-call `EventListener`。
  **MockWebServer 用 `mockwebserver3`（OkHttp 5.x），API 与旧版不同**：`MockResponse.Builder()
  .code(n).body(s)`、`server.close()`（不是 shutdown）、`RecordedRequest.requestLine`。

**还没做（M5 的"接线"半拉）**：`ProbeEngine`（`@Singleton` 宿主，`StateFlow<ProbeProgress>`、
`VaultSession` 锁定时取消、逐项落库 DAO）、Hilt 绑定、仪表盘 `onStartProbe` 触发、`probe_runs`
落库。目前 `ProbeOrchestrator` 是纯逻辑，UI 的 `onStartProbe` 还是空实现（`VaultNavHost.kt:91`）。

单测 328 个全绿（1 个 spike 按设计跳过）；`lint` 0 error / 23 warning；`assembleDebug` 通过。

**仅重试失败项（2026-09-06 补）**：明细页 `onRetryFailed` 从空实现接成真动作。`ProbeEngine`
加 `retryFailed()`——把上一轮 `lastRound` 里 `outcome != SUCCESS` 的 `taskId` 提出来当过滤条件，
复用 `runRound` 重跑（`startScoped(runScope, filter)` 抽出二级过滤，`start()` 恒 true、重试只留
失败项，`probe_runs.scope` 分别记 `"all"` / `"retry"`）。过滤发生在 `ProbePlan` 产出的骨架任务上，
所以 `probeEnabled = 0` 的供应商、reveal 失败的 Key 依旧会被 `ProbePlanBuilder` / `toTask` 挡掉——
重试不会绕过总闸（§8.6）。`ProbeRunViewModel` 暴露 `retryFailed()`，`VaultNavHost` 接 `vm::retryFailed`。

**探测触发点（2026-09-06 补）**：详情页「探测这一家」与 Key 行长按「单 Key 探测」两个手动入口。
`ProbeEngine` 加 `probeProvider(providerId)` / `probeKey(keyId)`，同样是 `startScoped` 的薄封装
（`runScope` 分别记 `"provider:<id>"` / `"key:<id>"`，filter 匹配 `providerId` / `keyId`）。只发
L1+L2（零成本，红线 36），单家/单 Key 都不绕过 `ProbePlanBuilder` 的总闸。详情页 HeaderCard 加
「探测这一家」按钮；Key 行长按触发单 Key 探测（`AppCard.onLongPress` 已有透传），密钥区标题下
给一行 footnote 提示长按手势（否则是隐藏入口）。`ProviderDetailViewModel` 注入 `ProbeEngine`。
**模型三路合并落库仍没做**：它依赖 L3 模型探测（发推理调用、要钱、红线 36 仅手动），而 L3
本身还没实现，`ModelMerger` 的写入侧没有触发源，不能单独落（否则是红线 16 的"没产生路径的字段"）。


### M6：客户端伪装（2026-09-06，纯函数层 + 预设 UI + 自动嗅探）

验收 = 测试 9（cURL 解析）/ 10（头部组装）/ 嗅探顺序全绿，四项全绿。**整条链路没有一处硬编码
UA 或特征头**（红线 22）——预设是数据不是代码。

- **`endpoint/HeaderAssembler`**（测试 10）：纯函数按 §8.2 顺序组装——基础头 → 预设头按序覆盖
  （UA 取预设值）→ **鉴权头最后加且预设不得覆盖**（`Authorization` / `x-api-key` /
  `anthropic-version` 出现即忽略并记 warn，红线 22）→ 占位符展开。占位符值（`app_version` /
  `android_release` / `arch`）由 `@AppPlaceholders` 注入（红线 20），`{uuid}` / `{random_hex:N}`
  现算。`mergeBodyPatch` 按 RFC 7386 合并，`null` 值删键；解析失败原样返回不抛（红线 8）。
- **`importer/CurlParser`**（测试 9）：续行 / 引号 / `-H` / `-A` / `--data-raw`；自动剔除
  `Authorization` / `cookie` / `content-length` / `host`，剔除清单回传给预览页告知。
- **`data/seed/ProfileSeeder` + `BuiltinPresets`**：幂等种入 8 个内置预设，按 `builtinRev`
  刷新**未被用户改过**（`userEdited == false`）的条目；自定义预设 `sortOrder = max + 1`。
- **预设 UI**：设置 → 客户端预设（列表 / 编辑 / cURL 导入）接真数据，走 `ProfileListViewModel` +
  `ProfileEditorViewModel` + `ProfileEditorScreen`；内置预设可编辑不可删（`delete()` 对
  `builtinKey` 非空 no-op）。**供应商编辑页的「客户端预设」下拉接真数据并落库**——
  `Provider.toDraft` / `ProviderDraft.toProvider` 的 `clientProfileId` 映射集中在一处，
  下标 0 = 默认（显式 null）、越界 = 保留原值（与 groupId 同一套理由）。
- **自动嗅探**（`probe/SniffPlan.kt` + `engine/ProbeEngine.trySniff`）：`CLIENT_BLOCKED` 时先换
  鉴权头（Bearer ↔ x-api-key，一次请求）→ 再按序试最多 4 个内置预设（**匹配本协议的排前、
  不匹配的也要试**，「适用协议」是排序提示不是硬过滤，§8.2 M0.5 实测）。命中写回
  `clientProfileId` + 置 `verified`，或写回 `authStyle`；**本轮该 host 出现过 429 立即停**
  （红线 29，`rateLimitedHosts` 与编排器各自记一份）。
- **`ProbeTask.headers` 语义变更**：从"鉴权头"扩成"最终完整头，已由引擎用 `HeaderAssembler`
  组好"——这样 `PlannedTask` 骨架仍然不含密钥明文（纯 Kotlin 包不碰 reveal，§6.1 推论 3），
  引擎在发请求前才 reveal、组头、`zeroize`。

单测 381 个全绿（1 个 spike 按设计跳过）；`lint` 0 error / 24 warning；`assembleDebug` 通过。
**还没做**：设备端到端（Agent Router 三条路径——默认 UA 被 401 拦 → 换 `claude_code` 转可用 →
换 `x-api-key` 也绕过；JustDoWork 403 空 body 给"上游没说原因"；全试过仍被拦给"TLS 指纹"
终止结论）。这一步要插设备、要花真实额度，`示例数据.md` 每轮只勾一家。

**拦截关键词设置（2026-09-06 补）**：`CLIENT_BLOCKED` 识别关键词从硬编码 `CLIENT_KEYWORDS`
接成 `app_settings` 可编辑项。`ProbeClassifier.classify` 增加 `clientKeywords` 参数（默认
`DEFAULT_CLIENT_KEYWORDS`）；`ProbeOrchestrator` 增 `clientKeywords` 构造参数；`ProbeEngine`
注入 `SettingsRepository` 读 `observeClientKeywords()`，传给编排器与 `trySniff`。
`SettingsRepository` 加 `observeClientKeywords` / `setClientKeywords`（JSON 数组存 `value`，
键 `clientKeywords`）。新增 `ClientKeywordsRoute` + `ClientKeywordsViewModel` +
`ClientKeywordsScreen`（增删关键词，点保存才写库——内存副本，中途退出不落半截改动）。
设备上已验证：删 `unauthorized client` + 加 `my custom phrase` → 保存 → 落库 JSON 数组正确。

### M7：余额（2026-09-06，纯逻辑层 + 引擎 + UI 接线）

- **`balance/` 八个文件**：`BalanceAdapter` 接口 + `BalanceSnapshot` + `FormatMoney`
  （`BigDecimal.setScale(2, HALF_UP)`，先舍入再相加）；六个内置适配器——`NewApiAdapter` /
  `DeepSeekAdapter` / `OpenRouterAdapter` / `SimpleBalanceAdapter`（SiliconFlow + Moonshot）/
  `CustomJsonAdapter`，外加 `none`。`BalanceRegistry.forProvider` 按 `balanceKind` 选适配器。
- **`NewApiAdapter` 先校准换算比**：解析前试读 `/api/status` 的 `quota_per_unit`，校准失败
  不致命——用默认值继续但 `quotaCalibrated` 保持 0，UI 据此标"换算比未校准"（§9.2）。
  绝不截断响应体（Agent Router 那份 5.4 KB 的字段排在公告之后）。
- **`engine/BalanceEngine`**（`@Singleton`）：单家点一下查一次，与 `ProbeEngine` 分开（生命周期
  与取消语义不同）。解出鉴权材料（令牌或默认 Key）→ 构造请求 → 执行 → 解析 → 落库；
  失败（网络/解析）也落一条 `error`，让 UI 区分"查询失败"与"余额为 0"（§9.3）。
  明文最短存活，`finally` 里 `zeroize`。余额响应体**绝不原样进日志**（红线 32）。
- **UI 接线**：`DashboardViewModel.refreshBalance` 逐家刷、经 `observeSummaries` 自然流回
  （红线 10）；余额明细页按 `balance != null` 与 `balanceFailed` 分两组（三种状态，不是两种）。
- 测试 4/5 相关用例在 `BalanceAdapterTest`（13 个用例，含 newapi 校准、缺字段不整条失败、
  FormatMoney 舍入）。

**还没做**：设备端到端（真实余额数字与后台对得上），以及"每个适配器贴一份真实响应样例"
（§4.3 第 6 步——贴不出来就该删预设，这一步要真实额度）。

**阈值设置（2026-09-06 补）**：余额低额阈值从硬编码 `DEFAULT_THRESHOLDS` 接成
`app_settings` 可编辑项。`SettingsRepository` 加 `observeBalanceThresholds` /
`setBalanceThresholds`（JSON 存 `value`，键 `balanceThresholds`，读方向单向容错）；
`DashboardViewModel` 从 `DEFAULT_THRESHOLDS` 改为订阅仓库（红线 10）；新增
`BalanceThresholdsRoute` 二级页 + `BalanceThresholdsViewModel` / `BalanceThresholdsScreen`，
编辑 USD / CNY 两个币种。初值仍是 `DEFAULT_THRESHOLDS`（红线 15：有名字有出处的领域常量）。
设备上已验证：改 10/100 → 保存落库 → 重进读到 10/100（曾踩一个坑——`remember` 初值在
Room 首帧异步返回前就用默认 5/30 建了输入框，真实值到了也不更新，改 ViewModel 初始值
为 null、读到非空才建输入框后修复）。

**手动代理设置（2026-09-06 补）**：手动 HTTP 代理从无到有接成 `app_settings` 可编辑项。
`SettingsRepository` 加 `observeProxy` / `setProxy`（纯字符串存 `value`，键 `httpProxy`）；
`net/OkHttpEngine` 增 `proxyProvider: () -> Proxy?` 构造参数，在每次请求的
`client.newBuilder().proxy(...)` 上按需套用（`OkHttpClient.proxy` 是构造期属性，不能改
单例，只能 per-request 重建）；新增 `net/ProxyProvider`（`@Singleton`，订阅 `observeProxy`、
把解析结果缓进 `AtomicReference<Proxy?>`，供 `proxy::current` 引用）。`parseProxy` 是
companion 里的纯函数（`host:port` → `Proxy`），支持 IPv6 方括号 `[::1]:8080`、缺端口默认 80、
空串/空白/`://`/纯冒号等非法串返回 null（走系统代理）。新增 `ProxyRoute` 二级页 +
`ProxyViewModel`（粗校验，拒绝 `://`、`/`、空格）/ `ProxyScreen`（单个 `host:port` 输入框）。

### M8：模型元数据（可砍，纯逻辑层已做，WorkManager 拉取未做）

- **`catalog/`**：`ModelCatalogMatcher` 三级匹配（精确 key → 精确 modelId → 归一化 id）全部
  走索引，结果写回 `models.catalogKey`；`CatalogNormalize` 归一化。`ModelCatalogDao` 有
  `findByKey` / `findByModelId` / `findByNormId` / `upsertAll` / `clear`。测试 9 个用例。
- **没做**：WorkManager 拉取 `api.json`（4.46 MB）那一半——`TokenVaultApp` 注释明确
  "现在还没有 Worker"，`DataScreen` 的"同步元数据"入口是空实现（`onSyncCatalog = {}`）。
  这是 §4.4 的第 1–3 步（分块解析、每 200 行一个事务、TTL 7 天 + 仅 UNMETERED 自动更新）。
  `model_catalog` 表已建、DAO 已建，差的是拉取与入库那一步。**可砍**：不影响核心四件事。

### M9：备份与同步（2026-09-06，编解码 + 引擎 + SAF 接线）

- **`backup/`**：`BackupCodec`（`magic ‖ headerLen ‖ header(明文 JSON) ‖ payload(AES-256-GCM)`，
  AAD 绑 header 原始字节）、`BackupHeader` / `BackupPayload`。测试 7 个用例。
- **`engine/BackupEngine`**（643 行）：导出/恢复编排。三条硬规矩落地——跨表引用用自然键
  （分组名 / `builtinKey` / 指纹重算，红线 27）、明文只在导出这一瞬出现（恢复端用自己 DEK
  重加密）、**不搬探测结果**（`health` / `lastOutcome` / `checkedAt` / `probeState` 导出时
  就不写，红线 28）。恢复三种模式（覆盖/合并/仅新增，默认合并），整过程单事务。
- **SAF 接线**（`VaultNavHost.SyncRouteContent`）：`CreateDocument` / `OpenDocument`、
  口令用 `AppSecretTextField`、用完即擦、恢复模式选择、事件走 Snackbar。
  M4 留下的 SAF 导出入口也一并填掉。
- **appSettings 白名单**显式含 `themeMode` / `localeTag`（权威存储在 boot，boot 不进备份）。
- 测试：`BackupCodecTest` 7 + `BackupPayloadTest` + `BackupEngineTest` 5，含往返断言。

**还没做**：第二台设备真机恢复验证（分组与客户端预设必须对得上，红线 27）；WebDAV 半边
（可砍，`onWebDav = {}` 空实现）。

### M10：打磨与发布（2026-09-06，发布链路已落地）

- **`audit_log` 持久化 + 日志页**：`AuditLogDao` 有 `observeRecent` / `trimToCount` /
  `trimOlderThan`（条数 + 天数双重上限），`LogScreen` + `LogViewModel` 接真数据；
  `DataScreen` 有清日志入口。
- **README.md**：功能、隐私声明、**如实写明 6 位 PIN 挡不住离线穷举**（§7.6），以及
  "元数据明文"的诚实边界。
- **发布链路**（`.github/workflows/ci.yml`）：单测 + lint + 仪器编译 + `assembleNightly`
  合并成一次 gradlew 调用；keystore 以 base64 存在 secret `VAULT_RELEASE_KEYSTORE_BASE64`，
  workflow 解码后设 `VAULT_RELEASE_STORE_FILE`；nightly 预发布走 `nightly-build` tag
  （仓库规则禁止建 `nightly` tag，GH013）。这一串 CI 迭代了 12 个提交才调通。

**还没做**：搜索/排序/批量操作；宽屏双栏（可砍）；无障碍 TalkBack 走通主路径；
更新页接 GitHub Releases API（`onCheckNow = {}` 空实现）。

**搜索 / 排序 / 批量（2026-09-06 补）**：管理页三项落地。搜索与排序是纯函数
（`ui/shell/UiMapping.kt` 的 `matchesQuery` / `sortProviders`，可 JVM 单测）：搜名称/备注/
host/分组名（**不搜加密列**，红线 3 推论），匹配是"小写 + 去空白"后的子串包含（与
`ProbeClassifier` 同一套约定）；排序四档（手动 `sortOrder` / 名称 / 余额 / 最近探测）。
余额档按币种字母序分块、块内金额降序（异币种不比金额，§9.3），最近探测用该家密钥
`checkedAt` 的最大值。`ManageUiState` 加 `sort` / `selection`；`ManageViewModel` 用两层
`combine`（`Snapshot` 数据三流 + `Controls` 五个 UI 态流）避免单个 combine 塞 8 个流丢类型。
多选：`AppCard` 增 `onLongPress` 透传（MIUIX `Card` 本就有）、长按进多选、顶栏换全选/退出、
FAB 换删除、底部"改分组"条；批量删除二次确认（文案写清连带删密钥/模型/账号），
批量改分组走已有的 `ProviderRepository.setGroup`。新增 `appOnPrimaryColor` 颜色出口
（`SelectionMark` 对勾用，页面不 import MIUIX）。

### 全局收尾状态（2026-09-06）

单测 **425 个**全绿（1 个 spike 按设计跳过）；`lint` 0 error；`assembleDebug` / `assembleNightly`
通过；strings 两份各 490 键。**功能层（M0–M10 的代码、测试、UI 接线）已全部落地并提交**
（最后一批提交 `cc8129f` M4–M6 纯逻辑层、`89b9c95` M7–M10 UI 接线、其后 12 个 CI 收尾）。

**唯一剩余的工作都是"要设备/要额度/要硬件"的验收，不是功能缺失**：

1. ADB 端到端：**M4 粘贴导入已在设备上跑通**（2026-09-06，输入单家块 → 解析预览
   → 确认 → 落库 → 详情页解出明文 `sk-test1234567890`；含明文弹层 `screencap` 全黑，
   `FLAG_SECURE` 继承正确）。剩 M5/M6/M7 探测与余额（要花真实额度）。
2. 生物识别设备验证（当前设备无指纹硬件）。
3. 额度耗尽的真实响应采集（等 DeepSeek 余额自然耗尽）。
4. 仪器测试（`androidTest/` 现有 `VaultDatabaseTest` 6 个用例，覆盖 `idx_keys_default`
   部分唯一索引、外键 CASCADE 与 SET_NULL；`BootStore` 原子写已由 JVM 单测覆盖。
   还差 `FLAG_SECURE` 的 connectedTest（功能上已验证——M4 详情页明文弹层截屏全黑）、
   Room 迁移测试（当前只有 v1，升 v2 时补）。
5. M8 的 WorkManager 拉取（可砍）。
