package com.platform.agent.rag.service;

import java.util.List;

/**
 * 向量相似度计算工具。
 */
public final class VectorSimilarity {

    private VectorSimilarity() {
    }

    /**
     * 余弦相似度（0~1）。维度不一致或零向量返回 0。
     */
    public static double cosine(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static double[] toArray(List<Double> list) {
        if (list == null) return new double[0];
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i) != null ? list.get(i) : 0;
        }
        return arr;
    }
}
