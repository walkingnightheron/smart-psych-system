package com.psych.assessment.repository;

import com.psych.assessment.entity.AssessmentReport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class AssessmentReportRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<AssessmentReport> ROW_MAPPER = (rs, rowNum) -> AssessmentReport.builder()
            .id(rs.getLong("id"))
            .sheetId(rs.getLong("sheet_id"))
            .activityId(rs.getLong("activity_id"))
            .userId(rs.getLong("user_id"))
            .questionnaireVersionId(rs.getLong("questionnaire_version_id"))
            .ruleVersion(rs.getString("rule_version"))
            .taskStatus(rs.getString("task_status"))
            .totalScore(rs.getBigDecimal("total_score"))
            .dimensionScoresJson(rs.getString("dimension_scores_json"))
            .levelCode(rs.getString("level_code"))
            .levelName(rs.getString("level_name"))
            .feedbackText(rs.getString("feedback_text"))
            .retryCount(rs.getInt("retry_count"))
            .maxRetries(rs.getInt("max_retries"))
            .lastError(rs.getString("last_error"))
            .generatedAt(JdbcHelper.getLocalDateTime(rs, "generated_at"))
            .createdAt(JdbcHelper.getLocalDateTime(rs, "created_at"))
            .updatedAt(JdbcHelper.getLocalDateTime(rs, "updated_at"))
            .build();

    public Long insertInitial(Long sheetId, Long activityId, Long userId, Long questionnaireVersionId, String ruleVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO assessment_report (sheet_id, activity_id, user_id, questionnaire_version_id, rule_version, task_status, retry_count, max_retries) VALUES (?, ?, ?, ?, ?, 'PENDING', 0, 3)",
                    new String[]{"id"}
            );
            ps.setLong(1, sheetId);
            ps.setLong(2, activityId);
            ps.setLong(3, userId);
            ps.setLong(4, questionnaireVersionId);
            ps.setString(5, ruleVersion);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<AssessmentReport> findById(Long id) {
        List<AssessmentReport> list = jdbcTemplate.query("SELECT * FROM assessment_report WHERE id = ?", ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<AssessmentReport> findBySheetId(Long sheetId) {
        List<AssessmentReport> list = jdbcTemplate.query("SELECT * FROM assessment_report WHERE sheet_id = ?", ROW_MAPPER, sheetId);
        return list.stream().findFirst();
    }

    public List<AssessmentReport> findByActivityId(Long activityId) {
        return jdbcTemplate.query("SELECT * FROM assessment_report WHERE activity_id = ? ORDER BY id ASC", ROW_MAPPER, activityId);
    }

    public List<AssessmentReport> findBySheetIds(Collection<Long> sheetIds) {
        if (sheetIds == null || sheetIds.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = sheetIds.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.query(
                "SELECT * FROM assessment_report WHERE sheet_id IN (" + placeholders + ")",
                ROW_MAPPER,
                sheetIds.toArray()
        );
    }

    public void markSuccess(
            Long reportId,
            BigDecimal totalScore,
            String dimensionScoresJson,
            String levelCode,
            String levelName,
            String feedbackText,
            LocalDateTime generatedAt) {
        jdbcTemplate.update(
                "UPDATE assessment_report SET task_status = 'SUCCESS', total_score = ?, dimension_scores_json = ?, level_code = ?, level_name = ?, feedback_text = ?, last_error = NULL, generated_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                totalScore,
                dimensionScoresJson,
                levelCode,
                levelName,
                feedbackText,
                JdbcHelper.toTimestamp(generatedAt),
                reportId
        );
    }

    public void recordDispatchError(Long sheetId, String errorMsg) {
        String truncated = truncateError(errorMsg);
        jdbcTemplate.update(
                "UPDATE assessment_report SET last_error = ?, updated_at = CURRENT_TIMESTAMP WHERE sheet_id = ? AND task_status <> 'SUCCESS'",
                truncated,
                sheetId
        );
    }

    public void updateRetryOrFailed(Long reportId, int retryCount, String taskStatus, String lastError) {
        jdbcTemplate.update(
                "UPDATE assessment_report SET retry_count = ?, task_status = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                retryCount,
                taskStatus,
                truncateError(lastError),
                reportId
        );
    }

    public void prepareForRetry(Long reportId, int retryCount) {
        jdbcTemplate.update(
                "UPDATE assessment_report SET retry_count = ?, task_status = 'PENDING', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                retryCount,
                reportId
        );
    }

    public long countPendingOrFailedByActivityIds(Collection<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) {
            return 0L;
        }
        String placeholders = activityIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM assessment_report WHERE activity_id IN (" + placeholders + ") AND task_status IN ('PENDING', 'FAILED')",
                Long.class,
                activityIds.toArray()
        );
        return cnt == null ? 0L : cnt;
    }

    private String truncateError(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 480 ? msg.substring(0, 480) : msg;
    }
}
