package com.tongji.llm.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tongji.config.EsProperties;
import com.tongji.knowpost.api.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * RAG 查询服务（混合检索）。
 *
 * <p>整体思路：
 * 1) 向量检索：解决"语义相关但词面不一致"的召回问题；
 * 2) 关键词检索：解决"术语、数字、专有词必须精确命中"的召回问题；
 * 3) RRF 融合：将两路结果统一排序，降低单一路径漏召回风险。
 * 4) 查询改写：HyDE + 关键词提取，提升召回质量。
 * 5) Reranker：Cross-Encoder 精细重排序。
 *
 * <p>注意：本类只负责"召回 + 融合 + 组装上下文"，最终回答由大模型生成。
 */
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);

    private static final int FETCH_MULTIPLIER = 3;
    private static final int MIN_FETCH_K = 20;
    private static final double RRF_K = 60.0;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final RagIndexService indexService;
    private final ElasticsearchClient es;
    private final EsProperties esProperties;
    private final ConversationStore conversationStore;
    private final QueryRewriter queryRewriter = new QueryRewriter();

    @Value("${spring.ai.openai.api-key}")
    private String dashScopeApiKey;
    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;
    @Value("${rag.rerank.model:gte-rerank-v2}")
    private String rerankModel;
    private RerankService rerankService;

    @PostConstruct
    void initRerank() {
        if (StringUtils.hasText(dashScopeApiKey)) {
            this.rerankService = new RerankService(dashScopeApiKey, rerankModel);
        }
    }

    /**
     * 流式问答主入口（Redis 会话模式）。
     *
     * @param userId    用户 ID，为 null 时退化为无状态模式（不持久化）
     * @param sessionId 会话 ID，为 null 或空时创建新会话
     */
    public Flux<String> streamAnswerFlux(long postId, String question,
                                          String userId, String sessionId,
                                          int topK, int maxTokens) {
        String pid = String.valueOf(postId);

        // 匿名用户：无状态模式，不读不写 Redis
        if (userId == null || userId.isBlank()) {
            return streamWithHistory(postId, question, null, topK, maxTokens);
        }

        String sid = (sessionId != null && !sessionId.isBlank())
                ? sessionId : conversationStore.create(pid, userId);

        List<ChatMessage> history = conversationStore.load(pid, userId, sid);
        Flux<String> stream = streamWithHistory(postId, question, history, topK, maxTokens);

        StringBuilder fullAnswer = new StringBuilder();
        return stream
                .doOnNext(fullAnswer::append)
                .doOnComplete(() -> conversationStore.append(pid, userId, sid,
                        new ChatMessage("user", question),
                        new ChatMessage("assistant", fullAnswer.toString())))
                .doOnError(e -> log.warn("Stream error for session {}: {}", sid, e.getMessage()));
    }

    /** 无会话的简化入口（兼容旧 GET 接口，不持久化历史）。 */
    public Flux<String> streamAnswerFlux(long postId, String question, int topK, int maxTokens) {
        return streamWithHistory(postId, question, null, topK, maxTokens);
    }

    /** 带历史的简化入口（兼容旧调用）。 */
    public Flux<String> streamAnswerFlux(long postId, String question,
                                          List<ChatMessage> history, int topK, int maxTokens) {
        return streamWithHistory(postId, question, history, topK, maxTokens);
    }

    /** 核心流式逻辑：检索 + 组装 prompt + 调用 LLM。 */
    private Flux<String> streamWithHistory(long postId, String question,
                                            List<ChatMessage> history, int topK, int maxTokens) {
        indexService.ensureIndexed(postId);

        List<String> contexts = searchContexts(String.valueOf(postId), question, Math.max(1, topK));
        String context = String.join("\n\n---\n\n", contexts);

        String system = "你是中文知识助手。只能依据提供的上下文回答；无法确定时明确说不确定。";
        String user = buildUserPrompt(question, context, history);

        return chatClient
                .prompt()
                .system(system)
                .user(user)
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-chat")
                        .temperature(0.2)
                        .maxTokens(maxTokens)
                        .build())
                .stream()
                .content();
    }

    /**
     * 组装用户 prompt：对话历史 + 当前问题 + 检索上下文。
     */
    private String buildUserPrompt(String question, String context, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();

        if (history != null && !history.isEmpty()) {
            List<ChatMessage> trimmed = trimHistory(history, 10);
            sb.append("[对话历史]\n");
            for (ChatMessage msg : trimmed) {
                String label = "user".equals(msg.role()) ? "用户" : "助手";
                sb.append(label).append(": ").append(msg.content()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("[当前问题]\n");
        sb.append(question).append("\n\n");

        sb.append("[参考资料]\n");
        sb.append(context).append("\n\n");

        if (history != null && !history.isEmpty()) {
            sb.append("请基于以上资料和对话历史作答。如果当前问题和历史对话有关，请结合上下文连贯回答。");
        } else {
            sb.append("请基于以上资料作答。");
        }
        return sb.toString();
    }

    /** 历史截断：保留最近 maxTurns 轮。 */
    private List<ChatMessage> trimHistory(List<ChatMessage> history, int maxTurns) {
        int maxMessages = maxTurns * 2;
        if (history.size() <= maxMessages) return history;
        return history.subList(history.size() - maxMessages, history.size());
    }

    // ═══════════════════════════════════════════════
    //  混合检索
    // ═══════════════════════════════════════════════

    private List<String> searchContexts(String postId, String query, int topK) {
        int fetchK = Math.max(topK * FETCH_MULTIPLIER, MIN_FETCH_K);

        RewrittenQuery rewritten = queryRewriter.rewrite(chatClient, query);
        String vectorQuery = rewritten.hypotheticalAnswer() != null ? rewritten.hypotheticalAnswer() : query;
        String keywordQuery = rewritten.searchQuery() != null ? rewritten.searchQuery() : query;

        List<RetrievalHit> vectorHits = vectorSearch(postId, vectorQuery, fetchK);
        List<RetrievalHit> keywordHits = keywordSearch(postId, keywordQuery, fetchK);
        List<RetrievalHit> fusedHits = fuseByRrf(vectorHits, keywordHits);

        List<RetrievalHit> reranked = fusedHits;
        if (rerankEnabled && rerankService != null) {
            reranked = rerankService.rerank(query, fusedHits, topK);
        }

        List<String> contexts = new ArrayList<>(topK);
        for (RetrievalHit hit : reranked) {
            if (!StringUtils.hasText(hit.text())) continue;
            contexts.add(hit.text());
            if (contexts.size() >= topK) break;
        }
        return contexts;
    }

    private List<RetrievalHit> vectorSearch(String postId, String query, int fetchK) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(fetchK).build()
        );
        if (docs == null || docs.isEmpty()) return List.of();

        List<RetrievalHit> hits = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            Map<String, Object> metadata = doc.getMetadata() == null ? Collections.emptyMap() : doc.getMetadata();
            if (!postId.equals(asString(metadata.get("postId")))) continue;
            String text = doc.getText();
            if (!StringUtils.hasText(text)) continue;
            String chunkId = firstNonBlank(asString(metadata.get("chunkId")), doc.getId());
            int position = asInt(metadata.get("position"), Integer.MAX_VALUE);
            hits.add(new RetrievalHit(firstNonBlank(chunkId, UUID.randomUUID().toString()), position, text));
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    private List<RetrievalHit> keywordSearch(String postId, String query, int fetchK) {
        if (!StringUtils.hasText(esProperties.getIndex())) return List.of();
        try {
            SearchResponse<Map<String, Object>> resp = es.search(s -> s
                    .index(esProperties.getIndex())
                    .size(fetchK)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(mm -> mm.field("content").query(query)))
                            .filter(f -> f.bool(fb -> fb
                                    .should(sh -> sh.term(t -> t.field("metadata.postId.keyword")
                                            .value(v -> v.stringValue(postId))))
                                    .should(sh -> sh.term(t -> t.field("metadata.postId")
                                            .value(v -> v.stringValue(postId))))
                                    .minimumShouldMatch("1"))
                            ))),
                    (Class<Map<String, Object>>) (Class<?>) Map.class
            );
            List<Hit<Map<String, Object>>> esHits = resp.hits() == null ? List.of() : resp.hits().hits();
            if (esHits == null || esHits.isEmpty()) return List.of();

            List<RetrievalHit> hits = new ArrayList<>(esHits.size());
            for (Hit<Map<String, Object>> hit : esHits) {
                Map<String, Object> source = hit.source();
                if (source == null || source.isEmpty()) continue;
                String text = asString(source.get("content"));
                if (!StringUtils.hasText(text)) continue;
                Map<String, Object> metadata = asMap(source.get("metadata"));
                if (!postId.equals(asString(metadata.get("postId")))) continue;
                String chunkId = firstNonBlank(asString(metadata.get("chunkId")), hit.id());
                int position = asInt(metadata.get("position"), Integer.MAX_VALUE);
                hits.add(new RetrievalHit(firstNonBlank(chunkId, UUID.randomUUID().toString()), position, text));
            }
            return hits;
        } catch (Exception e) {
            log.warn("Keyword retrieval failed, fallback to vector only: {}", e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════
    //  RRF 融合
    // ═══════════════════════════════════════════════

    private List<RetrievalHit> fuseByRrf(List<RetrievalHit> vectorHits, List<RetrievalHit> keywordHits) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, RetrievalHit> kept = new HashMap<>();
        applyRrf(vectorHits, scores, kept);
        applyRrf(keywordHits, scores, kept);
        if (scores.isEmpty()) return List.of();

        List<Map.Entry<String, Double>> rank = new ArrayList<>(scores.entrySet());
        rank.sort((a, b) -> {
            int scoreCmp = Double.compare(b.getValue(), a.getValue());
            if (scoreCmp != 0) return scoreCmp;
            RetrievalHit left = kept.get(a.getKey());
            RetrievalHit right = kept.get(b.getKey());
            int lp = left == null ? Integer.MAX_VALUE : left.position();
            int rp = right == null ? Integer.MAX_VALUE : right.position();
            return Integer.compare(lp, rp);
        });

        List<RetrievalHit> out = new ArrayList<>(rank.size());
        for (Map.Entry<String, Double> e : rank) {
            RetrievalHit hit = kept.get(e.getKey());
            if (hit != null) out.add(hit);
        }
        return out;
    }

    private void applyRrf(List<RetrievalHit> hits, Map<String, Double> scores, Map<String, RetrievalHit> kept) {
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            String key = keyOf(hit);
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            RetrievalHit current = kept.get(key);
            if (current == null || hit.position() < current.position()) {
                kept.put(key, hit);
            }
        }
    }

    private String keyOf(RetrievalHit hit) {
        if (StringUtils.hasText(hit.chunkId())) return hit.chunkId();
        return String.valueOf(Objects.hash(hit.text(), hit.position()));
    }

    // ═══════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Collections.emptyMap();
        Map<String, Object> out = new HashMap<>(raw.size());
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private String asString(Object value) { return value == null ? null : String.valueOf(value); }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }
}
