package com.psych.assessment.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SheetSubmittedEvent {
    private Long sheetId;
    private Long reportId;
    private Long activityId;
    private Long userId;
}
