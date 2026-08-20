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
-- 正文/负面词为中文：平台自动翻译为英文（PromptEnhancerService）后再交给生成引擎
-- INSERT IGNORE：脚本可重复执行（幂等），已存在的种子跳过
-- UPDATE：老库已有英文模板时刷新为中文版（幂等，无 id 匹配时无害）
INSERT IGNORE INTO prompt_template (id, name, category, engine_type, prompt, negative_prompt) VALUES
('tpl001', '黄昏城市剪影', '摄影', 'IMAGE',
 '黄昏时分城市天际线剪影，夕阳天空点缀橙色和紫色云霞，远处飞鸟掠过，电影感构图，高对比度，专业摄影，8k 画质',
 '模糊，低质量，水印，文字'),
('tpl002', '北欧极光之夜', '摄影', 'IMAGE',
 '北欧雪原上的极光之夜，绿色和紫色的北极光，璀璨星空，极光倒映在结冰的湖面上，超写实，长曝光摄影',
 '过饱和，噪点，水印'),
('tpl003', '江南水墨山水', '国风', 'IMAGE',
 '中国传统水墨画风格，江面薄雾笼罩的层叠山峦，小船上独坐的渔翁，依依垂柳，宣纸上的墨色晕染，笔触优雅，侘寂意境',
 '彩色，西方油画风格，水印'),
('tpl004', '敦煌飞天仕女', '国风', 'IMAGE',
 '敦煌飞天壁画风格，衣袂飘带的曼妙仕女，绚丽的矿物颜料色彩，金色背景，唐代审美，高细节插画',
 '现代服饰，畸形的手，水印'),
('tpl005', '赛博朋克霓虹雨夜', '赛博朋克', 'IMAGE',
 '雨夜中霓虹闪烁的赛博朋克小巷，全息广告招牌，湿润柏油路上的霓虹倒影，撑伞的孤独身影，紫青色调，未来感，银翼杀手氛围',
 '白天，乡村景色，模糊'),
('tpl006', '机甲武士', '赛博朋克', 'IMAGE',
 '身披铠甲的赛博武士，铠甲上流动的能量纹路，等离子武士刀泛着蓝光，暗黑未来道场背景，戏剧性打光，精密机械设计，概念艺术',
 '设计简陋，背景单调，水印'),
('tpl007', '宫崎骏风乡村田野', '动漫', 'IMAGE',
 '宫崎骏动画风格的乡村风景，起伏的翠绿山丘，红色屋顶的小屋，蓬松的云朵，柔和温暖的阳光，手绘动画美学，宁静而奇幻',
 '3d 渲染，写实照片，阴暗氛围'),
('tpl008', '新海诚式夏日天空', '动漫', 'IMAGE',
 '新海诚风格的夏日天空，高耸的积雨云，湛蓝的天空，前景是电线杆与学校天台，电影感调色，动漫背景美术，高细节',
 '低饱和，昏暗，低细节'),
('tpl009', '极简白色陶瓷产品', '产品', 'IMAGE',
 '极简白色陶瓷花瓶产品摄影，柔和影棚灯光，干净的浅灰背景，微妙阴影，居中构图，商业产品拍摄，4k',
 '杂乱背景，暖橙灯光，文字'),
('tpl010', '智能手表广告大片', '产品', 'IMAGE',
 '高端智能手表广告，悬浮手表屏幕泛着微光，深色渐变背景，戏剧性轮廓光，漂浮的尘埃颗粒，奢华科技美学，3d 渲染',
 '玩具质感，材质廉价，水印'),
('tpl011', '复古电影海报', '海报', 'IMAGE',
 '复古电影海报风格，粗体排印版式，装饰艺术设计，戏剧性的中央主角，橙蓝奶油复古配色，纸张肌理，电影标题设计',
 '现代 ui，文字杂乱叠加，模糊'),
('tpl012', '国潮音乐节海报', '海报', 'IMAGE',
 '国潮音乐节海报，传统纹样融合现代涂鸦，红金配色热烈醒目，动感构图，粗壮的汉字排印，高能量设计',
 '背景单调，色彩暗淡，缺乏活力'),
('tpl013', '川西雪山云海航拍', '风景', 'IMAGE',
 '无人机航拍云海之上的雪山群峰，金色晨光洒满山巅，壮阔的山谷，空气澄澈，气势恢宏，风光摄影杰作',
 '阴天灰云，低分辨率，水印'),
('tpl014', '老城街角清晨咖啡', '人像', 'IMAGE',
 '温馨的清晨咖啡馆，老人坐在窗边读报，暖阳洒在桌上，咖啡杯升腾热气，浅景深，电影感街头摄影，怀旧氛围',
 '现代室内装修，生硬闪光灯，脸部模糊'),
('tpl015', '科幻宇宙飞船运镜', '视频', 'VIDEO',
 '巨型飞船飞越外星行星的电影运镜镜头，摄像机从下方环绕上升，引擎辉光照亮云层，史诗级尺度，科幻电影特效，运镜顺滑',
 '镜头抖动，低细节，静态镜头');

-- 老库升级：已存在（英文版）的种子模板刷新为中文版
UPDATE prompt_template SET
  prompt = '黄昏时分城市天际线剪影，夕阳天空点缀橙色和紫色云霞，远处飞鸟掠过，电影感构图，高对比度，专业摄影，8k 画质',
  negative_prompt = '模糊，低质量，水印，文字' WHERE id = 'tpl001';
UPDATE prompt_template SET
  prompt = '北欧雪原上的极光之夜，绿色和紫色的北极光，璀璨星空，极光倒映在结冰的湖面上，超写实，长曝光摄影',
  negative_prompt = '过饱和，噪点，水印' WHERE id = 'tpl002';
UPDATE prompt_template SET
  prompt = '中国传统水墨画风格，江面薄雾笼罩的层叠山峦，小船上独坐的渔翁，依依垂柳，宣纸上的墨色晕染，笔触优雅，侘寂意境',
  negative_prompt = '彩色，西方油画风格，水印' WHERE id = 'tpl003';
UPDATE prompt_template SET
  prompt = '敦煌飞天壁画风格，衣袂飘带的曼妙仕女，绚丽的矿物颜料色彩，金色背景，唐代审美，高细节插画',
  negative_prompt = '现代服饰，畸形的手，水印' WHERE id = 'tpl004';
UPDATE prompt_template SET
  prompt = '雨夜中霓虹闪烁的赛博朋克小巷，全息广告招牌，湿润柏油路上的霓虹倒影，撑伞的孤独身影，紫青色调，未来感，银翼杀手氛围',
  negative_prompt = '白天，乡村景色，模糊' WHERE id = 'tpl005';
UPDATE prompt_template SET
  prompt = '身披铠甲的赛博武士，铠甲上流动的能量纹路，等离子武士刀泛着蓝光，暗黑未来道场背景，戏剧性打光，精密机械设计，概念艺术',
  negative_prompt = '设计简陋，背景单调，水印' WHERE id = 'tpl006';
UPDATE prompt_template SET
  prompt = '宫崎骏动画风格的乡村风景，起伏的翠绿山丘，红色屋顶的小屋，蓬松的云朵，柔和温暖的阳光，手绘动画美学，宁静而奇幻',
  negative_prompt = '3d 渲染，写实照片，阴暗氛围' WHERE id = 'tpl007';
UPDATE prompt_template SET
  prompt = '新海诚风格的夏日天空，高耸的积雨云，湛蓝的天空，前景是电线杆与学校天台，电影感调色，动漫背景美术，高细节',
  negative_prompt = '低饱和，昏暗，低细节' WHERE id = 'tpl008';
UPDATE prompt_template SET
  prompt = '极简白色陶瓷花瓶产品摄影，柔和影棚灯光，干净的浅灰背景，微妙阴影，居中构图，商业产品拍摄，4k',
  negative_prompt = '杂乱背景，暖橙灯光，文字' WHERE id = 'tpl009';
UPDATE prompt_template SET
  prompt = '高端智能手表广告，悬浮手表屏幕泛着微光，深色渐变背景，戏剧性轮廓光，漂浮的尘埃颗粒，奢华科技美学，3d 渲染',
  negative_prompt = '玩具质感，材质廉价，水印' WHERE id = 'tpl010';
UPDATE prompt_template SET
  prompt = '复古电影海报风格，粗体排印版式，装饰艺术设计，戏剧性的中央主角，橙蓝奶油复古配色，纸张肌理，电影标题设计',
  negative_prompt = '现代 ui，文字杂乱叠加，模糊' WHERE id = 'tpl011';
UPDATE prompt_template SET
  prompt = '国潮音乐节海报，传统纹样融合现代涂鸦，红金配色热烈醒目，动感构图，粗壮的汉字排印，高能量设计',
  negative_prompt = '背景单调，色彩暗淡，缺乏活力' WHERE id = 'tpl012';
UPDATE prompt_template SET
  prompt = '无人机航拍云海之上的雪山群峰，金色晨光洒满山巅，壮阔的山谷，空气澄澈，气势恢宏，风光摄影杰作',
  negative_prompt = '阴天灰云，低分辨率，水印' WHERE id = 'tpl013';
UPDATE prompt_template SET
  prompt = '温馨的清晨咖啡馆，老人坐在窗边读报，暖阳洒在桌上，咖啡杯升腾热气，浅景深，电影感街头摄影，怀旧氛围',
  negative_prompt = '现代室内装修，生硬闪光灯，脸部模糊' WHERE id = 'tpl014';
UPDATE prompt_template SET
  prompt = '巨型飞船飞越外星行星的电影运镜镜头，摄像机从下方环绕上升，引擎辉光照亮云层，史诗级尺度，科幻电影特效，运镜顺滑',
  negative_prompt = '镜头抖动，低细节，静态镜头' WHERE id = 'tpl015';
