package com.tongji.llm.rag;

import com.tongji.knowpost.api.dto.ChatMessage;

import java.util.List;

/**
 * 对话会话存储抽象。所有方法均绑定用户，实现按文章+用户隔离。
 */
public interface ConversationStore {

    /** 加载用户在某文章下的指定会话历史。 */
    List<ChatMessage> load(String postId, String userId, String sessionId);

    /** 追加一轮对话，同时续期 TTL。 */
    void append(String postId, String userId, String sessionId, ChatMessage userMsg, ChatMessage assistantMsg);

    /** 创建新会话，返回 sessionId。 */
    String create(String postId, String userId);

    /** 列出用户在某文章下的所有会话摘要。 */
    List<SessionInfo> listSessions(String postId, String userId);

    /** 删除用户在某文章下的指定会话。 */
    void delete(String postId, String userId, String sessionId);
}
