package com.psych.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class QuestionnaireDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionConfig {
        private String optCode;
        private String label;
        private BigDecimal score;
        private Integer sort;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionConfig {
        private Integer questionNo;
        private String stem;
        private String questionType;
        private String dimensionCode;
        private Integer isReverse;
        private Integer required;
        private List<OptionConfig> options;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionRule {
        private String code;
        private String name;
        private String formula;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LevelRule {
        private String code;
        private String name;
        private BigDecimal min;
        private BigDecimal max;
        private Boolean minInclusive;
        private Boolean maxInclusive;
        private String advice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoringRuleConfig {
        private String ruleVersion;
        private String formula;
        private BigDecimal reverseMaxPlusMin;
        private List<DimensionRule> dimensions;
        private List<LevelRule> levels;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionnaireUpsertRequest {
        private String code;
        private String title;
        private String description;
        private ScoringRuleConfig scoringRule;
        private List<QuestionConfig> questions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionnaireListItemVO {
        private Long id;
        private String code;
        private String title;
        private String description;
        private Integer latestVersion;
        private Integer questionCount;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionnaireDetailVO {
        private Long id;
        private String code;
        private String title;
        private String description;
        private Integer latestVersion;
        private Long creatorId;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private ScoringRuleConfig scoringRule;
        private List<QuestionConfig> questions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublishVersionRequest {
        private String ruleVersion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionnaireVersionVO {
        private Long id;
        private Long questionnaireId;
        private String questionnaireCode;
        private Integer versionNum;
        private String ruleVersion;
        private String title;
        private String description;
        private Integer questionCount;
        private List<QuestionConfig> questions;
        private ScoringRuleConfig scoringRule;
        private Long publishedBy;
        private LocalDateTime publishedAt;
        private String cacheSource;
    }
}
