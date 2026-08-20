-- ============================================================
-- 多轮对话 + 长期记忆 数据表
-- 执行顺序: init.sql → model_config.sql → rag.sql → chat.sql
-- 已有数据库升级：手动执行本文件
-- ============================================================
USE agent_platform;

-- ========== 会话 ==========
CREATE TABLE IF NOT EXISTS chat_conversation (
    id            VARCHAR(32)  PRIMARY KEY,
    user_id       VARCHAR(32)  NOT NULL,
    tenant_id     VARCHAR(32)  NOT NULL,
    title         VARCHAR(128) DEFAULT '新对话' COMMENT '自动取首条消息前 30 字',
    provider      VARCHAR(64)  NOT NULL COMMENT 'LLM 提供商',
    dataset_id    VARCHAR(32)  COMMENT '绑定的 RAG 知识库（可为空）',
    system_prompt TEXT COMMENT '会话级 systemPrompt（可为空）',
    message_count INT DEFAULT 0 COMMENT '冗余计数，列表页免 COUNT',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_conv_user_tenant (user_id, tenant_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话';

-- ========== 消息 ==========
CREATE TABLE IF NOT EXISTS chat_message (
    id               VARCHAR(32) PRIMARY KEY,
    conversation_id  VARCHAR(32) NOT NULL,
    user_id          VARCHAR(32) NOT NULL,
    tenant_id        VARCHAR(32) NOT NULL,
    role             VARCHAR(16) NOT NULL COMMENT 'user|assistant',
    content          TEXT NOT NULL,
    references_json  JSON COMMENT 'RAG 引用列表（RagReference[]），非 RAG 轮为 NULL',
    token_count      INT DEFAULT 0 COMMENT '字符数粗估（与 rag_chunk 同风格）',
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_msg_conv (conversation_id, created_at),
    INDEX idx_msg_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息';

-- ========== 用户记忆（长期/短期双板块） ==========
CREATE TABLE IF NOT EXISTS user_memory (
    id                     VARCHAR(32) PRIMARY KEY,
    user_id                VARCHAR(32) NOT NULL,
    tenant_id              VARCHAR(32) NOT NULL,
    content                TEXT NOT NULL COMMENT '提取出的事实/偏好（自然语言单句）',
    memory_type            VARCHAR(16) DEFAULT 'FACT' COMMENT 'FACT|PREFERENCE（v1 不区分检索行为，仅留扩展位）',
    category               VARCHAR(16) NOT NULL DEFAULT 'SHORT_TERM' COMMENT '记忆板块 LONG_TERM=长期(手动配置,对话全量注入)|SHORT_TERM=短期(自动提取/指令,检索注入)',
    source_conversation_id VARCHAR(32) COMMENT '来源会话',
    embedding              JSON COMMENT '向量 JSON 数组（与 rag_chunk.embedding 同范式）',
    token_count            INT DEFAULT 0,
    deleted                TINYINT NOT NULL DEFAULT 0 COMMENT '软删标记 0正常/1已删（行保留以阻止同内容再次提取"复活"）',
    created_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_mem_user_tenant (user_id, tenant_id),
    INDEX idx_mem_user_tenant_cat (user_id, tenant_id, category),
    INDEX idx_mem_conv (source_conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户记忆（长期/短期）';

-- 老库升级：user_memory 加软删标记列（幂等）
SET @has_mem_deleted := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'agent_platform' AND TABLE_NAME = 'user_memory' AND COLUMN_NAME = 'deleted');
SET @ddl_mem := IF(@has_mem_deleted = 0,
    'ALTER TABLE user_memory ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''软删标记 0正常/1已删（行保留以阻止同内容再次提取"复活"）'' AFTER token_count',
    'SELECT 1');
PREPARE stmt_mem FROM @ddl_mem;
EXECUTE stmt_mem;
DEALLOCATE PREPARE stmt_mem;

-- 老库升级：user_memory 加板块列 category（幂等；存量默认短期，保持原有检索注入行为不变）
SET @has_mem_category := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'agent_platform' AND TABLE_NAME = 'user_memory' AND COLUMN_NAME = 'category');
SET @ddl_mem_category := IF(@has_mem_category = 0,
    'ALTER TABLE user_memory ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT ''SHORT_TERM'' COMMENT ''记忆板块 LONG_TERM=长期(手动配置,对话全量注入)|SHORT_TERM=短期(自动提取/指令,检索注入)'' AFTER memory_type',
    'SELECT 1');
PREPARE stmt_mem_category FROM @ddl_mem_category;
EXECUTE stmt_mem_category;
DEALLOCATE PREPARE stmt_mem_category;

-- 老库升级：板块过滤索引（幂等）
SET @has_mem_cat_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'agent_platform' AND TABLE_NAME = 'user_memory' AND INDEX_NAME = 'idx_mem_user_tenant_cat');
SET @ddl_mem_cat_idx := IF(@has_mem_cat_idx = 0,
    'ALTER TABLE user_memory ADD INDEX idx_mem_user_tenant_cat (user_id, tenant_id, category)',
    'SELECT 1');
PREPARE stmt_mem_cat_idx FROM @ddl_mem_cat_idx;
EXECUTE stmt_mem_cat_idx;
DEALLOCATE PREPARE stmt_mem_cat_idx;
