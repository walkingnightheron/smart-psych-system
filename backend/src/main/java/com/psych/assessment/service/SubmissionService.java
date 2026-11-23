package com.psych.assessment.service;

import com.psych.assessment.cache.CacheService;
import com.psych.assessment.cache.CacheService.CacheLookup;
import com.psych.assessment.common.BizException;
import com.psych.assessment.common.ConflictException;
import com.psych.assessment.common.TimeStatusHelper;
import com.psych.assessment.dto.H5AndReportDtos.AnswerInput;
import com.psych.assessment.dto.H5AndReportDtos.AnsweredQuestionItemVO;
import com.psych.assessment.dto.H5AndReportDtos.ConflictSubmitData;
import com.psych.assessment.dto.H5AndReportDtos.H5ActivityDetailVO;
import com.psych.assessment.dto.H5AndReportDtos.H5ActivityItemVO;
import com.psych.assessment.dto.H5AndReportDtos.H5RecordDetailVO;
import com.psych.assessment.dto.H5AndReportDtos.H5RecordItemVO;
import com.psych.assessment.dto.H5AndReportDtos.SanitizedOptionVO;
import com.psych.assessment.dto.H5AndReportDtos.SanitizedQuestionVO;
import com.psych.assessment.dto.H5AndReportDtos.SubmitSheetRequest;
import com.psych.assessment.dto.H5AndReportDtos.SubmitSheetResponse;
import com.psych.assessment.dto.QuestionnaireDtos.OptionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireVersionVO;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.entity.AnswerItem;
import com.psych.assessment.entity.AnswerSheet;
import com.psych.assessment.entity.AssessmentActivity;
import com.psych.assessment.entity.AssessmentReport;
import com.psych.assessment.entity.QuestionnaireVersion;
import com.psych.assessment.repository.ActivityParticipantRepository;
import com.psych.assessment.repository.AnswerItemRepository;
import com.psych.assessment.repository.AnswerSheetRepository;
import com.psych.assessment.repository.AssessmentActivityRepository;
import com.psych.assessment.repository.AssessmentReportRepository;
import com.psych.assessment.repository.QuestionnaireVersionRepository;
import com.psych.assessment.scoring.ScoringEngine;
import com.psych.assessment.scoring.ScoringEngine.ScoringResult;
import com.psych.assessment.security.AccessControlService;
import com.psych.assessment.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final AssessmentActivityRepository activityRepository;
    private final ActivityParticipantRepository participantRepository;
    private final QuestionnaireVersionRepository versionRepository;
    private final QuestionnaireService questionnaireService;
    private final AnswerSheetRepository sheetRepository;
    private final AnswerItemRepository itemRepository;
    private final AssessmentReportRepository reportRepository;
    private final ScoringEngine scoringEngine;
    private final SubmitTransactionService submitTransactionService;
    private final AccessControlService accessControlService;
    private final CacheService cacheService;

    public List<H5ActivityItemVO> listMyActivities() {
        Long studentId = AuthContext.requireUserId();
        List<Long> actIds = participantRepository.findActivityIdsByUserId(studentId);
        if (actIds.isEmpty()) {
            return List.of();
        }

        List<AssessmentActivity> activities = activityRepository.findByIds(actIds).stream()
                .filter(a -> !"DRAFT".equals(a.getStatus()))
                .collect(Collectors.toList());
        if (activities.isEmpty()) {
            return List.of();
        }

        Map<Long, QuestionnaireVersion> versionMap = versionRepository.findAll().stream()
                .collect(Collectors.toMap(QuestionnaireVersion::getId, Function.identity()));

        List<AnswerSheet> mySheets = sheetRepository.findByUserId(studentId);
        Map<Long, AnswerSheet> sheetByActId = mySheets.stream()
                .collect(Collectors.toMap(AnswerSheet::getActivityId, Function.identity(), (a, b) -> a));

        List<Long> sheetIds = mySheets.stream().map(AnswerSheet::getId).collect(Collectors.toList());
        Map<Long, AssessmentReport> reportBySheetId = reportRepository.findBySheetIds(sheetIds).stream()
                .collect(Collectors.toMap(AssessmentReport::getSheetId, Function.identity(), (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        List<H5ActivityItemVO> result = new ArrayList<>();
        for (AssessmentActivity act : activities) {
            QuestionnaireVersion ver = versionMap.get(act.getQuestionnaireVersionId());
            int qCount = ver == null ? 0 : questionnaireService.parseQuestionsSnapshot(ver.getQuestionsSnapshot()).size();
            AnswerSheet sheet = sheetByActId.get(act.getId());
            boolean submitted = sheet != null;
            AssessmentReport report = submitted ? reportBySheetId.get(sheet.getId()) : null;

            result.add(H5ActivityItemVO.builder()
                    .id(act.getId())
                    .title(act.getTitle())
                    .questionnaireVersionId(act.getQuestionnaireVersionId())
                    .questionnaireTitle(ver == null ? null : ver.getTitle())
                    .versionNum(ver == null ? null : ver.getVersionNum())
                    .questionCount(qCount)
                    .status(act.getStatus())
                    .timeWindowStatus(TimeStatusHelper.computeTimeWindowStatus(act.getStartTime(), act.getEndTime(), now))
                    .canSubmit(TimeStatusHelper.canStudentSubmit(act.getStatus(), act.getStartTime(), act.getEndTime(), submitted, now))
                    .statusText(TimeStatusHelper.computeStudentStatusText(act.getStatus(), act.getStartTime(), act.getEndTime(), submitted, now))
                    .submitted(submitted)
                    .sheetId(submitted ? sheet.getId() : null)
                    .reportId(report != null ? report.getId() : null)
                    .reportTaskStatus(report != null ? report.getTaskStatus() : null)
                    .startTime(act.getStartTime())
                    .endTime(act.getEndTime())
                    .build());
        }
        return result;
    }

    public H5ActivityDetailVO getH5ActivityDetail(Long activityId) {
        Long studentId = AuthContext.requireUserId();

        CacheLookup<AssessmentActivity> actLookup = cacheService.getOrLoad(
                CacheService.activityKey(activityId),
                AssessmentActivity.class,
                () -> activityRepository.findById(activityId)
                        .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId))
        );
        AssessmentActivity activity = actLookup.getValue();

        // 1) Roster scope check -> 403 if not in roster
        accessControlService.checkStudentInParticipantRoster(activityId, studentId);

        // 2) Draft check -> 400 if draft
        if ("DRAFT".equals(activity.getStatus())) {
            throw new BizException(400, "该评估活动尚未发布，暂不可查看与填写");
        }

        // 3) Read immutable questionnaire version snapshot via hot cache
        QuestionnaireVersionVO versionVO = questionnaireService.getVersionSnapshotCached(activity.getQuestionnaireVersionId());
        String versionCacheSource = versionVO.getCacheSource();
        String combinedCacheSource = ("HIT".equals(actLookup.getValue() != null ? actLookup.getCacheSource() : "")
                && "HIT".equals(versionCacheSource)) ? "HIT" : versionCacheSource;

        // 4) Sanitize options: NEVER expose score or isReverse to student H5!
        List<SanitizedQuestionVO> sanitizedQuestions = versionVO.getQuestions().stream()
                .map(q -> SanitizedQuestionVO.builder()
                        .questionNo(q.getQuestionNo())
                        .stem(q.getStem())
                        .questionType(q.getQuestionType())
                        .required(q.getRequired())
                        .options(q.getOptions().stream()
                                .map(o -> SanitizedOptionVO.builder()
                                        .optCode(o.getOptCode())
                                        .label(o.getLabel())
                                        .sort(o.getSort())
                                        .build())
                                .collect(Collectors.toList()))
                        .build())
                .collect(Collectors.toList());

        Optional<AnswerSheet> existingSheet = sheetRepository.findByActivityAndUser(activityId, studentId);
        boolean submitted = existingSheet.isPresent();
        Long sheetId = existingSheet.map(AnswerSheet::getId).orElse(null);
        Long reportId = sheetId == null ? null : reportRepository.findBySheetId(sheetId).map(AssessmentReport::getId).orElse(null);

        LocalDateTime now = LocalDateTime.now();
        return H5ActivityDetailVO.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .status(activity.getStatus())
                .timeWindowStatus(TimeStatusHelper.computeTimeWindowStatus(activity.getStartTime(), activity.getEndTime(), now))
                .canSubmit(TimeStatusHelper.canStudentSubmit(activity.getStatus(), activity.getStartTime(), activity.getEndTime(), submitted, now))
                .statusText(TimeStatusHelper.computeStudentStatusText(activity.getStatus(), activity.getStartTime(), activity.getEndTime(), submitted, now))
                .submitted(submitted)
                .sheetId(sheetId)
                .reportId(reportId)
                .startTime(activity.getStartTime())
                .endTime(activity.getEndTime())
                .questionnaireVersionId(versionVO.getId())
                .questionnaireTitle(versionVO.getTitle())
                .questionnaireDescription(versionVO.getDescription())
                .versionNum(versionVO.getVersionNum())
                .ruleVersion(versionVO.getRuleVersion())
                .questions(sanitizedQuestions)
                .cacheSource(combinedCacheSource)
                .activityCacheSource(actLookup.getCacheSource())
                .versionCacheSource(versionCacheSource)
                .build();
    }

    public SubmitSheetResponse submitAnswerSheet(Long activityId, SubmitSheetRequest request) {
        Long studentId = AuthContext.requireUserId();

        // 1) Directly load real-time activity row from DB (NEVER rely on potentially stale cache for submit gate!)
        AssessmentActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));

        // 2) Student roster qualification check -> 403
        accessControlService.checkStudentInParticipantRoster(activityId, studentId);

        // 3) Check duplicate pre-flight -> 409 with original sheetId & reportId
        Optional<AnswerSheet> preExisting = sheetRepository.findByActivityAndUser(activityId, studentId);
        if (preExisting.isPresent()) {
            throw buildConflictException(activityId, preExisting.get());
        }

        // 4) Real-time status & time window check -> 400
        TimeStatusHelper.validateActivityOpenForSubmit(
                activity.getStatus(),
                activity.getStartTime(),
                activity.getEndTime(),
                LocalDateTime.now()
        );

        // 5) Load bound immutable questionnaire version snapshot & validate every answer
        if (request == null || request.getAnswers() == null || request.getAnswers().isEmpty()) {
            throw new BizException(400, "作答明细[answers]不能为空");
        }
        QuestionnaireVersion version = versionRepository.findById(activity.getQuestionnaireVersionId())
                .orElseThrow(() -> new BizException(400, "活动绑定的问卷版本快照不存在"));

        List<QuestionConfig> questions = questionnaireService.parseQuestionsSnapshot(version.getQuestionsSnapshot());
        ScoringRuleConfig rule = questionnaireService.parseScoringRule(version.getScoringRuleSnapshot());

        // Validates required questions, unknown questionNo, duplicate questionNo, and illegal selectedOptCode -> 400
        ScoringResult preScored = scoringEngine.calculate(questions, rule, request.getAnswers());

        Map<Integer, QuestionConfig> qMap = questions.stream()
                .collect(Collectors.toMap(QuestionConfig::getQuestionNo, Function.identity()));
        Map<Integer, String> selectedMap = new LinkedHashMap<>();
        for (AnswerInput input : request.getAnswers()) {
            selectedMap.put(input.getQuestionNo(), input.getSelectedOptCode().trim());
        }

        List<AnswerItem> preparedItems = new ArrayList<>();
        for (QuestionConfig q : questions) {
            String optCode = selectedMap.get(q.getQuestionNo());
            if (optCode == null) {
                continue;
            }
            BigDecimal rawScore = preScored.getItemScores().get(q.getQuestionNo());
            preparedItems.add(AnswerItem.builder()
                    .questionNo(q.getQuestionNo())
                    .selectedOptCode(optCode)
                    .rawScore(rawScore)
                    .dimensionCode(q.getDimensionCode())
                    .build());
        }

        // 6) Transactional atomic write + race-condition DuplicateKeyException -> 409 Conflict!
        try {
            return submitTransactionService.saveSheetAtomically(
                    activityId,
                    version.getId(),
                    studentId,
                    version.getRuleVersion(),
                    preparedItems
            );
        } catch (DuplicateKeyException dupEx) {
            Optional<AnswerSheet> winner = sheetRepository.findByActivityAndUser(activityId, studentId);
            if (winner.isEmpty()) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                winner = sheetRepository.findByActivityAndUser(activityId, studentId);
            }
            AnswerSheet winnerSheet = winner.orElseThrow(() -> new ConflictException(
                    "您已提交过本次评估答卷，请勿重复提交",
                    ConflictSubmitData.builder().activityId(activityId).build()
            ));
            throw buildConflictException(activityId, winnerSheet);
        }
    }

    public List<H5RecordItemVO> listMyRecords() {
        Long studentId = AuthContext.requireUserId();
        List<AnswerSheet> sheets = sheetRepository.findByUserId(studentId);
        if (sheets.isEmpty()) {
            return List.of();
        }

        Map<Long, AssessmentActivity> actMap = activityRepository.findByIds(
                sheets.stream().map(AnswerSheet::getActivityId).collect(Collectors.toSet())
        ).stream().collect(Collectors.toMap(AssessmentActivity::getId, Function.identity()));

        Map<Long, QuestionnaireVersion> verMap = versionRepository.findAll().stream()
                .collect(Collectors.toMap(QuestionnaireVersion::getId, Function.identity()));

        Map<Long, AssessmentReport> reportMap = reportRepository.findBySheetIds(
                sheets.stream().map(AnswerSheet::getId).collect(Collectors.toList())
        ).stream().collect(Collectors.toMap(AssessmentReport::getSheetId, Function.identity(), (a, b) -> a));

        return sheets.stream().map(s -> {
            AssessmentActivity act = actMap.get(s.getActivityId());
            QuestionnaireVersion ver = verMap.get(s.getQuestionnaireVersionId());
            AssessmentReport report = reportMap.get(s.getId());
            return H5RecordItemVO.builder()
                    .sheetId(s.getId())
                    .activityId(s.getActivityId())
                    .activityTitle(act == null ? null : act.getTitle())
                    .questionnaireVersionId(s.getQuestionnaireVersionId())
                    .questionnaireTitle(ver == null ? null : ver.getTitle())
                    .versionNum(ver == null ? null : ver.getVersionNum())
                    .submittedAt(s.getSubmittedAt())
                    .reportId(report == null ? null : report.getId())
                    .taskStatus(report == null ? null : report.getTaskStatus())
                    .totalScore(report != null && "SUCCESS".equals(report.getTaskStatus()) ? report.getTotalScore() : null)
                    .levelCode(report != null && "SUCCESS".equals(report.getTaskStatus()) ? report.getLevelCode() : null)
                    .levelName(report != null && "SUCCESS".equals(report.getTaskStatus()) ? report.getLevelName() : null)
                    .build();
        }).collect(Collectors.toList());
    }

    public H5RecordDetailVO getMyRecordDetail(Long sheetId) {
        Long studentId = AuthContext.requireUserId();
        AnswerSheet sheet = sheetRepository.findById(sheetId)
                .orElseThrow(() -> new BizException(404, "答卷记录不存在: sheetId=" + sheetId));

        // REQ01 & REQ06: Student can ONLY view own sheet -> 403 otherwise
        accessControlService.checkStudentRecordOwner(sheet.getUserId(), studentId);

        AssessmentActivity act = activityRepository.findById(sheet.getActivityId()).orElse(null);
        QuestionnaireVersion ver = versionRepository.findById(sheet.getQuestionnaireVersionId())
                .orElseThrow(() -> new BizException(404, "关联问卷版本不存在"));
        AssessmentReport report = reportRepository.findBySheetId(sheetId).orElse(null);

        List<QuestionConfig> questions = questionnaireService.parseQuestionsSnapshot(ver.getQuestionsSnapshot());
        Map<Integer, QuestionConfig> qMap = questions.stream()
                .collect(Collectors.toMap(QuestionConfig::getQuestionNo, Function.identity()));

        List<AnswerItem> items = itemRepository.findBySheetId(sheetId);
        List<AnsweredQuestionItemVO> itemVOs = items.stream().map(item -> {
            QuestionConfig q = qMap.get(item.getQuestionNo());
            String label = null;
            if (q != null && q.getOptions() != null) {
                label = q.getOptions().stream()
                        .filter(o -> o.getOptCode().equals(item.getSelectedOptCode()))
                        .map(OptionConfig::getLabel)
                        .findFirst()
                        .orElse(null);
            }
            return AnsweredQuestionItemVO.builder()
                    .questionNo(item.getQuestionNo())
                    .stem(q == null ? null : q.getStem())
                    .selectedOptCode(item.getSelectedOptCode())
                    .selectedOptLabel(label)
                    .dimensionCode(item.getDimensionCode())
                    .build();
        }).collect(Collectors.toList());

        return H5RecordDetailVO.builder()
                .sheetId(sheet.getId())
                .activityId(sheet.getActivityId())
                .activityTitle(act == null ? null : act.getTitle())
                .questionnaireVersionId(ver.getId())
                .questionnaireTitle(ver.getTitle())
                .versionNum(ver.getVersionNum())
                .ruleVersion(ver.getRuleVersion())
                .submittedAt(sheet.getSubmittedAt())
                .reportId(report == null ? null : report.getId())
                .taskStatus(report == null ? null : report.getTaskStatus())
                .items(itemVOs)
                .build();
    }

    private ConflictException buildConflictException(Long activityId, AnswerSheet existingSheet) {
        Long reportId = reportRepository.findBySheetId(existingSheet.getId())
                .map(AssessmentReport::getId)
                .orElse(null);
        ConflictSubmitData conflictData = ConflictSubmitData.builder()
                .sheetId(existingSheet.getId())
                .reportId(reportId)
                .activityId(activityId)
                .build();
        return new ConflictException("您已提交过本次评估答卷，请勿重复提交", conflictData);
    }
}
