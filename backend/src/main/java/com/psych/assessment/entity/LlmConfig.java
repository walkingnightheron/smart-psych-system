package com.psych.assessment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 功能路由配置（契约 8.1b，全库单行 id=1）。
 * 报告解读路由：report_* 字段；群体洞察路由：cohort_* 字段；不再内嵌任何 apiKey/baseUrl。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmConfig {
    private Long id;
    private Integer reportInsightEnabled;
    private Long reportProviderId;
    private String reportModel;
    private BigDecimal reportTemperature;
    private Integer reportMaxTokens;
    private Integer cohortInsightEnabled;
    private Long cohortProviderId;
    private String cohortModel;
    private BigDecimal cohortTemperature;
    private Integer cohortMaxTokens;
    /** 备用路由（自动降级）字段：DDL 若尚未补充以下列，Repository 以 null/0 兼容读取，默认关闭降级。 */
    private Long reportFallbackProviderId;
    private String reportFallbackModel;
    private Long cohortFallbackProviderId;
    private String cohortFallbackModel;
    private Integer fallbackEnabled;
    private Integer timeoutSeconds;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
