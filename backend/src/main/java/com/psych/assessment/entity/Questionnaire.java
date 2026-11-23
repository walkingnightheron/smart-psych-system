package com.psych.assessment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Questionnaire {
    private Long id;
    private String code;
    private String title;
    private String description;
    private String scoringRuleJson;
    private Integer latestVersion;
    private Long creatorId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
