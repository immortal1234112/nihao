package com.platform.agent.search.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

import com.platform.agent.rag.dto.RagReference;

/**
 * 联网搜索的结果封装：既带数据，也带「为什么没有数据」。
 * <p>
 * 之所以不直接返回 {@code List<RagReference>}，是因为「搜索成功但没有结果」和
 * 「搜索失败」对用户是完全不同的两件事：前者正常，后者需要提示。
 * 如果只返回空列表，上层就无法区分，前端只能显示「0 条」——这正是之前
 * Key 鉴权失败时表现成「没搜到」的根因。
 */
@Data
@AllArgsConstructor
public class WebSearchOutcome {

    /** 命中结果（失败或无结果时为空列表，非 null） */
    private final List<RagReference> references;

    /** 失败原因；null 表示调用成功（哪怕 0 条结果） */
    private final String error;

    /** 上游 HTTP 状态码；无则为 0，仅用于前端区分「配置错误」与「网络抖动」 */
    private final int statusCode;

    public static WebSearchOutcome ok(List<RagReference> refs) {
        return new WebSearchOutcome(refs, null, 0);
    }

    public static WebSearchOutcome failed(String error, int statusCode) {
        return new WebSearchOutcome(List.of(), error, statusCode);
    }

    public boolean hasError() {
        return error != null;
    }

    /**
     * 是否属于「配置类」错误：这类错误重试无用，必须让用户去改配置。
     * 401 Key 无效 / 402 欠费 / 403 被拒 都属于此类，前端应显眼提示而非轻描淡写。
     */
    public boolean isConfigError() {
        return statusCode == 401 || statusCode == 402 || statusCode == 403;
    }
}
