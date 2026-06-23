package com.tongji.counter.event;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * ES 计数同步消费者。
 *
 * <p>独立于聚合消费者，从同一 topic 消费计数事件，从 Redis 读取实时计数后写入 ES。
 * <ul>
 *   <li>Group: "es-sync"（与聚合消费者 "counter-agg" 独立）</li>
 *   <li>Redis 是权威数据源，ES 只做镜像</li>
 *   <li>防抖：同一实体 5 秒内只写一次 ES</li>
 *   <li>失败不丢消息：ack 在写 ES 成功后提交</li>
 * </ul>
 */
@Component
public class CounterEsSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(CounterEsSyncConsumer.class);
    private static final String INDEX = "zhiguang_content_index";
    private static final List<String> METRICS = List.of("like", "fav");
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(5);

    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final ElasticsearchClient es;

    public CounterEsSyncConsumer(ObjectMapper json, StringRedisTemplate redis,
                                  ElasticsearchClient es) {
        this.json = json;
        this.redis = redis;
        this.es = es;
    }

    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "es-sync")
    public void onMessage(List<String> messages, Acknowledgment ack) {
        // 1. 解析 + 去重（仅 like/fav，且 5s 防抖）
        Map<String, String> toSync = new LinkedHashMap<>(); // key=eType|eId
        for (String raw : messages) {
            CounterEvent evt;
            try {
                evt = json.readValue(raw, CounterEvent.class);
            } catch (Exception e) {
                continue;
            }
            if (!METRICS.contains(evt.getMetric())) continue;

            String eid = evt.getEntityId();
            String etype = evt.getEntityType();

            String lockKey = "es_sync:" + etype + ":" + eid;
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, "1", RATE_LIMIT_WINDOW);
            if (Boolean.TRUE.equals(acquired)) {
                toSync.put(etype + "|" + eid, eid);
            }
        }

        if (toSync.isEmpty()) {
            ack.acknowledge();
            return;
        }

        // 2. 从 Redis 读实时计数 + 管道 GET
        List<Object> rawCounts;
        try {
            rawCounts = redis.executePipelined(
                    (RedisCallback<Object>) connection -> {
                        for (String eid : toSync.values()) {
                            byte[] key = CounterKeys.sdsKey("knowpost", eid).getBytes();
                            connection.stringCommands().get(key);
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Redis pipeline GET failed, will retry: {}", e.getMessage());
            return; // 不 ack
        }

        // 3. 解码 SDS → like/fav 值
        List<String> eids = List.copyOf(toSync.values());
        Map<String, Long[]> entityCounts = new HashMap<>();
        for (int i = 0; i < eids.size(); i++) {
            Object raw = rawCounts.get(i);
            if (raw instanceof byte[] bytes && bytes.length == 20) {
                long like = readInt32BE(bytes, CounterSchema.IDX_LIKE * 4);
                long fav = readInt32BE(bytes, CounterSchema.IDX_FAV * 4);
                entityCounts.put(eids.get(i), new Long[]{like, fav});
            } else {
                entityCounts.put(eids.get(i), new Long[]{0L, 0L});
            }
        }

        // 4. 逐条写 ES（update 不支持 bulk simplify）
        try {
            for (String eid : eids) {
                Long[] counts = entityCounts.getOrDefault(eid, new Long[]{0L, 0L});
                es.update(UpdateRequest.of(u -> u.index(INDEX).id(eid)
                        .doc(Map.of("like_count", counts[0], "favorite_count", counts[1]))
                        .docAsUpsert(true)), Map.class);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("ES bulk update failed, will retry: {}", e.getMessage());
        }
    }

    private static long readInt32BE(byte[] bytes, int offset) {
        return ((long)(bytes[offset] & 0xFF) << 24)
             | ((long)(bytes[offset+1] & 0xFF) << 16)
             | ((long)(bytes[offset+2] & 0xFF) << 8)
             | (bytes[offset+3] & 0xFF);
    }
}
