








-- ============================================
-- AI Agent 企业级创作平台 — 数据库初始化脚本
-- ============================================

-- 字符集：本文件含中文，导入时须 utf8mb4，否则中文会被双重编码
SET NAMES utf8mb4;

-- 创建数据库
CREATE DATABASE IF NOT EXISTS agent_platform DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS user_service DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS security_service DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS storage_service DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ==================== agent_platform ====================
USE agent_platform;

CREATE TABLE IF NOT EXISTS agent_task (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    workflow_type VARCHAR(16) NOT NULL COMMENT 'SINGLE|PIPELINE|BATCH',
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    name VARCHAR(255) DEFAULT NULL COMMENT '作品名称（用户生成时命名，可空）',
    request_params JSON,
    result JSON,
    error_msg TEXT,
    retry_count INT DEFAULT 0,
    priority INT DEFAULT 0,
    completed_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_tenant (user_id, tenant_id),
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_workflow_step (
    id VARCHAR(32) PRIMARY KEY,
    task_id VARCHAR(32) NOT NULL,
    step_order INT NOT NULL,
    step_type VARCHAR(16) NOT NULL COMMENT 'IMAGE|VIDEO|AUDIT|POST_PROCESS',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    input JSON,
    output JSON,
    started_at DATETIME,
    finished_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ==================== user_service ====================
USE user_service;

CREATE TABLE IF NOT EXISTS user_account (
    id VARCHAR(32) PRIMARY KEY,
    username VARCHAR(64) UNIQUE NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    email VARCHAR(128),
    phone VARCHAR(32),
    avatar_url VARCHAR(512),
    status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED|LOCKED',
    last_login_at DATETIME,
    last_login_ip VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tenant (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT 'ACTIVE|SUSPENDED|EXPIRED',
    quota_config JSON,
    storage_provider VARCHAR(32) DEFAULT 'minio',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_tenant (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    role_code VARCHAR(32) NOT NULL COMMENT 'SUPER_ADMIN|TENANT_ADMIN|DEVELOPER|CONTENT_CREATOR|VIEWER',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_tenant (user_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS api_key (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    key_hash VARCHAR(256) NOT NULL,
    key_prefix VARCHAR(16) NOT NULL,
    name VARCHAR(128),
    rate_limit_qpm INT DEFAULT 60,
    last_used_at DATETIME,
    expires_at DATETIME,
    status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT 'ACTIVE|REVOKED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quota_usage (
    id VARCHAR(32) PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32),
    quota_type VARCHAR(16) NOT NULL COMMENT 'IMAGE|VIDEO|STORAGE|API_CALL',
    period VARCHAR(16) NOT NULL COMMENT 'DAILY|MONTHLY|TOTAL',
    period_key VARCHAR(16) NOT NULL,
    used BIGINT DEFAULT 0,
    limit_amount BIGINT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quota (tenant_id, user_id, quota_type, period, period_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ==================== security_service ====================
USE security_service;

CREATE TABLE IF NOT EXISTS audit_log (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32),
    tenant_id VARCHAR(32),
    action VARCHAR(64) NOT NULL,
    detail TEXT,
    ip VARCHAR(64),
    action_time DATETIME NOT NULL,
    result VARCHAR(16) COMMENT 'SUCCESS|FAIL',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_tenant (user_id, tenant_id),
    INDEX idx_action_time (action_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ==================== storage_service ====================
USE storage_service;

CREATE TABLE IF NOT EXISTS storage_file (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    original_name VARCHAR(512),
    object_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128),
    file_size BIGINT,
    file_md5 VARCHAR(64),
    width INT,
    height INT,
    duration_ms INT,
    thumbnail_key VARCHAR(1024),
    provider VARCHAR(32) DEFAULT 'minio',
    storage_class VARCHAR(16) DEFAULT 'TEMP' COMMENT 'TEMP|ASSET|ARCHIVE',
    visibility VARCHAR(16) DEFAULT 'PRIVATE' COMMENT 'PUBLIC|PRIVATE|TENANT',
    expire_at DATETIME,
    source_task_id VARCHAR(32),
    source_type VARCHAR(16) COMMENT 'IMAGE_GEN|VIDEO_GEN|UPLOAD',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_tenant (user_id, tenant_id),
    INDEX idx_task (source_task_id),
    INDEX idx_expire (expire_at),
    INDEX idx_storage_class (storage_class)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
