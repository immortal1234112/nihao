package com.platform.agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 检索结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResult {

    private String query;

    private List<RagReference> chunks;
}
