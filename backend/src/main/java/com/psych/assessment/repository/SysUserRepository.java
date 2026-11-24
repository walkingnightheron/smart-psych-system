package com.psych.assessment.repository;

import com.psych.assessment.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class SysUserRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<SysUser> ROW_MAPPER = (rs, rowNum) -> SysUser.builder()
            .id(rs.getLong("id"))
            .username(rs.getString("username"))
            .passwordHash(rs.getString("password_hash"))
            .realName(rs.getString("real_name"))
            .role(rs.getString("role"))
            .deptClass(rs.getString("dept_class"))
            .phone(rs.getString("phone"))
            .status(rs.getInt("status"))
            .createdAt(JdbcHelper.getLocalDateTime(rs, "created_at"))
            .updatedAt(JdbcHelper.getLocalDateTime(rs, "updated_at"))
            .build();

    public Optional<SysUser> findById(Long id) {
        List<SysUser> list = jdbcTemplate.query(
                "SELECT * FROM sys_user WHERE id = ?",
                ROW_MAPPER,
                id
        );
        return list.stream().findFirst();
    }

    public Optional<SysUser> findByUsername(String username) {
        List<SysUser> list = jdbcTemplate.query(
                "SELECT * FROM sys_user WHERE username = ?",
                ROW_MAPPER,
                username
        );
        return list.stream().findFirst();
    }

    public List<SysUser> findAllActive(String role) {
        if (role != null && !role.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT * FROM sys_user WHERE status = 1 AND role = ? ORDER BY id ASC",
                    ROW_MAPPER,
                    role.trim().toUpperCase()
            );
        }
        return jdbcTemplate.query(
                "SELECT * FROM sys_user WHERE status = 1 ORDER BY id ASC",
                ROW_MAPPER
        );
    }

    public List<SysUser> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.query(
                "SELECT * FROM sys_user WHERE id IN (" + placeholders + ") ORDER BY id ASC",
                ROW_MAPPER,
                ids.toArray()
        );
    }

    public void updateProfile(Long id, String realName, String deptClass, String phone) {
        jdbcTemplate.update(
                "UPDATE sys_user SET real_name = ?, dept_class = ?, phone = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                realName, deptClass, phone, id
        );
    }

    /**
     * 学生名册查询（仅 role = STUDENT），支持学号/姓名/学院班级模糊搜索与班级筛选（契约 7.4）。
     */
    public List<SysUser> searchStudents(String keyword, String deptClass) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sys_user WHERE role = 'STUDENT'");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username LIKE ? OR real_name LIKE ? OR dept_class LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (deptClass != null && !deptClass.isBlank()) {
            sql.append(" AND dept_class LIKE ?");
            args.add("%" + deptClass.trim() + "%");
        }
        sql.append(" ORDER BY id ASC");
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /**
     * 仅更新非空资料字段，绝不触碰密码（契约 7.4 幂等更新）。
     */
    public int updateStudentSelective(Long id, String realName, String deptClass, String phone) {
        StringBuilder sql = new StringBuilder("UPDATE sys_user SET updated_at = CURRENT_TIMESTAMP");
        List<Object> args = new ArrayList<>();
        if (realName != null && !realName.isBlank()) {
            sql.append(", real_name = ?");
            args.add(realName.trim());
        }
        if (deptClass != null && !deptClass.isBlank()) {
            sql.append(", dept_class = ?");
            args.add(deptClass.trim());
        }
        if (phone != null && !phone.isBlank()) {
            sql.append(", phone = ?");
            args.add(phone.trim());
        }
        sql.append(" WHERE id = ?");
        args.add(id);
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /**
     * 单个学生信息全量更新（契约 7.4 PUT /api/admin/students/{id}）。
     */
    public int updateStudentFull(Long id, String realName, String deptClass, String phone, Integer status) {
        return jdbcTemplate.update(
                "UPDATE sys_user SET real_name = ?, dept_class = ?, phone = ?, status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                realName, deptClass, phone, status, id
        );
    }

    /**
     * 启用/禁用账号（契约 7.4 PUT /api/admin/students/{id}/status）。
     */
    public int updateStatus(Long id, int status) {
        return jdbcTemplate.update(
                "UPDATE sys_user SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, id
        );
    }

    /**
     * 重置密码（仅更新 password_hash，契约 7.4 reset-password）。
     */
    public int updatePasswordHash(Long id, String passwordHash) {
        return jdbcTemplate.update(
                "UPDATE sys_user SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                passwordHash, id
        );
    }

    /**
     * 物理删除账号（仅用于无历史答卷且不在已发布活动名单中的学生，契约 7.4 DELETE）。
     */
    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", id);
    }

    public Long insert(SysUser user) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sys_user (username, password_hash, real_name, role, dept_class, phone, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getRealName());
            ps.setString(4, user.getRole());
            ps.setString(5, user.getDeptClass());
            ps.setString(6, user.getPhone());
            ps.setInt(7, user.getStatus() == null ? 1 : user.getStatus());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
        return count == null ? 0L : count;
    }
}
