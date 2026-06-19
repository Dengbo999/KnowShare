package com.tongji.llm.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryRewriterTest {

    private final QueryRewriter rewriter = new QueryRewriter();

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @BeforeEach
    void setUp() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(DeepSeekChatOptions.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    void shouldReturnEmptyForNullQuestion() {
        RewrittenQuery result = rewriter.rewrite(chatClient, null);
        assertNull(result.hypotheticalAnswer());
        assertNull(result.searchQuery());

        result = rewriter.rewrite(chatClient, "");
        assertNull(result.hypotheticalAnswer());
        assertNull(result.searchQuery());
    }

    @Test
    void shouldParseValidJsonResponse() {
        when(callResponseSpec.content()).thenReturn(
                "{\"hypothetical_answer\":\"这是假设答案段落\", \"search_query\":\"关键词1 关键词2\"}");

        RewrittenQuery result = rewriter.rewrite(chatClient, "如何提高性能？");

        assertEquals("这是假设答案段落", result.hypotheticalAnswer());
        assertEquals("关键词1 关键词2", result.searchQuery());
    }

    @Test
    void shouldHandleMarkdownCodeBlockWrapping() {
        when(callResponseSpec.content()).thenReturn(
                "```json\n{\"hypothetical_answer\":\"假设答案\", \"search_query\":\"性能 优化\"}\n```");

        RewrittenQuery result = rewriter.rewrite(chatClient, "测试问题");

        assertEquals("假设答案", result.hypotheticalAnswer());
        assertEquals("性能 优化", result.searchQuery());
    }

    @Test
    void shouldReturnEmptyOnMalformedJson() {
        when(callResponseSpec.content()).thenReturn("这不是有效的JSON格式");

        RewrittenQuery result = rewriter.rewrite(chatClient, "问题");

        assertNull(result.hypotheticalAnswer());
        assertNull(result.searchQuery());
    }

    @Test
    void shouldReturnEmptyOnLlmException() {
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM timeout"));

        RewrittenQuery result = rewriter.rewrite(chatClient, "问题");

        assertNull(result.hypotheticalAnswer());
        assertNull(result.searchQuery());
    }

    @Test
    void shouldReturnPartialWhenFieldsMissing() {
        // 只返回 search_query，缺少 hypothetical_answer
        when(callResponseSpec.content()).thenReturn(
                "{\"search_query\": \"搜索词1 搜索词2\"}");

        RewrittenQuery result = rewriter.rewrite(chatClient, "问题");

        assertNull(result.hypotheticalAnswer());
        assertEquals("搜索词1 搜索词2", result.searchQuery());
    }

    @Test
    void shouldRejectNullValuesInJson() {
        when(callResponseSpec.content()).thenReturn(
                "{\"hypothetical_answer\": null, \"search_query\": \"关键词\"}");

        RewrittenQuery result = rewriter.rewrite(chatClient, "问题");

        assertNull(result.hypotheticalAnswer());
        assertEquals("关键词", result.searchQuery());
    }

    @Test
    void shouldIncludeQuestionInUserPrompt() {
        // 验证 prompt 中包含用户问题
        StringBuilder capturedUserPrompt = new StringBuilder();
        when(requestSpec.user(anyString())).thenAnswer(invocation -> {
            capturedUserPrompt.append(invocation.getArgument(0, String.class));
            return requestSpec;
        });
        when(callResponseSpec.content()).thenReturn(
                "{\"hypothetical_answer\":\"答案\", \"search_query\":\"搜索\"}");

        rewriter.rewrite(chatClient, "什么是RAG技术？");

        assertTrue(capturedUserPrompt.toString().contains("什么是RAG技术？"));
    }
}
