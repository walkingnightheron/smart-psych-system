package com.psych.assessment.repository;

import com.psych.assessment.entity.AssessmentActivity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class AssessmentActivityRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<AssessmentActivity> ROW_MAPPER = (rs, rowNum) -> AssessmentActivity.builder()
            .id(rs.getLong("id"))
            .title(rs.getString("title"))
            .questionnaireVersionId(rs.getLong("questionnaire_version_id"))
            .status(rs.getString("status"))
            .startTime(JdbcHelper.getLocalDateTime(rs, "start_time"))
            .endTime(JdbcHelper.getLocalDateTime(rs, "end_time"))
            .ownerId(rs.getLong("owner_id"))
            .authorizedAdminIds(rs.getString("authorized_admin_ids"))
            .createdAt(JdbcHelper.getLocalDateTime(rs, "created_at"))
            .updatedAt(JdbcHelper.getLocalDateTime(rs, "updated_at"))
            .build();

    public List<AssessmentActivity> findAll() {
        return jdbcTemplate.query("SELECT * FROM assessment_activity ORDER BY id DESC", ROW_MAPPER);
    }

    public Optional<AssessmentActivity> findById(Long id) {
        List<AssessmentActivity> list = jdbcTemplate.query(
                "SELECT * FROM assessment_activity WHERE id = ?",
                ROW_MAPPER,
                id
        );
        return list.stream().findFirst();
    }

    public List<AssessmentActivity> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.query(
                "SELECT * FROM assessment_activity WHERE id IN (" + placeholders + ") ORDER BY id DESC",
                ROW_MAPPER,
                ids.toArray()
        );
    }

    public Long insert(AssessmentActivity activity) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO assessment_activity (title, questionnaire_version_id, status, start_time, end_time, owner_id, authorized_admin_ids) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setString(1, activity.getTitle());
            ps.setLong(2, activity.getQuestionnaireVersionId());
            ps.setString(3, activity.getStatus() == null ? "DRAFT" : activity.getStatus());
            ps.setTimestamp(4, JdbcHelper.toTimestamp(activity.getStartTime()));
            ps.setTimestamp(5, JdbcHelper.toTimestamp(activity.getEndTime()));
            ps.setLong(6, activity.getOwnerId());
            ps.setString(7, activity.getAuthorizedAdminIds());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateDraft(AssessmentActivity activity) {
        jdbcTemplate.update(
                "UPDATE assessment_activity SET title = ?, questionnaire_version_id = ?, start_time = ?, end_time = ?, authorized_admin_ids = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                activity.getTitle(),
                activity.getQuestionnaireVersionId(),
                JdbcHelper.toTimestamp(activity.getStartTime()),
                JdbcHelper.toTimestamp(activity.getEndTime()),
                activity.getAuthorizedAdminIds(),
                activity.getId()
        );
    }

    public void updateStatus(Long id, String status) {
        jdbcTemplate.update(
                "UPDATE assessment_activity SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status,
                id
        );
    }

    /**
     * 已发布活动安全调整：仅更新标题、结束时间、授权管理员；
     * 绝不触碰 questionnaire_version_id / start_time / status（REQ03 版本冻结红线）。
     */
    public void updatePublishedSettings(Long id, String title, java.time.LocalDateTime endTime, String authorizedAdminIds) {
        jdbcTemplate.update(
                "UPDATE assessment_activity SET title = ?, end_time = ?, authorized_admin_ids = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                title,
                JdbcHelper.toTimestamp(endTime),
                authorizedAdminIds,
                id
        );
    }
}
