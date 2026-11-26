package com.psych.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.cache.CacheService;
import com.psych.assessment.cache.CacheService.CacheLookup;
import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.QuestionnaireDtos.OptionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.PublishVersionRequest;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireDetailVO;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireListItemVO;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireUpsertRequest;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireVersionVO;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.entity.Questionnaire;
import com.psych.assessment.entity.QuestionnaireQuestion;
import com.psych.assessment.entity.QuestionnaireVersion;
import com.psych.assessment.repository.QuestionnaireQuestionRepository;
import com.psych.assessment.repository.QuestionnaireRepository;
import com.psych.assessment.repository.QuestionnaireVersionRepository;
import com.psych.assessment.scoring.QuestionnaireValidator;
import com.psych.assessment.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuestionnaireService {

    private final QuestionnaireRepository questionnaireRepository;
    private final QuestionnaireQuestionRepository questionRepository;
    private final QuestionnaireVersionRepository versionRepository;
    private final QuestionnaireValidator validator;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public List<QuestionnaireListItemVO> listQuestionnaires() {
        List<Questionnaire> all = questionnaireRepository.findAll();
        return all.stream()
                .map(q -> QuestionnaireListItemVO.builder()
                        .id(q.getId())
                        .code(q.getCode())
                        .title(q.getTitle())
                        .description(q.getDescription())
                        .latestVersion(q.getLatestVersion())
                        .questionCount(questionRepository.countByQuestionnaireId(q.getId()))
                        .updatedAt(q.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    public QuestionnaireDetailVO getQuestionnaireDraft(Long id) {
        Questionnaire q = questionnaireRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "问卷不存在: id=" + id));
        List<QuestionnaireQuestion> rows = questionRepository.findByQuestionnaireId(id);
        List<QuestionConfig> questions = rows.stream()
                .map(this::toQuestionConfig)
                .collect(Collectors.toList());
        ScoringRuleConfig rule = parseScoringRule(q.getScoringRuleJson());

        return QuestionnaireDetailVO.builder()
                .id(q.getId())
                .code(q.getCode())
                .title(q.getTitle())
                .description(q.getDescription())
                .latestVersion(q.getLatestVersion())
                .creatorId(q.getCreatorId())
                .createdAt(q.getCreatedAt())
                .updatedAt(q.getUpdatedAt())
                .scoringRule(rule)
                .questions(questions)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public QuestionnaireDetailVO createQuestionnaire(QuestionnaireUpsertRequest req) {
        String code = req == null ? null : (req.getCode() == null ? null : req.getCode().trim());
        try {
            validator.validateUpsertRequest(req);
            if (questionnaireRepository.findByCode(code).isPresent()) {
                throw new BizException(400, "问卷编码[" + code + "]已存在，请使用唯一编码");
            }

            List<QuestionConfig> sortedQuestions = normalizeQuestions(req.getQuestions());
            ScoringRuleConfig rule = req.getScoringRule();
            if (rule.getRuleVersion() == null || rule.getRuleVersion().isBlank()) {
                rule.setRuleVersion("R-draft");
            }

            Long creatorId = AuthContext.requireUserId();
            Long qId = questionnaireRepository.insert(Questionnaire.builder()
                    .code(code)
                    .title(req.getTitle().trim())
                    .description(req.getDescription())
                    .scoringRuleJson(writeJson(rule))
                    .latestVersion(0)
                    .creatorId(creatorId)
                    .build());

            questionRepository.insertAll(qId, toQuestionEntities(qId, sortedQuestions));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", code);
            detail.put("title", req.getTitle().trim());
            detail.put("questionCount", sortedQuestions.size());
            auditLogService.recordSuccess(AuditLogService.ACT_QUESTIONNAIRE_CREATE,
                    AuditLogService.RES_QUESTIONNAIRE, qId, "新建问卷[" + code + "]", detail);
            return getQuestionnaireDraft(qId);
        } catch (BizException ex) {
            auditLogService.recordFailure(AuditLogService.ACT_QUESTIONNAIRE_CREATE,
                    AuditLogService.RES_QUESTIONNAIRE, null, "新建问卷失败[" + code + "]: " + ex.getMessage(), null);
            throw ex;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public QuestionnaireDetailVO updateQuestionnaireDraft(Long id, QuestionnaireUpsertRequest req) {
        String code = req == null ? null : (req.getCode() == null ? null : req.getCode().trim());
        try {
            Questionnaire existing = questionnaireRepository.findById(id)
                    .orElseThrow(() -> new BizException(404, "问卷不存在: id=" + id));
            validator.validateUpsertRequest(req);

            Optional<Questionnaire> byCode = questionnaireRepository.findByCode(code);
            if (byCode.isPresent() && !byCode.get().getId().equals(id)) {
                throw new BizException(400, "问卷编码[" + code + "]已被其他问卷占用");
            }

            List<QuestionConfig> sortedQuestions = normalizeQuestions(req.getQuestions());
            ScoringRuleConfig rule = req.getScoringRule();
            if (rule.getRuleVersion() == null || rule.getRuleVersion().isBlank()) {
                rule.setRuleVersion("R-v" + (existing.getLatestVersion() + 1));
            }

            // REQ03: Strictly update draft tables ONLY — never modify questionnaire_version!
            questionnaireRepository.updateDraft(
                    id,
                    code,
                    req.getTitle().trim(),
                    req.getDescription(),
                    writeJson(rule)
            );
            questionRepository.deleteByQuestionnaireId(id);
            questionRepository.insertAll(id, toQuestionEntities(id, sortedQuestions));

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", code);
            detail.put("title", req.getTitle().trim());
            detail.put("questionCount", sortedQuestions.size());
            auditLogService.recordSuccess(AuditLogService.ACT_QUESTIONNAIRE_UPDATE_DRAFT,
                    AuditLogService.RES_QUESTIONNAIRE, id, "修改问卷草稿[" + code + "]", detail);
            return getQuestionnaireDraft(id);
        } catch (BizException ex) {
            auditLogService.recordFailure(AuditLogService.ACT_QUESTIONNAIRE_UPDATE_DRAFT,
                    AuditLogService.RES_QUESTIONNAIRE, id, "修改问卷草稿失败[" + code + "]: " + ex.getMessage(), null);
            throw ex;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public QuestionnaireVersionVO publishVersion(Long questionnaireId, PublishVersionRequest req) {
        Questionnaire draft = questionnaireRepository.findById(questionnaireId)
                .orElseThrow(() -> new BizException(404, "问卷不存在: id=" + questionnaireId));
        List<QuestionnaireQuestion> draftQuestions = questionRepository.findByQuestionnaireId(questionnaireId);
        List<QuestionConfig> questions = draftQuestions.stream()
                .map(this::toQuestionConfig)
                .collect(Collectors.toList());
        ScoringRuleConfig rule = parseScoringRule(draft.getScoringRuleJson());

        // Full validation before freezing snapshot
        validator.validateQuestionsAndRule(questions, rule);

        int nextVer = (draft.getLatestVersion() == null ? 0 : draft.getLatestVersion()) + 1;
        String ruleVersion;
        if (req != null && req.getRuleVersion() != null && !req.getRuleVersion().isBlank()) {
            ruleVersion = req.getRuleVersion().trim();
        } else if (rule.getRuleVersion() != null && !rule.getRuleVersion().isBlank() && nextVer == 1) {
            ruleVersion = rule.getRuleVersion().trim();
        } else {
            ruleVersion = "R-v" + nextVer;
        }
        rule.setRuleVersion(ruleVersion);

        Long publisherId = AuthContext.requireUserId();
        QuestionnaireVersion snapshot = QuestionnaireVersion.builder()
                .questionnaireId(questionnaireId)
                .versionNum(nextVer)
                .ruleVersion(ruleVersion)
                .title(draft.getTitle())
                .description(draft.getDescription())
                .questionsSnapshot(writeJson(questions))
                .scoringRuleSnapshot(writeJson(rule))
                .publishedBy(publisherId)
                .build();

        Long versionId = versionRepository.insertSnapshot(snapshot);
        questionnaireRepository.updateLatestVersion(questionnaireId, nextVer);
        cacheService.evictKey(CacheService.versionKey(versionId));

        QuestionnaireVersion saved = versionRepository.findById(versionId)
                .orElseThrow(() -> new BizException(500, "快照版本读取失败"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", draft.getCode());
        detail.put("versionId", versionId);
        detail.put("versionNum", nextVer);
        detail.put("ruleVersion", ruleVersion);
        auditLogService.recordSuccess(AuditLogService.ACT_QUESTIONNAIRE_PUBLISH,
                AuditLogService.RES_QUESTIONNAIRE, questionnaireId, "发布问卷版本[" + draft.getCode() + "] V" + nextVer, detail);
        return toVersionVO(saved, draft.getCode(), "MISS_DB");
    }

    public List<QuestionnaireVersionVO> listVersionsByQuestionnaire(Long questionnaireId) {
        Questionnaire q = questionnaireRepository.findById(questionnaireId)
                .orElseThrow(() -> new BizException(404, "问卷不存在: id=" + questionnaireId));
        return versionRepository.findByQuestionnaireId(questionnaireId).stream()
                .map(v -> toVersionVO(v, q.getCode(), null))
                .collect(Collectors.toList());
    }

    public List<QuestionnaireVersionVO> listAllPublishedVersions() {
        Map<Long, String> codeMap = new HashMap<>();
        for (Questionnaire q : questionnaireRepository.findAll()) {
            codeMap.put(q.getId(), q.getCode());
        }
        return versionRepository.findAll().stream()
                .map(v -> toVersionVO(v, codeMap.get(v.getQuestionnaireId()), null))
                .collect(Collectors.toList());
    }

    public QuestionnaireVersionVO getVersionSnapshotCached(Long versionId) {
        String key = CacheService.versionKey(versionId);
        CacheLookup<QuestionnaireVersionVO> lookup = cacheService.getOrLoad(
                key,
                QuestionnaireVersionVO.class,
                () -> {
                    QuestionnaireVersion v = versionRepository.findById(versionId)
                            .orElseThrow(() -> new BizException(404, "问卷版本快照不存在: versionId=" + versionId));
                    String qCode = questionnaireRepository.findById(v.getQuestionnaireId())
                            .map(Questionnaire::getCode)
                            .orElse(null);
                    return toVersionVO(v, qCode, null);
                }
        );
        QuestionnaireVersionVO vo = lookup.getValue();
        vo.setCacheSource(lookup.getCacheSource());
        return vo;
    }

    private List<QuestionConfig> normalizeQuestions(List<QuestionConfig> questions) {
        List<QuestionConfig> copy = new ArrayList<>(questions);
        copy.sort(Comparator.comparing(QuestionConfig::getQuestionNo));
        for (QuestionConfig q : copy) {
            q.getOptions().sort(Comparator.comparing(o -> o.getSort() == null ? 0 : o.getSort()));
        }
        return copy;
    }

    private List<QuestionnaireQuestion> toQuestionEntities(Long questionnaireId, List<QuestionConfig> configs) {
        List<QuestionnaireQuestion> list = new ArrayList<>(configs.size());
        for (QuestionConfig c : configs) {
            list.add(QuestionnaireQuestion.builder()
                    .questionnaireId(questionnaireId)
                    .questionNo(c.getQuestionNo())
                    .stem(c.getStem().trim())
                    .questionType(c.getQuestionType())
                    .dimensionCode(c.getDimensionCode().trim())
                    .isReverse(c.getIsReverse())
                    .required(c.getRequired())
                    .optionsJson(writeJson(c.getOptions()))
                    .build());
        }
        return list;
    }

    private QuestionConfig toQuestionConfig(QuestionnaireQuestion row) {
        List<OptionConfig> opts = parseOptions(row.getOptionsJson());
        return QuestionConfig.builder()
                .questionNo(row.getQuestionNo())
                .stem(row.getStem())
                .questionType(row.getQuestionType())
                .dimensionCode(row.getDimensionCode())
                .isReverse(row.getIsReverse())
                .required(row.getRequired())
                .options(opts)
                .build();
    }

    public QuestionnaireVersionVO toVersionVO(QuestionnaireVersion v, String questionnaireCode, String cacheSource) {
        List<QuestionConfig> questions = parseQuestionsSnapshot(v.getQuestionsSnapshot());
        ScoringRuleConfig rule = parseScoringRule(v.getScoringRuleSnapshot());
        return QuestionnaireVersionVO.builder()
                .id(v.getId())
                .questionnaireId(v.getQuestionnaireId())
                .questionnaireCode(questionnaireCode)
                .versionNum(v.getVersionNum())
                .ruleVersion(v.getRuleVersion())
                .title(v.getTitle())
                .description(v.getDescription())
                .questionCount(questions.size())
                .questions(questions)
                .scoringRule(rule)
                .publishedBy(v.getPublishedBy())
                .publishedAt(v.getPublishedAt())
                .cacheSource(cacheSource)
                .build();
    }

    public List<QuestionConfig> parseQuestionsSnapshot(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<QuestionConfig>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("解析问卷快照JSON失败", ex);
        }
    }

    public ScoringRuleConfig parseScoringRule(String json) {
        try {
            return objectMapper.readValue(json, ScoringRuleConfig.class);
        } catch (Exception ex) {
            throw new IllegalStateException("解析计分规则JSON失败", ex);
        }
    }

    private List<OptionConfig> parseOptions(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<OptionConfig>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("解析选项JSON失败", ex);
        }
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化JSON失败", ex);
        }
    }
}
