package com.psych.assessment.service;

import com.psych.assessment.dto.H5AndReportDtos.SubmitSheetResponse;
import com.psych.assessment.entity.AnswerItem;
import com.psych.assessment.mq.SheetSubmittedEvent;
import com.psych.assessment.repository.AnswerItemRepository;
import com.psych.assessment.repository.AnswerSheetRepository;
import com.psych.assessment.repository.AssessmentReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubmitTransactionService {

    private final AnswerSheetRepository sheetRepository;
    private final AnswerItemRepository itemRepository;
    private final AssessmentReportRepository reportRepository;
    private final SubmissionFaultInjector faultInjector;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public SubmitSheetResponse saveSheetAtomically(
            Long activityId,
            Long questionnaireVersionId,
            Long userId,
            String ruleVersion,
            List<AnswerItem> preparedItems) {
        LocalDateTime submittedAt = LocalDateTime.now();

        Long sheetId = sheetRepository.insert(activityId, questionnaireVersionId, userId, submittedAt);

        for (int i = 0; i < preparedItems.size(); i++) {
            faultInjector.checkBeforeItemInsert(i);
            AnswerItem item = preparedItems.get(i);
            item.setSheetId(sheetId);
            itemRepository.insertOne(item);
        }

        Long reportId = reportRepository.insertInitial(
                sheetId,
                activityId,
                userId,
                questionnaireVersionId,
                ruleVersion
        );

        // Published inside transaction; @TransactionalEventListener(AFTER_COMMIT) fires ONLY after commit succeeds!
        eventPublisher.publishEvent(new SheetSubmittedEvent(sheetId, reportId, activityId, userId));

        return SubmitSheetResponse.builder()
                .sheetId(sheetId)
                .reportId(reportId)
                .activityId(activityId)
                .taskStatus("PENDING")
                .submittedAt(submittedAt)
                .build();
    }
}
