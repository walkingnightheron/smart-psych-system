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
public class AssessmentActivity {
    private Long id;
    private String title;
    private Long questionnaireVersionId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long ownerId;
    private String authorizedAdminIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
