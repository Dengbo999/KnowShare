package com.tongji.knowpost.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * RAG 流式问答请求体。
 * sessionId 为空时自动创建新会话。
 */
public record QaStreamRequest(
        @NotBlank String question,
        String sessionId,
        int topK,
        int maxTokens
) {
    public int topK() {
        return topK > 0 ? topK : 5;
    }

    public int maxTokens() {
        return maxTokens > 0 ? maxTokens : 1024;
    }
}
