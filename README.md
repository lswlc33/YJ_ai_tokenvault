# 元记 · AI Token Vault

本地优先的 AI API 密钥保险库。Android 原生，Kotlin + Jetpack Compose + MIUIX。

你的 API 密钥、中转站访问令牌、平台账号密码都存在**你自己的手机里**，字段级加密，
不经过任何服务器。备份是自包含的加密包，能导到另一台从没见过这台机器的手机上恢复。

---

## 功能

- **三层内容**：供应商（中转站 / 官方端点）→ API 密钥 → 模型列表 + 平台登录账号。
- **字段级加密**：密钥、账号用户名与密码、NewAPI 访问令牌用 AES-256-GCM 加密，
  密钥 DEK 由你的 PIN / 恢复密钥 / 生物识别三选一解锁（Argon2id 包裹）。
- **探测引擎**：连通性 → 密钥有效性 → 逐模型探测，三级外加余额查询。瞬时失败
  （断网 / 429 / 5xx）不改写"这钥匙好不好"的持久结论。
- **客户端伪装**：内置 8 套预设（Claude Code / Codex CLI / OpenAI SDK 等），
  也支持从 cURL 命令导入真实客户端指纹，绕过中转站按客户端特征拦截。
- **余额**：适配 new-api / DeepSeek / OpenRouter / SiliconFlow / Moonshot 与自定义 JSON，
  按币种分列、失败与"余额为 0"分开显示。
- **备份 / 恢复**：自包含加密备份包（AES-256-GCM，AAD 绑定 header），
  恢复支持覆盖 / 合并 / 仅新增三种模式。
- **文本导入 / 导出**：把中转站给的 `示例数据` 文本一次粘贴进来，也能反向导出。

## 隐私声明

- 所有数据只存在本机应用的私有目录，**没有任何联网上传**（除了你自己配的 WebDAV）。
- 密钥与账号密码经 **AES-256-GCM 字段级加密**存储；数据库本体跑在系统 SQLite 上，
  **没有整库加密**——供应商名称、备注、端点、模型列表这些**元数据在应用私有目录里是明文**。
- 探测与余额查询会向**你自己配置的供应商**发起请求；除此之外应用不连接任何服务器。

## 你必须知道的安全边界

**6 位 PIN 挡不住离线穷举。** PIN 的空间只有 10⁶，拿到 boot 文件后一张消费级 GPU
**分钟级**就能扫完——KDF 参数调得再高也只影响倍数，不影响"能不能破"。

所以：

- 设一个**长密码**，而不是 6 位数字 PIN（PIN 是解锁的便捷项，不是安全的边界）。
- 要把备份包放到云盘 / 网盘 / WebDAV 之前，**单独设一个长备份口令**——备份口令默认
  沿用 PIN，也就是同样是分钟级可破。
- 恢复密钥（32 位十六进制 = 128 位熵）是唯一真正抗离线穷举的凭证，抄下来放好。

## 构建

```powershell
.\gradlew.bat :app:testDebugUnitTest   # JVM 单测（主力，几秒）
.\gradlew.bat :app:lint                # Android lint
.\gradlew.bat :app:assembleDebug       # debug APK
.\gradlew.bat :app:installDebug        # 装到已连接设备
```

要求 JDK 17。版本号与工具链见 `AGENTS.md`。

## 项目结构

```
domain/ endpoint/ probe/ balance/ catalog/ importer/ backup/ crypto/
    纯 Kotlin，零 Android 依赖，JVM 单测全覆盖。
net/        OkHttp 实现 + 脱敏拦截器
data/       Room 实体 / DAO / 映射器 / 仓库实现
platform/   Keystore / 生物识别 / 剪贴板 / SAF / 窗口安全
di/         Hilt Module
ui/         Compose UI（miuix 是唯一 MIUIX 入口）
screens/    页面
```

架构与不变量见 `CLAUDE.md`，动手方式见 `AGENTS.md`，完整设计见 `old_plan.md`
（`new_plan.md` 是精简版里程碑表）。

## 许可证

暂无。私有项目。
