package com.psych.assessment.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
@Service
public class CacheService {

    public static final String KEY_PREFIX = "psych:";
    public static final String QVER_KEY_PREFIX = "psych:qver:";
    public static final String ACT_KEY_PREFIX = "psych:act:";
    private static final int MAX_EVENTS = 50;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;

    private volatile String configuredMode;
    private volatile String activeBackend;
    private final long ttlSeconds;

    private final Map<String, MemoryEntry> memoryStore = new ConcurrentHashMap<>();
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong evictCount = new AtomicLong(0);
    private final Deque<CacheEvent> recentEvents = new ConcurrentLinkedDeque<>();

    public CacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            @Value("${app.cache.mode:redis-with-fallback}") String mode,
            @Value("${app.cache.ttl-seconds:1800}") long ttlSeconds) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.configuredMode = mode;
        this.ttlSeconds = ttlSeconds;
        this.activeBackend = "memory".equalsIgnoreCase(mode) ? "MEMORY" : "REDIS";
    }

    public static String versionKey(Long versionId) {
        return QVER_KEY_PREFIX + versionId;
    }

    public static String activityKey(Long activityId) {
        return ACT_KEY_PREFIX + activityId;
    }

    public void setMode(String mode) {
        this.configuredMode = mode;
        this.activeBackend = "memory".equalsIgnoreCase(mode) ? "MEMORY" : "REDIS";
    }

    public String getMode() {
        return configuredMode;
    }

    public void resetMetrics() {
        hitCount.set(0);
        missCount.set(0);
        evictCount.set(0);
        recentEvents.clear();
    }

    public <T> CacheLookup<T> getOrLoad(String key, Class<T> clazz, Supplier<T> dbLoader) {
        String raw = readRaw(key);
        if (raw != null) {
            try {
                T cached = objectMapper.readValue(raw, clazz);
                hitCount.incrementAndGet();
                recordEvent("HIT", key, "命中热点缓存 (" + activeBackend + ")");
                return new CacheLookup<>(cached, "HIT");
            } catch (Exception ex) {
                log.warn("Failed to deserialize cache key {}, evicting", key, ex);
                evictKey(key);
            }
        }

        missCount.incrementAndGet();
        recordEvent("MISS_DB", key, "缓存未命中，回源数据库加载");
        T loaded = dbLoader.get();
        if (loaded != null) {
            put(key, loaded);
        }
        return new CacheLookup<>(loaded, "MISS_DB");
    }

    public void put(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            writeRaw(key, json);
            recordEvent("PUT", key, "写入缓存 TTL=" + ttlSeconds + "s (" + activeBackend + ")");
        } catch (Exception ex) {
            log.warn("Failed to serialize value for cache key {}", key, ex);
        }
    }

    public int evictKey(String key) {
        int removed = 0;
        if (memoryStore.remove(key) != null) {
            removed = 1;
        }
        if (!"memory".equalsIgnoreCase(configuredMode)) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    Boolean deleted = redis.delete(key);
                    if (Boolean.TRUE.equals(deleted)) {
                        removed = Math.max(removed, 1);
                    }
                    activeBackend = "REDIS";
                } catch (Exception ex) {
                    handleRedisFallback(ex);
                }
            }
        }
        evictCount.incrementAndGet();
        recordEvent("EVICT", key, "主动失效缓存键");
        return removed;
    }

    public int evictAll() {
        int removed = memoryStore.size();
        memoryStore.clear();
        if (!"memory".equalsIgnoreCase(configuredMode)) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    Set<String> keys = redis.keys(KEY_PREFIX + "*");
                    if (keys != null && !keys.isEmpty()) {
                        Long delCount = redis.delete(keys);
                        if (delCount != null) {
                            removed = Math.max(removed, delCount.intValue());
                        }
                    }
                    activeBackend = "REDIS";
                } catch (Exception ex) {
                    handleRedisFallback(ex);
                }
            }
        }
        evictCount.incrementAndGet();
        recordEvent("EVICT", KEY_PREFIX + "*", "清理全部热点缓存");
        return removed;
    }

    public CacheMetricsVO getMetrics() {
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        double hitRate = total == 0 ? 0.0 : Math.round((hits * 10000.0) / total) / 100.0;
        return CacheMetricsVO.builder()
                .configuredMode(configuredMode)
                .activeBackend(activeBackend)
                .hitCount(hits)
                .missCount(misses)
                .evictCount(evictCount.get())
                .hitRate(hitRate)
                .memoryKeyCount(memoryStore.size())
                .recentEvents(new ArrayList<>(recentEvents))
                .build();
    }

    private String readRaw(String key) {
        if (!"memory".equalsIgnoreCase(configuredMode)) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    String val = redis.opsForValue().get(key);
                    activeBackend = "REDIS";
                    return val;
                } catch (Exception ex) {
                    if ("redis".equalsIgnoreCase(configuredMode)) {
                        throw new IllegalStateException("Redis unavailable in strict redis mode", ex);
                    }
                    handleRedisFallback(ex);
                }
            } else if (!"redis-with-fallback".equalsIgnoreCase(configuredMode)) {
                throw new IllegalStateException("StringRedisTemplate bean not available");
            } else {
                activeBackend = "MEMORY_FALLBACK";
            }
        } else {
            activeBackend = "MEMORY";
        }
        MemoryEntry entry = memoryStore.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAtMillis) {
            memoryStore.remove(key);
            return null;
        }
        return entry.json;
    }

    private void writeRaw(String key, String json) {
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        if (!"memory".equalsIgnoreCase(configuredMode)) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    redis.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
                    activeBackend = "REDIS";
                    memoryStore.put(key, new MemoryEntry(json, expireAt));
                    return;
                } catch (Exception ex) {
                    if ("redis".equalsIgnoreCase(configuredMode)) {
                        throw new IllegalStateException("Redis unavailable in strict redis mode", ex);
                    }
                    handleRedisFallback(ex);
                }
            } else {
                activeBackend = "MEMORY_FALLBACK";
            }
        } else {
            activeBackend = "MEMORY";
        }
        memoryStore.put(key, new MemoryEntry(json, expireAt));
    }

    private void handleRedisFallback(Exception ex) {
        if (!"MEMORY_FALLBACK".equals(activeBackend)) {
            log.warn("Redis unavailable ({}), seamlessly falling back to local ConcurrentHashMap cache", ex.getMessage());
        }
        activeBackend = "MEMORY_FALLBACK";
    }

    private void recordEvent(String eventType, String key, String detail) {
        recentEvents.addFirst(CacheEvent.builder()
                .timestamp(LocalDateTime.now())
                .eventType(eventType)
                .key(key)
                .detail(detail)
                .build());
        while (recentEvents.size() > MAX_EVENTS) {
            recentEvents.removeLast();
        }
    }

    private record MemoryEntry(String json, long expireAtMillis) {
    }

    @Data
    @AllArgsConstructor
    public static class CacheLookup<T> {
        private T value;
        private String cacheSource;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheEvent {
        private LocalDateTime timestamp;
        private String eventType;
        private String key;
        private String detail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheMetricsVO {
        private String configuredMode;
        private String activeBackend;
        private long hitCount;
        private long missCount;
        private long evictCount;
        private double hitRate;
        private int memoryKeyCount;
        private List<CacheEvent> recentEvents;
    }
}
