package com.psych.assessment.repository;

import com.psych.assessment.entity.Questionnaire;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class QuestionnaireRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Questionnaire> ROW_MAPPER = (rs, rowNum) -> Questionnaire.builder()
            .id(rs.getLong("id"))
            .code(rs.getString("code"))
            .title(rs.getString("title"))
            .description(rs.getString("description"))
            .scoringRuleJson(rs.getString("scoring_rule_json"))
            .latestVersion(rs.getInt("latest_version"))
            .creatorId(rs.getLong("creator_id"))
            .createdAt(JdbcHelper.getLocalDateTime(rs, "created_at"))
            .updatedAt(JdbcHelper.getLocalDateTime(rs, "updated_at"))
            .build();

    public List<Questionnaire> findAll() {
        return jdbcTemplate.query("SELECT * FROM questionnaire ORDER BY id DESC", ROW_MAPPER);
    }

    public Optional<Questionnaire> findById(Long id) {
        List<Questionnaire> list = jdbcTemplate.query("SELECT * FROM questionnaire WHERE id = ?", ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<Questionnaire> findByCode(String code) {
        List<Questionnaire> list = jdbcTemplate.query("SELECT * FROM questionnaire WHERE code = ?", ROW_MAPPER, code);
        return list.stream().findFirst();
    }

    public Long insert(Questionnaire q) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO questionnaire (code, title, description, scoring_rule_json, latest_version, creator_id) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setString(1, q.getCode());
            ps.setString(2, q.getTitle());
            ps.setString(3, q.getDescription());
            ps.setString(4, q.getScoringRuleJson());
            ps.setInt(5, q.getLatestVersion() == null ? 0 : q.getLatestVersion());
            ps.setLong(6, q.getCreatorId());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateDraft(Long id, String code, String title, String description, String scoringRuleJson) {
        jdbcTemplate.update(
                "UPDATE questionnaire SET code = ?, title = ?, description = ?, scoring_rule_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                code, title, description, scoringRuleJson, id
        );
    }

    public void updateLatestVersion(Long id, int latestVersion) {
        jdbcTemplate.update(
                "UPDATE questionnaire SET latest_version = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                latestVersion, id
        );
    }

    public long countAll() {
        Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM questionnaire", Long.class);
        return cnt == null ? 0L : cnt;
    }
}
