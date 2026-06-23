package com.tongji.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计数事件批量聚合消费者。
 *
 * <p>流程：Kafka 批量拉取 → HashMap 去重合并 → Redis pipeline Lua 写入 SDS + 作者计数 → ack。
 * <p>无 Hash 缓冲，无定时 flush，HashMap 即缓冲，SDS 即终态。
 */
@Service
public class CounterAggregationConsumer {

    private static final Logger log = LoggerFactory.getLogger(CounterAggregationConsumer.class);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;
    private final UserCounterService userCounterService;
    private final KnowPostMapper knowPostMapper;

    public CounterAggregationConsumer(ObjectMapper objectMapper, StringRedisTemplate redis,
                                       UserCounterService userCounterService, KnowPostMapper knowPostMapper) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.userCounterService = userCounterService;
        this.knowPostMapper = knowPostMapper;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        this.incrScript.setScriptText(INCR_FIELD_LUA);
    }

    /**
     * 批量消费：HashMap 去重 → 管道 Lua 写 SDS + 作者计数 → ack。
     */
    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "counter-agg")
    public void onMessage(List<String> messages, Acknowledgment ack) {
        // 1. HashMap 去重合并：key = entityType|entityId|idx, value = net delta
        Map<String, DeltaEntry> deduped = new HashMap<>();
        for (String raw : messages) {
            CounterEvent evt;
            try {
                evt = objectMapper.readValue(raw, CounterEvent.class);
            } catch (Exception e) {
                log.warn("Skip malformed counter event: {}", e.getMessage());
                continue;
            }
            String key = evt.getEntityType() + "|" + evt.getEntityId() + "|" + evt.getIdx();
            deduped.compute(key, (k, v) -> v == null
                    ? new DeltaEntry(evt.getEntityType(), evt.getEntityId(), evt.getIdx(), evt.getDelta())
                    : v.add(evt.getDelta()));
        }

        if (deduped.isEmpty()) {
            ack.acknowledge();
            return;
        }

        // 2. 收集作者计数（knowpost 类型）——先查 creatorId
        Map<Long, AuthorDelta> authorDeltas = collectAuthorDeltas(deduped);

        // 3. 管道批量写入 Redis（分片每 50 条一批）
        List<Map.Entry<String, DeltaEntry>> entries = List.copyOf(deduped.entrySet());
        int batchSize = 50;
        try {
            for (int i = 0; i < entries.size(); i += batchSize) {
                final int start = i;
                final int end = Math.min(i + batchSize, entries.size());
                redis.executePipelined((RedisCallback<Object>) connection -> {
                    for (int j = start; j < end; j++) {
                        DeltaEntry e = entries.get(j).getValue();
                        byte[] cntKey = CounterKeys.sdsKey(e.entityType, e.entityId).getBytes();
                        byte[] schemaLen = String.valueOf(CounterSchema.SCHEMA_LEN).getBytes();
                        byte[] fieldSize = String.valueOf(CounterSchema.FIELD_SIZE).getBytes();
                        byte[] idx = String.valueOf(e.idx).getBytes();
                        byte[] delta = String.valueOf(e.delta).getBytes();
                        connection.eval(
                                incrScript.getScriptAsString().getBytes(),
                                org.springframework.data.redis.connection.ReturnType.INTEGER,
                                1,
                                cntKey, schemaLen, fieldSize, idx, delta
                        );
                    }
                    return null;
                });
            }
        } catch (Exception ex) {
            log.error("Pipeline SDS write failed, will retry: {}", ex.getMessage());
            // 不 ack，Kafka 重投
            return;
        }

        // 4. 更新作者计数（非致命，失败不影响 ack）
        for (AuthorDelta ad : authorDeltas.values()) {
            try {
                if (ad.likeDelta != 0) {
                    userCounterService.incrementLikesReceived(ad.creatorId, (int) ad.likeDelta);
                }
                if (ad.favDelta != 0) {
                    userCounterService.incrementFavsReceived(ad.creatorId, (int) ad.favDelta);
                }
            } catch (Exception ex) {
                log.warn("Author counter update failed for user {}: {}", ad.creatorId, ex.getMessage());
            }
        }

        // 5. 全部成功，提交 ack
        ack.acknowledge();
    }

    /**
     * 收集需要更新的作者计数（knowpost 类型，按 creatorId 合并）。幂等 ✅
     */
    private Map<Long, AuthorDelta> collectAuthorDeltas(Map<String, DeltaEntry> deduped) {
        Map<Long, AuthorDelta> result = new HashMap<>();
        for (DeltaEntry e : deduped.values()) {
            if (!"knowpost".equals(e.entityType)) continue;
            try {
                KnowPost post = knowPostMapper.findById(Long.valueOf(e.entityId));
                if (post == null || post.getCreatorId() == null) continue;
                long owner = post.getCreatorId();
                AuthorDelta ad = result.computeIfAbsent(owner, AuthorDelta::new);
                if (e.idx == CounterSchema.IDX_LIKE) ad.likeDelta += e.delta;
                else if (e.idx == CounterSchema.IDX_FAV) ad.favDelta += e.delta;
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    // ── 内部类型 ──

    private static class DeltaEntry {
        final String entityType;
        final String entityId;
        final int idx;
        long delta;

        DeltaEntry(String entityType, String entityId, int idx, long delta) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.idx = idx;
            this.delta = delta;
        }

        DeltaEntry add(long d) { this.delta += d; return this; }
    }

    private static class AuthorDelta {
        final long creatorId;
        long likeDelta;
        long favDelta;

        AuthorDelta(long creatorId) { this.creatorId = creatorId; }
    }

    // ── Lua 脚本 ──

    private static final String INCR_FIELD_LUA = """

            local cntKey = KEYS[1]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2]) -- 固定为4
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])

            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end

            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end

            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            local off = idx * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            return 1
            """;
}
