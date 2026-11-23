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
public class AnswerSheet {
    private Long id;
    private Long activityId;
    private Long questionnaireVersionId;
    private Long userId;
    private LocalDateTime submittedAt;
}
