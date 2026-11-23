package com.psych.assessment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.cache.CacheService;
import com.psych.assessment.common.BizException;
import com.psych.assessment.common.TimeStatusHelper;
import com.psych.assessment.dto.ActivityDtos.ActivityDetailVO;
import com.psych.assessment.dto.ActivityDtos.ActivityListItemVO;
import com.psych.assessment.dto.ActivityDtos.ActivityUpsertRequest;
import com.psych.assessment.dto.AuthDtos.DashboardSummaryVO;
import com.psych.assessment.dto.AuthDtos.RecentActivityVO;
import com.psych.assessment.dto.AuthDtos.UserVO;
import com.psych.assessment.entity.AssessmentActivity;
import com.psych.assessment.entity.QuestionnaireVersion;
import com.psych.assessment.entity.SysUser;
import com.psych.assessment.repository.ActivityParticipantRepository;
import com.psych.assessment.repository.AnswerSheetRepository;
import com.psych.assessment.repository.AssessmentActivityRepository;
import com.psych.assessment.repository.AssessmentReportRepository;
import com.psych.assessment.repository.QuestionnaireRepository;
import com.psych.assessment.repository.QuestionnaireVersionRepository;
import com.psych.assessment.repository.SysUserRepository;
import com.psych.assessment.security.AccessControlService;
import com.psych.assessment.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final AssessmentActivityRepository activityRepository;
    private final ActivityParticipantRepository participantRepository;
    private final QuestionnaireVersionRepository versionRepository;
    private final QuestionnaireRepository questionnaireRepository;
    private final AnswerSheetRepository sheetRepository;
    private final AssessmentReportRepository reportRepository;
    private final SysUserRepository userRepository;
    private final AuthService authService;
    private final AccessControlService accessControlService;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public static BigDecimal computeCompletionRate(long completedCount, long participantCount) {
        if (participantCount <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(completedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(participantCount), 2, RoundingMode.HALF_UP);
    }

    public List<ActivityListItemVO> listAuthorizedActivitiesForCurrentAdmin() {
        Long adminId = AuthContext.requireUserId();
        List<AssessmentActivity> visible = activityRepository.findAll().stream()
                .filter(a -> accessControlService.canAdminAccessActivity(a, adminId))
                .collect(Collectors.toList());

        Map<Long, QuestionnaireVersion> versionMap = versionRepository.findAll().stream()
                .collect(Collectors.toMap(QuestionnaireVersion::getId, Function.identity()));
        Map<Long, String> ownerNameMap = userRepository.findAllActive(null).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getRealName, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        return visible.stream()
                .map(a -> buildListItemVO(a, versionMap.get(a.getQuestionnaireVersionId()), ownerNameMap.get(a.getOwnerId()), now))
                .collect(Collectors.toList());
    }

    public ActivityDetailVO getActivityDetailForAdmin(Long activityId) {
        Long adminId = AuthContext.requireUserId();
        AssessmentActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));
        accessControlService.checkAdminActivityAccess(activity, adminId);

        QuestionnaireVersion version = versionRepository.findById(activity.getQuestionnaireVersionId())
                .orElse(null);
        String ownerName = userRepository.findById(activity.getOwnerId())
                .map(SysUser::getRealName)
                .orElse(null);

        List<Long> participantIds = participantRepository.findUserIdsByActivityId(activityId);
        List<UserVO> participants = userRepository.findByIds(participantIds).stream()
                .map(authService::toUserVO)
                .collect(Collectors.toList());

        long participantCount = participantIds.size();
        long completedCount = sheetRepository.countParticipantCompletedByActivityId(activityId);
        LocalDateTime now = LocalDateTime.now();

        return ActivityDetailVO.builder()
                .id(activity.getId())
                .title(activity.getTitle())
                .questionnaireVersionId(activity.getQuestionnaireVersionId())
                .questionnaireTitle(version == null ? null : version.getTitle())
                .versionNum(version == null ? null : version.getVersionNum())
                .ruleVersion(version == null ? null : version.getRuleVersion())
                .status(activity.getStatus())
                .timeWindowStatus(TimeStatusHelper.computeTimeWindowStatus(activity.getStartTime(), activity.getEndTime(), now))
                .statusText(TimeStatusHelper.computeActivityStatusText(activity.getStatus(), activity.getStartTime(), activity.getEndTime(), now))
                .startTime(activity.getStartTime())
                .endTime(activity.getEndTime())
                .ownerId(activity.getOwnerId())
                .ownerName(ownerName)
                .authorizedAdminIds(accessControlService.parseAuthorizedAdminIds(activity.getAuthorizedAdminIds()))
                .participantUserIds(participantIds)
                .participants(participants)
                .participantCount(participantCount)
                .completedCount(completedCount)
                .completionRate(computeCompletionRate(completedCount, participantCount))
                .createdAt(activity.getCreatedAt())
                .updatedAt(activity.getUpdatedAt())
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public ActivityDetailVO createActivity(ActivityUpsertRequest req) {
        validateActivityUpsert(req);
        Long ownerId = AuthContext.requireUserId();
        List<Long> authAdmins = normalizeAdminIds(req.getAuthorizedAdminIds(), ownerId);
        List<Long> studentIds = normalizeAndValidateStudentIds(req.getParticipantUserIds());

        Long actId = activityRepository.insert(AssessmentActivity.builder()
                .title(req.getTitle().trim())
                .questionnaireVersionId(req.getQuestionnaireVersionId())
                .status("DRAFT")
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .ownerId(ownerId)
                .authorizedAdminIds(writeJson(authAdmins))
                .build());

        participantRepository.replaceParticipants(actId, studentIds);
        return getActivityDetailForAdmin(actId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ActivityDetailVO updateDraftActivity(Long activityId, ActivityUpsertRequest req) {
        Long adminId = AuthContext.requireUserId();
        AssessmentActivity existing = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));
        accessControlService.checkAdminActivityAccess(existing, adminId);

        if (!"DRAFT".equals(existing.getStatus())) {
            throw new BizException(400, "仅草稿(DRAFT)状态的活动允许修改配置与绑定版本，当前状态为: " + existing.getStatus());
        }
        validateActivityUpsert(req);

        List<Long> authAdmins = normalizeAdminIds(req.getAuthorizedAdminIds(), existing.getOwnerId());
        List<Long> studentIds = normalizeAndValidateStudentIds(req.getParticipantUserIds());

        existing.setTitle(req.getTitle().trim());
        existing.setQuestionnaireVersionId(req.getQuestionnaireVersionId());
        existing.setStartTime(req.getStartTime());
        existing.setEndTime(req.getEndTime());
        existing.setAuthorizedAdminIds(writeJson(authAdmins));

        activityRepository.updateDraft(existing);
        participantRepository.replaceParticipants(activityId, studentIds);
        cacheService.evictKey(CacheService.activityKey(activityId));

        return getActivityDetailForAdmin(activityId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ActivityDetailVO publishActivity(Long activityId) {
        Long adminId = AuthContext.requireUserId();
        AssessmentActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));
        accessControlService.checkAdminActivityAccess(activity, adminId);

        // REQ04: Strict state transition DRAFT -> PUBLISHED only
        if (!"DRAFT".equals(activity.getStatus())) {
            throw new BizException(400, "非法状态流转：仅草稿(DRAFT)状态可发布，当前状态为 " + activity.getStatus());
        }
        if (activity.getQuestionnaireVersionId() == null
                || versionRepository.findById(activity.getQuestionnaireVersionId()).isEmpty()) {
            throw new BizException(400, "活动绑定的已发布问卷版本快照不存在，禁止发布");
        }
        if (activity.getStartTime() == null || activity.getEndTime() == null
                || !activity.getStartTime().isBefore(activity.getEndTime())) {
            throw new BizException(400, "活动开放开始时间必须早于结束时间");
        }
        long participantCount = participantRepository.countByActivityId(activityId);
        if (participantCount <= 0) {
            throw new BizException(400, "活动目标学生名单不能为空，请至少勾选1名参与学生后再发布");
        }

        activityRepository.updateStatus(activityId, "PUBLISHED");
        cacheService.evictKey(CacheService.activityKey(activityId));
        return getActivityDetailForAdmin(activityId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ActivityDetailVO closeActivity(Long activityId) {
        Long adminId = AuthContext.requireUserId();
        AssessmentActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));
        accessControlService.checkAdminActivityAccess(activity, adminId);

        // REQ04: Strict state transition PUBLISHED -> CLOSED only
        if (!"PUBLISHED".equals(activity.getStatus())) {
            throw new BizException(400, "非法状态流转：仅已发布(PUBLISHED)状态可关闭，当前状态为 " + activity.getStatus());
        }

        activityRepository.updateStatus(activityId, "CLOSED");
        // REQ04 & REQ09: Immediately evict activity cache!
        cacheService.evictKey(CacheService.activityKey(activityId));
        return getActivityDetailForAdmin(activityId);
    }

    public DashboardSummaryVO getDashboardSummary() {
        Long adminId = AuthContext.requireUserId();
        List<AssessmentActivity> visible = activityRepository.findAll().stream()
                .filter(a -> accessControlService.canAdminAccessActivity(a, adminId))
                .collect(Collectors.toList());

        List<Long> visibleIds = visible.stream().map(AssessmentActivity::getId).collect(Collectors.toList());
        long activeCount = visible.stream().filter(a -> "PUBLISHED".equals(a.getStatus())).count();
        long validSheetCount = sheetRepository.countByActivityIds(visibleIds);
        long pendingOrFailedCount = reportRepository.countPendingOrFailedByActivityIds(visibleIds);

        LocalDateTime now = LocalDateTime.now();
        List<RecentActivityVO> recent = visible.stream()
                .limit(5)
                .map(a -> {
                    long pCount = participantRepository.countByActivityId(a.getId());
                    long cCount = sheetRepository.countParticipantCompletedByActivityId(a.getId());
                    return RecentActivityVO.builder()
                            .id(a.getId())
                            .title(a.getTitle())
                            .status(a.getStatus())
                            .statusText(TimeStatusHelper.computeActivityStatusText(a.getStatus(), a.getStartTime(), a.getEndTime(), now))
                            .participantCount(pCount)
                            .completedCount(cCount)
                            .completionRate(computeCompletionRate(cCount, pCount))
                            .startTime(a.getStartTime())
                            .endTime(a.getEndTime())
                            .build();
                })
                .collect(Collectors.toList());

        return DashboardSummaryVO.builder()
                .questionnaireCount(questionnaireRepository.countAll())
                .publishedVersionCount(versionRepository.countAll())
                .activeActivityCount(activeCount)
                .totalActivityCount(visible.size())
                .validSheetCount(validSheetCount)
                .pendingOrFailedReportCount(pendingOrFailedCount)
                .recentActivities(recent)
                .build();
    }

    private ActivityListItemVO buildListItemVO(
            AssessmentActivity a,
            QuestionnaireVersion version,
            String ownerName,
            LocalDateTime now) {
        long pCount = participantRepository.countByActivityId(a.getId());
        long cCount = sheetRepository.countParticipantCompletedByActivityId(a.getId());
        return ActivityListItemVO.builder()
                .id(a.getId())
                .title(a.getTitle())
                .questionnaireVersionId(a.getQuestionnaireVersionId())
                .questionnaireTitle(version == null ? null : version.getTitle())
                .versionNum(version == null ? null : version.getVersionNum())
                .ruleVersion(version == null ? null : version.getRuleVersion())
                .status(a.getStatus())
                .timeWindowStatus(TimeStatusHelper.computeTimeWindowStatus(a.getStartTime(), a.getEndTime(), now))
                .statusText(TimeStatusHelper.computeActivityStatusText(a.getStatus(), a.getStartTime(), a.getEndTime(), now))
                .startTime(a.getStartTime())
                .endTime(a.getEndTime())
                .ownerId(a.getOwnerId())
                .ownerName(ownerName)
                .authorizedAdminIds(accessControlService.parseAuthorizedAdminIds(a.getAuthorizedAdminIds()))
                .participantCount(pCount)
                .completedCount(cCount)
                .completionRate(computeCompletionRate(cCount, pCount))
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    private void validateActivityUpsert(ActivityUpsertRequest req) {
        if (req == null) {
            throw new BizException(400, "活动请求参数不能为空");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(400, "活动名称[title]不能为空");
        }
        if (req.getTitle().trim().length() > 128) {
            throw new BizException(400, "活动名称[title]长度不能超过128个字符");
        }
        if (req.getQuestionnaireVersionId() == null) {
            throw new BizException(400, "必须指定已发布的问卷版本[questionnaireVersionId]");
        }
        if (versionRepository.findById(req.getQuestionnaireVersionId()).isEmpty()) {
            throw new BizException(400, "指定的问卷版本快照不存在: versionId=" + req.getQuestionnaireVersionId());
        }
        if (req.getStartTime() == null || req.getEndTime() == null) {
            throw new BizException(400, "活动开始时间[startTime]与结束时间[endTime]不能为空");
        }
        if (!req.getStartTime().isBefore(req.getEndTime())) {
            throw new BizException(400, "活动开始时间[startTime]必须早于结束时间[endTime]");
        }
    }

    private List<Long> normalizeAdminIds(List<Long> adminIds, Long ownerId) {
        if (adminIds == null || adminIds.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        for (Long id : adminIds) {
            if (id != null && !id.equals(ownerId)) {
                SysUser user = userRepository.findById(id)
                        .orElseThrow(() -> new BizException(400, "授权管理员不存在: userId=" + id));
                if (!"ADMIN".equals(user.getRole())) {
                    throw new BizException(400, "用户[" + user.getUsername() + "]不是管理员角色，不能加入授权管理员列表");
                }
                set.add(id);
            }
        }
        return new ArrayList<>(set);
    }

    private List<Long> normalizeAndValidateStudentIds(List<Long> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        for (Long id : studentIds) {
            if (id != null) {
                SysUser user = userRepository.findById(id)
                        .orElseThrow(() -> new BizException(400, "目标学生不存在: userId=" + id));
                if (!"STUDENT".equals(user.getRole())) {
                    throw new BizException(400, "用户[" + user.getUsername() + "]不是学生角色，不能加入活动参与名单");
                }
                set.add(id);
            }
        }
        return new ArrayList<>(set);
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON序列化失败", ex);
        }
    }
}
