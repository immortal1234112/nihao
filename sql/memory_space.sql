-- ============================================================
-- 记忆空间（记忆分组）—— 让不同对话参考不同的长期/短期记忆
-- 执行顺序: init.sql → model_config.sql → rag.sql → chat.sql → memory_space.sql
-- 幂等：可重复执行
--
-- ⚠️ 字符集：本文件含中文，导入时必须指定 utf8mb4，否则中文会被双重编码（页面显示乱码）：
--     docker exec -i mysql mysql -uroot -p<密码> --default-character-set=utf8mb4 < sql/memory_space.sql
--   本文件首行已 SET NAMES utf8mb4 兜底，但 mysql 客户端的 character_set_client
--   在读取文件时就要正确，故命令行参数仍然必要。
-- ============================================================
SET NAMES utf8mb4;
USE agent_platform;

-- ========== 记忆空间 ==========
-- 每个用户在自己的租户下可以有多个空间，每个空间是一套独立的「长期 + 短期」记忆。
-- 对话创建时绑定一个空间，该对话的注入与提取都限定在该空间内。
CREATE TABLE IF NOT EXISTS memory_space (
    id          VARCHAR(32)  PRIMARY KEY,
    user_id     VARCHAR(32)  NOT NULL,
    tenant_id   VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL COMMENT '空间名，如「工作」「写作」',
    description VARCHAR(255) DEFAULT '' COMMENT '空间说明（前端展示）',
    is_default  TINYINT      NOT NULL DEFAULT 0 COMMENT '1=默认空间（每用户唯一，不可删）',
    sort_order  INT          NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '软删 0正常/1已删（记忆随空间软删）',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_space_user_tenant (user_id, tenant_id, deleted),
    UNIQUE KEY uk_space_user_name (user_id, tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆空间';

-- ========== user_memory 加 space_id ==========
SET @has_mem_space := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'agent_platform' AND TABLE_NAME = 'user_memory' AND COLUMN_NAME = 'space_id');
SET @ddl_mem_space := IF(@has_mem_space = 0,
    'ALTER TABLE user_memory ADD COLUMN space_id VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''所属记忆空间'' AFTER tenant_id',
    'SELECT 1');
PREPARE stmt_mem_space FROM @ddl_mem_space;
EXECUTE stmt_mem_space;
DEALLOCATE PREPARE stmt_mem_space;

-- 空间维度检索索引（覆盖原 idx_mem_user_tenant_cat 的查询场景）
SET @has_mem_space_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'agent_platform' AND TABLE_NAME = 'user_memory' AND INDEX_NAME = 'idx_mem_space_cat');
SET @ddl_mem_space_idx := IF(@has_mem_space_idx = 0,
    'ALTER TABLE user_memory ADD INDEX idx_mem_space_cat (user_id, tenant_id, space_id, category)',
    'SELECT 1');
PREPARE stmt_mem_space_idx FROM @ddl_mem_space_idx;
EXECUTE stmt_mem_space_idx;
DEALLOCATE PREPARE stmt_mem_space_idx;

-- ========== chat_conversation 加 space_id ==========
-- 可空：存量会话为 NULL，运行时按「用户默认空间」解析，行为与改造前一致。
SET @has_conv_space := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'agent_platform' AND TABLE_NAME = 'chat_conversation' AND COLUMN_NAME = 'space_id');
SET @ddl_conv_space := IF(@has_conv_space = 0,
    'ALTER TABLE chat_conversation ADD COLUMN space_id VARCHAR(32) DEFAULT NULL COMMENT ''绑定的记忆空间（NULL=默认空间）'' AFTER dataset_id',
    'SELECT 1');
PREPARE stmt_conv_space FROM @ddl_conv_space;
EXECUTE stmt_conv_space;
DEALLOCATE PREPARE stmt_conv_space;

-- ========== 存量数据回填 ==========
-- 1) 为「已有记忆」或「已有会话」的用户补建默认空间
INSERT INTO memory_space (id, user_id, tenant_id, name, description, is_default, sort_order, deleted)
SELECT REPLACE(UUID(), '-', ''), t.user_id, t.tenant_id, '默认',
       '系统自动创建，承接改造前的全部记忆', 1, 0, 0
FROM (
    SELECT DISTINCT user_id, tenant_id FROM user_memory
    UNION
    SELECT DISTINCT user_id, tenant_id FROM chat_conversation
) t
LEFT JOIN memory_space s
       ON s.user_id = t.user_id AND s.tenant_id = t.tenant_id AND s.name = '默认'
WHERE s.id IS NULL;

-- 2) 存量记忆挂到各自用户的默认空间
UPDATE user_memory m
JOIN memory_space s
  ON s.user_id = m.user_id AND s.tenant_id = m.tenant_id AND s.is_default = 1 AND s.deleted = 0
SET m.space_id = s.id
WHERE m.space_id = '';

-- 3) 存量会话挂到各自用户的默认空间（仅 NULL 的，不动已显式指定的）
UPDATE chat_conversation c
JOIN memory_space s
  ON s.user_id = c.user_id AND s.tenant_id = c.tenant_id AND s.is_default = 1 AND s.deleted = 0
SET c.space_id = s.id
WHERE c.space_id IS NULL;

-- 4) 兜底：仍有 space_id 为空的记忆（用户已无会话也无默认空间等极端情况）→ 补建并挂载
INSERT INTO memory_space (id, user_id, tenant_id, name, description, is_default, sort_order, deleted)
SELECT REPLACE(UUID(), '-', ''), m.user_id, m.tenant_id, '默认',
       '系统自动创建，承接改造前的全部记忆', 1, 0, 0
FROM user_memory m
LEFT JOIN memory_space s
       ON s.user_id = m.user_id AND s.tenant_id = m.tenant_id AND s.is_default = 1 AND s.deleted = 0
WHERE m.space_id = '' AND s.id IS NULL
GROUP BY m.user_id, m.tenant_id;

UPDATE user_memory m
JOIN memory_space s
  ON s.user_id = m.user_id AND s.tenant_id = m.tenant_id AND s.is_default = 1 AND s.deleted = 0
SET m.space_id = s.id
WHERE m.space_id = '';
