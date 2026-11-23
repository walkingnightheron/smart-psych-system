package com.psych.assessment.repository;

import com.psych.assessment.entity.QuestionnaireVersion;
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
 * Immutable snapshot repository (REQ03): strictly insert & select ONLY.
 */
@Repository
@RequiredArgsConstructor
public class QuestionnaireVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<QuestionnaireVersion> ROW_MAPPER = (rs, rowNum) -> QuestionnaireVersion.builder()
            .id(rs.getLong("id"))
            .questionnaireId(rs.getLong("questionnaire_id"))
            .versionNum(rs.getInt("version_num"))
            .ruleVersion(rs.getString("rule_version"))
            .title(rs.getString("title"))
            .description(rs.getString("description"))
            .questionsSnapshot(rs.getString("questions_snapshot"))
            .scoringRuleSnapshot(rs.getString("scoring_rule_snapshot"))
            .publishedBy(rs.getLong("published_by"))
            .publishedAt(JdbcHelper.getLocalDateTime(rs, "published_at"))
            .build();

    public Long insertSnapshot(QuestionnaireVersion version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO questionnaire_version (questionnaire_id, version_num, rule_version, title, description, questions_snapshot, scoring_rule_snapshot, published_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setLong(1, version.getQuestionnaireId());
            ps.setInt(2, version.getVersionNum());
            ps.setString(3, version.getRuleVersion());
            ps.setString(4, version.getTitle());
            ps.setString(5, version.getDescription());
            ps.setString(6, version.getQuestionsSnapshot());
            ps.setString(7, version.getScoringRuleSnapshot());
            ps.setLong(8, version.getPublishedBy());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<QuestionnaireVersion> findById(Long id) {
        List<QuestionnaireVersion> list = jdbcTemplate.query(
                "SELECT * FROM questionnaire_version WHERE id = ?",
                ROW_MAPPER,
                id
        );
        return list.stream().findFirst();
    }

    public List<QuestionnaireVersion> findByQuestionnaireId(Long questionnaireId) {
        return jdbcTemplate.query(
                "SELECT * FROM questionnaire_version WHERE questionnaire_id = ? ORDER BY version_num DESC",
                ROW_MAPPER,
                questionnaireId
        );
    }

    public List<QuestionnaireVersion> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM questionnaire_version ORDER BY id DESC",
                ROW_MAPPER
        );
    }

    public long countAll() {
        Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM questionnaire_version", Long.class);
        return cnt == null ? 0L : cnt;
    }
}
