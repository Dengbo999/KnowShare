package com.tongji.knowpost.listener;

import com.tongji.counter.event.CounterEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Feed 缓存批量更新监听器。
 *
 * <p>优化：通过无锁队列 + 定时批量 drain + HashMap 去重，将高频点赞/收藏事件
 * 合并为少量缓存更新，一次 drain 可处理数百事件。
 * <ul>
 *   <li>监听器只负责入队（极轻量）</li>
 *   <li>200ms 定时 drain，HashMap 按 entityId+metric 合并 delta</li>
 *   <li>批量更新 Caffeine 本地缓存 + Redis 页面 JSON</li>
 *   <li>批量更新创作者收到的点赞/收藏数</li>
 * </ul>
 */
@Component
public class FeedCacheInvalidationListener {

    private final Cache<String, FeedPageResponse> feedPublicCache;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    /** 无锁事件队列，超大容量避免背压丢事件 */
    private final ConcurrentLinkedQueue<CounterEvent> queue = new ConcurrentLinkedQueue<>();

    public FeedCacheInvalidationListener(
            @Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
            StringRedisTemplate redis,
            ObjectMapper objectMapper) {
        this.feedPublicCache = feedPublicCache;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 收到计数事件，仅入队，不做任何 IO。
     */
    @EventListener
    public void onCounterChanged(CounterEvent event) {
        if (!"knowpost".equals(event.getEntityType())) return;
        String metric = event.getMetric();
        if (!"like".equals(metric) && !"fav".equals(metric)) return;
        queue.offer(event);
    }

    /**
     * 200ms 批量 drain：HashMap 去重后一次性更新缓存和作者计数。
     */
    @Scheduled(fixedDelay = 200)
    public void drain() {
        if (queue.isEmpty()) return;

        // ── 1. drain 队列，合并 delta ──
        // key = eid|metric, value = 净增量
        Map<String, Integer> deltas = new HashMap<>();
        int count = 0;
        CounterEvent evt;
        while ((evt = queue.poll()) != null && count < 500) {
            String key = evt.getEntityId() + "|" + evt.getMetric();
            deltas.merge(key, evt.getDelta(), Integer::sum);
            count++;
        }

        // ── 2. 按 entityId 分组 ──
        // key = eid, value = {metric → delta}
        Map<String, Map<String, Integer>> byEntity = new HashMap<>();
        for (Map.Entry<String, Integer> e : deltas.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            String eid = parts[0];
            String metric = parts[1];
            byEntity.computeIfAbsent(eid, k -> new HashMap<>())
                    .merge(metric, e.getValue(), Integer::sum);
        }

        // ── 3. 批量更新缓存 + 作者计数 ──
        long hourSlot = System.currentTimeMillis() / 3600000L;

        for (Map.Entry<String, Map<String, Integer>> entry : byEntity.entrySet()) {
            String eid = entry.getKey();
            Map<String, Integer> metricDeltas = entry.getValue();

            // 3a. 找到受影响的页面键
            Set<String> pageKeys = new LinkedHashSet<>();
            Set<String> cur = redis.opsForSet().members("feed:public:index:" + eid + ":" + hourSlot);
            if (cur != null) pageKeys.addAll(cur);
            Set<String> prev = redis.opsForSet().members("feed:public:index:" + eid + ":" + (hourSlot - 1));
            if (prev != null) pageKeys.addAll(prev);
            if (pageKeys.isEmpty()) continue;

            // 3c. 对每个页面键，应用所有 metric 的 delta
            for (String pageKey : pageKeys) {
                updatePageCache(pageKey, eid, metricDeltas);
            }
        }
    }

    /**
     * 更新单个页面缓存（本地 Caffeine + Redis）。
     */
    private void updatePageCache(String pageKey, String eid, Map<String, Integer> metricDeltas) {
        // 本地缓存
        FeedPageResponse local = feedPublicCache.getIfPresent(pageKey);
        if (local != null) {
            feedPublicCache.put(pageKey, applyAllDeltas(local, eid, metricDeltas, true));
        }

        // Redis 缓存
        String cached = redis.opsForValue().get(pageKey);
        if (cached != null) {
            try {
                FeedPageResponse resp = objectMapper.readValue(cached, FeedPageResponse.class);
                FeedPageResponse updated = applyAllDeltas(resp, eid, metricDeltas, false);
                writePageJsonKeepingTtl(pageKey, updated);
            } catch (Exception ignored) {
            }
        } else {
            redis.opsForSet().remove("feed:public:index:" + eid + ":" +
                    (System.currentTimeMillis() / 3600000L), pageKey);
        }
    }

    /**
     * 一次遍历 items，应用所有 metric 的 delta。
     */
    private FeedPageResponse applyAllDeltas(FeedPageResponse page, String eid,
                                             Map<String, Integer> metricDeltas, boolean preserveUserFlags) {
        List<FeedItemResponse> items = new ArrayList<>(page.items().size());
        for (FeedItemResponse it : page.items()) {
            if (eid.equals(it.id())) {
                Long like = it.likeCount();
                Long fav = it.favoriteCount();
                like = Math.max(0L, (like == null ? 0L : like) + metricDeltas.getOrDefault("like", 0));
                fav = Math.max(0L, (fav == null ? 0L : fav) + metricDeltas.getOrDefault("fav", 0));
                it = new FeedItemResponse(it.id(), it.title(), it.description(), it.coverImage(),
                        it.tags(), it.authorAvatar(), it.authorNickname(), it.tagJson(),
                        like, fav,
                        preserveUserFlags ? it.liked() : null,
                        preserveUserFlags ? it.faved() : null,
                        it.isTop());
            }
            items.add(it);
        }
        return new FeedPageResponse(items, page.page(), page.size(), page.hasMore());
    }

    private void writePageJsonKeepingTtl(String key, FeedPageResponse page) {
        try {
            String json = objectMapper.writeValueAsString(page);
            long ttl = redis.getExpire(key);
            if (ttl > 0) {
                redis.opsForValue().set(key, json, Duration.ofSeconds(ttl));
            } else {
                redis.opsForValue().set(key, json);
            }
        } catch (Exception ignored) {
        }
    }
}
