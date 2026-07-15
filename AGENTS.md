# AGENTS.md

## 项目概述

`ai_token_vault`（中文名：元记）是一个 Flutter/Dart 应用，用于集中管理 AI API Key、端点和余额。纯本地加密存储，支持 Windows 和 Android 双平台。

## 支持平台

- **Windows** — 桌面端，使用 `sqflite_common_ffi` + `window_manager`
- **Android** — 移动端，使用 `sqflite` + `local_auth` 生物识别

不支持 iOS、macOS、Linux、Web。不要添加这些平台的代码或配置。

## 目录结构

```
Ai/
├── Taskfile.yml              # 构建任务（task 命令）
├── AGENTS.md                 # 本文件
├── pubspec.yaml              # 依赖声明
├── analysis_options.yaml     # Lint 规则
├── android/                  # Android 平台工程
├── windows/                  # Windows 平台工程
├── build/                    # Flutter 构建中间产物（gitignore）
├── dist/                     # 最终构建产物（gitignore）
│   ├── windows/              # Windows Release 全部运行文件
│   └── android/              # Android APK
└── lib/
    ├── main.dart             # 入口：平台初始化 + ProviderScope
    ├── app.dart              # App Shell：主题、路由、生命周期
    ├── core/                 # 基础设施层
    │   ├── auth/             # 生物识别服务（BiometricService）
    │   ├── crypto/           # AES-256-GCM 加解密 + PBKDF2 密钥派生
    │   ├── db/               # SQLite 数据库 + DAO
    │   ├── log/              # 日志服务
    │   ├── probe/            # API 探测（余额/可用性检测）
    │   ├── theme/            # Material / MIUIx 双主题 + AppStyle 扩展
    │   ├── utils/            # 工具函数
    │   └── webdav/           # WebDAV 备份客户端
    ├── models/               # 数据模型（不可变，纯数据）
    ├── repositories/         # 数据仓库（业务逻辑 + 持久化编排）
    ├── state/                # Riverpod 状态管理
    │   ├── lock_controller.dart   # 锁屏控制器（PIN/生物识别解锁）
    │   ├── providers.dart        # 全局 Provider 注册
    │   └── vault_controllers.dart # 金库数据控制器
    └── features/             # UI 页面
        ├── dashboard/        # 首页仪表盘（Key 概览/余额）
        ├── detail/           # Key 详情页
        ├── editor/           # Key 编辑页
        ├── home/             # 主框架（底部导航 / 侧边栏）
        ├── lock/             # 锁屏/解锁页（PIN Pad + 生物识别）
        ├── log/              # 操作日志页
        └── settings/         # 设置页（主题/安全/探测/备份）
```

## 构建命令

使用 [Taskfile](https://taskfile.dev/) 管理构建任务：

```bash
task build:windows    # 构建 Windows Release → dist/windows/
task build:android    # 构建 Android APK → dist/android/
task build:all        # 依次构建所有平台
task clean            # 清理 build/ 和 dist/
```


## 架构模式

### 分层架构

`models` → `repositories` → `state`（Riverpod）→ `features`（UI）

- **models** — 纯数据类，不可变，使用 `copyWith`
- **repositories** — 业务逻辑编排，依赖注入 DAO 和 CryptoService
- **state** — Riverpod `StateNotifier` 管理 UI 状态
- **features** — `ConsumerWidget` / `ConsumerStatefulWidget` 读取状态并渲染

### 依赖注入

全局 Provider 注册在 `lib/state/providers.dart`：
- `cryptoServiceProvider` — 无状态单例
- `biometricServiceProvider` — 无状态单例
- `databaseProvider` — 异步单例
- `settingsRepositoryProvider` — 异步依赖 db + crypto

### 安全模型

- PIN → PBKDF2-HMAC-SHA256（10,000 次迭代）→ 32 字节 vaultKey
- vaultKey 存内存，锁定后丢弃
- 敏感字段用 AES-256-GCM 加密存储（nonce 每次随机）
- 生物识别解锁：vaultKey 加密后存本地文件，生物识别验证后解密

## 主题系统

双主题：Material You + MIUIx，通过 `AppStyle` ThemeExtension 统一间距/圆角。

- `lib/core/theme/material_theme.dart` — Material 3，支持动态取色
- `lib/core/theme/miuix_theme.dart` — MIUIx 风格，浅灰背景 + 高饱和按钮
- `lib/core/theme/theme_ext.dart` — `AppStyle` 扩展，`context.appStyle` 读取

## 编码规范

- **格式**：两空格缩进，多行 widget 树尾逗号，提交前 `dart format`
- **Lint**：遵循 `flutter_lints` + `prefer_const_constructors` + `prefer_final_locals`
- **命名**：`PascalCase` 类名，`camelCase` 成员，`snake_case.dart` 文件名
- **注释**：不要添加无意义注释，代码应自解释；仅在复杂逻辑处添加必要说明
- **Widget**：优先使用 `const` 构造函数；保持 widget 树可读，拆分过长的 build 方法

## Android 特殊配置

- `MainActivity` 必须继承 `FlutterFragmentActivity`（`local_auth` 生物识别依赖）
- `AndroidManifest.xml` 启用 `android:enableOnBackInvokedCallback="true"`（预见式返回）
- `NormalTheme` 窗口背景使用 `@color/window_surface`（匹配 Material 3 surface 色）

## 安全注意事项

- 不要提交真实的 API Key、PIN、密钥库导出文件或本地数据库
- `lib/core/crypto/`、`lib/core/db/`、`lib/repositories/` 的改动视为安全敏感
- 保持本地加密存储行为，除非变更明确针对备份或同步功能

## Agent 注意事项

- 优先使用 `task` 命令构建，构建产物统一输出到 `dist/`
- 修改加密、迁移或锁屏逻辑前，先检查相关代码的上下文
- 保持小改动，记录使用的验证命令
- 生成的平台文件、构建产物、本地数据库不要纳入源码变更
