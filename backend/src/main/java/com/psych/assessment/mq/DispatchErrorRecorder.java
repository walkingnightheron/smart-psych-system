package com.psych.assessment.mq;

import com.psych.assessment.repository.AssessmentReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DispatchErrorRecorder {

    private final AssessmentReportRepository reportRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long sheetId, String errorMsg) {
        reportRepository.recordDispatchError(sheetId, errorMsg);
    }
}
