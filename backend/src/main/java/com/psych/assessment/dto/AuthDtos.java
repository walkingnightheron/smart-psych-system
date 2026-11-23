package com.psych.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class AuthDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserVO {
        private Long id;
        private String username;
        private String realName;
        private String role;
        private String deptClass;
        private String phone;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginResponse {
        private String token;
        private UserVO user;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateProfileRequest {
        private String realName;
        private String deptClass;
        private String phone;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardSummaryVO {
        private long questionnaireCount;
        private long publishedVersionCount;
        private long activeActivityCount;
        private long totalActivityCount;
        private long validSheetCount;
        private long pendingOrFailedReportCount;
        private List<RecentActivityVO> recentActivities;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentActivityVO {
        private Long id;
        private String title;
        private String status;
        private String statusText;
        private long participantCount;
        private long completedCount;
        private java.math.BigDecimal completionRate;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}
