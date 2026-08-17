# 阶段 1 设计文档：项目化创作 + 资产中心

> 版本：v1.0（2026-08-16）
> 范围：把平台从"AI 图片/视频生成工具"升级为"项目化内容生产"，落地项目工作台与资产中心。
> 原则：不拆新微服务；所有新表带 tenant_id；agent_task 改动全部为可空列，兼容存量数据。

---

## 1. 背景与目标

现有底座已具备：多租户、任务编排、模型适配器、RAG、文件存储、API Key。
本阶段目标：让用户围绕一个**项目**持续创作，生成结果沉淀为**可搜索、可复用、可追溯的资产**。

最小闭环：

```
建项目 → 传参考素材/挂知识库 → 生成图片/视频/文案 → 自动归档为资产 → 审核状态流转 → 时间线可追溯
```

---

## 2. 已定决策汇总（本次设计评审拍板项）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 服务拆分 | 阶段 1 不拆新微服务，在 agent-core 内建 `project` / `asset` 领域包，接口边界干净，复杂度达标后再拆 |
| 2 | 资产元数据存放 | asset 表放 agent_platform 主库；storage-service 只存 blob，asset.file_id 引用 storage_file.id，不做外键 |
| 3 | asset_version 表 | **砍掉**，用 `parent_asset_id` 派生链 + `derivation_type` 表达版本语义；修订=EDIT 边，UI 默认展示链上最新 EDIT 结果 |
| 4 | 重试/抽卡语义 | **兄弟节点**：REGEN/VARIANT 均挂同一 parent，不链式下挂 |
| 5 | 项目归属 | asset.project_id 可空，空=租户级素材库；跨项目复用第一阶段只做**引用**，不做拷贝 |
| 6 | TEXT 资产 | 第一等公民：asset.content_text 可空列，文案生成结果入库可搜索复用 |
| 7 | 删除语义 | 软删除（deleted 列 + @TableLogic），链不剪断，UI 显示"原素材已删除"占位，审计页可见全链 |
| 8 | 项目成员体系 | **完整成员体系**：project_member 表为唯一事实源（project 表无 owner_id 列），角色 OWNER/EDITOR/REVIEWER/VIEWER |
| 9 | 管理员旁路 | SUPER_ADMIN / TENANT_ADMIN 对租户内所有项目拥有**全部权限**（查看+管理），用于接管/转移/审计 |
| 10 | 权限正交 | 平台动作只看租户角色（user_tenant.role_code）；项目动作只看 project_member.role_code；互不放大 |
| 11 | 硬性不变量 | ① 成员必须先存在于该租户 user_tenant；② 任何时刻项目 ≥1 名 OWNER（最后一名不可移除/降级） |
| 12 | 批次概念 | 提前到阶段 1：agent_task.batch_group_id（可空），批量任务同组，工作台按组聚合；不做工作流引擎 |
| 13 | 成本记录 | 阶段 1 即记录：agent_task.cost 可空列，任务完成时按 model_capability 定价估算写入；账本/对账留阶段 2 |
| 14 | 审核最小形态 | 阶段 1 只做 asset.status（DRAFT/UNDER_REVIEW/APPROVED/REJECTED）+ 待审核列表页；评论、审批流留阶段 2 |
| 15 | 创作入口 | 全局创作页与项目内创作并存，主推项目内进入（自动带 projectId/简报/参考素材）；全局结果弹"归档到项目" |
| 16 | 知识库挂载 | project_dataset 映射表，创作助手检索限定项目挂载数据集 |
| 17 | 模型路由 | 阶段 1 只做 model_capability 能力目录 + 质量档位（FAST/STANDARD/HIGH_QUALITY），熔断/降级/自动路由留阶段 3 |
| 18 | 前端菜单 | 只新增"项目""资产"两个一级入口，其余菜单维持原样，跑通后再做 IA 大重排 |

---

## 3. 数据库设计

- 新表全部落在 **agent_platform** 库（与 agent_task 同库）。
- ID 用雪花算法（MyBatis-Plus ASSIGN_ID），与现有实体一致。
- **执行环境：Docker MySQL 3307**（本机 3306 是无关实例，严禁连错）。

### 3.1 新表 DDL

```sql
-- ============ 项目 ============
CREATE TABLE IF NOT EXISTS project (
    id              VARCHAR(32)  PRIMARY KEY,
    tenant_id       VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    cover_asset_id  VARCHAR(32)  DEFAULT NULL COMMENT '封面资产，引用 asset.id',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|ARCHIVED',
    deadline        DATETIME     DEFAULT NULL,
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant (tenant_id),
    INDEX idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目';

-- 项目成员：唯一事实源（project 表故意不设 owner_id 列，所有权=OWNER 角色行）
CREATE TABLE IF NOT EXISTS project_member (
    id          VARCHAR(32) PRIMARY KEY,
    project_id  VARCHAR(32) NOT NULL,
    user_id     VARCHAR(32) NOT NULL,
    role_code   VARCHAR(16) NOT NULL COMMENT 'OWNER|EDITOR|REVIEWER|VIEWER',
    created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_user (project_id, user_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员';

-- 创作简报：每项目一份
CREATE TABLE IF NOT EXISTS creative_brief (
    id              VARCHAR(32)  PRIMARY KEY,
    project_id      VARCHAR(32)  NOT NULL,
    target_audience VARCHAR(255) DEFAULT '',
    theme           VARCHAR(255) DEFAULT '',
    tone            VARCHAR(64)  DEFAULT '',
    channels        VARCHAR(255) DEFAULT '',
    brand_words     VARCHAR(512) DEFAULT '',
    banned_words    VARCHAR(512) DEFAULT '',
    extra           JSON,
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='创作简报';

-- 项目挂载的知识库（引用 rag_dataset.id）
CREATE TABLE IF NOT EXISTS project_dataset (
    id          VARCHAR(32) PRIMARY KEY,
    project_id  VARCHAR(32) NOT NULL,
    dataset_id  VARCHAR(32) NOT NULL,
    created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_dataset (project_id, dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目知识库挂载';

-- ============ 资产 ============
CREATE TABLE IF NOT EXISTS asset (
    id                VARCHAR(32)  PRIMARY KEY,
    tenant_id         VARCHAR(32)  NOT NULL,
    project_id        VARCHAR(32)  DEFAULT NULL COMMENT 'NULL=租户级素材库',
    parent_asset_id   VARCHAR(32)  DEFAULT NULL COMMENT '派生链：直接来源资产',
    derivation_type   VARCHAR(16)  NOT NULL COMMENT 'UPLOAD|IMPORT|GENERATED|VARIANT|REGEN|EDIT|CONVERT|EXPORT',
    asset_type        VARCHAR(16)  NOT NULL COMMENT 'IMAGE|VIDEO|AUDIO|TEXT|FILE',
    title             VARCHAR(255) DEFAULT '',
    description       TEXT,
    file_id           VARCHAR(32)  DEFAULT NULL COMMENT 'storage_service.storage_file.id，TEXT 资产可为空',
    content_text      TEXT COMMENT 'TEXT 资产正文',
    thumb_file_id     VARCHAR(32)  DEFAULT NULL,
    prompt            TEXT COMMENT '生成提示词快照',
    model_provider    VARCHAR(64)  DEFAULT '',
    model_code        VARCHAR(128) DEFAULT '',
    params            JSON COMMENT '生成参数快照（尺寸/时长/种子等）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|UNDER_REVIEW|APPROVED|REJECTED',
    starred           TINYINT      DEFAULT 0,
    deleted           TINYINT      DEFAULT 0 COMMENT '软删除，链不剪断',
    created_by        VARCHAR(32)  NOT NULL,
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant_project (tenant_id, project_id),
    INDEX idx_parent (parent_asset_id),
    INDEX idx_tenant_status (tenant_id, status),
    INDEX idx_tenant_type (tenant_id, asset_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容资产';

-- 资产标签（租户内名称去重；字典化留待资产中心优化阶段）
CREATE TABLE IF NOT EXISTS asset_tag (
    id          VARCHAR(32) PRIMARY KEY,
    tenant_id   VARCHAR(32) NOT NULL,
    asset_id    VARCHAR(32) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_asset_name (tenant_id, asset_id, name),
    INDEX idx_tenant_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产标签';

-- 资产-任务双向溯源（完整事实来源；parent 链只是展示优化）
CREATE TABLE IF NOT EXISTS asset_task_relation (
    id            VARCHAR(32) PRIMARY KEY,
    asset_id      VARCHAR(32) NOT NULL,
    task_id       VARCHAR(32) NOT NULL,
    relation_type VARCHAR(8)  NOT NULL COMMENT 'INPUT|OUTPUT',
    created_at    DATETIME    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_asset_task (asset_id, task_id, relation_type),
    INDEX idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产-任务溯源关系';

-- ============ 模型能力目录（第一阶段最小版） ============
CREATE TABLE IF NOT EXISTS model_capability (
    id            VARCHAR(32)   PRIMARY KEY,
    capability    VARCHAR(32)   NOT NULL COMMENT 'TEXT_CHAT|TEXT_GEN|TEXT2IMAGE|IMAGE2IMAGE|TEXT2VIDEO|IMAGE2VIDEO|EMBEDDING',
    provider      VARCHAR(64)   NOT NULL COMMENT '关联 model_config.provider',
    model_code    VARCHAR(128)  NOT NULL COMMENT '实际调用的模型标识',
    label         VARCHAR(128)  DEFAULT '',
    tier          VARCHAR(16)   DEFAULT 'STANDARD' COMMENT '质量档位 FAST|STANDARD|HIGH_QUALITY',
    cost_unit     VARCHAR(8)    NOT NULL DEFAULT 'CALL' COMMENT 'CALL|TOKEN|SECOND',
    cost_per_unit DECIMAL(10,6) NOT NULL DEFAULT 0 COMMENT '单位成本（估算用，非账本）',
    enabled       TINYINT       DEFAULT 1,
    priority      INT           DEFAULT 0 COMMENT '同 capability 内优先级，小者优先',
    created_at    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cap_provider_model (capability, provider, model_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型能力目录';
```

### 3.2 存量表变更：agent_task

```sql
ALTER TABLE agent_task
    ADD COLUMN project_id       VARCHAR(32)  DEFAULT NULL COMMENT '所属项目，可空兼容存量',
    ADD COLUMN source_asset_id  VARCHAR(32)  DEFAULT NULL COMMENT '输入资产（图生图/图生视频的来源）',
    ADD COLUMN batch_group_id   VARCHAR(32)  DEFAULT NULL COMMENT '批次号，批量任务同组',
    ADD COLUMN cost             DECIMAL(12,6) DEFAULT NULL COMMENT '估算成本，任务完成时写入',
    ADD INDEX idx_project (project_id),
    ADD INDEX idx_batch (batch_group_id);
```

全部可空列 + 普通索引，不影响存量数据；不需要动 storage_file（已有 source_task_id 关联任务）。

### 3.3 权限矩阵（项目内动作）

| 动作 | OWNER | EDITOR | REVIEWER | VIEWER |
|---|---|---|---|---|
| 查看项目、资产、时间线 | ✓ | ✓ | ✓ | ✓ |
| 上传/引用素材、发起创作 | ✓ | ✓ | — | — |
| 编辑简报、项目设置 | ✓ | ✓ | — | — |
| 发起审核 | ✓ | ✓ | — | — |
| 审核通过/驳回（阶段 2 落地动作） | ✓ | — | ✓ | — |
| 导出/分享 | ✓ | ✓ | — | — |
| 成员管理、归档 | ✓ | — | — | — |

合并规则：平台动作（建项目、配模型、看成本）只看 user_tenant.role_code；项目动作只看 project_member.role_code；SUPER_ADMIN / TENANT_ADMIN 对租户内所有项目拥有全部权限（旁路）。

---

## 4. 后端设计（agent-core）

### 4.1 领域包结构

```
com.platform.agent.project
    entity/   Project, ProjectMember, CreativeBrief, ProjectDataset
    mapper/   （MyBatis-Plus BaseMapper）
    dto/      请求/响应对象
    service/  ProjectService, ProjectMemberService, BriefService,
              ProjectAccessService   ← 统一权限强制点
    controller/ ProjectController, ProjectMemberController,
                BriefController, ProjectDatasetController

com.platform.agent.asset
    entity/   Asset, AssetTag, AssetTaskRelation
    mapper/
    dto/
    service/  AssetService, AssetLineageService, AssetArchiveService
    controller/ AssetController
```

### 4.2 权限强制

`ProjectAccessService` 提供统一入口：

- `requireProjectRole(projectId, role...)` —— 从上下文取 userId/tenantId，查 project_member；不满足抛 403。
- `isAdmin(userId, tenantId)` —— 查 user_tenant.role_code ∈ {SUPER_ADMIN, TENANT_ADMIN}，命中直接放行（全权旁路）。
- 网关继续只做租户级鉴权，项目级强制只在 agent-core 项目域内做，这是唯一清晰边界。

### 4.3 API 清单

统一前缀 `/api/v1`，鉴权沿用现有机制。

**项目**
| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | /projects | 创建项目（创建者自动成为 OWNER） | 租户角色 ≥ CONTENT_CREATOR |
| GET | /projects | 项目列表（默认"我参与的"；`?scope=all` 管理员全租户） | 成员/管理员 |
| GET | /projects/{id} | 详情（含简报摘要、成员、资产统计） | 成员或管理员 |
| PUT | /projects/{id} | 改名、封面、截止日期 | OWNER/EDITOR |
| POST | /projects/{id}/archive | 归档（阶段 1 不做物理删除） | OWNER/管理员 |
| GET | /projects/{id}/timeline | 项目时间线（任务+资产按时间聚合，按 batch_group_id 分组） | 成员 |

**成员**
| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /projects/{id}/members | 成员列表 | 成员 |
| POST | /projects/{id}/members | 添加成员（必须已是租户成员） | OWNER |
| PUT | /projects/{id}/members/{memberId} | 改角色（最后 OWNER 不可降级） | OWNER |
| DELETE | /projects/{id}/members/{memberId} | 移除（最后 OWNER 不可移除） | OWNER |

**简报 / 知识库**
| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET / PUT | /projects/{id}/brief | 读/更新简报 | 读=成员；写=OWNER/EDITOR |
| GET | /projects/{id}/datasets | 挂载列表 | 成员 |
| POST | /projects/{id}/datasets | 挂载数据集 | OWNER/EDITOR |
| DELETE | /projects/{id}/datasets/{datasetId} | 解除挂载 | OWNER/EDITOR |

**资产**
| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /assets | 列表，过滤：projectId/assetType/status/tag/starred/keyword/derivationType；分页 | 见可见性规则 |
| GET | /assets/{id} | 详情（含标签、参数快照、文件信息） | 可见即可读 |
| PUT | /assets/{id} | 改 title/description/tags/status/projectId（移动归属） | 源与目标项目均有写权限 |
| POST | /assets/{id}/star · /unstar | 收藏/取消 | 可见即可 |
| POST | /assets/{id}/submit-review | DRAFT → UNDER_REVIEW | 所属项目 OWNER/EDITOR |
| DELETE | /assets/{id} | 软删除（链不剪断） | 所属项目 OWNER/EDITOR |
| GET | /assets/{id}/lineage | 完整溯源：parent 链双向 + 关联任务 + 提示词 + 模型 | 可见即可 |

可见性规则：租户级资产（project_id 空）全租户可见；项目资产仅项目成员（或管理员）可见。

**任务变更**
- `POST /tasks` 请求体新增 `projectId`、`sourceAssetId`、`batchGroupId`（全部可选，兼容存量调用）。
- `GET /tasks` 新增 `projectId`、`batchGroupId` 过滤参数。
- 归档自动触发（见 4.4），`POST /tasks/{id}/archive` 仅作失败兜底重试入口。

**模型能力目录**
- `GET /model-capabilities` —— 启用列表，按 capability 分组、含 tier/成本，供创作页"简单模式"质量档位选择。

### 4.4 任务归档流程（生成结果 → 资产）

监听现有任务完成事件（沿用任务状态回写链路），`AssetArchiveService` 执行：

1. 解析任务 result 中产出文件（storage_file 已有 source_task_id 关联）；
2. 创建 asset 行：
   - `derivation_type`：有 sourceAssetId 且模态变化 → CONVERT；同模态变体 → VARIANT；重试 → REGEN；直接生成 → GENERATED；上传 → UPLOAD（上传接口单独写）；
   - `parent_asset_id` ← sourceAssetId（仅单输入源时）；
   - prompt/model/params 从 request_params 快照，`status=DRAFT`；
3. 写 asset_task_relation：产出 → OUTPUT；有输入资产 → INPUT；
4. 成本估算：model_capability 查 cost_unit/cost_per_unit → 图片按 CALL×1、视频按 SECOND×时长、文本按 TOKEN×usage → 回写 task.cost；
5. 归档失败不阻塞任务主流程，记录日志，兜底接口可手动重试。

### 4.5 现有任务级审核状态的处理

agent_task.status 已含 REVIEWING/PASS/REJECT。阶段 1 两者并存：任务状态继续管"执行"，asset.status 管"内容审核"。阶段 2 审核流落地时统一收敛到 asset.status，任务级审核状态仅保留执行语义。

---

## 5. 前端设计（web-admin，React + TS）

### 5.1 菜单调整（最小改动）

现有菜单保持不动，仅新增两个一级入口：

```
首页 / 项目（新）/ 创作（原 workspace）/ 资产（新）/ 知识库 / 任务 / 文件 / 模型配置 / 管理
```

### 5.2 页面清单

**1. 项目列表页** `pages/projects/index.tsx`
- 卡片网格：封面、名称、状态、截止日期、资产数、成员头像
- Tab："我参与的" / "全部"（管理员可见）
- 新建项目弹窗（名称 + 截止日期）

**2. 项目工作台** `pages/projects/detail.tsx`（核心页面，投入最多打磨）
- 左侧 Tab 导航：概览 / 资产 / 创作 / 成员
- 概览：简报摘要卡片 + 时间线（上传与生成按时间聚合，batch_group 分组展示，点击节点跳资产详情）
- 资产：内嵌资产网格（固定 projectId 过滤）
- 创作：创作页内嵌，自动带 projectId + 简报上下文 + 项目知识库
- 成员：成员列表 + 角色管理（OWNER/EDITOR/REVIEWER/VIEWER）+ 添加成员（从租户成员选）

**3. 资产中心** `pages/assets/index.tsx`
- 网格/列表双视图切换
- 过滤栏：项目、类型（图片/视频/文案/其他）、状态、标签、星标、关键词搜索
- 详情抽屉：预览、派生链（parent + children 可视化）、生成参数、关联任务、标签编辑、四个操作（复用创作 / 放入审核 / 保存到项目 / 下载）

**4. 创作页改造**（现有 workspace 页）
- 三栏布局：左=素材与参考图，中=结果画布，右=参数面板
- 简单模式：目标、风格、比例、参考图、质量档位（FAST/STANDARD/HIGH_QUALITY → 映射 model_capability）、生成按钮
- 高级参数折叠：模型、种子、负面提示词、清晰度、时长、帧率
- 结果四操作：保存到项目（弹窗选项目+标签）、创建变体、放入审核、下载
- 从项目进入时自动带上下文；全局进入生成后弹归档弹窗

**5. 待审核列表**（最小形态）
- 资产中心加"待审核"过滤视图：UNDER_REVIEW 状态列表 + 通过/驳回操作（OWNER/REVIEWER）

---

## 6. 开发顺序与验收

### 6.1 开发顺序

1. **DDL**：写 `sql/phase1_project_asset.sql`（3.1 新表 + 3.2 ALTER），在 Docker 3307 的 agent_platform 库执行
2. **project 域**：实体/mapper/service/controller + ProjectAccessService 权限强制
3. **asset 域**：实体/mapper/service/controller + 归档回写 + 成本估算 + 任务接口扩展
4. **模型能力目录**：model_capability 种子数据（对齐现有 model_config 的 provider 行）+ 查询接口
5. **前端**：项目列表 → 工作台 → 资产中心 → 创作页改造 → 菜单/路由
6. **联调验收**（Docker 环境起全栈）

### 6.2 验收标准

- [ ] 建项目 → 传参考图 → 图生图 → 图生视频 → 提交审核，整条链在项目时间线可见、可点击追溯
- [ ] 任务完成后资产自动归档，task.cost 有值，prompt/模型/参数快照完整
- [ ] 非成员访问项目 API 返回 403；TENANT_ADMIN 可管理任意项目；最后一名 OWNER 无法被移除
- [ ] 删除资产后，子资产 lineage 完整且显示"原素材已删除"占位
- [ ] 文案生成结果可归档为 TEXT 资产，资产中心可搜索
- [ ] 批量任务同 batchGroupId 在工作台聚合展示
- [ ] 存量任务/资产零影响（新列可空，旧页面行为不变）

---

## 7. 明确不做（阶段 2 / 3）

- review_request / 评论 / 完整审批流（阶段 2）
- 交付包、分享链接、访客 token（阶段 2）
- 工作流模板引擎、workflow-service 拆分（阶段 2）
- 用量账本、对账、退款补偿（阶段 2）
- 完整 ModelRouter（熔断、限流、自动降级）、成本治理（阶段 3）
- asset_version 表、标签字典化、跨项目资产拷贝（触发真实需求再加）
- 第三方渠道发布、Webhook、开放 API 平台（阶段 3）

---

## 8. 风险与注意

1. **数据库环境**：迁移 SQL 必须连 Docker 3307；本机 3306 是无关实例，MCP 默认连本机，执行前必须确认连接目标。
2. **跨库引用**：asset.file_id → storage_service 库，无外键，归档时校验文件存在性。
3. **租户隔离延续**：所有新表查询必须带 tenant_id 条件，ProjectAccessService 是唯一入口，禁止绕过直查。
4. **归档幂等**：任务完成事件可能重放，asset 创建前按 (task_id, relation_type=OUTPUT) 查重。
