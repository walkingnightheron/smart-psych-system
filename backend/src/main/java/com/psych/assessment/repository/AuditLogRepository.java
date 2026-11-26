package com.psych.assessment.repository;

import com.psych.assessment.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * audit_log 审计日志仓储（管理端操作留痕）。
 * 写入失败绝不外抛：由 {@code AuditLogService} 在 REQUIRES_NEW 事务内兜底吞掉，不影响主业务。
 */
@Repository
@RequiredArgsConstructor
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<AuditLog> ROW_MAPPER = (rs, rowNum) -> AuditLog.builder()
            .id(rs.getLong("id"))
            .operatorId(JdbcHelper.getLongOpt(rs, "operator_id"))
            .operatorUsername(rs.getString("operator_username"))
            .operatorRealName(rs.getString("operator_real_name"))
            .actionType(rs.getString("action_type"))
            .resourceType(rs.getString("resource_type"))
            .resourceId(JdbcHelper.getLongOpt(rs, "resource_id"))
            .summary(rs.getString("summary"))
            .detailJson(rs.getString("detail_json"))
            .ipAddress(rs.getString("ip_address"))
            .createdAt(JdbcHelper.getLocalDateTime(rs, "created_at"))
            .build();

    public Long insert(AuditLog log) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO audit_log (operator_id, operator_username, operator_real_name, action_type, resource_type, resource_id, summary, detail_json, ip_address) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setObject(1, log.getOperatorId());
            ps.setString(2, log.getOperatorUsername());
            ps.setString(3, log.getOperatorRealName());
            ps.setString(4, log.getActionType());
            ps.setString(5, log.getResourceType());
            ps.setObject(6, log.getResourceId());
            ps.setString(7, log.getSummary());
            ps.setString(8, log.getDetailJson());
            ps.setString(9, log.getIpAddress());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public List<AuditLog> search(String keyword, String actionType, String resourceType,
                                 LocalDateTime startTime, LocalDateTime endTime,
                                 int offset, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, actionType, resourceType, startTime, endTime);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long count(String keyword, String actionType, String resourceType,
                      LocalDateTime startTime, LocalDateTime endTime) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_log WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, actionType, resourceType, startTime, endTime);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String keyword, String actionType,
                               String resourceType, LocalDateTime startTime, LocalDateTime endTime) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (operator_username LIKE ? OR operator_real_name LIKE ? OR summary LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (actionType != null && !actionType.isBlank()) {
            sql.append(" AND action_type = ?");
            args.add(actionType.trim());
        }
        if (resourceType != null && !resourceType.isBlank()) {
            sql.append(" AND resource_type = ?");
            args.add(resourceType.trim());
        }
        if (startTime != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.valueOf(startTime));
        }
        if (endTime != null) {
            sql.append(" AND created_at <= ?");
            args.add(Timestamp.valueOf(endTime));
        }
    }
}
