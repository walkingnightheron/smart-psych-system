package com.psych.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.common.BizException;
import com.psych.assessment.common.TimeStatusHelper;
import com.psych.assessment.dto.ActivityDtos.ActivityStatisticsVO;
import com.psych.assessment.dto.ActivityDtos.DimensionAverageItem;
import com.psych.assessment.dto.ActivityDtos.LevelDistributionItem;
import com.psych.assessment.dto.ActivityDtos.ParticipantDetailItem;
import com.psych.assessment.dto.H5AndReportDtos.DimensionScoreVO;
import com.psych.assessment.dto.H5AndReportDtos.ReportDetailVO;
import com.psych.assessment.dto.QuestionnaireDtos.DimensionRule;
import com.psych.assessment.dto.QuestionnaireDtos.LevelRule;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.entity.AnswerSheet;
import com.psych.assessment.entity.AssessmentActivity;
import com.psych.assessment.entity.AssessmentReport;
import com.psych.assessment.entity.QuestionnaireVersion;
import com.psych.assessment.entity.SysUser;
import com.psych.assessment.mq.ReportTaskDispatcher;
import com.psych.assessment.repository.ActivityParticipantRepository;
import com.psych.assessment.repository.AnswerSheetRepository;
import com.psych.assessment.repository.AssessmentActivityRepository;
import com.psych.assessment.repository.AssessmentReportRepository;
import com.psych.assessment.repository.QuestionnaireVersionRepository;
import com.psych.assessment.repository.SysUserRepository;
import com.psych.assessment.security.AccessControlService;
import com.psych.assessment.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportAndStatsService {

    private final AssessmentActivityRepository activityRepository;
    private final ActivityParticipantRepository participantRepository;
    private final QuestionnaireVersionRepository versionRepository;
    private final QuestionnaireService questionnaireService;
    private final AnswerSheetRepository sheetRepository;
    private final AssessmentReportRepository reportRepository;
    private final SysUserRepository userRepository;
    private final AccessControlService accessControlService;
    private final ReportTaskDispatcher reportTaskDispatcher;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public ActivityStatisticsVO getActivityStatistics(Long activityId) {
        Long adminId = AuthContext.requireUserId();
        AssessmentActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));

        // REQ01 & REQ08: Strictly check admin authorization scope -> 403
        accessControlService.checkAdminActivityAccess(activity, adminId);

        QuestionnaireVersion version = versionRepository.findById(activity.getQuestionnaireVersionId())
                .orElseThrow(() -> new BizException(404, "活动绑定的问卷版本不存在"));
        ScoringRuleConfig rule = questionnaireService.parseScoringRule(version.getScoringRuleSnapshot());

        List<Long> participantIds = participantRepository.findUserIdsByActivityId(activityId);
        Map<Long, SysUser> userMap = userRepository.findByIds(participantIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));

        List<AnswerSheet> sheets = sheetRepository.findByActivityId(activityId);
        Map<Long, AnswerSheet> sheetByUserId = sheets.stream()
                .collect(Collectors.toMap(AnswerSheet::getUserId, Function.identity(), (a, b) -> a));

        List<AssessmentReport> reports = reportRepository.findByActivityId(activityId);
        Map<Long, AssessmentReport> reportBySheetId = reports.stream()
                .collect(Collectors.toMap(AssessmentReport::getSheetId, Function.identity(), (a, b) -> a));

        long participantCount = participantIds.size();
        long completedCount = participantIds.stream().filter(sheetByUserId::containsKey).count();
        BigDecimal completionRate = ActivityService.computeCompletionRate(completedCount, participantCount);

        Map<String, Long> levelCountMap = new LinkedHashMap<>();
        Map<String, String> levelNameMap = new LinkedHashMap<>();
        if (rule.getLevels() != null) {
            for (LevelRule lv : rule.getLevels()) {
                levelCountMap.put(lv.getCode(), 0L);
                levelNameMap.put(lv.getCode(), lv.getName());
            }
        }

        Map<String, List<BigDecimal>> dimValueMap = new LinkedHashMap<>();
        Map<String, String> dimNameMap = new LinkedHashMap<>();
        if (rule.getDimensions() != null) {
            for (DimensionRule d : rule.getDimensions()) {
                dimValueMap.put(d.getCode(), new ArrayList<>());
                dimNameMap.put(d.getCode(), d.getName());
            }
        }

        long successReportCount = 0;
        long pendingReportCount = 0;
        long failedReportCount = 0;

        List<ParticipantDetailItem> participantDetails = new ArrayList<>();
        for (Long uid : participantIds) {
            SysUser u = userMap.get(uid);
            AnswerSheet sheet = sheetByUserId.get(uid);
            AssessmentReport report = sheet == null ? null : reportBySheetId.get(sheet.getId());

            if (report != null) {
                if ("SUCCESS".equals(report.getTaskStatus())) {
                    successReportCount++;
                    if (report.getLevelCode() != null) {
                        levelCountMap.put(report.getLevelCode(), levelCountMap.getOrDefault(report.getLevelCode(), 0L) + 1L);
                        levelNameMap.putIfAbsent(report.getLevelCode(), report.getLevelName());
                    }
                    Map<String, BigDecimal> dimScores = parseDimensionScores(report.getDimensionScoresJson());
                    for (Map.Entry<String, BigDecimal> e : dimScores.entrySet()) {
                        dimValueMap.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                    }
                } else if ("PENDING".equals(report.getTaskStatus())) {
                    pendingReportCount++;
                } else if ("FAILED".equals(report.getTaskStatus())) {
                    failedReportCount++;
                }
            }

            participantDetails.add(ParticipantDetailItem.builder()
                    .userId(uid)
                    .username(u == null ? null : u.getUsername())
                    .realName(u == null ? null : u.getRealName())
                    .deptClass(u == null ? null : u.getDeptClass())
                    .submitted(sheet != null)
                    .sheetId(sheet == null ? null : sheet.getId())
                    .submittedAt(sheet == null ? null : sheet.getSubmittedAt())
                    .reportId(report == null ? null : report.getId())
                    .taskStatus(report == null ? null : report.getTaskStatus())
                    .totalScore(report != null && "SUCCESS".equals(report.getTaskStatus()) ? report.getTotalScore() : null)
                    .levelCode(report != null && "SUCCESS".equals(report.getTaskStatus()) ? report.getLevelCode() : null)
                    .levelName(report != null && "SUCCESS".equals(report.getTaskStatus()) ? report.getLevelName() : null)
                    .retryCount(report == null ? null : report.getRetryCount())
                    .lastError(report == null ? null : report.getLastError())
                    .build());
        }

        List<LevelDistributionItem> levelDistribution = new ArrayList<>();
        for (Map.Entry<String, Long> entry : levelCountMap.entrySet()) {
            levelDistribution.add(LevelDistributionItem.builder()
                    .levelCode(entry.getKey())
                    .levelName(levelNameMap.getOrDefault(entry.getKey(), entry.getKey()))
                    .count(entry.getValue())
                    .build());
        }

        List<DimensionAverageItem> dimensionAverages = new ArrayList<>();
        for (Map.Entry<String, List<BigDecimal>> entry : dimValueMap.entrySet()) {
            List<BigDecimal> vals = entry.getValue();
            BigDecimal avg = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            if (!vals.isEmpty()) {
                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal v : vals) {
                    sum = sum.add(v);
                }
                avg = sum.divide(BigDecimal.valueOf(vals.size()), 2, RoundingMode.HALF_UP);
            }
            dimensionAverages.add(DimensionAverageItem.builder()
                    .dimensionCode(entry.getKey())
                    .dimensionName(dimNameMap.getOrDefault(entry.getKey(), entry.getKey()))
                    .avgScore(avg)
                    .build());
        }

        LocalDateTime now = LocalDateTime.now();
        return ActivityStatisticsVO.builder()
                .activityId(activity.getId())
                .activityTitle(activity.getTitle())
                .status(activity.getStatus())
                .statusText(TimeStatusHelper.computeActivityStatusText(activity.getStatus(), activity.getStartTime(), activity.getEndTime(), now))
                .questionnaireVersionId(version.getId())
                .questionnaireTitle(version.getTitle())
                .versionNum(version.getVersionNum())
                .ruleVersion(version.getRuleVersion())
                .participantCount(participantCount)
                .completedCount(completedCount)
                .completionRate(completionRate)
                .successReportCount(successReportCount)
                .pendingReportCount(pendingReportCount)
                .failedReportCount(failedReportCount)
                .levelDistribution(levelDistribution)
                .dimensionAverages(dimensionAverages)
                .participantDetails(participantDetails)
                .build();
    }

    public ReportDetailVO getReportForAdmin(Long reportId) {
        Long adminId = AuthContext.requireUserId();
        AssessmentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BizException(404, "评估报告不存在: reportId=" + reportId));
        AssessmentActivity activity = activityRepository.findById(report.getActivityId())
                .orElseThrow(() -> new BizException(404, "关联活动不存在"));
        accessControlService.checkAdminActivityAccess(activity, adminId);
        return buildReportDetailVO(report, activity);
    }

    public ReportDetailVO getReportForStudent(Long reportId) {
        Long studentId = AuthContext.requireUserId();
        AssessmentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BizException(404, "评估报告不存在: reportId=" + reportId));
        accessControlService.checkStudentRecordOwner(report.getUserId(), studentId);
        AssessmentActivity activity = activityRepository.findById(report.getActivityId()).orElse(null);
        return buildReportDetailVO(report, activity);
    }

    public ReportDetailVO retryReportForAdmin(Long reportId, boolean resetCounter) {
        Long adminId = AuthContext.requireUserId();
        AssessmentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BizException(404, "评估报告不存在: reportId=" + reportId));
        AssessmentActivity activity = activityRepository.findById(report.getActivityId())
                .orElseThrow(() -> new BizException(404, "关联活动不存在"));
        accessControlService.checkAdminActivityAccess(activity, adminId);

        executeControlledRetry(report, resetCounter);
        auditLogService.recordSuccess(AuditLogService.ACT_REPORT_RETRY,
                AuditLogService.RES_REPORT, reportId, "重试报告[reportId=" + reportId + "]", null);
        AssessmentReport latest = reportRepository.findById(reportId).orElse(report);
        return buildReportDetailVO(latest, activity);
    }

    public ReportDetailVO retryReportForStudent(Long reportId) {
        Long studentId = AuthContext.requireUserId();
        AssessmentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BizException(404, "评估报告不存在: reportId=" + reportId));
        accessControlService.checkStudentRecordOwner(report.getUserId(), studentId);

        executeControlledRetry(report, false);
        auditLogService.recordSuccess(AuditLogService.ACT_REPORT_RETRY,
                AuditLogService.RES_REPORT, reportId, "学生重试报告[reportId=" + reportId + "]", null);
        AssessmentActivity activity = activityRepository.findById(report.getActivityId()).orElse(null);
        AssessmentReport latest = reportRepository.findById(reportId).orElse(report);
        return buildReportDetailVO(latest, activity);
    }

    private void executeControlledRetry(AssessmentReport report, boolean resetCounter) {
        if ("SUCCESS".equals(report.getTaskStatus())) {
            return;
        }
        int currentRetry = report.getRetryCount() == null ? 0 : report.getRetryCount();
        int maxRetries = report.getMaxRetries() == null ? 3 : report.getMaxRetries();
        if (currentRetry >= maxRetries && !resetCounter) {
            throw new BizException(400, "已达到最大重试次数上限(" + maxRetries + "次)，无法继续自动重试");
        }
        int nextBaseRetry = resetCounter ? 0 : currentRetry;
        reportRepository.prepareForRetry(report.getId(), nextBaseRetry);
        reportTaskDispatcher.dispatch(report.getSheetId());
    }

    private ReportDetailVO buildReportDetailVO(AssessmentReport report, AssessmentActivity activity) {
        QuestionnaireVersion version = versionRepository.findById(report.getQuestionnaireVersionId()).orElse(null);
        SysUser user = userRepository.findById(report.getUserId()).orElse(null);
        AnswerSheet sheet = sheetRepository.findById(report.getSheetId()).orElse(null);

        Map<String, BigDecimal> dimScores = "SUCCESS".equals(report.getTaskStatus())
                ? parseDimensionScores(report.getDimensionScoresJson())
                : Collections.emptyMap();

        Map<String, String> dimNames = new LinkedHashMap<>();
        if (version != null) {
            ScoringRuleConfig rule = questionnaireService.parseScoringRule(version.getScoringRuleSnapshot());
            if (rule.getDimensions() != null) {
                for (DimensionRule d : rule.getDimensions()) {
                    dimNames.put(d.getCode(), d.getName());
                }
            }
        }

        List<DimensionScoreVO> dimensions = dimScores.entrySet().stream()
                .map(e -> DimensionScoreVO.builder()
                        .code(e.getKey())
                        .name(dimNames.getOrDefault(e.getKey(), e.getKey()))
                        .score(e.getValue())
                        .build())
                .collect(Collectors.toList());

        return ReportDetailVO.builder()
                .id(report.getId())
                .reportId(report.getId())
                .sheetId(report.getSheetId())
                .activityId(report.getActivityId())
                .activityTitle(activity == null ? null : activity.getTitle())
                .userId(report.getUserId())
                .username(user == null ? null : user.getUsername())
                .realName(user == null ? null : user.getRealName())
                .questionnaireVersionId(report.getQuestionnaireVersionId())
                .questionnaireTitle(version == null ? null : version.getTitle())
                .versionNum(version == null ? null : version.getVersionNum())
                .ruleVersion(report.getRuleVersion())
                .taskStatus(report.getTaskStatus())
                .totalScore("SUCCESS".equals(report.getTaskStatus()) ? report.getTotalScore() : null)
                .dimensionScores("SUCCESS".equals(report.getTaskStatus()) ? dimScores : null)
                .dimensions("SUCCESS".equals(report.getTaskStatus()) ? dimensions : Collections.emptyList())
                .levelCode("SUCCESS".equals(report.getTaskStatus()) ? report.getLevelCode() : null)
                .levelName("SUCCESS".equals(report.getTaskStatus()) ? report.getLevelName() : null)
                .feedbackText("SUCCESS".equals(report.getTaskStatus()) ? report.getFeedbackText() : null)
                .retryCount(report.getRetryCount())
                .maxRetries(report.getMaxRetries())
                .lastError(report.getLastError())
                .submittedAt(sheet == null ? null : sheet.getSubmittedAt())
                .generatedAt(report.getGeneratedAt())
                .build();
    }

    private Map<String, BigDecimal> parseDimensionScores(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, BigDecimal> map = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, BigDecimal>>() {});
            return map == null ? Collections.emptyMap() : map;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }
}
