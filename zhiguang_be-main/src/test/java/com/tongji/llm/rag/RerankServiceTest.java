package com.tongji.llm.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RerankServiceTest {

    private RestTemplate mockHttp;
    private RerankService service;

    // 预置候选列表
    private List<RetrievalHit> candidates;

    @BeforeEach
    void setUp() {
        mockHttp = mock(RestTemplate.class);
        service = new RerankService(mockHttp, "test-api-key", "gte-rerank-v2");

        candidates = new ArrayList<>();
        candidates.add(new RetrievalHit("1#0", 0, "文本排序模型广泛应用于搜索引擎和推荐系统中"));
        candidates.add(new RetrievalHit("1#1", 1, "量子计算是计算科学的一个前沿领域"));
        candidates.add(new RetrievalHit("1#2", 2, "预训练语言模型的发展给文本排序带来了新进展"));
    }

    @Test
    void shouldReturnOriginalListWhenCandidatesNull() {
        List<RetrievalHit> result = service.rerank("测试", null, 3);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnOriginalListWhenSingleCandidate() {
        List<RetrievalHit> single = List.of(candidates.get(0));
        List<RetrievalHit> result = service.rerank("测试", single, 3);
        assertSame(single, result); // 单候选直接返回原列表
    }

    @Test
    void shouldRerankByRelevanceScore() {
        // 模拟 API 返回：index=2（相关）得分最高，index=1（量子）得分最低
        String mockResponse = """
                {
                  "output": {
                    "results": [
                      {"index": 2, "relevance_score": 0.95},
                      {"index": 0, "relevance_score": 0.72},
                      {"index": 1, "relevance_score": 0.15}
                    ]
                  }
                }""";

        when(mockHttp.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

        List<RetrievalHit> result = service.rerank("什么是文本排序模型？", candidates, 3);

        assertEquals(3, result.size());
        // 最高分 0.95 → index=2 应该排第一
        assertEquals("预训练语言模型的发展给文本排序带来了新进展", result.get(0).text());
        // 最低分 0.15 → index=1 应该排最后
        assertEquals("量子计算是计算科学的一个前沿领域", result.get(2).text());
    }

    @Test
    void shouldFallbackOnHttpError() {
        when(mockHttp.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR));

        List<RetrievalHit> result = service.rerank("测试", candidates, 3);
        // 降级：返回原始列表
        assertEquals(candidates, result);
    }

    @Test
    void shouldFallbackOnException() {
        when(mockHttp.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        List<RetrievalHit> result = service.rerank("测试", candidates, 3);
        assertEquals(candidates, result);
    }

    @Test
    void shouldFallbackOnMalformedResponse() {
        when(mockHttp.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("not valid json", HttpStatus.OK));

        List<RetrievalHit> result = service.rerank("测试", candidates, 3);
        assertEquals(candidates, result);
    }

    @Test
    void shouldHandleEmptyResults() {
        String mockResponse = """
                {"output": {"results": []}}""";

        when(mockHttp.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

        List<RetrievalHit> result = service.rerank("测试", candidates, 3);
        assertEquals(candidates, result);
    }

    @Test
    void shouldReturnEmptyForEmptyCandidates() {
        List<RetrievalHit> result = service.rerank("测试", Collections.emptyList(), 3);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldSkipOnEmptyApiKey() {
        RerankService noKeyService = new RerankService(mockHttp, "", "gte-rerank-v2");
        List<RetrievalHit> result = noKeyService.rerank("测试", candidates, 3);
        assertEquals(candidates, result);
        verifyNoInteractions(mockHttp);
    }

    @Test
    void shouldSendCorrectRequestFormat() {
        when(mockHttp.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("""
                        {"output": {"results": [{"index": 0, "relevance_score": 0.9}]}}""",
                        HttpStatus.OK));

        service.rerank("什么是文本排序？", candidates, 2);

        // 验证 HTTP 调用被触发
        verify(mockHttp).postForEntity(
                contains("text-rerank"),
                any(HttpEntity.class),
                eq(String.class)
        );
    }
}
