package com.psych.assessment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * 批量统计一组学生各自参与的活动数（学生名册 participatedActivityCount）。
     */
    public Map<Long, Long> countGroupByUserIds(Collection<Long> userIds) {
        Map<Long, Long> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        String placeholders = userIds.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, COUNT(*) AS cnt FROM activity_participant WHERE user_id IN (" + placeholders + ") GROUP BY user_id",
                userIds.toArray()
        );
        for (Map<String, Object> row : rows) {
            Object userId = row.get("user_id");
            Object cnt = row.get("cnt");
            if (userId != null && cnt != null) {
                result.put(((Number) userId).longValue(), ((Number) cnt).longValue());
            }
        }
        return result;
    }

    /**
     * 统计学生被纳入的非草稿（已发布/已关闭）活动数（契约 7.4 删除保护红线）。
     */
    public long countNonDraftActivitiesByUserId(Long userId) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_participant ap JOIN assessment_activity a ON ap.activity_id = a.id WHERE ap.user_id = ? AND a.status <> 'DRAFT'",
                Long.class,
                userId
        );
        return cnt == null ? 0L : cnt;
    }

    /**
     * 查询学生所在的草稿活动 ID 列表（删除学生前收集缓存清理目标）。
     */
    public List<Long> findDraftActivityIdsByUserId(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT ap.activity_id FROM activity_participant ap JOIN assessment_activity a ON ap.activity_id = a.id WHERE ap.user_id = ? AND a.status = 'DRAFT' ORDER BY ap.activity_id ASC",
                Long.class,
                userId
        );
    }

    /**
     * 清理学生在全部活动名单中的记录（仅允许在删除无历史数据学生时调用）。
     */
    public int deleteByUserId(Long userId) {
        return jdbcTemplate.update("DELETE FROM activity_participant WHERE user_id = ?", userId);
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
