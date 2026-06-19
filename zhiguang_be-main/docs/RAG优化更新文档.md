# RAG 知识问答系统优化更新文档

> 日期：2026-06-19 | 分支：main

---

## 一、优化总览

本次对 RAG 知识问答系统进行了四个维度的深度优化，覆盖**切块 → 查询改写 → 重排序 → 对话记忆**全链路。

```
优化前：question → 固定800字符切块检索 → RRF融合 → LLM回答
优化后：question → 递归语义切块 → HyDE改写 → 混合检索 → RRF → Reranker → topK → LLM回答
                                                        ↑
                                               Redis 多轮对话记忆
```

---

## 二、切块策略优化

### 问题

原有切块逻辑：先按 Markdown 标题分段，再按固定 800 字符窗口 + 100 字符重叠切分。存在句子被截断、不感知语义边界、特殊内容（代码块/表格/列表）被错误拆分等问题。

### 方案

引入 **MarkdownChunker 递归语义切分器**，6 级切分层级：

| Level | 分隔符 | 说明 |
|--------|--------|------|
| 0 | 标题 `#`/`##`/`###` | 按章节标题分段 |
| 1 | 连续空行 | 按段落切分 |
| 2 | `[。！？]\n` | 句尾 + 换行 |
| 3 | `[。！？；](?=\S)` | 句中句尾 |
| 4 | `[，、](?=\S)` | 子句 |
| 5 | CJK/空格边界 | 字符窗口兜底 |

**特性**：
- Token 自适应估算（中文 ~1.5/token，英文 ~4/token）
- 代码块/表格/列表保护，不参与切分
- 章节标题注入 chunk 元数据（`sectionTitle`）
- 碎片向 `targetTokens`（默认 500）合并
- 重叠在句子边界截断

### 文件

| 操作 | 文件 |
|------|------|
| 新增 | `MarkdownChunker.java` |
| 新增 | `MarkdownChunkerTest.java`（10 用例） |
| 新增 | `Chunk.java`、`ChunkOptions.java` |
| 修改 | `RagIndexService.java` |

### 配置

```java
ChunkOptions.defaults()  // targetTokens=500, minTokens=100, overlapTokens=50
```

---

## 三、查询改写/扩展

### 问题

用户问题原封不动用于检索。口语化问题和文档用词不一致时召回率低（如"怎么提高效率" vs "性能优化方法"）。

### 方案

**HyDE（假设文档嵌入）+ 关键词提取**，单次 LLM 调用同时完成两个任务：

1. 生成"假设答案"段落 → 用于向量语义检索
2. 提取 5-8 个核心概念词 → 用于 BM25 关键词检索

```
question → QueryRewriter(DeepSeek, temp=0.3, maxTokens=300)
  → hypotheticalAnswer → vectorSearch
  → searchQuery       → keywordSearch
```

失败时静默降级到原始查询。

### 文件

| 操作 | 文件 |
|------|------|
| 新增 | `QueryRewriter.java` |
| 新增 | `QueryRewriterTest.java`（8 用例） |
| 新增 | `RewrittenQuery.java` |
| 修改 | `RagQueryService.java` |

---

## 四、Reranker 重排序

### 问题

RRF 融合仅按"排名位置"打分，丢弃了原始相关度分数，也没有候选之间的精细比较。融合后的 20-60 个候选直接按 RRF 分数截断 topK，精度不足。

### 方案

**DashScope Rerank API（`gte-rerank-v2`）**，Cross-Encoder 级别精细打分：

```
RRF融合(20-60候选) → Reranker(Cross-Encoder) → 重排序 → topK截断
```

- 复用现有 DashScope API Key
- 失败时静默降级到 RRF 排序
- 可通过 `rag.rerank.enabled` 开关

### 文件

| 操作 | 文件 |
|------|------|
| 新增 | `RerankService.java` |
| 新增 | `RetrievalHit.java`（从 RagQueryService 提取） |
| 新增 | `RerankServiceTest.java`（10 用例） |
| 修改 | `RagQueryService.java` |

### 配置

```yaml
rag:
  rerank:
    enabled: true          # 默认开启
    model: gte-rerank-v2   # DashScope 模型
```

> ⚠️ 需在阿里云百炼控制台开通 `gte-rerank-v2` 模型。

---

## 五、多轮对话记忆（Redis 会话）

### 问题

每次问答独立，不支持追问。前端 React state 存历史，刷新丢失。

### 方案

**Redis 会话存储**，后端持久化对话历史，**按用户隔离**：

```
Key:   rag:session:{postId}:{userId}:{sessionId}
Value: JSON [{role, content, timestamp}, ...]
TTL:   7 天（每次追加续期）
```

- 通过 `@AuthenticationPrincipal Jwt` 从 JWT 提取 userId
- 匿名用户退化为无状态模式（不读写 Redis）
- 会话管理 API 必须登录
- SCAN 查找仅限当前用户范围

- 按文章隔离，每篇知文独立对话
- 前端 sessionId 存 localStorage，刷新不丢
- 对话气泡 UI + 新建对话按钮

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/knowposts/{id}/qa/stream` | 流式问答（可选登录，登录后自动持久化） |
| `POST` | `/api/v1/knowposts/{id}/qa/sessions` | 创建新会话（需登录） |
| `GET` | `/api/v1/knowposts/{id}/qa/sessions` | 列出会话列表（需登录） |
| `DELETE` | `/api/v1/knowposts/{id}/qa/sessions/{sid}` | 删除会话（需登录） |

### 文件

| 操作 | 文件 |
|------|------|
| 新增 | `ConversationStore.java` |
| 新增 | `RedisConversationStore.java` |
| 新增 | `SessionInfo.java` |
| 修改 | `ChatMessage.java`（加 `timestamp`） |
| 修改 | `QaStreamRequest.java`（`history` → `sessionId`） |
| 修改 | `RagQueryService.java`（注入 ConversationStore，流完后自动保存；userId 参数） |
| 修改 | `KnowPostRagController.java`（新增 3 个会话管理端点，JWT 提取 userId） |
| 修改 | `CourseDetailPage.tsx`（sessionId 管理 + 对话气泡 UI） |
| 修改 | `CourseDetailPage.module.css`（对话样式） |

---

## 六、完整文件清单

### 新增文件（10 个）

```
src/main/java/com/tongji/llm/rag/
├── MarkdownChunker.java
├── Chunk.java
├── ChunkOptions.java
├── QueryRewriter.java
├── RewrittenQuery.java
├── RerankService.java
├── RetrievalHit.java
├── ConversationStore.java
├── RedisConversationStore.java
└── SessionInfo.java

src/main/java/com/tongji/knowpost/api/dto/
├── ChatMessage.java    （修改：加 timestamp）
└── QaStreamRequest.java（修改：history→sessionId）

src/test/java/com/tongji/llm/rag/
├── MarkdownChunkerTest.java（10 用例）
├── QueryRewriterTest.java  （8 用例）
└── RerankServiceTest.java  （10 用例）
```

### 修改文件（8 个）

```
RagIndexService.java           - 接入 MarkdownChunker
RagQueryService.java           - 接入 QueryRewriter + RerankService + ConversationStore
KnowPostRagController.java     - 新增会话管理端点 + POST 接口
CourseDetailPage.tsx           - 前端 sessionId 管理 + 对话气泡
CourseDetailPage.module.css    - 对话 UI 样式
```

### 测试覆盖

```
JUnit 测试: 30 用例，全部通过 ✅
├── JwtServiceTest:        1
├── HotKeyDetectorTest:    1
├── MarkdownChunkerTest:  10
├── QueryRewriterTest:     8
└── RerankServiceTest:    10
```

---

## 七、待优化项

| 优化项 | 优先级 | 说明 |
|--------|:--:|------|
| 跨文章 RAG 问答 | 中 | 当前仅支持单篇知文范围内检索 |
| 对话历史全文搜索 | 低 | Redis SCAN 可满足当前规模，后续可接 ES |
| 多模态问答（图片） | 低 | 需要视觉模型支持 |
| 答案引用溯源 | 低 | 标注每个回答引用了哪些 chunk |

---

## 八、部署注意

1. **DashScope Rerank API**：需在阿里云百炼控制台开通 `gte-rerank-v2` 模型，API Key 复用现有 `spring.ai.openai.api-key`
2. **Redis**：需确保 `192.168.183.100:6379` 可用，会话 key 自动 7 天过期
3. **application.yml**：已通过 `.gitignore` 排除，部署时需手动配置
