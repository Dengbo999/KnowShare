package com.tongji.llm.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 查询改写器：在检索前用 LLM 同时完成 HyDE（假设文档嵌入）和关键词提取，
 * 提升向量检索和关键词检索的召回质量。
 *
 * <p>失败时静默降级：返回空字段，上游回退到原始查询。
 */
class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final ObjectMapper json = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            你是一个查询优化专家，你的任务是将用户问题改写为更适合检索的形式。

            请完成以下两个任务：
            1. 生成"假设答案"：假设在相关资料中找到了答案，答案段落会是什么样子的？
               请写一段2-3句话的中文段落。这段文字将被用于语义向量检索。
            2. 提取搜索关键词：从问题中提取最重要的概念和术语（5-8个），以空格分隔。
               这些关键词将被用于BM25关键词检索。

            请严格按以下JSON格式输出，不要输出任何其他内容：
            {"hypothetical_answer":"你的假设答案...", "search_query":"关键词1 关键词2 ..."}

            注意：JSON中的字符串值不要换行。""";

    /**
     * 改写用户问题，返回假设答案 + 搜索关键词。
     *
     * @param chatClient LLM 客户端（DeepSeek）
     * @param question   用户原始问题
     * @return 改写结果，字段可能为空（调用失败或解析失败时）
     */
    RewrittenQuery rewrite(ChatClient chatClient, String question) {
        if (question == null || question.isBlank()) {
            return RewrittenQuery.EMPTY;
        }

        String userPrompt = "问题：" + question;

        try {
            String raw = chatClient
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .options(DeepSeekChatOptions.builder()
                            .model("deepseek-chat")
                            .temperature(0.3)
                            .maxTokens(300)
                            .build())
                    .call()
                    .content();

            return parseResponse(raw);
        } catch (Exception e) {
            log.warn("Query rewrite failed, falling back to original: {}", e.getMessage());
            return RewrittenQuery.EMPTY;
        }
    }

    /**
     * 解析 LLM 返回的 JSON，做容错处理。
     */
    private RewrittenQuery parseResponse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return RewrittenQuery.EMPTY;
        }

        // 去掉可能的 markdown 代码块包裹
        String jsonStr = raw.trim();
        if (jsonStr.startsWith("```")) {
            int start = jsonStr.indexOf('\n');
            int end = jsonStr.lastIndexOf("```");
            if (start >= 0 && end > start) {
                jsonStr = jsonStr.substring(start, end).trim();
            }
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = json.readValue(jsonStr, Map.class);
            String hypothetical = asString(map.get("hypothetical_answer"));
            String searchQuery = asString(map.get("search_query"));
            return new RewrittenQuery(
                    StringUtils.hasText(hypothetical) ? hypothetical : null,
                    StringUtils.hasText(searchQuery) ? searchQuery : null
            );
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse query rewrite JSON: {}", e.getMessage());
            return RewrittenQuery.EMPTY;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

/**
 * 改写后的查询：包含用于向量检索的假设答案和用于关键词检索的搜索词。
 */
record RewrittenQuery(String hypotheticalAnswer, String searchQuery) {
    static final RewrittenQuery EMPTY = new RewrittenQuery(null, null);
}
