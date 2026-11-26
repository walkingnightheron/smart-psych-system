package com.psych.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作审计日志 DTO（管理端 GET /api/admin/audit-logs）。
 * 安全红线：detailJson 由写入侧统一脱敏，绝不回传任何完整 API Key/密码/Token。
 */
public class AuditDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditLogVO {
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditLogPageVO {
        private long total;
        private int page;
        private int pageSize;
        private List<AuditLogVO> list;
    }
}
