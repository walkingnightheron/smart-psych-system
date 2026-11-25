package com.psych.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ActivityDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityUpsertRequest {
        private String title;
        private Long questionnaireVersionId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private List<Long> participantUserIds;
        private List<Long> authorizedAdminIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdjustPublishedSettingsRequest {
        private String title;
        private LocalDateTime endTime;
        private List<Long> addParticipantUserIds;
        private List<Long> authorizedAdminIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityListItemVO {
        private Long id;
        private String title;
        private Long questionnaireVersionId;
        private String questionnaireTitle;
        private Integer versionNum;
        private String ruleVersion;
        private String status;
        private String timeWindowStatus;
        private String statusText;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long ownerId;
        private String ownerName;
        private List<Long> authorizedAdminIds;
        private long participantCount;
        private long completedCount;
        private BigDecimal completionRate;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityDetailVO {
        private Long id;
        private String title;
        private Long questionnaireVersionId;
        private String questionnaireTitle;
        private Integer versionNum;
        private String ruleVersion;
        private String status;
        private String timeWindowStatus;
        private String statusText;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long ownerId;
        private String ownerName;
        private List<Long> authorizedAdminIds;
        private List<Long> participantUserIds;
        private List<AuthDtos.UserVO> participants;
        private long participantCount;
        private long completedCount;
        private BigDecimal completionRate;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LevelDistributionItem {
        private String levelCode;
        private String levelName;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionAverageItem {
        private String dimensionCode;
        private String dimensionName;
        private BigDecimal avgScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParticipantDetailItem {
        private Long userId;
        private String username;
        private String realName;
        private String deptClass;
        private boolean submitted;
        private Long sheetId;
        private LocalDateTime submittedAt;
        private Long reportId;
        private String taskStatus;
        private BigDecimal totalScore;
        private String levelCode;
        private String levelName;
        private Integer retryCount;
        private String lastError;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityStatisticsVO {
        private Long activityId;
        private String activityTitle;
        private String status;
        private String statusText;
        private Long questionnaireVersionId;
        private String questionnaireTitle;
        private Integer versionNum;
        private String ruleVersion;
        private long participantCount;
        private long completedCount;
        private BigDecimal completionRate;
        private long successReportCount;
        private long pendingReportCount;
        private long failedReportCount;
        private List<LevelDistributionItem> levelDistribution;
        private List<DimensionAverageItem> dimensionAverages;
        private List<ParticipantDetailItem> participantDetails;
    }
}
