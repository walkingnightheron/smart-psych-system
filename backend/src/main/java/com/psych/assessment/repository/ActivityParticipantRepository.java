package com.psych.assessment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class ActivityParticipantRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<Long> findUserIdsByActivityId(Long activityId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id FROM activity_participant WHERE activity_id = ? ORDER BY user_id ASC",
                Long.class,
                activityId
        );
    }

    public List<Long> findActivityIdsByUserId(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT activity_id FROM activity_participant WHERE user_id = ? ORDER BY activity_id DESC",
                Long.class,
                userId
        );
    }

    public boolean existsByActivityAndUser(Long activityId, Long userId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_participant WHERE activity_id = ? AND user_id = ?",
                Integer.class,
                activityId,
                userId
        );
        return cnt != null && cnt > 0;
    }

    public long countByActivityId(Long activityId) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_participant WHERE activity_id = ?",
                Long.class,
                activityId
        );
        return cnt == null ? 0L : cnt;
    }

    public void replaceParticipants(Long activityId, Collection<Long> userIds) {
        jdbcTemplate.update("DELETE FROM activity_participant WHERE activity_id = ?", activityId);
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<Long> uniqueIds = new LinkedHashSet<>(userIds);
        for (Long userId : uniqueIds) {
            if (userId != null) {
                jdbcTemplate.update(
                        "INSERT INTO activity_participant (activity_id, user_id) VALUES (?, ?)",
                        activityId,
                        userId
                );
            }
        }
    }
}
