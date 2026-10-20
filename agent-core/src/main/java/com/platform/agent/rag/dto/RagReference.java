package com.platform.agent.rag.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索引用（单条命中）：这一轮回答依据了哪些外部资料，存入
 * {@code chat_message.references_json}，前端以「参考资料」UI 渲染。
 * <p>
 * 来源有两种：
 * <ul>
 *   <li>{@link #SOURCE_RAG} 知识库分块（相似度 = 余弦相似度）</li>
 *   <li>{@link #SOURCE_WEB} 联网搜索网页（相似度 = 上游排序分归一化）</li>
 * </ul>
 * {@code sourceType} 是后加的可空字段：更早的存量 JSON 反序列化后为 null，
 * 消费方按「非 WEB 即 RAG」处理即可。
 */
@Data
@NoArgsConstructor
public class RagReference {

    /** 知识库分块来源 */
    public static final String SOURCE_RAG = "RAG";

    /** 联网搜索网页来源 */
    public static final String SOURCE_WEB = "WEB";

    /** RAG：分块 id；WEB：合成 id（web-N） */
    private String chunkId;

    /** RAG：分块正文；WEB：网页正文/摘要 */
    private String content;

    /** 相关度 0~1（RAG=余弦相似度，WEB=搜索排序分按 0~3 归一化） */
    private double similarity;

    /** 仅 RAG：所属文档 id */
    private String documentId;

    /** RAG：来源文档名；WEB：网页标题 */
    private String documentName;

    /** RAG=知识库分块（存量数据为 null，视为 RAG）| WEB=联网搜索 */
    private String sourceType;

    /** 仅 WEB：网页地址 */
    private String url;

    /** 仅 WEB：站点域名，如 github.com */
    private String site;

    /** 知识库引用的构造点（新建引用恒为 RAG 来源） */
    public RagReference(String chunkId, String content, double similarity,
                        String documentId, String documentName) {
        this.chunkId = chunkId;
        this.content = content;
        this.similarity = similarity;
        this.documentId = documentId;
        this.documentName = documentName;
        this.sourceType = SOURCE_RAG;
    }

    /** 是否 WEB 来源（存量数据 sourceType 为 null，判为 RAG） */
    public boolean isWeb() {
        return SOURCE_WEB.equals(sourceType);
    }
}
