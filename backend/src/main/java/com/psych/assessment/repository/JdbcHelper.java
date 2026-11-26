package com.psych.assessment.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

public final class JdbcHelper {

    private JdbcHelper() {
    }

    public static LocalDateTime getLocalDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    public static Timestamp toTimestamp(LocalDateTime dt) {
        return dt == null ? null : Timestamp.valueOf(dt);
    }

    /**
     * 可选列读取（用于 llm_config 备用路由等后置字段，DDL 尚未补列时返回 null 而非抛异常）。
     * 依据 SQL 标准 SQLSTATE 42S22（列不存在）判断，兼容 H2 与 MySQL。
     */
    public static Long getLongOpt(ResultSet rs, String column) throws SQLException {
        try {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        } catch (SQLException e) {
            if (isMissingColumn(e)) {
                return null;
            }
            throw e;
        }
    }

    public static Integer getIntOpt(ResultSet rs, String column) throws SQLException {
        try {
            int value = rs.getInt(column);
            return rs.wasNull() ? null : value;
        } catch (SQLException e) {
            if (isMissingColumn(e)) {
                return null;
            }
            throw e;
        }
    }

    public static String getStringOpt(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            if (isMissingColumn(e)) {
                return null;
            }
            throw e;
        }
    }

    private static boolean isMissingColumn(SQLException e) {
        String state = e.getSQLState();
        return "42S22".equals(state) || "S0022".equals(state);
    }
}
