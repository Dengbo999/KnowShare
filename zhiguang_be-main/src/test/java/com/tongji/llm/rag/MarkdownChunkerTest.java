package com.tongji.llm.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker();
    private final ChunkOptions opts = ChunkOptions.defaults(); // 500/100/50

    @Test
    void emptyText_shouldReturnEmpty() {
        List<Chunk> result = chunker.chunk("", opts);
        assertTrue(result.isEmpty());

        result = chunker.chunk(null, opts);
        assertTrue(result.isEmpty());
    }

    @Test
    void shortText_shouldReturnSingleChunk() {
        String text = "这是一段短文本，只有一句话。";
        List<Chunk> chunks = chunker.chunk(text, opts);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.getFirst().text());
    }

    @Test
    void shouldSplitByHeadings() {
        String text = """
                ## 第一章
                这是第一章的内容，介绍基本概念。这里有一些详细的说明文字。

                ## 第二章
                这是第二章的内容，深入讨论技术细节。

                ### 2.1 小节
                这是第二章的一个小节。包含更多具体信息。
                """;

        List<Chunk> chunks = chunker.chunk(text, opts);
        assertFalse(chunks.isEmpty());

        // 应该有多个 chunk，按标题分开
        boolean hasCh1 = chunks.stream().anyMatch(c -> "第一章".equals(c.sectionTitle()));
        boolean hasCh2 = chunks.stream().anyMatch(c -> "第二章".equals(c.sectionTitle()));
        boolean hasCh21 = chunks.stream().anyMatch(c -> "2.1 小节".equals(c.sectionTitle()));
        assertTrue(hasCh1, "Should contain 第一章");
        assertTrue(hasCh2, "Should contain 第二章");
        assertTrue(hasCh21, "Should contain 2.1 小节");
    }

    @Test
    void shouldSplitLongParagraphBySentences() {
        // 构造一个很长的段落，无空行，但有多句话
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("这是第").append(i + 1).append("句话。它包含了一些有意义的内容和描述文字。");
        }
        String text = sb.toString();

        List<Chunk> chunks = chunker.chunk(text, opts);
        // 长段落应该被切成多个 chunk
        assertTrue(chunks.size() > 1, "Long paragraph should be split into multiple chunks");
    }

    @Test
    void codeBlocks_shouldNotBeSplit() {
        String text = """
                这是一段介绍文字。

                ```java
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("Hello World");
                        // 很多行代码
                        for (int i = 0; i < 100; i++) {
                            System.out.println(i);
                        }
                    }
                }
                ```

                这是代码后面的说明文字。
                """;

        List<Chunk> chunks = chunker.chunk(text, opts);
        // 代码块的内容应该完整保留在某个 chunk 中
        boolean codeBlockIntact = chunks.stream().anyMatch(c ->
                c.text().contains("public class Hello") && c.text().contains("System.out.println")
        );
        assertTrue(codeBlockIntact, "Code block should remain intact in one chunk");
    }

    @Test
    void allChunks_shouldHaveSequentialIndices() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("## 章节").append(i + 1).append("\n");
            sb.append("这是第").append(i + 1).append("章节的内容。包含一些详细描述和相关说明文字。\n\n");
        }

        List<Chunk> chunks = chunker.chunk(sb.toString(), opts);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index(), "Chunk index should be sequential");
        }
    }

    @Test
    void chunkText_shouldNotExceedReasonableSize() {
        // 构造长的混合内容
        StringBuilder sb = new StringBuilder();
        sb.append("## 介绍\n\n");
        for (int i = 0; i < 20; i++) {
            sb.append("这是一段比较长的文本内容，用来测试切分器是否能够合理地将内容切分成适当大小。");
            sb.append("同时包含一些英文内容 Mixed English Content for testing purposes. ");
            sb.append("还有更多中文描述和分析。\n\n");
        }

        List<Chunk> chunks = chunker.chunk(sb.toString(), opts);

        // 每个 chunk 的 token 数不应远超过目标值
        for (Chunk c : chunks) {
            int tokens = MarkdownChunker.estimateTokens(c.text());
            // 允许有一定余量（重叠可能导致略微超出）
            assertTrue(tokens <= opts.targetTokens() * 3,
                    "Chunk " + c.index() + " has " + tokens + " tokens, exceeding limit. " +
                    "Text preview: " + c.text().substring(0, Math.min(100, c.text().length())));
        }
    }

    @Test
    void estimateTokens_shouldHandleMixedContent() {
        // 纯中文
        int cnTokens = MarkdownChunker.estimateTokens("这是一段中文测试文本");
        assertTrue(cnTokens > 0);

        // 纯英文
        int enTokens = MarkdownChunker.estimateTokens("This is a test sentence in English");
        assertTrue(enTokens > 0);

        // 同字符数的中文 token 数应多于英文（中文每个字符≈0.67 token，英文每个字符≈0.25 token）
        String cn10 = "这是一段中文测试文本";   // 10 chars
        String en10 = "This is a t";             // 10 chars (approx)
        assertTrue(MarkdownChunker.estimateTokens(cn10) >= MarkdownChunker.estimateTokens(en10),
                "Chinese should produce more tokens than English for same char count");
    }

    @Test
    void shouldHandleTextWithNoPunctuation() {
        // 无标点英文长文本（Level 5 窗口兜底）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("this is a very long sentence without any punctuation marks to test the fallback window splitter behavior ");
        }

        List<Chunk> chunks = chunker.chunk(sb.toString(), opts);
        assertTrue(chunks.size() > 1, "Unpunctuated long text should be split");
    }

    @Test
    void shouldPreserveSectionTitleInChunks() {
        String text = """
                ## 核心观点
                这是一个重要的观点。它包含了一些详细的说明和分析。这部分内容需要被认真对待和分析。

                这个观点的延伸讨论。更多的上下文信息和补充说明。

                ## 案例分析
                具体的案例描述。通过实际例子来说明上述观点。
                """;

        List<Chunk> chunks = chunker.chunk(text, opts);
        assertFalse(chunks.isEmpty());

        // 至少有一个 chunk 标记为"核心观点"
        long coreCount = chunks.stream().filter(c -> "核心观点".equals(c.sectionTitle())).count();
        assertTrue(coreCount > 0, "Should have chunks with section title '核心观点'");
    }
}
