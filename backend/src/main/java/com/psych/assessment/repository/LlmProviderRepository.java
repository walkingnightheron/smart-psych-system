package com.psych.assessment.repository;

import com.psych.assessment.entity.LlmProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * llm_provider 多行 CRUD（契约 8.1）。
 * 注意：apiKey 明文仅在 create/update 入参出现，读取时不做任何日志；脱敏在 Service 层完成。
 */
@Repository
@RequiredArgsConstructor
public class LlmProviderRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<LlmProvider> ROW_MAPPER = (rs, rowNum) -> LlmProvider.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .baseUrl(rs.getString("base_url"))
            .apiKey(rs.getString("api_key"))
            .enabled(rs.getInt("enabled"))
            .updatedBy((Long) rs.getObject("updated_by"))
            .createdAt(JdbcHelper.getLocalDateTime(rs, "created_at"))
            .updatedAt(JdbcHelper.getLocalDateTime(rs, "updated_at"))
            .build();

    public List<LlmProvider> findAll() {
        return jdbcTemplate.query("SELECT * FROM llm_provider ORDER BY id", ROW_MAPPER);
    }

    public Optional<LlmProvider> findById(Long id) {
        List<LlmProvider> list = jdbcTemplate.query(
                "SELECT * FROM llm_provider WHERE id = ?", ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    public LlmProvider insert(LlmProvider p) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO llm_provider (name, base_url, api_key, enabled, updated_by) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, p.getName());
            ps.setString(2, p.getBaseUrl());
            ps.setString(3, p.getApiKey() == null ? "" : p.getApiKey());
            ps.setInt(4, p.getEnabled() == null ? 1 : p.getEnabled());
            ps.setObject(5, p.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        p.setId(key == null ? null : key.longValue());
        return p;
    }

    public int update(LlmProvider p) {
        return jdbcTemplate.update(
                "UPDATE llm_provider SET name = ?, base_url = ?, api_key = ?, enabled = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                p.getName(),
                p.getBaseUrl(),
                p.getApiKey() == null ? "" : p.getApiKey(),
                p.getEnabled() == null ? 1 : p.getEnabled(),
                p.getUpdatedBy(),
                p.getId()
        );
    }

    public int delete(Long id) {
        return jdbcTemplate.update("DELETE FROM llm_provider WHERE id = ?", id);
    }
}
