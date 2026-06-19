package com.tongji.knowpost.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对话消息（OpenAI 兼容格式），用于多轮对话历史。
 */
public record ChatMessage(String role, String content, long timestamp) {

    @JsonCreator
    public ChatMessage(@JsonProperty("role") String role,
                       @JsonProperty("content") String content,
                       @JsonProperty("timestamp") long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    /** 便捷构造：timestamp 默认取当前时间。 */
    public ChatMessage(String role, String content) {
        this(role, content, System.currentTimeMillis());
    }
}
