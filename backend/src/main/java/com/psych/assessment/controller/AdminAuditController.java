package com.psych.assessment.controller;

import com.psych.assessment.common.Result;
import com.psych.assessment.dto.AuditDtos.AuditLogPageVO;
import com.psych.assessment.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端操作审计日志查询接口（仅 ADMIN，由 AuthInterceptor 统一拦截 /api/admin/**）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditLogService auditLogService;

    @GetMapping("/audit-logs")
    public Result<AuditLogPageVO> listAuditLogs(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "actionType", required = false) String actionType,
            @RequestParam(value = "resourceType", required = false) String resourceType,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return Result.ok(auditLogService.search(keyword, actionType, resourceType,
                startTime, endTime, page, pageSize));
    }
}
