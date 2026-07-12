USE agent_platform;

CREATE TABLE IF NOT EXISTS model_config (
    id          VARCHAR(32)  PRIMARY KEY,
    provider    VARCHAR(64)  NOT NULL UNIQUE COMMENT 'Provider name: openai, deepseek, qwen, etc.',
    api_key     VARCHAR(512) DEFAULT '' COMMENT 'API Key',
    api_secret  VARCHAR(512) DEFAULT '' COMMENT 'API Secret (for OAuth models like Baidu, Spark)',
    base_url    VARCHAR(512) DEFAULT '' COMMENT 'Custom base URL, empty = use default',
    model       VARCHAR(128) DEFAULT '' COMMENT 'Model name override, empty = use default',
    enabled     TINYINT      DEFAULT 0 COMMENT '1=enabled, 0=disabled',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Insert default rows for all providers (disabled by default)
-- LLM
INSERT INTO model_config (id, provider, api_key, api_secret, base_url, model, enabled) VALUES
('1', 'openai', '', '', 'https://api.openai.com', 'gpt-4o', 0),
('2', 'anthropic', '', '', 'https://api.anthropic.com', 'claude-sonnet-4-20250514', 0),
('3', 'deepseek', '', '', 'https://api.deepseek.com', 'deepseek-chat', 0),
('4', 'qwen', '', '', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-turbo', 0),
('5', 'baidu', '', '', '', 'ernie-4.0-8k', 0),
('6', 'spark', '', '', 'https://spark-api-open.xf-yun.com/v1', '4.0Ultra', 0),
('7', 'zhipu', '', '', 'https://open.bigmodel.cn/api/paas/v4', 'glm-4-plus', 0),
('8', 'moonshot', '', '', 'https://api.moonshot.cn', 'moonshot-v1-8k', 0),
('9', 'google', '', '', 'https://generativelanguage.googleapis.com', 'gemini-2.0-flash', 0),
-- 图片生成
('10', 'local-image', '', '', 'http://localhost:7860', 'sdxl-turbo', 1),
('11', 'qwen-image', '', '', 'https://dashscope.aliyuncs.com', 'wanx-v1', 0),
('12', 'dalle', '', '', 'https://api.openai.com', 'dall-e-3', 0),
('13', 'stability', '', '', 'https://api.stability.ai', 'stable-diffusion-xl', 0),
('14', 'midjourney', '', '', 'https://api.midjourney.com', 'midjourney-v6', 0),
('15', 'ernie-image', '', '', '', 'ernie-vilg-v2', 0),
-- 视频生成
('16', 'local-video', '', '', 'http://localhost:7860', 'cogvideox-2b', 1),
('17', 'runway', '', '', 'https://api.runwayml.com', 'gen3-alpha', 0),
('18', 'kling', '', '', 'https://api.klingai.com', 'kling-v1', 0),
('19', 'pika', '', '', 'https://api.pika.art', 'pika-2.0', 0),
('20', 'luma', '', '', 'https://api.lumalabs.ai', 'dream-machine-v1', 0);
