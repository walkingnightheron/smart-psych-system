package com.psych.assessment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.dto.AuditDtos.AuditLogPageVO;
import com.psych.assessment.dto.AuditDtos.AuditLogVO;
import com.psych.assessment.entity.AuditLog;
import com.psych.assessment.repository.AuditLogRepository;
import com.psych.assessment.security.AuthContext;
import com.psych.assessment.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 操作审计日志服务（管理端操作留痕）。
 *
 * <p>设计红线：
 * <ul>
 *   <li>写入采用 {@code REQUIRES_NEW} 独立事务，主业务回滚时审计仍可落库；</li>
 *   <li>审计写入失败绝不影响主业务（内部吞异常，仅 warn 日志）；</li>
 *   <li>detailJson 对敏感字段（apiKey/password/token/secret 等）统一脱敏，绝不记录完整密钥。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    // ---------- actionType 常量 ----------
    public static final String ACT_QUESTIONNAIRE_CREATE = "QUESTIONNAIRE_CREATE";
    public static final String ACT_QUESTIONNAIRE_UPDATE_DRAFT = "QUESTIONNAIRE_UPDATE_DRAFT";
    public static final String ACT_QUESTIONNAIRE_PUBLISH = "QUESTIONNAIRE_PUBLISH";
    public static final String ACT_ACTIVITY_CREATE = "ACTIVITY_CREATE";
    public static final String ACT_ACTIVITY_ADJUST_SETTINGS = "ACTIVITY_ADJUST_SETTINGS";
    public static final String ACT_ACTIVITY_PUBLISH = "ACTIVITY_PUBLISH";
    public static final String ACT_ACTIVITY_CLOSE = "ACTIVITY_CLOSE";
    public static final String ACT_ACTIVITY_ROSTER_IMPORT = "ACTIVITY_ROSTER_IMPORT";
    public static final String ACT_STUDENT_CREATE = "STUDENT_CREATE";
    public static final String ACT_STUDENT_UPDATE = "STUDENT_UPDATE";
    public static final String ACT_STUDENT_STATUS = "STUDENT_STATUS";
    public static final String ACT_STUDENT_RESET_PASSWORD = "STUDENT_RESET_PASSWORD";
    public static final String ACT_STUDENT_DELETE = "STUDENT_DELETE";
    public static final String ACT_LLM_PROVIDER_CREATE = "LLM_PROVIDER_CREATE";
    public static final String ACT_LLM_PROVIDER_UPDATE = "LLM_PROVIDER_UPDATE";
    public static final String ACT_LLM_PROVIDER_DELETE = "LLM_PROVIDER_DELETE";
    public static final String ACT_LLM_ROUTING_SAVE = "LLM_ROUTING_SAVE";
    public static final String ACT_REPORT_RETRY = "REPORT_RETRY";
    public static final String ACT_XLSX_IMPORT = "XLSX_IMPORT";
    public static final String ACT_STATS_EXPORT = "STATS_EXPORT";

    // ---------- resourceType 常量 ----------
    public static final String RES_QUESTIONNAIRE = "QUESTIONNAIRE";
    public static final String RES_ACTIVITY = "ACTIVITY";
    public static final String RES_STUDENT = "STUDENT";
    public static final String RES_LLM_PROVIDER = "LLM_PROVIDER";
    public static final String RES_LLM_CONFIG = "LLM_CONFIG";
    public static final String RES_REPORT = "REPORT";

    private static final String[] SENSITIVE_KEY_MARKERS =
            {"apikey", "api_key", "password", "passwd", "pwd", "token", "secret", "authorization"};

    private static final DateTimeFormatter STD_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    // ==================================================================
    // 统一记录方法（REQUIRES_NEW，内部吞异常，绝不影响主业务）
    // ==================================================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String actionType, String resourceType, Long resourceId, String summary,
                              Map<String, Object> detail) {
        persist(actionType, resourceType, resourceId, summary, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String actionType, String resourceType, Long resourceId, String summary,
                              Map<String, Object> detail) {
        persist(actionType, resourceType, resourceId, summary, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String actionType, String resourceType, Long resourceId, String summary) {
        persist(actionType, resourceType, resourceId, summary, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String actionType, String resourceType, Long resourceId, String summary) {
        persist(actionType, resourceType, resourceId, summary, null);
    }

    private void persist(String actionType, String resourceType, Long resourceId, String summary,
                         Map<String, Object> detail) {
        try {
            AuthUser user = AuthContext.get();
            AuditLog log = AuditLog.builder()
                    .operatorId(user == null ? null : user.getId())
                    .operatorUsername(user == null ? null : user.getUsername())
                    .operatorRealName(user == null ? null : user.getRealName())
                    .actionType(actionType)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .summary(summary)
                    .detailJson(serializeDetail(detail))
                    .ipAddress(resolveClientIp())
                    .build();
            repository.insert(log);
        } catch (Exception ex) {
            log.warn("审计日志写入失败(已忽略，不影响主业务): actionType={}, resourceType={}, resourceId={}, cause={}",
                    actionType, resourceType, resourceId, ex.getClass().getSimpleName());
        }
    }

    // ==================================================================
    // 分页查询（管理端 GET /api/admin/audit-logs）
    // ==================================================================

    public AuditLogPageVO search(String keyword, String actionType, String resourceType,
                                 String startTime, String endTime, int page, int pageSize) {
        int p = page <= 0 ? 1 : page;
        int ps = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LocalDateTime start = parseTime(startTime, "startTime");
        LocalDateTime end = parseTime(endTime, "endTime");

        long total = repository.count(keyword, actionType, resourceType, start, end);
        List<AuditLogVO> list = repository.search(keyword, actionType, resourceType, start, end,
                        (p - 1) * ps, ps).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return AuditLogPageVO.builder()
                .total(total)
                .page(p)
                .pageSize(ps)
                .list(list)
                .build();
    }

    private AuditLogVO toVO(AuditLog log) {
        return AuditLogVO.builder()
                .id(log.getId())
                .operatorId(log.getOperatorId())
                .operatorUsername(log.getOperatorUsername())
                .operatorRealName(log.getOperatorRealName())
                .actionType(log.getActionType())
                .resourceType(log.getResourceType())
                .resourceId(log.getResourceId())
                .summary(log.getSummary())
                .detailJson(log.getDetailJson())
                .ipAddress(log.getIpAddress())
                .createdAt(log.getCreatedAt())
                .build();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String serializeDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sanitize(detail));
        } catch (Exception ex) {
            return null;
        }
    }

    /** 递归脱敏：key 含 apiKey/password/token/secret 等标记的值一律替换为 ***。 */
    @SuppressWarnings("unchecked")
    private Object sanitize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, isSensitiveKey(key) ? "***" : sanitize(v));
            });
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object v : (List<?>) value) {
                out.add(sanitize(v));
            }
            return out;
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        for (String marker : SENSITIVE_KEY_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
            return request.getRemoteAddr();
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDateTime parseTime(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return LocalDateTime.parse(trimmed, STD_FMT);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ex) {
            throw new com.psych.assessment.common.BizException(400,
                    field + " 时间格式不正确，应使用 yyyy-MM-dd HH:mm:ss");
        }
    }
}
