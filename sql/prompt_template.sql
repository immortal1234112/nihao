-- ============================================
-- 提示词模板库 — 独立脚本（在 agent_platform 库执行）
-- 用法：mysql -u root -p < sql/prompt_template.sql
-- ============================================
USE agent_platform;

CREATE TABLE IF NOT EXISTS prompt_template (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(128) NOT NULL COMMENT '模板名称',
    category VARCHAR(32) NOT NULL COMMENT '分类：摄影|插画|国风|赛博朋克|动漫|产品|海报|人像|风景|视频',
    engine_type VARCHAR(16) NOT NULL DEFAULT 'IMAGE' COMMENT '适用引擎：IMAGE|VIDEO|ALL',
    prompt TEXT NOT NULL COMMENT '提示词正文',
    negative_prompt TEXT COMMENT '负面提示词',
    scope VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' COMMENT '可见范围：PUBLIC|TENANT|PRIVATE',
    user_id VARCHAR(32) COMMENT '创建者（PRIVATE/TENANT 归属）',
    tenant_id VARCHAR(32) COMMENT '所属租户（TENANT 归属）',
    usage_count INT DEFAULT 0 COMMENT '使用次数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_scope (scope),
    INDEX idx_engine (engine_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词模板库';

-- ==================== 种子模板（15 条，PUBLIC 官方精选） ====================
-- scope 未显式指定：建表默认 PUBLIC（官方模板）
-- INSERT IGNORE：脚本可重复执行（幂等），已存在的种子跳过
INSERT IGNORE INTO prompt_template (id, name, category, engine_type, prompt, negative_prompt) VALUES
('tpl001', '黄昏城市剪影', '摄影', 'IMAGE',
 'Golden hour city skyline silhouette, dramatic sunset sky with orange and purple clouds, birds flying in the distance, cinematic composition, high contrast, professional photography, 8k',
 'blurry, low quality, watermark, text'),
('tpl002', '北欧极光之夜', '摄影', 'IMAGE',
 'Northern lights over a snowy Nordic landscape, aurora borealis in green and purple, starry night sky, reflection on frozen lake, hyperrealistic, long exposure photography',
 'oversaturation, noise, watermark'),
('tpl003', '江南水墨山水', '国风', 'IMAGE',
 'Traditional Chinese ink wash painting, misty mountains over a river, lone fisherman on a small boat, willow trees, monochrome ink on rice paper, elegant brushwork, wabi-sabi',
 'colorful, western oil painting style, watermark'),
('tpl004', '敦煌飞天仕女', '国风', 'IMAGE',
 'Dunhuang flying apsaras mural style, graceful celestial maiden with flowing ribbons, vivid mineral pigments, golden background, Tang dynasty aesthetic, high detail illustration',
 'modern clothes, deformed hands, watermark'),
('tpl005', '赛博朋克霓虹雨夜', '赛博朋克', 'IMAGE',
 'Cyberpunk neon-lit alley in the rain, holographic advertisements, reflections on wet asphalt, lone figure with umbrella, purple and cyan color palette, futuristic, blade runner atmosphere',
 'daylight, rural scenery, blurry'),
('tpl006', '机甲武士', '赛博朋克', 'IMAGE',
 'Cybernetic samurai warrior in armor with glowing energy lines, katana emitting blue plasma, dark futuristic dojo background, dramatic lighting, detailed mechanical design, concept art',
 'weak design, plain background, watermark'),
('tpl007', '宫崎骏风乡村田野', '动漫', 'IMAGE',
 'Studio Ghibli style countryside landscape, rolling green hills, small red-roofed houses, fluffy clouds, gentle warm sunlight, hand-drawn animation aesthetic, peaceful and whimsical',
 '3d render, realistic photo, dark mood'),
('tpl008', '新海诚式夏日天空', '动漫', 'IMAGE',
 'Makoto Shinkai style summer sky, towering cumulus clouds, vivid blue sky, power lines and school rooftop in foreground, cinematic color grading, anime background art, high detail',
 'desaturated, dark, low detail'),
('tpl009', '极简白色陶瓷产品', '产品', 'IMAGE',
 'Minimalist white ceramic vase product photography, soft studio lighting, clean light gray background, subtle shadows, centered composition, commercial product shot, 4k',
 'cluttered background, warm orange lighting, text'),
('tpl010', '智能手表广告大片', '产品', 'IMAGE',
 'Premium smartwatch product advertisement, floating watch with glowing screen, dark gradient background, dramatic rim lighting, dust particles, luxury tech aesthetic, 3d render',
 'toy-like, poor materials, watermark'),
('tpl011', '复古电影海报', '海报', 'IMAGE',
 'Retro movie poster style, bold typography layout, art deco design, dramatic central character, vintage color palette of orange teal and cream, paper texture, cinematic title design',
 'modern ui, text overlay mess, blurry'),
('tpl012', '国潮音乐节海报', '海报', 'IMAGE',
 'Chinese streetwear music festival poster, traditional patterns mixed with modern graffiti, vibrant red and gold colors, dynamic composition, bold Chinese typography, high energy design',
 'plain background, dull colors, no energy'),
('tpl013', '川西雪山云海航拍', '风景', 'IMAGE',
 'Aerial drone shot of snow-capped mountains above a sea of clouds, golden morning light on peaks, dramatic valleys, crisp air, majestic scale, landscape photography masterpiece',
 'cloudy grey sky, low resolution, watermark'),
('tpl014', '老城街角清晨咖啡', '人像', 'IMAGE',
 'Cozy morning cafe scene, elderly man reading newspaper by window with warm sunlight, steam rising from coffee cup, shallow depth of field, cinematic street photography, nostalgic mood',
 'modern interiors, harsh flash, blurry face'),
('tpl015', '科幻宇宙飞船运镜', '视频', 'VIDEO',
 'Cinematic tracking shot of a massive spaceship flying over an alien planet, camera orbits from below, engine glow illuminating clouds, epic scale, sci-fi film VFX, smooth motion',
 'jittery camera, low detail, static shot');
