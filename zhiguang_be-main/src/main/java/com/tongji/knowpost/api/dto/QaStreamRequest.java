package com.tongji.knowpost.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * RAG 流式问答请求体。
 */
public record QaStreamRequest(
        @NotBlank String question,
        String sessionId,
        String scope,
        int topK,
        int maxTokens
) {
    public String scope() {
        return scope != null && !scope.isBlank() ? scope : "single";
    }

    public int topK() {
        return topK > 0 ? topK : 5;
    }

    public int maxTokens() {
        return maxTokens > 0 ? maxTokens : 1024;
    }
}
