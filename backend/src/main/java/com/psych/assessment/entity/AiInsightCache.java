package com.psych.assessment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 洞察结果缓存（契约 8.2/8.3：报告解读按 report:{id}、群体洞察按 activity:{id} 缓存）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiInsightCache {
    private String cacheKey;
    private String content;
    private String model;
    private LocalDateTime generatedAt;
}
