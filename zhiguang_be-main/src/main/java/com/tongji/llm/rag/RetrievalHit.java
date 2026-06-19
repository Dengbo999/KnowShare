package com.tongji.llm.rag;

/**
 * 检索命中的统一结构，用于向量/关键词两路召回和 RRF 融合。
 *
 * @param chunkId  分块主键（用于跨通道去重）
 * @param position 原文中分块顺序（用于稳定 tie-break）
 * @param text     分块正文
 */
record RetrievalHit(String chunkId, int position, String text) {
}
