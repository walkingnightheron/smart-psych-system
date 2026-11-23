package com.psych.assessment.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.dto.H5AndReportDtos.AnswerInput;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.entity.AnswerItem;
import com.psych.assessment.entity.AnswerSheet;
import com.psych.assessment.entity.AssessmentReport;
import com.psych.assessment.entity.QuestionnaireVersion;
import com.psych.assessment.repository.AnswerItemRepository;
import com.psych.assessment.repository.AnswerSheetRepository;
import com.psych.assessment.repository.AssessmentReportRepository;
import com.psych.assessment.repository.QuestionnaireVersionRepository;
import com.psych.assessment.scoring.ScoringEngine;
import com.psych.assessment.scoring.ScoringEngine.ScoringResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReportConsumerService {

    private final AnswerSheetRepository sheetRepository;
    private final AnswerItemRepository itemRepository;
    private final AssessmentReportRepository reportRepository;
    private final QuestionnaireVersionRepository versionRepository;
    private final ScoringEngine scoringEngine;
    private final MqFaultInjector faultInjector;
    private final ObjectMapper objectMapper;
    private final ReportTaskDispatcher dispatcher;

    private final ConcurrentHashMap<Long, Object> sheetLocks = new ConcurrentHashMap<>();
    private final AtomicLong idempotentSkipCount = new AtomicLong(0);

    public ReportConsumerService(
            AnswerSheetRepository sheetRepository,
            AnswerItemRepository itemRepository,
            AssessmentReportRepository reportRepository,
            QuestionnaireVersionRepository versionRepository,
            ScoringEngine scoringEngine,
            MqFaultInjector faultInjector,
            ObjectMapper objectMapper,
            @Lazy ReportTaskDispatcher dispatcher) {
        this.sheetRepository = sheetRepository;
        this.itemRepository = itemRepository;
        this.reportRepository = reportRepository;
        this.versionRepository = versionRepository;
        this.scoringEngine = scoringEngine;
        this.faultInjector = faultInjector;
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
    }

    @RabbitListener(queues = RabbitMqConfig.REPORT_QUEUE)
    public void onRabbitMessage(String payload) {
        Long sheetId = Long.parseLong(payload.trim());
        consumeReportTask(sheetId);
    }

    public boolean consumeReportTask(Long sheetId) {
        Object lock = sheetLocks.computeIfAbsent(sheetId, k -> new Object());
        boolean shouldScheduleRetry = false;

        synchronized (lock) {
            Optional<AssessmentReport> reportOpt = reportRepository.findBySheetId(sheetId);
            if (reportOpt.isEmpty()) {
                log.warn("AssessmentReport not found for sheetId={}, ignoring", sheetId);
                return false;
            }
            AssessmentReport report = reportOpt.get();

            // REQ10 Idempotency guarantee: already SUCCESS => skip immediately without overwriting or duplicating
            if ("SUCCESS".equals(report.getTaskStatus())) {
                idempotentSkipCount.incrementAndGet();
                log.info("Idempotent consumer skip: report for sheetId={} is already SUCCESS", sheetId);
                return true;
            }

            int currentRetries = report.getRetryCount() == null ? 0 : report.getRetryCount();
            int maxRetries = report.getMaxRetries() == null ? 3 : report.getMaxRetries();
            if ("FAILED".equals(report.getTaskStatus()) && currentRetries >= maxRetries) {
                log.warn("Report id={} (sheetId={}) already reached maxRetries={}, skipping automatic retry",
                        report.getId(), sheetId, maxRetries);
                return false;
            }

            try {
                faultInjector.checkDuringConsume(sheetId);

                AnswerSheet sheet = sheetRepository.findById(sheetId)
                        .orElseThrow(() -> new IllegalStateException("答卷主表记录不存在: sheetId=" + sheetId));
                QuestionnaireVersion version = versionRepository.findById(sheet.getQuestionnaireVersionId())
                        .orElseThrow(() -> new IllegalStateException("问卷版本快照不存在: versionId=" + sheet.getQuestionnaireVersionId()));
                List<AnswerItem> items = itemRepository.findBySheetId(sheetId);

                List<QuestionConfig> questions = objectMapper.readValue(
                        version.getQuestionsSnapshot(),
                        new TypeReference<List<QuestionConfig>>() {}
                );
                ScoringRuleConfig rule = objectMapper.readValue(
                        version.getScoringRuleSnapshot(),
                        ScoringRuleConfig.class
                );
                List<AnswerInput> answerInputs = items.stream()
                        .map(i -> new AnswerInput(i.getQuestionNo(), i.getSelectedOptCode()))
                        .collect(Collectors.toList());

                ScoringResult result = scoringEngine.calculate(questions, rule, answerInputs);
                String dimJson = objectMapper.writeValueAsString(result.getDimensionScores());

                reportRepository.markSuccess(
                        report.getId(),
                        result.getTotalScore(),
                        dimJson,
                        result.getLevelCode(),
                        result.getLevelName(),
                        result.getAdvice(),
                        LocalDateTime.now()
                );
                log.info("Report generated successfully: reportId={}, sheetId={}, totalScore={}, level={}",
                        report.getId(), sheetId, result.getTotalScore(), result.getLevelCode());
                return true;
            } catch (Exception ex) {
                int nextRetryCount = currentRetries + 1;
                boolean exhausted = nextRetryCount >= maxRetries;
                String nextStatus = exhausted ? "FAILED" : "PENDING";
                String errMsg = "异步报告计算失败(第" + nextRetryCount + "/" + maxRetries + "次): " + ex.getMessage();
                reportRepository.updateRetryOrFailed(report.getId(), nextRetryCount, nextStatus, errMsg);
                log.warn("Report generation failed for sheetId={}, retry={}/{}, status={}: {}",
                        sheetId, nextRetryCount, maxRetries, nextStatus, ex.getMessage());
                if (!exhausted) {
                    shouldScheduleRetry = true;
                }
            }
        }

        if (shouldScheduleRetry) {
            dispatcher.dispatchRetry(sheetId);
        }
        return false;
    }

    public long getIdempotentSkipCount() {
        return idempotentSkipCount.get();
    }

    public void resetIdempotentSkipCount() {
        idempotentSkipCount.set(0);
    }
}
