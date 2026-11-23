package com.psych.assessment.repository;

import com.psych.assessment.entity.AnswerSheet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class AnswerSheetRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<AnswerSheet> ROW_MAPPER = (rs, rowNum) -> AnswerSheet.builder()
            .id(rs.getLong("id"))
            .activityId(rs.getLong("activity_id"))
            .questionnaireVersionId(rs.getLong("questionnaire_version_id"))
            .userId(rs.getLong("user_id"))
            .submittedAt(JdbcHelper.getLocalDateTime(rs, "submitted_at"))
            .build();

    public Long insert(Long activityId, Long questionnaireVersionId, Long userId, LocalDateTime submittedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO answer_sheet (activity_id, questionnaire_version_id, user_id, submitted_at) VALUES (?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setLong(1, activityId);
            ps.setLong(2, questionnaireVersionId);
            ps.setLong(3, userId);
            ps.setTimestamp(4, JdbcHelper.toTimestamp(submittedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<AnswerSheet> findById(Long id) {
        List<AnswerSheet> list = jdbcTemplate.query("SELECT * FROM answer_sheet WHERE id = ?", ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<AnswerSheet> findByActivityAndUser(Long activityId, Long userId) {
        List<AnswerSheet> list = jdbcTemplate.query(
                "SELECT * FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                ROW_MAPPER,
                activityId,
                userId
        );
        return list.stream().findFirst();
    }

    public List<AnswerSheet> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "SELECT * FROM answer_sheet WHERE user_id = ? ORDER BY submitted_at DESC, id DESC",
                ROW_MAPPER,
                userId
        );
    }

    public List<AnswerSheet> findByActivityId(Long activityId) {
        return jdbcTemplate.query(
                "SELECT * FROM answer_sheet WHERE activity_id = ? ORDER BY id ASC",
                ROW_MAPPER,
                activityId
        );
    }

    public long countParticipantCompletedByActivityId(Long activityId) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT s.user_id) FROM answer_sheet s INNER JOIN activity_participant p ON s.activity_id = p.activity_id AND s.user_id = p.user_id WHERE s.activity_id = ?",
                Long.class,
                activityId
        );
        return cnt == null ? 0L : cnt;
    }

    public long countByActivityIds(Collection<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) {
            return 0L;
        }
        String placeholders = activityIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM answer_sheet WHERE activity_id IN (" + placeholders + ")",
                Long.class,
                activityIds.toArray()
        );
        return cnt == null ? 0L : cnt;
    }
}
