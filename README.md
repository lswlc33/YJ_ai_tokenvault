# 元记 — AI Token Vault

集中管理你的 AI API Key、端点与余额。纯本地加密存储，你的密钥 never 离开设备。

## 功能特性

- **加密存储** — AES-256-GCM 加密，PIN + PBKDF2 密钥派生，生物识别快速解锁
- **多厂商管理** — 支持 DeepSeek、OpenAI、Anthropic 等任意 OpenAI 兼容端点
- **一键探测** — 批量检测 Key 有效性、模型列表、余额查询
- **模型元数据** — 自动匹配 [models.dev](https://models.dev) 元数据，展示上下文长度、模态等信息
- **余额追踪** — 实时查询各 Key 余额，仪表盘总览
- **WebDAV 备份** — 支持 WebDAV 同步与本地导入/导出
- **双主题** — Material You 动态取色 + MIUIx 风格
- **操作日志** — 完整记录探测、编辑等操作历史

## 支持平台

| 平台 | 状态 | 说明 |
|------|------|------|
| Windows | ✅ | 桌面端，`sqflite_common_ffi` + `window_manager` |
| Android | ✅ | 移动端，`sqflite` + `local_auth` 生物识别 |

> 不支持 iOS、macOS、Linux、Web。

## 快速开始

### 环境要求

- Flutter SDK ≥ 3.3.0
- [Taskfile](https://taskfile.dev/)（可选，用于构建任务）

### 安装依赖

```bash
flutter pub get
```

### 调试运行

```bash
flutter run -d windows    # Windows
flutter run -d android    # Android
```

### 构建发布版

使用 [Taskfile](https://taskfile.dev/)：

```bash
task build:windows    # → dist/windows/
task build:android    # → dist/android/
task build:all        # 全平台构建
task clean            # 清理构建产物
task analyze          # 静态分析
```

或直接使用 Flutter：

```bash
flutter build windows --release
flutter build apk --release --split-per-abi
```

## 项目结构

```
lib/
├── main.dart                  # 入口
├── app.dart                   # App Shell：主题、路由、生命周期
├── core/                      # 基础设施层
│   ├── auth/                  # 生物识别服务
│   ├── crypto/                # AES-256-GCM 加解密 + PBKDF2
│   ├── db/                    # SQLite 数据库 + DAO
│   ├── log/                   # 日志服务
│   ├── model_metadata/        # 模型元数据匹配（models.dev）
│   ├── probe/                 # API 探测（余额/可用性/模型列表）
│   ├── theme/                 # Material / MIUIx 双主题
│   ├── utils/                 # 工具函数
│   └── webdav/                # WebDAV 备份客户端
├── models/                    # 数据模型（不可变）
├── repositories/              # 数据仓库（业务逻辑编排）
├── state/                     # Riverpod 状态管理
│   ├── lock_controller.dart   # 锁屏控制器
│   ├── providers.dart         # 全局 Provider 注册
│   └── vault_controllers.dart # 金库数据控制器
└── features/                  # UI 页面
    ├── dashboard/             # 首页仪表盘
    ├── detail/                # 厂家详情页
    ├── editor/                # Key 编辑页
    ├── home/                  # 主框架（底部导航）
    ├── lock/                  # 锁屏/解锁页
    ├── log/                   # 操作日志页
    └── settings/              # 设置页
```

## 架构

分层架构：`models` → `repositories` → `state`（Riverpod）→ `features`（UI）

- **models** — 纯数据类，不可变，使用 `copyWith`
- **repositories** — 业务逻辑编排，依赖注入 DAO 和 CryptoService
- **state** — Riverpod `StateNotifier` 管理 UI 状态
- **features** — `ConsumerWidget` 读取状态并渲染

## 安全模型

1. 用户设置 PIN → PBKDF2-HMAC-SHA256（10,000 次迭代）→ 32 字节 vaultKey
2. vaultKey 仅存内存，锁定后立即丢弃
3. API Key 等敏感字段使用 AES-256-GCM 加密存储（nonce 每次随机生成）
4. 生物识别解锁：vaultKey 加密后存本地文件，验证通过后解密还原

## 技术栈

| 类别 | 依赖 |
|------|------|
| 状态管理 | flutter_riverpod |
| 数据库 | sqflite / sqflite_common_ffi |
| 加密 | pointycastle (AES-256-GCM + PBKDF2) |
| 主题 | dynamic_color (Material You) |
| 窗口 | window_manager |
| 生物识别 | local_auth |
| 网络 | dio |
| 备份 | webdav_client, file_picker |

## License

MIT
