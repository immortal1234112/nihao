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

-- ========== 长期记忆 ==========
CREATE TABLE IF NOT EXISTS user_memory (
    id                     VARCHAR(32) PRIMARY KEY,
    user_id                VARCHAR(32) NOT NULL,
    tenant_id              VARCHAR(32) NOT NULL,
    content                TEXT NOT NULL COMMENT '提取出的事实/偏好（自然语言单句）',
    memory_type            VARCHAR(16) DEFAULT 'FACT' COMMENT 'FACT|PREFERENCE（v1 不区分检索行为，仅留扩展位）',
    source_conversation_id VARCHAR(32) COMMENT '来源会话',
    embedding              JSON COMMENT '向量 JSON 数组（与 rag_chunk.embedding 同范式）',
    token_count            INT DEFAULT 0,
    created_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_mem_user_tenant (user_id, tenant_id),
    INDEX idx_mem_conv (source_conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户长期记忆';
