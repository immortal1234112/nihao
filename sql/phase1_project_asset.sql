-- ============================================
-- 阶段 1：项目化创作 + 资产中心 — 迁移脚本
-- 设计文档：docs/phase1-project-asset-design.md
-- 全文件幂等：可重复执行（启动脚本每次都会应用），ALTER 用 information_schema 探测跳过已存在列
-- 执行环境：Docker MySQL 3307（本机 3306 是无关实例）
-- ============================================

USE agent_platform;

-- ==================== 项目 ====================
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
    updated_at  DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_dataset (project_id, dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目知识库挂载';

-- ==================== 资产 ====================
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

-- 资产标签（租户内去重；字典化留待资产中心优化阶段）
CREATE TABLE IF NOT EXISTS asset_tag (
    id          VARCHAR(32) PRIMARY KEY,
    tenant_id   VARCHAR(32) NOT NULL,
    asset_id    VARCHAR(32) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
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
    updated_at    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_asset_task (asset_id, task_id, relation_type),
    INDEX idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产-任务溯源关系';

-- ==================== 模型能力目录（第一阶段最小版） ====================
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

-- ==================== agent_task 扩展（可空列，兼容存量） ====================
-- 幂等 ALTER：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema
-- 探测 + 动态 SQL 实现（启动脚本每次都会重跑本文件）
SET @has_project_id := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'agent_platform' AND TABLE_NAME = 'agent_task' AND COLUMN_NAME = 'project_id');
SET @ddl := IF(@has_project_id = 0,
    'ALTER TABLE agent_task ADD COLUMN project_id VARCHAR(32) DEFAULT NULL COMMENT ''所属项目，可空兼容存量'', ADD COLUMN source_asset_id VARCHAR(32) DEFAULT NULL COMMENT ''输入资产（图生图/图生视频的来源）'', ADD COLUMN batch_group_id VARCHAR(32) DEFAULT NULL COMMENT ''批次号，批量任务同组'', ADD COLUMN cost DECIMAL(12,6) DEFAULT NULL COMMENT ''估算成本，任务完成时写入'', ADD INDEX idx_project (project_id), ADD INDEX idx_batch (batch_group_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 作品名称列（2026-08-17，用户生成时命名；画廊/归档资产标题用）
SET @has_task_name := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'agent_platform' AND TABLE_NAME = 'agent_task' AND COLUMN_NAME = 'name');
SET @ddl2 := IF(@has_task_name = 0,
    'ALTER TABLE agent_task ADD COLUMN name VARCHAR(255) DEFAULT NULL COMMENT ''作品名称（用户生成时命名，可空）''',
    'SELECT 1');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- ==================== 模型能力目录种子数据 ====================
-- 成本为估算占位值，运营页可调整；enabled=1 的行与 model_config.enabled 对齐
-- 文本对话 TEXT_CHAT（cost_unit=TOKEN，单 token 估算成本）
INSERT IGNORE INTO model_capability (id, capability, provider, model_code, label, tier, cost_unit, cost_per_unit, enabled, priority) VALUES
('1',  'TEXT_CHAT', 'openai',     'gpt-4o',                 'GPT-4o',      'HIGH_QUALITY', 'TOKEN', 0.000015, 0, 30),
('2',  'TEXT_CHAT', 'anthropic',  'claude-sonnet-4-20250514', 'Claude Sonnet 4', 'HIGH_QUALITY', 'TOKEN', 0.000020, 0, 40),
('3',  'TEXT_CHAT', 'deepseek',   'deepseek-chat',          'DeepSeek V3', 'STANDARD',     'TOKEN', 0.000001, 1, 10),
('4',  'TEXT_CHAT', 'qwen',       'qwen-turbo',             '通义千问 Turbo', 'STANDARD',  'TOKEN', 0.000002, 0, 20),
('5',  'TEXT_CHAT', 'baidu',      'ernie-4.0-8k',           '文心一言 4.0', 'STANDARD',    'TOKEN', 0.000003, 0, 50),
('6',  'TEXT_CHAT', 'spark',      '4.0Ultra',               '讯飞星火 4.0', 'STANDARD',    'TOKEN', 0.000003, 0, 60),
('7',  'TEXT_CHAT', 'zhipu',      'glm-4-plus',             '智谱 GLM-4 Plus', 'STANDARD', 'TOKEN', 0.000005, 0, 30),
('8',  'TEXT_CHAT', 'moonshot',   'moonshot-v1-8k',         'Kimi',         'STANDARD',     'TOKEN', 0.000004, 0, 40),
('9',  'TEXT_CHAT', 'google',     'gemini-2.0-flash',       'Gemini 2.0 Flash', 'FAST',     'TOKEN', 0.000001, 0, 20),
('10', 'TEXT_CHAT', 'agnes',      'agnes-2.5-flash',        'Agnes Flash',  'FAST',         'TOKEN', 0.000002, 0, 10),
-- 文案生成 TEXT_GEN 复用文本模型（cost_unit=TOKEN）
('11', 'TEXT_GEN',  'deepseek',   'deepseek-chat',          'DeepSeek V3',  'STANDARD',     'TOKEN', 0.000001, 1, 10),
('12', 'TEXT_GEN',  'openai',     'gpt-4o',                 'GPT-4o',       'HIGH_QUALITY', 'TOKEN', 0.000015, 0, 30),
('13', 'TEXT_GEN',  'agnes',      'agnes-2.5-flash',        'Agnes Flash',  'FAST',         'TOKEN', 0.000002, 0, 10),
-- 文生图 TEXT2IMAGE（cost_unit=CALL，每次生成估算成本）
('20', 'TEXT2IMAGE', 'local-image',  'sdxl-turbo',          'SDXL Turbo (本地)',  'FAST',         'CALL', 0.000000, 1, 0),
('21', 'TEXT2IMAGE', 'qwen-image',   'wanx-v1',             '通义万相',           'STANDARD',     'CALL', 0.150000, 0, 10),
('22', 'TEXT2IMAGE', 'dalle',        'dall-e-3',            'DALL-E 3',           'HIGH_QUALITY', 'CALL', 0.500000, 0, 30),
('23', 'TEXT2IMAGE', 'stability',    'stable-diffusion-xl', 'Stable Diffusion XL','STANDARD',     'CALL', 0.200000, 0, 20),
('24', 'TEXT2IMAGE', 'midjourney',   'midjourney-v6',       'Midjourney V6',      'HIGH_QUALITY', 'CALL', 0.800000, 0, 40),
('25', 'TEXT2IMAGE', 'ernie-image',  'ernie-vilg-v2',       '文心一格',           'STANDARD',     'CALL', 0.100000, 0, 20),
('26', 'TEXT2IMAGE', 'agnes-image',  'agnes-image-2.1-flash', 'Agnes Image',     'FAST',         'CALL', 0.050000, 0, 10),
-- 文生视频 TEXT2VIDEO（cost_unit=SECOND，每秒估算成本）
('30', 'TEXT2VIDEO', 'local-video', 'cogvideox-2b',         'CogVideoX 2B (本地)', 'FAST',         'SECOND', 0.000000, 1, 0),
('31', 'TEXT2VIDEO', 'runway',      'gen3-alpha',           'Runway Gen-3',        'HIGH_QUALITY', 'SECOND', 1.000000, 0, 50),
('32', 'TEXT2VIDEO', 'kling',       'kling-v1',             '可灵 V1',             'HIGH_QUALITY', 'SECOND', 0.300000, 0, 30),
('33', 'TEXT2VIDEO', 'pika',        'pika-2.0',             'Pika 2.0',            'STANDARD',     'SECOND', 0.500000, 0, 40),
('34', 'TEXT2VIDEO', 'luma',        'dream-machine-v1',     'Luma Dream Machine',  'HIGH_QUALITY', 'SECOND', 0.800000, 0, 40),
('35', 'TEXT2VIDEO', 'agnes-video', 'agnes-video-v2.0',     'Agnes Video',         'FAST',         'SECOND', 0.100000, 0, 10),
('36', 'TEXT2VIDEO', 'cogvideo',    'cogvideox-flash',      'CogVideoX Flash (免费)','FAST',        'SECOND', 0.000000, 0, 20),
('37', 'TEXT2VIDEO', 'wan21',       'wan2.1',               '通义万相 Wan2.1',     'STANDARD',     'SECOND', 0.200000, 0, 30),
-- Embedding EMBEDDING（cost_unit=TOKEN）
('40', 'EMBEDDING', 'siliconflow-embedding', 'BAAI/bge-m3',        'BGE-M3',            'STANDARD', 'TOKEN', 0.000000, 1, 0),
('41', 'EMBEDDING', 'qwen-embedding',        'text-embedding-v3',  '通义 Embedding V3', 'STANDARD', 'TOKEN', 0.000001, 0, 10),
('42', 'EMBEDDING', 'openai-embedding',      'text-embedding-3-small', 'OpenAI Embedding', 'STANDARD', 'TOKEN', 0.000002, 0, 20);
-- 注：IMAGE2IMAGE / IMAGE2VIDEO 行待引擎能力确认后补充
