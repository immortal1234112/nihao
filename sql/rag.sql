-- ============================================================
-- RAG 检索模块数据表 (自建轻量 RAG)
-- 执行顺序: init.sql → model_config.sql → rag.sql
-- 向量存 rag_chunk.embedding JSON 列, 应用层余弦相似度检索
-- ============================================================
USE agent_platform;

-- ========== RAG 知识库 ==========
CREATE TABLE IF NOT EXISTS rag_dataset (
    id                 VARCHAR(32) PRIMARY KEY,
    name               VARCHAR(128) NOT NULL COMMENT '知识库名称',
    description        VARCHAR(512) DEFAULT '' COMMENT '描述',
    tenant_id          VARCHAR(32) NOT NULL,
    user_id            VARCHAR(32) NOT NULL,
    embedding_provider VARCHAR(64)  NOT NULL DEFAULT 'qwen-embedding' COMMENT 'embedding 提供商',
    embedding_model    VARCHAR(128) DEFAULT '' COMMENT 'embedding 模型(创建后不可换,换需重建)',
    dimension          INT          DEFAULT 1024 COMMENT '向量维度',
    doc_count          INT          DEFAULT 0,
    chunk_count        INT          DEFAULT 0,
    status             VARCHAR(16)  DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rag_dataset_tenant (tenant_id),
    INDEX idx_rag_dataset_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 知识库';

-- ========== RAG 文档 ==========
CREATE TABLE IF NOT EXISTS rag_document (
    id          VARCHAR(32) PRIMARY KEY,
    dataset_id  VARCHAR(32) NOT NULL,
    tenant_id   VARCHAR(32) NOT NULL,
    file_name   VARCHAR(256) NOT NULL,
    file_size   BIGINT       DEFAULT 0,
    file_type   VARCHAR(16)  COMMENT 'txt|md',
    chunk_count INT          DEFAULT 0,
    status      VARCHAR(16)  DEFAULT 'PARSING' COMMENT 'PARSING|READY|FAILED',
    error_msg   TEXT,
    parsed_at   DATETIME,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rag_doc_dataset (dataset_id),
    INDEX idx_rag_doc_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 文档';

-- ========== RAG 分块 ==========
CREATE TABLE IF NOT EXISTS rag_chunk (
    id           VARCHAR(32) PRIMARY KEY,
    dataset_id   VARCHAR(32) NOT NULL,
    document_id  VARCHAR(32) NOT NULL,
    tenant_id    VARCHAR(32) NOT NULL,
    chunk_index  INT NOT NULL,
    content      TEXT NOT NULL,
    embedding    JSON COMMENT '向量 JSON 数组',
    token_count  INT DEFAULT 0,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rag_chunk_dataset (dataset_id),
    INDEX idx_rag_chunk_doc (document_id),
    INDEX idx_rag_chunk_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 分块(含向量)';
