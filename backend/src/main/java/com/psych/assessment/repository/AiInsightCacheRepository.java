package com.psych.assessment.repository;

import com.psych.assessment.entity.AiInsightCache;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * AI 洞察缓存表（cache_key 主键：report:{id} / activity:{id}），upsert 兼容 MySQL 与 H2。
 */
@Repository
@RequiredArgsConstructor
public class AiInsightCacheRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<AiInsightCache> ROW_MAPPER = (rs, rowNum) -> AiInsightCache.builder()
            .cacheKey(rs.getString("cache_key"))
            .content(rs.getString("content"))
            .model(rs.getString("model"))
            .generatedAt(JdbcHelper.getLocalDateTime(rs, "generated_at"))
            .build();

    public Optional<AiInsightCache> findByKey(String cacheKey) {
        List<AiInsightCache> list = jdbcTemplate.query(
                "SELECT * FROM ai_insight_cache WHERE cache_key = ?",
                ROW_MAPPER,
                cacheKey
        );
        return list.stream().findFirst();
    }

    public void upsert(String cacheKey, String content, String model) {
        if (updateRow(cacheKey, content, model) == 0) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO ai_insight_cache (cache_key, content, model) VALUES (?, ?, ?)",
                        cacheKey, content, model
                );
            } catch (DuplicateKeyException ex) {
                updateRow(cacheKey, content, model);
            }
        }
    }

    private int updateRow(String cacheKey, String content, String model) {
        return jdbcTemplate.update(
                "UPDATE ai_insight_cache SET content = ?, model = ?, generated_at = CURRENT_TIMESTAMP WHERE cache_key = ?",
                content, model, cacheKey
        );
    }
}
