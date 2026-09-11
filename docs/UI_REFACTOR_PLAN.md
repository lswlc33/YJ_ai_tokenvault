# 管理页与密钥体系重构计划

## 目标

把「供应商」从配置主体降级为 **Key 合集**，把所有请求、探测、余额与客户端伪装配置下沉到 **每一把 Key**，同时保留一个独立的供应商设置页，用于维护名称、备注、官网、分组、颜色、置顶和官网连通性。

## 设计不变量

1. 供应商只负责组织信息：
   - 名称
   - 备注
   - 官网
   - 分组
   - 颜色
   - 置顶
   - 官网连通性
2. Key 才是请求主体，所有行为配置都挂在 Key 上：
   - Base URL
   - 协议
   - 鉴权头
   - 客户端伪装
   - 路径覆盖
   - 超时
   - 允许 HTTP
   - 余额类型 / 令牌 / 用户 ID
   - 探测权限
3. 取消“默认 Key”概念，改用排序：
   - `sortOrder` 升序 + `id` 升序
   - 只考虑 `enabled = true` 的 Key
   - 排第一的启用 Key 就是当前优先 Key
4. 全局设置页只管软件自身，不承载供应商或 Key 的行为配置。

## 数据层重构

### 1. `providers`

保留字段：

- `id`
- `name`
- `note`
- `websiteUrl`
- `groupId`
- `color`
- `pinned`
- `sortOrder`
- `websiteLatencyMs`
- `websiteCheckedAt`
- `websiteError`
- `createdAt`
- `updatedAt`

移除字段：

- `apiBaseUrl`
- `apiRoot`
- `apiVersion`
- `supportedProtocols`
- `pathOverrides`
- `authStyle`
- `allowInsecure`
- `clientProfileId`
- `balanceKind`
- `balanceBaseUrl`
- `balanceUserId`
- `balanceTokenEnc`
- `balanceConfig`
- `quotaPerUnit`
- `quotaCalibrated`
- `timeoutSeconds`
- `probeEnabled`
- `probeReachability`
- `probeKeyValidity`
- `probeBalance`
- `probeModels`
- `probeModelReachability`
- 旧的余额快照列
- 旧的可达性列

### 2. `api_keys`

新增字段：

- `note`：Key 备注，与 `label` 分离

移除字段：

- `isDefault`

保留字段：

- `id`
- `providerId`
- `label`
- `secretEnc`
- `fingerprint`
- `enabled`
- `health`
- `lastOutcome`
- `healthDetail`
- `httpStatus`
- `latencyMs`
- `checkedAt`
- `okAt`
- 余额快照列
- `sortOrder`
- `createdAt`
- `updatedAt`

### 3. 新表 `key_settings`

一张 Key 一行，保存全部行为配置：

- `keyId`
- `apiBaseUrl`
- `apiRoot`
- `apiVersion`
- `supportedProtocols`
- `pathOverrides`
- `authStyle`
- `allowInsecure`
- `clientProfileId`
- `timeoutSeconds`
- `balanceKind`
- `balanceBaseUrl`
- `balanceUserId`
- `balanceTokenEnc`
- `balanceConfig`
- `quotaPerUnit`
- `quotaCalibrated`
- `probeEnabled`
- `probeReachability`
- `probeKeyValidity`
- `probeBalance`
- `probeModels`
- `probeModelReachability`
- `updatedAt`

### 4. 迁移策略：v2 → v3

1. 建 `key_settings`。
2. 给 `api_keys` 加 `note`。
3. 把 `providers` 上的行为配置复制到该供应商每一把 Key 的 `key_settings`。
4. 把 `providers.isDefault` 对应的 Key 排到最前。
5. 删除 `api_keys.isDefault` 与部分唯一索引。
6. 重建 `providers`，只保留合集字段。
7. 保留网站连通性新列，初始为空。

## 领域层重构

### `Provider`

只保留合集信息与官网连通性。

### `ApiKey`

新增：

- `note`
- `settings: KeySettings`

删除：

- `isDefault`

### `KeySettings`

新增领域模型，包含全部请求与探测配置。

## 仓库与引擎重构

- `ProviderRepository`
  - 只保存合集信息
  - 新增 `checkWebsite(providerId)`
  - 保存 / 读取官网连通性
- `ApiKeyRepository`
  - 返回带 `KeySettings` 的 `ApiKey`
  - 删除 `setDefault`
  - 新增 `moveUp` / `moveDown` / `reorder`
  - 保存 Key 备注
  - 保存 Key 设置
- `ProbeEngine`
  - 计划改为按 Key 生成
  - 使用 `key.settings` 决定端点、协议、鉴权、探测权限
- `BalanceEngine`
  - 使用 `key.settings` 决定余额适配器与令牌
- `BackupEngine`
  - 备份格式升级为 v3
  - Key 明细带上 `note` 与 `KeySettings`
  - 旧备份导入时：
    - Provider 配置复制到每把 Key
    - `isDefault = true` 的 Key 排到最前

## UI 重构

### 管理页

- 供应商卡片改成“合集卡片”
- 合集内直接展示前几把 Key
- 搜索范围改为：
  - Key 名称
  - Key 备注
  - 供应商名称
  - 供应商备注
  - host
  - 分组名

### 供应商详情页

- 只显示：
  - 名称
  - 备注
  - 官网
  - 分组
  - 颜色
  - 置顶
  - 官网连通性
  - Key 列表
- Key 列表按排序展示，不显示“默认”角标

### 供应商设置页

只包含：

1. 基本信息
   - 名称
   - 备注
   - 官网
2. 官网连通性
   - 手动检测
   - 延迟
   - HTTP 状态
   - 检测时间
3. 组织与展示
   - 分组
   - 颜色
   - 置顶

### Key 展示页

包含：

1. 密钥遮蔽串 / 显示 / 复制
2. 名称
3. 备注
4. 状态、延迟、最近探测、上次成功
5. 余额
6. 请求行为摘要
   - Base URL
   - 协议
   - 客户端伪装
   - 超时
   - 鉴权头
7. 模型列表
8. 操作
   - 探测这把 Key
   - 上移 / 下移
   - 删除

### Key 设置页

包含：

1. 基础信息
   - 名称
   - 备注
   - 启用
   - 排序
2. 连接与协议
   - Base URL
   - 协议
   - 鉴权头
3. 请求行为
   - 客户端伪装
   - 路径覆盖
   - 超时
   - 允许 HTTP
4. 余额
   - 余额类型
   - 访问令牌
   - 用户 ID
   - 自定义 JSON 配置
5. 探测权限
   - 允许探测这把 Key
   - 可达性
   - 密钥有效性
   - 余额查询
   - 模型列表检测
   - 模型可达性

### 全局设置页

保持现有分组，但只管软件自身：

- 外观
- 安全
- 探测
- 客户端预设
- 数据
- 同步
- 关于
- 更新

## 实施顺序

### 阶段 1：数据与领域模型

1. 建 `KeySettings`
2. 重建 `Provider` / `ApiKey`
3. 建 `key_settings` 表与 DAO
4. 写 v2 → v3 迁移
5. 更新仓库接口与实现
6. 更新备份 / 导入导出模型

### 阶段 2：引擎与业务规则

1. ProbePlan 改为按 Key 生成任务
2. ProbeEngine 改用 `key.settings`
3. BalanceEngine 改用 `key.settings`
4. 删除默认 Key 逻辑
5. 引入排序规则
6. 补充单测与迁移测试

### 阶段 3：UI 重构

1. 管理页改供应商合集 + 内嵌 Key
2. 供应商详情页瘦身
3. 新增供应商设置页
4. 新增 Key 展示页
5. 新增 Key 设置页
6. 全局设置页保持职责不变
7. 更新导航与路由

### 阶段 4：验收

1. `:app:testDebugUnitTest`
2. `:app:lint`
3. `:app:assembleDebug`
4. 真机 / 模拟器手动回归：
   - 新建供应商
   - 新建 Key
   - 编辑 Key 备注
   - 调整 Key 排序
   - 编辑 Key 连接配置
   - 编辑余额配置
   - 编辑探测权限
   - 官网连通性检测
   - 备份 / 恢复
   - 旧数据迁移
