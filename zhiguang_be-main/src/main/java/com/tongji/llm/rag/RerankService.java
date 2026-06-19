package com.tongji.llm.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 重排序服务：调用 DashScope Rerank API 对候选文档做 Cross-Encoder 级别的精细打分。
 *
 * <p>插入位置：RRF 融合之后、topK 截断之前。失败时返回原始列表（静默降级）。
 */
class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);
    private static final ObjectMapper json = new ObjectMapper();
    private static final String API_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final RestTemplate http;
    private final String apiKey;
    private final String model;

    RerankService(String apiKey, String model) {
        this(new RestTemplate(), apiKey, model);
    }

    /** 测试用：允许注入 mock RestTemplate。 */
    RerankService(RestTemplate http, String apiKey, String model) {
        this.http = http;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 对候选列表重排序，返回按相关度降序的新列表。
     *
     * @param query      用户问题（原始问题即可）
     * @param candidates RRF 融合后的候选列表
     * @param topN       重排序后保留的 TopN
     * @return 重新排序后的列表（长度为 min(topN, candidates.size())）
     */
    List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates, int topN) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates != null ? candidates : Collections.emptyList();
        }
        if (!StringUtils.hasText(query)) {
            return candidates;
        }
        if (!StringUtils.hasText(apiKey)) {
            log.debug("Rerank skipped: no API key configured");
            return candidates;
        }

        try {
            // 构建请求体
            Map<String, Object> requestBody = buildRequest(query, candidates, topN);

            // 发送 HTTP 请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> resp = http.postForEntity(API_URL, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(resp.getBody())) {
                log.warn("Rerank API returned non-OK: {}", resp.getStatusCode());
                return candidates;
            }

            // 解析响应
            return parseResponse(resp.getBody(), candidates);
        } catch (Exception e) {
            log.warn("Rerank failed, falling back to RRF order: {}", e.getMessage());
            return candidates;
        }
    }

    /**
     * 构建 DashScope Rerank API 请求体。
     */
    private Map<String, Object> buildRequest(String query, List<RetrievalHit> candidates, int topN) {
        List<String> documents = new ArrayList<>(candidates.size());
        for (RetrievalHit hit : candidates) {
            documents.add(hit.text());
        }

        Map<String, Object> input = Map.of(
                "query", query,
                "documents", documents
        );

        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("top_n", Math.min(topN, documents.size()));
        parameters.put("return_documents", false);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        return body;
    }

    /**
     * 解析 API 响应，按 relevance_score 降序重建候选列表。
     *
     * <p>响应格式：{"output":{"results":[{"index":2,"relevance_score":0.95}, ...]}}
     */
    @SuppressWarnings("unchecked")
    private List<RetrievalHit> parseResponse(String body, List<RetrievalHit> candidates) {
        try {
            Map<String, Object> root = json.readValue(body, new TypeReference<>() {});
            Map<String, Object> output = (Map<String, Object>) root.get("output");
            if (output == null) {
                log.warn("Rerank response missing 'output' field");
                return candidates;
            }
            List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
            if (results == null || results.isEmpty()) {
                log.warn("Rerank response has empty results");
                return candidates;
            }

            // 按 relevance_score 降序排列
            List<Map<String, Object>> sorted = new ArrayList<>(results);
            sorted.sort(Comparator.<Map<String, Object>, Double>comparing(
                    m -> ((Number) m.getOrDefault("relevance_score", 0.0)).doubleValue()
            ).reversed());

            // 按重排序后的索引重建候选列表
            List<RetrievalHit> reranked = new ArrayList<>(sorted.size());
            for (Map<String, Object> entry : sorted) {
                int idx = ((Number) entry.get("index")).intValue();
                if (idx >= 0 && idx < candidates.size()) {
                    reranked.add(candidates.get(idx));
                }
            }
            return reranked;
        } catch (Exception e) {
            log.warn("Failed to parse rerank response: {}", e.getMessage());
            return candidates;
        }
    }
}
