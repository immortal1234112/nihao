package com.platform.agent.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块服务（轻量版）。
 * 策略：按空行切段落 → 长段落按句子分隔符再切 → 贪心拼接为 ≤chunkSize 的块，相邻块保留 overlap 衔接。
 */
@Service
public class ChunkingService {

    private static final String SENTENCE_DELIMITERS = "。！？；!?;\n";

    /**
     * 分块。
     *
     * @param text      原始文本
     * @param chunkSize 单块目标长度
     * @param overlap   相邻块重叠字符数
     */
    public List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        int size = Math.max(50, chunkSize);
        int ov = Math.max(0, Math.min(overlap, size / 2));
        return packChunks(splitSegments(text, size), size, ov);
    }

    /** 段落 → 句段（每段 ≤ chunkSize） */
    private List<String> splitSegments(String text, int chunkSize) {
        List<String> segments = new ArrayList<>();
        for (String para : text.split("\\n\\s*\\n")) {
            para = para.trim();
            if (para.isEmpty()) continue;
            if (para.length() <= chunkSize) {
                segments.add(para);
                continue;
            }
            // 长段落按句子切
            StringBuilder buf = new StringBuilder();
            for (String sentence : splitSentences(para)) {
                if (sentence.length() > chunkSize) {
                    if (buf.length() > 0) {
                        segments.add(buf.toString());
                        buf.setLength(0);
                    }
                    segments.addAll(hardSplit(sentence, chunkSize));
                } else if (buf.length() + sentence.length() > chunkSize && buf.length() > 0) {
                    segments.add(buf.toString());
                    buf.setLength(0);
                    buf.append(sentence);
                } else {
                    buf.append(sentence);
                }
            }
            if (buf.length() > 0) {
                segments.add(buf.toString());
            }
        }
        return segments;
    }

    private List<String> splitSentences(String para) {
        List<String> sentences = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (char c : para.toCharArray()) {
            buf.append(c);
            if (SENTENCE_DELIMITERS.indexOf(c) >= 0) {
                sentences.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            sentences.add(buf.toString());
        }
        return sentences;
    }

    private List<String> hardSplit(String s, int chunkSize) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < s.length(); i += chunkSize) {
            parts.add(s.substring(i, Math.min(i + chunkSize, s.length())));
        }
        return parts;
    }

    /** 贪心打包，块间保留 overlap 字符衔接 */
    private List<String> packChunks(List<String> segments, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String seg : segments) {
            if (current.length() + seg.length() > chunkSize && current.length() > 0) {
                chunks.add(current.toString());
                // 保留上一块末尾 overlap 字符作为上下文衔接
                String tail = current.substring(Math.max(0, current.length() - overlap));
                current = new StringBuilder(tail);
            }
            current.append(seg);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
