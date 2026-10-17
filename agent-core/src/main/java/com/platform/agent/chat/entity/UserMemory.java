package com.platform.agent.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 用户记忆实体（长期/短期双板块）。
 * embedding 为 JSON 数组，实体侧用 String 承载（与 rag_chunk.embedding 同风格），检索在应用层做余弦。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("user_memory")
public class UserMemory extends BaseEntity {

    public static final String TYPE_FACT = "FACT";
    public static final String TYPE_PREFERENCE = "PREFERENCE";

    /** 长期板块：用户手动配置，作为对话基础无条件注入 */
    public static final String CATEGORY_LONG_TERM = "LONG_TERM";
    /** 短期板块：自动提取/「记住 X」指令产生，按相关性检索注入 */
    public static final String CATEGORY_SHORT_TERM = "SHORT_TERM";

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    /** 所属记忆空间（{@link MemorySpace}）；存量数据由迁移脚本回填为默认空间 */
    @TableField("space_id")
    private String spaceId;

    @TableField("content")
    private String content;

    @TableField("memory_type")
    private String memoryType;

    @TableField("category")
    private String category;

    @TableField("source_conversation_id")
    private String sourceConversationId;

    @TableField("embedding")
    private String embeddingJson;

    @TableField("token_count")
    private Integer tokenCount;

    /**
     * 软删标记（0 正常 / 1 已删）。
     * 不用 @TableLogic：提取去重必须能看到已删记录（防止删除后同内容被重新提取"复活"），
     * 检索/列表/计数手动过滤 deleted=0。
     */
    @TableField("deleted")
    private Integer deleted;
}
