package com.psych.assessment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentReport {
    private Long id;
    private Long sheetId;
    private Long activityId;
    private Long userId;
    private Long questionnaireVersionId;
    private String ruleVersion;
    private String taskStatus;
    private BigDecimal totalScore;
    private String dimensionScoresJson;
    private String levelCode;
    private String levelName;
    private String feedbackText;
    private Integer retryCount;
    private Integer maxRetries;
    private String lastError;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
