# RAG 切块策略重构设计

> 日期: 2026-06-19 | 状态: 已确认

## 背景

当前 `RagIndexService` 中的切块逻辑过于简单：
- 先按 Markdown 标题分段
- 再按固定 800 字符窗口切分，100 字符重叠
- 不感知句子边界，可能在词中截断
- 不区分内容类型（代码块、表格、列表）

目标：在不引入新依赖的前提下，实现递归语义切分。

## 架构

```
com.tongji.llm.rag
├── MarkdownChunker.java    (新增, package-private)
├── RagIndexService.java    (修改: 替换 chunkMarkdown / getChunks)
└── RagQueryService.java    (不变)
```

## 核心算法

### 五级递归切分

| Level | 分隔符 | 说明 |
|-------|--------|------|
| 0 | `(?=\n#{1,4}\s)` | 标题边界 |
| 1 | `\n{2,}` | 段落空行 |
| 2 | `(?<=[。！？])\n` | 句尾 + 换行 |
| 3 | `(?<=[。！？；])(?=\S)` | 句尾（句中） |
| 4 | `(?<=[，、])(?=\S)` | 子句 |
| 5 | 字符窗口 | 兜底：在 CJK/空格边界截断 |

### Token 估算

中文字符 ≈ 1.5 字/token，英文 ≈ 4 字/token，CJK 标点不计入。

### 默认参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| targetTokens | 500 | 目标 token 数 |
| minTokens | 100 | 低于此值合并到前一个 chunk |
| overlapTokens | 50 | 相邻 chunk 重叠约 50 tokens |

### 特殊内容保护

- 代码块 (` ``` ` 配对) → 内部不切分
- 表格行 (`|...|`) → 整表保持
- 列表项 (`- ` / `1. `) → 连续列表保持

## 接口

```java
class MarkdownChunker {
    List<Chunk> chunk(String markdown, ChunkOptions options);
}

record Chunk(String text, String sectionTitle, int index) {}
record ChunkOptions(int targetTokens, int minTokens, int overlapTokens) {
    static ChunkOptions defaults() { return new ChunkOptions(500, 100, 50); }
}
```

## RagIndexService 改动

- 删除 `chunkMarkdown()` 和 `getChunks()` 两个私有方法
- 注入 `MarkdownChunker`
- 调用处改为 `chunker.chunk(text, defaults())`
- metadata 新增 `sectionTitle` 字段

## 测试计划

| 用例 | 覆盖场景 |
|------|---------|
| 空文本 | 边界 |
| 短文本 (< 500 tokens) | 单 chunk |
| 按标题自然拆分 | Level 0 |
| 长段落按句子拆分 | Level 2-3 |
| 无标点长文本 | Level 5 兜底 |
| 代码块保护 | 特殊内容 |
| 小片段合并 | minTokens |
| 重叠正确性 | overlap |
| Token 估算 (中/英/混合) | 估算器 |

## 影响范围

| 维度 | 变动 |
|------|------|
| 新增文件 | `MarkdownChunker.java` (~180 行) |
| 修改文件 | `RagIndexService.java` (删 50 + 改 30 行) |
| 新增测试 | `MarkdownChunkerTest.java` |
| 新增依赖 | 无 |
| 对外 API | 无变化 |
