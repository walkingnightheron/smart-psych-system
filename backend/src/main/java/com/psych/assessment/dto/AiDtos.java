package com.psych.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能分析配置与 AI 洞察 DTO（契约 8.1/8.1b/8.2/8.3）。
 * 安全红线：任何 VO 绝不携带完整 apiKey，仅回 hasApiKey 与 apiKeyTail 尾4位。
 */
public class AiDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmProviderVO {
        private Long id;
        private String name;
        private String baseUrl;
        private boolean hasApiKey;
        private String apiKeyTail;
        private boolean enabled;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmProviderUpsertRequest {
        private String name;
        private String baseUrl;
        /** 新增时必填；修改时留空表示保留原值 */
        private String apiKey;
        private Boolean enabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmModelsResultVO {
        private boolean ok;
        private List<String> models;
        /** 失败时的友好原因（绝不含 API Key） */
        private String error;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmConfigVO {
        private boolean reportInsightEnabled;
        private Long reportProviderId;
        private String reportProviderName;
        private String reportModel;
        private BigDecimal reportTemperature;
        private Integer reportMaxTokens;
        private boolean cohortInsightEnabled;
        private Long cohortProviderId;
        private String cohortProviderName;
        private String cohortModel;
        private BigDecimal cohortTemperature;
        private Integer cohortMaxTokens;
        /** 备用路由（自动降级）字段：仅在启用时有效，providerName 由 Service 回填。 */
        private boolean fallbackEnabled;
        private Long reportFallbackProviderId;
        private String reportFallbackProviderName;
        private String reportFallbackModel;
        private Long cohortFallbackProviderId;
        private String cohortFallbackProviderName;
        private String cohortFallbackModel;
        private Integer timeoutSeconds;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmConfigUpdateRequest {
        private Boolean reportInsightEnabled;
        private Long reportProviderId;
        private String reportModel;
        private BigDecimal reportTemperature;
        private Integer reportMaxTokens;
        private Boolean cohortInsightEnabled;
        private Long cohortProviderId;
        private String cohortModel;
        private BigDecimal cohortTemperature;
        private Integer cohortMaxTokens;
        /** 备用路由（自动降级）配置 */
        private Boolean fallbackEnabled;
        private Long reportFallbackProviderId;
        private String reportFallbackModel;
        private Long cohortFallbackProviderId;
        private String cohortFallbackModel;
        private Integer timeoutSeconds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmTestRequest {
        private Long providerId;
        private String model;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmTestResultVO {
        private boolean ok;
        private long latencyMs;
        private String model;
        private String reply;
        /** 失败时的友好原因（绝不含 API Key） */
        private String error;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiInsightVO {
        private boolean available;
        private String content;
        private String model;
        private LocalDateTime generatedAt;
        private String disclaimer;
        /** available=false 时的降级原因（前端据此回退静态文案，绝不阻断主流程） */
        private String reason;
    }
}
