package com.psych.assessment.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.common.BizException;
import com.psych.assessment.entity.AssessmentActivity;
import com.psych.assessment.repository.ActivityParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AccessControlService {

    private final ActivityParticipantRepository participantRepository;
    private final ObjectMapper objectMapper;

    public List<Long> parseAuthorizedAdminIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Long> ids = objectMapper.readValue(json, new TypeReference<List<Long>>() {});
            return ids == null ? Collections.emptyList() : ids;
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    public boolean canAdminAccessActivity(AssessmentActivity activity, Long currentAdminId) {
        if (activity == null || currentAdminId == null) {
            return false;
        }
        if (Objects.equals(activity.getOwnerId(), currentAdminId)) {
            return true;
        }
        List<Long> authorized = parseAuthorizedAdminIds(activity.getAuthorizedAdminIds());
        return authorized.contains(currentAdminId);
    }

    public boolean checkAdminActivityAccess(AssessmentActivity activity, Long currentAdminId) {
        if (!canAdminAccessActivity(activity, currentAdminId)) {
            throw new BizException(403, "无权访问他人未授权的活动或数据");
        }
        return true;
    }

    public void checkStudentInParticipantRoster(Long activityId, Long studentUserId) {
        if (!participantRepository.existsByActivityAndUser(activityId, studentUserId)) {
            throw new BizException(403, "您不在本次评估活动的目标参与名单内");
        }
    }

    public void checkStudentRecordOwner(Long ownerUserId, Long currentStudentId) {
        if (!Objects.equals(ownerUserId, currentStudentId)) {
            throw new BizException(403, "无权访问他人的答卷记录或评估报告");
        }
    }
}
