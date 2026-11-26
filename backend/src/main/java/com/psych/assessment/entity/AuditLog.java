package com.psych.assessment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作审计日志（契约：管理端操作留痕）。
 *
 * <p>对应表 DDL（由 DDL 文件统一维护，此处仅作结构对齐）：
 * <pre>
 * CREATE TABLE IF NOT EXISTS audit_log (
 *     id BIGINT PRIMARY KEY AUTO_INCREMENT,
 *     operator_id BIGINT,
 *     operator_username VARCHAR(64),
 *     operator_real_name VARCHAR(64),
 *     action_type VARCHAR(64) NOT NULL,
 *     resource_type VARCHAR(64) NOT NULL,
 *     resource_id BIGINT,
 *     summary VARCHAR(255),
 *     detail_json CLOB,
 *     ip_address VARCHAR(64),
 *     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
 * );
 * </pre>
 * 安全红线：detailJson 绝不记录完整 API Key、密码、Token，写入前由 Service 层统一脱敏。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {
    private Long id;
    private Long operatorId;
    private String operatorUsername;
    private String operatorRealName;
    private String actionType;
    private String resourceType;
    private Long resourceId;
    private String summary;
    private String detailJson;
    private String ipAddress;
    private LocalDateTime createdAt;
}
