package com.psych.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class H5AndReportDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class H5ActivityItemVO {
        private Long id;
        private String title;
        private Long questionnaireVersionId;
        private String questionnaireTitle;
        private Integer versionNum;
        private Integer questionCount;
        private String status;
        private String timeWindowStatus;
        private boolean canSubmit;
        private String statusText;
        private boolean submitted;
        private Long sheetId;
        private Long reportId;
        private String reportTaskStatus;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SanitizedOptionVO {
        private String optCode;
        private String label;
        private Integer sort;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SanitizedQuestionVO {
        private Integer questionNo;
        private String stem;
        private String questionType;
        private Integer required;
        private List<SanitizedOptionVO> options;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class H5ActivityDetailVO {
        private Long activityId;
        private String activityTitle;
        private String status;
        private String timeWindowStatus;
        private boolean canSubmit;
        private String statusText;
        private boolean submitted;
        private Long sheetId;
        private Long reportId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long questionnaireVersionId;
        private String questionnaireTitle;
        private String questionnaireDescription;
        private Integer versionNum;
        private String ruleVersion;
        private List<SanitizedQuestionVO> questions;
        private String cacheSource;
        private String activityCacheSource;
        private String versionCacheSource;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerInput {
        private Integer questionNo;
        private String selectedOptCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitSheetRequest {
        private List<AnswerInput> answers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitSheetResponse {
        private Long sheetId;
        private Long reportId;
        private Long activityId;
        private String taskStatus;
        private LocalDateTime submittedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictSubmitData {
        private Long sheetId;
        private Long reportId;
        private Long activityId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class H5RecordItemVO {
        private Long sheetId;
        private Long activityId;
        private String activityTitle;
        private Long questionnaireVersionId;
        private String questionnaireTitle;
        private Integer versionNum;
        private LocalDateTime submittedAt;
        private Long reportId;
        private String taskStatus;
        private BigDecimal totalScore;
        private String levelCode;
        private String levelName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnsweredQuestionItemVO {
        private Integer questionNo;
        private String stem;
        private String selectedOptCode;
        private String selectedOptLabel;
        private String dimensionCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class H5RecordDetailVO {
        private Long sheetId;
        private Long activityId;
        private String activityTitle;
        private Long questionnaireVersionId;
        private String questionnaireTitle;
        private Integer versionNum;
        private String ruleVersion;
        private LocalDateTime submittedAt;
        private Long reportId;
        private String taskStatus;
        private List<AnsweredQuestionItemVO> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionScoreVO {
        private String code;
        private String name;
        private BigDecimal score;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportDetailVO {
        private Long id;
        private Long reportId;
        private Long sheetId;
        private Long activityId;
        private String activityTitle;
        private Long userId;
        private String username;
        private String realName;
        private Long questionnaireVersionId;
        private String questionnaireTitle;
        private Integer versionNum;
        private String ruleVersion;
        private String taskStatus;
        private BigDecimal totalScore;
        private Map<String, BigDecimal> dimensionScores;
        private List<DimensionScoreVO> dimensions;
        private String levelCode;
        private String levelName;
        private String feedbackText;
        private Integer retryCount;
        private Integer maxRetries;
        private String lastError;
        private LocalDateTime submittedAt;
        private LocalDateTime generatedAt;
    }
}
