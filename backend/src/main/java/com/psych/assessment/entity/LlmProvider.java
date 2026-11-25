package com.psych.assessment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LLM 提供商（契约 8.1 多提供商）。
 * 安全红线：apiKey 仅存库，任何响应一律脱敏（hasApiKey + apiKeyTail 尾4位），绝不返回完整 Key。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProvider {
    private Long id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private Integer enabled;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
