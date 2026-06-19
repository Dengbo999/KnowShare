package com.tongji.llm.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tongji.config.EsProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

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
 * 1) 向量检索：解决“语义相关但词面不一致”的召回问题；
 * 2) 关键词检索：解决“术语、数字、专有词必须精确命中”的召回问题；
 * 3) RRF 融合：将两路结果统一排序，降低单一路径漏召回风险。
 *
 * <p>注意：本类只负责“召回 + 融合 + 组装上下文”，最终回答由大模型生成。
 */
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);

    // 实际召回数 = topK * FETCH_MULTIPLIER，避免只拿 topK 导致融合空间太小
    private static final int FETCH_MULTIPLIER = 3;
    // 兜底召回下限：即使 topK 很小，也至少召回一定数量用于融合
    private static final int MIN_FETCH_K = 20;
    // RRF 的常用平滑常数，值越大越“平滑”，减少前几名绝对优势
    private static final double RRF_K = 60.0;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final RagIndexService indexService;
    private final ElasticsearchClient es;
    private final EsProperties esProperties;

    /**
     * 流式问答主入口。
     *
     * @param postId    目标知文 ID（只在该文分块范围内检索）
     * @param question  用户问题
     * @param topK      最终送入模型的上下文分块数量
     * @param maxTokens 模型输出 token 上限
     * @return SSE/Flux 流式文本
     */
    public Flux<String> streamAnswerFlux(long postId, String question, int topK, int maxTokens) {
        // 查询前先确保该文章已完成（或校验）索引，避免“问到了但库里没切片”
        indexService.ensureIndexed(postId);

        // 混合检索得到上下文片段，再拼接为一个上下文字符串喂给模型
        List<String> contexts = searchContexts(String.valueOf(postId), question, Math.max(1, topK));
        String context = String.join("\n\n---\n\n", contexts);

        // 强约束提示词：只允许基于上下文回答，避免模型胡编
        String system = "你是中文知识助手。只能依据提供的上下文回答；无法确定时明确说不确定。";
        String user = "问题：" + question + "\n\n上下文如下（可能不完整）：\n"
                + context + "\n\n请基于以上上下文作答。";

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
     * 混合检索入口：
     * - 向量召回 + 关键词召回
     * - RRF 融合
     * - 截取 topK 上下文
     */
    private List<String> searchContexts(String postId, String query, int topK) {
        int fetchK = Math.max(topK * FETCH_MULTIPLIER, MIN_FETCH_K);

        List<RetrievalHit> vectorHits = vectorSearch(postId, query, fetchK);
        List<RetrievalHit> keywordHits = keywordSearch(postId, query, fetchK);
        List<RetrievalHit> fusedHits = fuseByRrf(vectorHits, keywordHits);

        List<String> contexts = new ArrayList<>(topK);
        for (RetrievalHit hit : fusedHits) {
            if (!StringUtils.hasText(hit.text())) {
                continue;
            }
            contexts.add(hit.text());
            if (contexts.size() >= topK) {
                break;
            }
        }
        return contexts;
    }

    /**
     * 向量召回：
     * - 从向量库按语义相似度取前 fetchK
     * - 再按 metadata.postId 做二次过滤，确保只拿当前文章
     */
    private List<RetrievalHit> vectorSearch(String postId, String query, int fetchK) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(fetchK).build()
        );

        if (docs == null || docs.isEmpty()) {
            return List.of();
        }

        List<RetrievalHit> hits = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            Map<String, Object> metadata = doc.getMetadata() == null ? Collections.emptyMap() : doc.getMetadata();
            String pid = asString(metadata.get("postId"));
            if (!postId.equals(pid)) {
                continue;
            }

            String text = doc.getText();
            if (!StringUtils.hasText(text)) {
                continue;
            }

            // chunkId 是跨检索通道去重的主键；缺失时退化用文档 id
            String chunkId = firstNonBlank(asString(metadata.get("chunkId")), doc.getId());
            int position = asInt(metadata.get("position"), Integer.MAX_VALUE);
            hits.add(new RetrievalHit(firstNonBlank(chunkId, UUID.randomUUID().toString()), position, text));
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    /**
     * 关键词召回：
     * - 在 ES 的 content 字段上做 match
     * - 用 metadata.postId 过滤到当前文章（兼容 keyword / 非 keyword 映射）
     */
    private List<RetrievalHit> keywordSearch(String postId, String query, int fetchK) {
        // 未配置向量索引名时，无法直接查该索引，返回空让系统自动退化
        if (!StringUtils.hasText(esProperties.getIndex())) {
            return List.of();
        }

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
                                            .minimumShouldMatch("1")
                                    ))
                            )),
                    (Class<Map<String, Object>>) (Class<?>) Map.class
            );

            List<Hit<Map<String, Object>>> esHits = resp.hits() == null ? List.of() : resp.hits().hits();
            if (esHits == null || esHits.isEmpty()) {
                return List.of();
            }

            List<RetrievalHit> hits = new ArrayList<>(esHits.size());
            for (Hit<Map<String, Object>> hit : esHits) {
                Map<String, Object> source = hit.source();
                if (source == null || source.isEmpty()) {
                    continue;
                }

                String text = asString(source.get("content"));
                if (!StringUtils.hasText(text)) {
                    continue;
                }

                Map<String, Object> metadata = asMap(source.get("metadata"));
                String pid = asString(metadata.get("postId"));
                if (!postId.equals(pid)) {
                    continue;
                }

                String chunkId = firstNonBlank(asString(metadata.get("chunkId")), hit.id());
                int position = asInt(metadata.get("position"), Integer.MAX_VALUE);
                hits.add(new RetrievalHit(firstNonBlank(chunkId, UUID.randomUUID().toString()), position, text));
            }
            return hits;
        } catch (Exception e) {
            // 关键词检索失败不影响主链路：降级为仅向量召回
            log.warn("Keyword retrieval failed, fallback to vector only: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合两路结果。
     *
     * <p>每一路结果按名次给分：score += 1 / (k + rank)。
     * 不依赖原始分值尺度，适合融合不同检索器（向量分与 BM25 分不可直接相加）。
     */
    private List<RetrievalHit> fuseByRrf(List<RetrievalHit> vectorHits, List<RetrievalHit> keywordHits) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, RetrievalHit> kept = new HashMap<>();

        applyRrf(vectorHits, scores, kept);
        applyRrf(keywordHits, scores, kept);

        if (scores.isEmpty()) {
            return List.of();
        }

        List<Map.Entry<String, Double>> rank = new ArrayList<>(scores.entrySet());
        rank.sort((a, b) -> {
            // 先按融合分降序，再按原文位置升序做稳定 tie-break
            int scoreCmp = Double.compare(b.getValue(), a.getValue());
            if (scoreCmp != 0) {
                return scoreCmp;
            }
            RetrievalHit left = kept.get(a.getKey());
            RetrievalHit right = kept.get(b.getKey());
            int lp = left == null ? Integer.MAX_VALUE : left.position();
            int rp = right == null ? Integer.MAX_VALUE : right.position();
            return Integer.compare(lp, rp);
        });

        List<RetrievalHit> out = new ArrayList<>(rank.size());
        for (Map.Entry<String, Double> e : rank) {
            RetrievalHit hit = kept.get(e.getKey());
            if (hit != null) {
                out.add(hit);
            }
        }
        return out;
    }

    /**
     * 对单路召回应用 RRF 计分。
     */
    private void applyRrf(List<RetrievalHit> hits, Map<String, Double> scores, Map<String, RetrievalHit> kept) {
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            String key = keyOf(hit);
            double gain = 1.0 / (RRF_K + i + 1);
            scores.merge(key, gain, Double::sum);

            RetrievalHit current = kept.get(key);
            if (current == null || hit.position() < current.position()) {
                kept.put(key, hit);
            }
        }
    }

    /**
     * 用于去重的稳定 key：
     * - 优先 chunkId（索引时已写入）
     * - 没有 chunkId 时退化为 (text, position) 哈希
     */
    private String keyOf(RetrievalHit hit) {
        if (StringUtils.hasText(hit.chunkId())) {
            return hit.chunkId();
        }
        return String.valueOf(Objects.hash(hit.text(), hit.position()));
    }

    @SuppressWarnings("unchecked")
    // 安全把 Object 转成 Map<String, Object>，避免 ClassCastException
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Collections.emptyMap();
        }
        Map<String, Object> out = new HashMap<>(raw.size());
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    // 统一 null -> String 处理，减少重复判空
    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // 统一 Object -> int 转换，失败时使用 fallback
    private int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    // 从候选值里取第一个非空白字符串
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }

    /**
     * 检索统一结构：
     * - chunkId: 分块主键（用于去重）
     * - position: 原文中分块顺序（用于稳定排序）
     * - text: 分块正文
     */
    private record RetrievalHit(String chunkId, int position, String text) {
    }
}
