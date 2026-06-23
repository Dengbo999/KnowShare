package com.tongji.llm.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 递归切分器。
 *
 * <p>策略：多级递归切分，优先在语义边界断开，保证 chunk 内部语义完整。
 * <ol>
 *   <li>Level 0 — 标题切分：在 # / ## / ### / #### 前断开，提取章节标题</li>
 *   <li>Level 1 — 段落切分：按连续空行断开</li>
 *   <li>Level 2 — 句尾 + 换行：。！？ 后紧跟换行时断开</li>
 *   <li>Level 3 — 句中句尾：。！？； 后仍有内容时断开</li>
 *   <li>Level 4 — 子句切分：，、 后断开</li>
 *   <li>Level 5 — 字符窗口兜底：在 CJK 字符 / 空格边界截断</li>
 * </ol>
 *
 * <p>特殊内容保护：代码块、表格、连续列表项不参与切分。
 */
class MarkdownChunker {

    // ── 切分正则（按层级） ──
    private static final Pattern SPLIT_PARAGRAPH = Pattern.compile("\n{2,}");
    private static final Pattern SPLIT_SENTENCE_NL = Pattern.compile("(?<=[。！？])\n");
    private static final Pattern SPLIT_SENTENCE_INLINE = Pattern.compile("(?<=[。！？；])(?=\\S)");
    private static final Pattern SPLIT_CLAUSE = Pattern.compile("(?<=[，、])(?=\\S)");

    // ── 特殊块正则 ──
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern TABLE_BLOCK = Pattern.compile("(?:^\\|.+\\|$\\n?)+", Pattern.MULTILINE);
    private static final Pattern LIST_BLOCK = Pattern.compile("(?:^(?:\\s*(?:[-*+]|\\d+\\.)\\s).+$\\n?)+", Pattern.MULTILINE);

    // ── 标题拆分 ──
    private static final Pattern HEADING_SPLIT = Pattern.compile("(?m)(?=^#{1,4}\\s)");
    private static final Pattern HEADING_LINE = Pattern.compile("^#{1,4}\\s+(.*)", Pattern.MULTILINE);

    /**
     * 主入口：将 Markdown 文本切分为语义完整的片段。
     *
     * @param markdown Markdown 原文
     * @param opts     切分配置
     * @return 有序 chunk 列表
     */
    public List<Chunk> chunk(String markdown, ChunkOptions opts) {
        if (markdown == null || markdown.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 保护特殊块（代码块 / 表格 / 列表），替换为占位符
        ProtectedText pt = protectSpecialBlocks(markdown);

        // 2. 计算文本级别的目标字符数（根据 token 密度自适应）
        int targetChars = calcTargetChars(pt.text, opts.targetTokens());

        // 3. 按标题分段，每段携带章节标题
        List<Section> sections = splitByHeadings(pt.text);

        // 4. 对每段递归切分（合并操作在同一章节内进行，不跨章节）
        List<String> parts = new ArrayList<>();
        List<String> partTitles = new ArrayList<>(); // parts → section title 的映射
        for (Section sec : sections) {
            List<String> secParts = splitRecursive(sec.text, /* level */ 1, targetChars);
            secParts = mergeToTargetSize(secParts, opts.targetTokens());
            for (String p : secParts) {
                parts.add(p);
                partTitles.add(sec.title);
            }
        }

        // 5. 还原特殊块
        parts = restorePlaceholders(parts, pt);

        // 6. 添加重叠并组装 Chunk
        return buildChunks(parts, partTitles, opts);
    }

    // ═══════════════════════════════════════════════
    //  特殊块保护
    // ═══════════════════════════════════════════════

    private ProtectedText protectSpecialBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        String result = text;

        result = replaceAll(result, CODE_BLOCK, blocks);
        result = replaceAll(result, TABLE_BLOCK, blocks);
        result = replaceAll(result, LIST_BLOCK, blocks);

        return new ProtectedText(result, blocks);
    }

    private String replaceAll(String text, Pattern pattern, List<String> blocks) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int idx = blocks.size();
            blocks.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement("__BLOCK_" + idx + "__"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private List<String> restorePlaceholders(List<String> parts, ProtectedText pt) {
        List<String> out = new ArrayList<>(parts.size());
        for (String p : parts) {
            String restored = p;
            for (int i = 0; i < pt.blocks.size(); i++) {
                restored = restored.replace("__BLOCK_" + i + "__", pt.blocks.get(i));
            }
            out.add(restored);
        }
        return out;
    }

    // ═══════════════════════════════════════════════
    //  标题拆分
    // ═══════════════════════════════════════════════

    private List<Section> splitByHeadings(String text) {
        String[] parts = HEADING_SPLIT.split(text);
        List<Section> sections = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isBlank()) continue;
            String title = extractTitle(part);
            sections.add(new Section(part, title));
        }
        if (sections.isEmpty()) {
            sections.add(new Section(text, ""));
        }
        return sections;
    }

    private String extractTitle(String section) {
        Matcher m = HEADING_LINE.matcher(section);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    // ═══════════════════════════════════════════════
    //  递归切分
    // ═══════════════════════════════════════════════

    private List<String> splitRecursive(String text, int level, int targetChars) {
        // 短文本直接保留
        if (estimateTokens(text) <= tokensFromChars(targetChars)) {
            return text.isBlank() ? Collections.emptyList() : List.of(text);
        }

        // 选择当前层级的正则
        Pattern pattern = switch (level) {
            case 1 -> SPLIT_PARAGRAPH;
            case 2 -> SPLIT_SENTENCE_NL;
            case 3 -> SPLIT_SENTENCE_INLINE;
            case 4 -> SPLIT_CLAUSE;
            default -> null;
        };

        if (pattern == null) {
            // Level 5+：字符窗口兜底
            return splitByWindow(text, targetChars);
        }

        String[] parts = pattern.split(text);
        if (parts.length <= 1) {
            // 当前级别切不动，降级
            return splitRecursive(text, level + 1, targetChars);
        }

        // 递归处理每个片段（同级继续，因为片段可能仍超长）
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            result.addAll(splitRecursive(part, level, targetChars));
        }
        return result;
    }

    /**
     * 字符窗口兜底切分：仅在 CJK 字符或空白边界截断。
     */
    private List<String> splitByWindow(String text, int targetChars) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int len = text.length();

        while (start < len) {
            int end = Math.min(start + targetChars, len);
            if (end < len) {
                // 回退到最近的合法断点
                end = findBreakPoint(text, start, end);
                if (end <= start) {
                    end = Math.min(start + targetChars, len); // 找不到断点就硬切
                }
            }
            result.add(text.substring(start, end));
            start = end;
        }
        return result;
    }

    /**
     * 从 end 向前扫描，寻找 CJK 字符或空白边界作为断点。
     * 回退不超过 20% targetChars。
     */
    private int findBreakPoint(String text, int start, int end) {
        int maxBack = Math.max(1, (end - start) / 5);
        int scanEnd = Math.max(start + 1, end - maxBack);

        for (int i = end; i >= scanEnd; i--) {
            char c = text.charAt(i);
            char prev = text.charAt(i - 1);
            // CJK 字符边界：前一个是 CJK
            if (isCJK(prev)) return i;
            // 英文单词边界：后一个是空格或换行
            if (c == ' ' || c == '\n') return i;
        }
        return end;
    }

    // ═══════════════════════════════════════════════
    //  后处理：合并小片段 + 重叠
    // ═══════════════════════════════════════════════

    /**
     * 将过小的片段向 targetTokens 靠拢：累积直到接近目标大小。
     * 避免递归切分产生大量碎片后又全部合并回去。
     */
    private List<String> mergeToTargetSize(List<String> parts, int targetTokens) {
        if (parts.size() <= 1) return parts;
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String part : parts) {
            if (buf.isEmpty()) {
                buf.append(part);
                continue;
            }
            int currentTokens = estimateTokens(buf.toString());
            int partTokens = estimateTokens(part);
            if (currentTokens + partTokens <= targetTokens) {
                buf.append('\n').append(part);
            } else {
                out.add(buf.toString());
                buf.setLength(0);
                buf.append(part);
            }
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString());
        }
        return out;
    }

    /**
     * 组装最终 Chunk：携带章节标题、位置索引，并在 chunk 间添加重叠。
     */
    private List<Chunk> buildChunks(List<String> parts, List<String> partTitles, ChunkOptions opts) {
        if (parts.isEmpty()) return Collections.emptyList();

        List<Chunk> chunks = new ArrayList<>(parts.size());
        int overlapChars = calcOverlapChars(parts, opts.overlapTokens());

        for (int i = 0; i < parts.size(); i++) {
            String text = parts.get(i);
            // 添加与前一个 chunk 的重叠
            if (i > 0 && overlapChars > 0) {
                String prev = parts.get(i - 1);
                String overlap = extractOverlap(prev, overlapChars);
                if (StringUtils.hasText(overlap)) {
                    text = overlap + "\n...\n" + text;
                }
            }
            String title = i < partTitles.size() ? partTitles.get(i) : "";
            chunks.add(new Chunk(text, title, i));
        }
        return chunks;
    }

    /**
     * 从段落末尾提取重叠文本（在句子边界截断）。
     */
    private String extractOverlap(String text, int maxChars) {
        int start = Math.max(0, text.length() - maxChars);
        String tail = text.substring(start);
        // 尝试在句号后开始，保证重叠片段语义完整
        int dotIdx = Math.max(
                tail.lastIndexOf('。'),
                Math.max(tail.lastIndexOf('！'), tail.lastIndexOf('？'))
        );
        if (dotIdx > 0 && dotIdx < tail.length() - 1) {
            tail = tail.substring(dotIdx + 1);
        }
        return tail.trim();
    }

    // ═══════════════════════════════════════════════
    //  Token 估算与字符数换算
    // ═══════════════════════════════════════════════

    /**
     * 估算文本的 token 数量。
     * 中文（含日韩）≈ 1.5 字符/token，英文 ≈ 4 字符/token。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCJK(text.charAt(i))) {
                cjk++;
            } else if (!Character.isWhitespace(text.charAt(i))) {
                other++;
            }
        }
        return (int) Math.ceil(cjk / 1.5 + other / 4.0);
    }

    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }

    /**
     * 根据目标 token 数估算目标字符数。
     * 基于全文的 token 密度自适应。
     */
    private int calcTargetChars(String text, int targetTokens) {
        int totalTokens = estimateTokens(text);
        if (totalTokens == 0) return targetTokens * 2;
        return (int) ((long) targetTokens * text.length() / totalTokens);
    }

    private int tokensFromChars(int chars) {
        // 粗略：1 token ≈ 2 chars（混合中英文场景）
        return chars / 2;
    }

    private int calcOverlapChars(List<String> parts, int overlapTokens) {
        if (parts.isEmpty()) return 0;
        long totalChars = 0;
        int totalTokens = 0;
        for (String p : parts) {
            totalChars += p.length();
            totalTokens += estimateTokens(p);
        }
        if (totalTokens == 0) return 0;
        return (int) ((long) overlapTokens * totalChars / totalTokens);
    }

    // ═══════════════════════════════════════════════
    //  内部类型
    // ═══════════════════════════════════════════════

    private record Section(String text, String title) {}

    private record ProtectedText(String text, List<String> blocks) {}
}

/**
 * 切块结果。
 */
record Chunk(String text, String sectionTitle, int index) {}

/**
 * 切块参数。
 */
record ChunkOptions(int targetTokens, int minTokens, int overlapTokens) {
    static ChunkOptions defaults() {
        return new ChunkOptions(500, 100, 50);
    }
}
