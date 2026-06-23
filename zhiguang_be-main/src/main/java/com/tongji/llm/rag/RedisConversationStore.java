package com.tongji.llm.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.knowpost.api.dto.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Redis 对话会话存储。
 *
 * <p>Key: {@code rag:session:{postId}:{userId}:{sessionId}}
 * <br>Value: JSON 消息数组，TTL 7 天
 */
@Component
public class RedisConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);
    private static final ObjectMapper json = new ObjectMapper();
    private static final Duration TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "rag:session:";

    private final StringRedisTemplate redis;

    public RedisConversationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<ChatMessage> load(String postId, String userId, String sessionId) {
        if (!StringUtils.hasText(postId) || !StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) {
            return Collections.emptyList();
        }
        try {
            String raw = redis.opsForValue().get(key(postId, userId, sessionId));
            if (!StringUtils.hasText(raw)) return Collections.emptyList();
            return json.readValue(raw, new TypeReference<List<ChatMessage>>() {});
        } catch (Exception e) {
            log.warn("Failed to load conversation {}:{}:{}: {}", postId, userId, sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void append(String postId, String userId, String sessionId, ChatMessage userMsg, ChatMessage assistantMsg) {
        if (!StringUtils.hasText(postId) || !StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) return;
        try {
            List<ChatMessage> history = load(postId, userId, sessionId);
            List<ChatMessage> updated = new ArrayList<>(history.size() + 2);
            updated.addAll(history);
            updated.add(userMsg);
            updated.add(assistantMsg);
            String raw = json.writeValueAsString(updated);
            String k = key(postId, userId, sessionId);
            redis.opsForValue().set(k, raw);
            redis.expire(k, TTL);
        } catch (Exception e) {
            log.warn("Failed to append conversation {}:{}:{}: {}", postId, userId, sessionId, e.getMessage());
        }
    }

    @Override
    public String create(String postId, String userId) {
        return UUID.randomUUID().toString();
    }

    @Override
    public List<SessionInfo> listSessions(String postId, String userId) {
        if (!StringUtils.hasText(postId) || !StringUtils.hasText(userId)) return Collections.emptyList();
        List<SessionInfo> sessions = new ArrayList<>();
        try {
            String pattern = KEY_PREFIX + postId + ":" + userId + ":*";
            redis.execute((RedisConnection connection) -> {
                try (Cursor<byte[]> cursor = connection.keyCommands()
                        .scan(ScanOptions.scanOptions().match(pattern).count(20).build())) {
                    while (cursor.hasNext()) {
                        String fullKey = new String(cursor.next(), StandardCharsets.UTF_8);
                        String sid = fullKey.substring(fullKey.lastIndexOf(':') + 1);
                        List<ChatMessage> msgs = load(postId, userId, sid);
                        String title = extractTitle(msgs);
                        long count = msgs.size();
                        long createdAt = msgs.isEmpty() ? 0 : msgs.get(0).timestamp();
                        long updatedAt = msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).timestamp();
                        sessions.add(new SessionInfo(sid, title, count, createdAt, updatedAt));
                    }
                } catch (Exception e) {
                    log.warn("Scan sessions failed for {}:{}: {}", postId, userId, e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("List sessions failed for {}:{}: {}", postId, userId, e.getMessage());
        }
        sessions.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
        return sessions;
    }

    @Override
    public void delete(String postId, String userId, String sessionId) {
        if (!StringUtils.hasText(postId) || !StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) return;
        redis.delete(key(postId, userId, sessionId));
    }

    private String extractTitle(List<ChatMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return "新对话";
        for (ChatMessage m : msgs) {
            if ("user".equals(m.role()) && StringUtils.hasText(m.content())) {
                String c = m.content();
                return c.length() > 30 ? c.substring(0, 30) + "..." : c;
            }
        }
        return "新对话";
    }

    private static String key(String postId, String userId, String sessionId) {
        return KEY_PREFIX + postId + ":" + userId + ":" + sessionId;
    }
}
