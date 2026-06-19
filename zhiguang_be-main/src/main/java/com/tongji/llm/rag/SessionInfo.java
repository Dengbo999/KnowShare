package com.tongji.llm.rag;

/**
 * 会话摘要信息（用于会话列表）。
 */
public record SessionInfo(String sessionId, String title, long messageCount, long createdAt, long updatedAt) {
}
