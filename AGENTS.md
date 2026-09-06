# AGENTS.md

元记 · AI Token Vault —— Android 原生（Kotlin + Compose + MIUIX）。
完整设计在 `old_plan.md`，架构不变量在 `CLAUDE.md`，这份文件只讲**怎么动手**。

## 常用命令

Windows 用 `.\gradlew.bat`，Git Bash / CI 用 `./gradlew`。

```powershell
.\gradlew.bat :app:testDebugUnitTest          # JVM 单测，主力，几秒
.\gradlew.bat :app:lint                       # Android lint
.\gradlew.bat :app:assembleDebug              # debug APK
.\gradlew.bat :app:installDebug               # 装到已连接设备
.\gradlew.bat :app:connectedDebugAndroidTest  # 仪器测试（需设备）
.\gradlew.bat :app:assembleNightly            # 发布页上的那个 APK（跑 R8）
```

首次克隆后执行一次，把 hook 接上：

```powershell
git config core.hooksPath .githooks
```

`pre-commit` 会拦住 `示例数据.md`、疑似真实密钥的字面量，然后跑
`:app:compileDebugKotlin :app:testDebugUnitTest`。工作中提交可以用
`SKIP_VERIFY=1 git commit ...` 跳过。`commit-msg` 要求提交信息用中文，没有逃生口。

## 工具链

| 项 | 值 | 说明 |
| --- | --- | --- |
| JDK | 17 | |
| Gradle | 9.5.0 | wrapper 走腾讯云镜像 |
| AGP | 9.3.2 | **自带 Kotlin 支持**，所以插件块里没有 `org.jetbrains.kotlin.android` |
| Kotlin | 2.3.21 | 不升 2.4.x：KSP 最新版 2.3.11 是对着 Kotlin 2.3.20 编的 |
| KSP | 2.3.11 | Room + Hilt 都走 KSP |
| SDK | minSdk 33 / compileSdk 37 / targetSdk 37 | `miuix-blur` 要求 33 |
| MIUIX | 0.9.3 | 实验期库，锁死版本 |

版本全部写死在 `gradle/libs.versions.toml`，不用动态版本。升级 MIUIX 要单独开分支
过一遍所有页面（它的 API 可能无预告变更）。

## 代码风格

- 中文注释、中文提交信息。注释解释**为什么**，不解释代码在做什么。
- 用户可见文本一律进 `values/strings.xml`（英文，默认）与
  `values-zh-rCN/strings.xml`（中文），两份键名必须一致。Kotlin 里不留中文字面量，
  `ArchitectureRulesTest` 会检查。
- 页面代码里不出现 `Color(0xFF…)`、不出现裸 `fontSize`：颜色走 `LocalStatusPalette`
  与 MIUIX 的 `colorScheme`，尺寸与排版走 `LocalAppTokens` / `AppTextStyle`。
- 触控目标 ≥ 48dp，不用 `Modifier.scale()` 缩小交互控件。
- 每个提交都要能独立构建。

## MIUIX 使用规范

**只有 `ui/miuix/` 能 `import top.yukonga.miuix`。** 其余地方一律通过这一层的包装：
`AppScaffold` / `AppTopBar` / `AppNavBar` / `AppCard` / `AppArrowRow` / `AppText` /
`AppDialog` / `AppIconButton` / `SectionTitle` / `AppSnackbarHost` / `AppIcon`…

需要一个还没包装的 MIUIX 组件时，先在 `ui/miuix/` 加包装，不要在页面里直接 import。
理由：MIUIX 是实验期库，升级时只改这一层；而且图标、`ScrollBehavior`、
`SnackbarHostState` 这些类型一旦渗到页面里，包装层就名存实亡了。

**`Window*` 系列一律禁用**（`WindowDialog` 等）：它们是独立系统窗口，`FLAG_SECURE`
不继承，而本项目的弹层里就有展示明文密钥和密码的。只用 `Overlay*`。
CI 与单测都会检查。

**分层 Scaffold**：外层 `VaultShell` 只管底栏与 Snackbar，**每个页面自己套一个
`AppScaffold`** 提供 topBar / FAB，顶栏折叠状态用
`rememberAppTopBarScrollState()` + `Modifier.appTopBarScroll(state)` 绑到本页的
可滚动容器上。

## 会联网的本地探针

`ProtocolSpike`（M0.5 协议踩点）会真的打 `示例数据.md` 里那三家中转站、真的消耗额度，
所以它靠 `assumeTrue` **默认跳过**：需要 `-DvaultSpike=true` **且**仓库根存在
`示例数据.md`，两个条件缺一个就自己让开。CI 与 pre-commit 因此不会碰到网络。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ProtocolSpike*" -DvaultSpike=true
```

跑完在 `.local/spike/` 落两份 JSON：`spike-<ts>.json` 是**未脱敏**的原始响应
（`.gitignore` 写死 `.local/`，绝不入库），`spike-<ts>-scrubbed.json` 是脱敏版，
整理 fixture 时读后者。**入库前必须自己再核一遍**——上游会把密钥后 4 位回显在错误消息里，
new-api 的 `/api/user/self` 还会回显访问令牌、邮箱与用户名。

已经采到的结果在 `app/src/test/resources/fixtures/`，结论写进了 `old_plan.md` §16
「M0.5 实测结论」。**不要为了"验证一下"重跑它**：一轮约 30 个请求，上次花掉了
Agent Router 约 $0.5 的免费额度。

## 端到端验收（真实数据，只在本机跑）

`示例数据.md` 里是**真实可用的密钥与访问令牌**，永不入库（`.gitignore` 写死）。
它的用途是模拟器 / 真机上的端到端验收；CI 单测用提交进仓库的脱敏 fixture
（`app/src/test/resources/sample_import.txt`，M4 建立）。

```powershell
adb devices                                   # 确认设备已连接
.\gradlew.bat :app:installDebug
adb shell am start -n com.lc33.tokenvault/.MainActivity
Get-Content -Raw 示例数据.md | Set-Clipboard    # 再在应用里用"从剪贴板填充"
adb logcat -s TokenVault                      # 看探测与余额的日志
```

## 发布签名

keystore 与口令都不进仓库。四个值来自环境变量，或本地
`~/.android/tokenvault-release.properties`（键名 `storeFile` / `storePassword` /
`keyAlias` / `keyPassword`）：

| 环境变量 | 含义 |
| --- | --- |
| `VAULT_RELEASE_STORE_FILE` | keystore 路径，相对仓库根 |
| `VAULT_RELEASE_STORE_PASSWORD` | keystore 口令 |
| `VAULT_RELEASE_KEY_ALIAS` | 密钥别名 |
| `VAULT_RELEASE_KEY_PASSWORD` | 密钥口令 |

CI 里 keystore 以 base64 存在 secret `VAULT_RELEASE_KEYSTORE_BASE64` 中，
workflow 解码成文件后再设 `VAULT_RELEASE_STORE_FILE`。

四个值缺任意一个就不创建 signingConfig，于是 `assembleRelease` / `assembleNightly`
停在未签名 APK。这个失败是故意的：用别的密钥签出来的包无法覆盖安装用户已有的版本。
