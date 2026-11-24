package com.psych.assessment.dto;

import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class XlsxDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class XlsxRowError {
        private String sheet;
        private Integer row;
        private String column;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class XlsxErrorPayload {
        private int errorCount;
        private List<XlsxRowError> errors;

        public static XlsxErrorPayload of(List<XlsxRowError> errors) {
            return XlsxErrorPayload.builder()
                    .errorCount(errors == null ? 0 : errors.size())
                    .errors(errors)
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedQuestionnaireXlsxVO {
        private int questionCount;
        private boolean hasScoringRule;
        private List<QuestionConfig> questions;
        private ScoringRuleConfig scoringRule;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentRosterItemVO {
        private Long id;
        private String username;
        private String realName;
        private String deptClass;
        private String phone;
        private Integer status;
        private LocalDateTime createdAt;
        private long participatedActivityCount;
        private long submittedSheetCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateStudentRequest {
        private String username;
        private String realName;
        private String deptClass;
        private String phone;
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateStudentRequest {
        private String realName;
        private String deptClass;
        private String phone;
        private Integer status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateStudentStatusRequest {
        private Integer status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResetStudentPasswordRequest {
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResetPasswordResultVO {
        private Long id;
        private String username;
        private String passwordRuleHint;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentImportResultVO {
        private int totalRows;
        private int createdCount;
        private int updatedCount;
        private int skippedCount;
        private String passwordRuleHint;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParticipantImportResultVO {
        private Long activityId;
        private String mode;
        private int totalRows;
        private int createdUserCount;
        private int addedCount;
        private int alreadyInRosterCount;
        private int currentParticipantCount;
        private String passwordRuleHint;
    }
}
