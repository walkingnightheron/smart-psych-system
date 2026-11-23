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
public class QuestionnaireVersion {
    private Long id;
    private Long questionnaireId;
    private Integer versionNum;
    private String ruleVersion;
    private String title;
    private String description;
    private String questionsSnapshot;
    private String scoringRuleSnapshot;
    private Long publishedBy;
    private LocalDateTime publishedAt;
}
