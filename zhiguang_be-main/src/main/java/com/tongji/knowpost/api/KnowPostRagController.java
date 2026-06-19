package com.tongji.knowpost.api;

import com.tongji.auth.token.JwtService;
import com.tongji.knowpost.api.dto.QaStreamRequest;
import com.tongji.llm.rag.ConversationStore;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.llm.rag.RagQueryService;
import com.tongji.llm.rag.SessionInfo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowposts")
@Validated
@RequiredArgsConstructor
public class KnowPostRagController {

    private final RagIndexService indexService;
    private final RagQueryService ragQueryService;
    private final ConversationStore conversationStore;
    private final JwtService jwtService;

    /**
     * 单篇知文 RAG 问答（Redis 会话模式，用户隔离）。
     * 支持匿名访问：无 JWT 时退化为无状态模式。
     */
    @PostMapping(value = "/{id}/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> qaStream(@PathVariable("id") long id,
                                 @Valid @RequestBody QaStreamRequest req,
                                 @AuthenticationPrincipal Jwt jwt) {
        String userId = extractUserId(jwt);
        return ragQueryService.streamAnswerFlux(id, req.question(),
                userId, req.sessionId(), req.topK(), req.maxTokens());
    }

    /**
     * @deprecated 保留旧 GET 接口（无状态）。
     */
    @Deprecated
    @GetMapping(value = "/{id}/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> qaStreamGet(@PathVariable("id") long id,
                                    @RequestParam("question") String question,
                                    @RequestParam(value = "topK", defaultValue = "5") int topK,
                                    @RequestParam(value = "maxTokens", defaultValue = "1024") int maxTokens) {
        return ragQueryService.streamAnswerFlux(id, question, topK, maxTokens);
    }

    // ── 会话管理（需登录） ──

    @PostMapping("/{id}/qa/sessions")
    public Map<String, String> createSession(@PathVariable("id") long id,
                                              @AuthenticationPrincipal Jwt jwt) {
        String userId = requireUserId(jwt);
        String sessionId = conversationStore.create(String.valueOf(id), userId);
        return Map.of("sessionId", sessionId);
    }

    @GetMapping("/{id}/qa/sessions")
    public List<SessionInfo> listSessions(@PathVariable("id") long id,
                                           @AuthenticationPrincipal Jwt jwt) {
        String userId = requireUserId(jwt);
        return conversationStore.listSessions(String.valueOf(id), userId);
    }

    @DeleteMapping("/{id}/qa/sessions/{sessionId}")
    public void deleteSession(@PathVariable("id") long id,
                              @PathVariable("sessionId") String sessionId,
                              @AuthenticationPrincipal Jwt jwt) {
        String userId = requireUserId(jwt);
        conversationStore.delete(String.valueOf(id), userId, sessionId);
    }

    @PostMapping("/{id}/rag/reindex")
    public int reindex(@PathVariable("id") long id) {
        return indexService.reindexSinglePost(id);
    }

    // ── 辅助方法 ──

    /** 提取 userId，匿名返回 null。 */
    private String extractUserId(Jwt jwt) {
        if (jwt == null) return null;
        try {
            return String.valueOf(jwtService.extractUserId(jwt));
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取 userId，匿名抛出 401（会话管理必须登录）。 */
    private String requireUserId(Jwt jwt) {
        if (jwt == null) throw new IllegalStateException("需要登录");
        return String.valueOf(jwtService.extractUserId(jwt));
    }
}
