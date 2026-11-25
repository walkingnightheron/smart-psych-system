package com.psych.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.ActivityDtos.ActivityStatisticsVO;
import com.psych.assessment.dto.AiDtos.AiInsightVO;
import com.psych.assessment.dto.QuestionnaireDtos.DimensionRule;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.entity.AiInsightCache;
import com.psych.assessment.entity.AssessmentActivity;
import com.psych.assessment.entity.AssessmentReport;
import com.psych.assessment.entity.LlmConfig;
import com.psych.assessment.entity.LlmProvider;
import com.psych.assessment.entity.QuestionnaireVersion;
import com.psych.assessment.repository.AiInsightCacheRepository;
import com.psych.assessment.repository.AssessmentActivityRepository;
import com.psych.assessment.repository.AssessmentReportRepository;
import com.psych.assessment.repository.LlmProviderRepository;
import com.psych.assessment.repository.QuestionnaireVersionRepository;
import com.psych.assessment.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * AI 洞察服务（契约 8.2 报告个性化解读 / 8.3 活动群体洞察），按功能路由到对应提供商。
 * 降级红线：功能关闭/提供商缺失/禁用/无 Key/调用失败时一律 available=false + 友好 reason，绝不抛 500。
 * 隐私红线：prompt 仅含维度分、区间名与群体聚合统计，绝不含姓名/学号等个体 PII。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInsightService {

    public static final String DISCLAIMER =
            "本解读由 AI 生成，仅供自我觉察参考，不构成医学或心理诊断；如有困扰请联系学校心理健康教育中心或专业机构。";

    static final String REASON_NOT_READY = "报告尚未生成完成";
    static final String REASON_REPORT_DISABLED = "AI 解读未启用";
    static final String REASON_COHORT_DISABLED = "AI 洞察未启用";
    static final String REASON_REPORT_FAILED = "AI 解读暂时不可用";
    static final String REASON_COHORT_FAILED = "AI 洞察暂时不可用";
    static final String REASON_NO_COHORT_DATA = "暂无足够的统计数据生成群体洞察";

    private static final String REPORT_SYSTEM_PROMPT =
            "你是高校心理健康教育中心的AI助手，为大学生撰写心理测评结果的个性化解读。必须遵守："
            + "1) 语气支持性、温暖、非评判；"
            + "2) 不下医学或心理诊断结论，不贴任何疾病标签，不推荐药物；"
            + "3) 使用简体中文，篇幅200~400字；"
            + "4) 若结果显示较高心理负荷或危机倾向，温和引导其联系辅导员、学校心理健康教育中心或专业机构寻求支持；"
            + "5) 仅基于提供的数据描述，不编造信息，不涉及任何个人身份信息。";

    private static final String COHORT_SYSTEM_PROMPT =
            "你是高校心理健康教育中心的AI分析助手，面向活动管理者输出群体心理测评趋势洞察。必须遵守："
            + "1) 仅做群体层面的趋势描述与系统性工作建议，不涉及、不推测任何个体情况；"
            + "2) 语气客观、支持性、非诊断，不下医学结论，不贴标签；"
            + "3) 使用简体中文，篇幅200~400字；"
            + "4) 若群体负荷偏高，建议通过团体辅导、心理健康宣传、辅导员重点关注等方式提供支持，并提示有需要的学生联系学校心理健康教育中心；"
            + "5) 仅基于提供的统计数据，不编造信息，不包含任何个人身份信息。";

    private final LlmRoutingService llmRoutingService;
    private final LlmProviderRepository providerRepository;
    private final LlmClient llmClient;
    private final AiInsightCacheRepository cacheRepository;
    private final AssessmentReportRepository reportRepository;
    private final AssessmentActivityRepository activityRepository;
    private final QuestionnaireVersionRepository versionRepository;
    private final QuestionnaireService questionnaireService;
    private final AccessControlService accessControlService;
    private final ReportAndStatsService reportAndStatsService;
    private final ObjectMapper objectMapper;

    /**
     * 报告个性化解读（契约 8.2）：学生须为报告本人，管理员须对报告所属活动有数据范围权限，否则 403。
     * 路由：report_provider_id 对应提供商的 baseUrl/key + report_model/report_temperature/report_max_tokens。
     */
    public AiInsightVO reportInterpretation(Long reportId, Long requesterUserId, boolean isAdmin) {
        AssessmentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BizException(404, "评估报告不存在: reportId=" + reportId));
        if (isAdmin) {
            AssessmentActivity activity = activityRepository.findById(report.getActivityId())
                    .orElseThrow(() -> new BizException(404, "关联活动不存在"));
            accessControlService.checkAdminActivityAccess(activity, requesterUserId);
        } else {
            accessControlService.checkStudentRecordOwner(report.getUserId(), requesterUserId);
        }

        if (!"SUCCESS".equals(report.getTaskStatus())) {
            return unavailable(REASON_NOT_READY);
        }

        LlmConfig cfg = llmRoutingService.loadOrCreateConfig();
        if (!enabled(cfg.getReportInsightEnabled())) {
            return unavailable(REASON_REPORT_DISABLED);
        }

        String cacheKey = "report:" + reportId;
        Optional<AiInsightCache> cached = cacheRepository.findByKey(cacheKey);
        if (cached.isPresent()) {
            return available(cached.get().getContent(), cached.get().getModel(), cached.get().getGeneratedAt());
        }

        Route route = resolve("报告解读", cfg.getReportProviderId(), cfg.getReportModel(),
                cfg.getReportTemperature(), cfg.getReportMaxTokens());
        if (route == null) {
            return unavailable(resolveReason("报告解读", cfg.getReportProviderId(), cfg.getReportModel()));
        }

        try {
            String userPrompt = buildReportUserPrompt(report);
            String content = llmClient.chat(route.provider.getBaseUrl(), route.provider.getApiKey(), route.model,
                    route.temperature, route.maxTokens, route.timeout, REPORT_SYSTEM_PROMPT, userPrompt);
            cacheRepository.upsert(cacheKey, content, route.model);
            LocalDateTime generatedAt = cacheRepository.findByKey(cacheKey)
                    .map(AiInsightCache::getGeneratedAt)
                    .orElseGet(LocalDateTime::now);
            return available(content, route.model, generatedAt);
        } catch (Exception ex) {
            String safeDetail = safeReasonDetail(ex, route.provider.getApiKey());
            log.warn("AI report interpretation failed: reportId={}, model={}, detail={}", reportId, route.model, safeDetail);
            return unavailable(isAdmin && !safeDetail.isEmpty() ? REASON_REPORT_FAILED + "（" + safeDetail + "）" : REASON_REPORT_FAILED);
        }
    }

    /**
     * 活动群体洞察（契约 8.3）：校验活动数据范围（越权 403），基于统计聚合构造 prompt，按 activity:{id} 缓存。
     * 路由：cohort_provider_id 对应提供商的 baseUrl/key + cohort_model/cohort_temperature/cohort_max_tokens。
     */
    public AiInsightVO cohortInsight(Long activityId, Long adminId) {
        AssessmentActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));
        accessControlService.checkAdminActivityAccess(activity, adminId);

        LlmConfig cfg = llmRoutingService.loadOrCreateConfig();
        if (!enabled(cfg.getCohortInsightEnabled())) {
            return unavailable(REASON_COHORT_DISABLED);
        }

        String cacheKey = "activity:" + activityId;
        Optional<AiInsightCache> cached = cacheRepository.findByKey(cacheKey);
        if (cached.isPresent()) {
            return available(cached.get().getContent(), cached.get().getModel(), cached.get().getGeneratedAt());
        }

        ActivityStatisticsVO stats = reportAndStatsService.getActivityStatistics(activityId);
        if (stats.getParticipantCount() == 0) {
            return unavailable(REASON_NO_COHORT_DATA);
        }

        Route route = resolve("群体洞察", cfg.getCohortProviderId(), cfg.getCohortModel(),
                cfg.getCohortTemperature(), cfg.getCohortMaxTokens());
        if (route == null) {
            return unavailable(resolveReason("群体洞察", cfg.getCohortProviderId(), cfg.getCohortModel()));
        }

        try {
            String userPrompt = buildCohortUserPrompt(stats);
            String content = llmClient.chat(route.provider.getBaseUrl(), route.provider.getApiKey(), route.model,
                    route.temperature, route.maxTokens, route.timeout, COHORT_SYSTEM_PROMPT, userPrompt);
            cacheRepository.upsert(cacheKey, content, route.model);
            LocalDateTime generatedAt = cacheRepository.findByKey(cacheKey)
                    .map(AiInsightCache::getGeneratedAt)
                    .orElseGet(LocalDateTime::now);
            return available(content, route.model, generatedAt);
        } catch (Exception ex) {
            String safeDetail = safeReasonDetail(ex, route.provider.getApiKey());
            log.warn("AI cohort insight failed: activityId={}, model={}, detail={}", activityId, route.model, safeDetail);
            return unavailable(safeDetail.isEmpty() ? REASON_COHORT_FAILED : REASON_COHORT_FAILED + "（" + safeDetail + "）");
        }
    }

    private String safeReasonDetail(Exception ex, String apiKey) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "";
        }
        String safe = ex.getMessage();
        if (apiKey != null && !apiKey.isBlank()) {
            safe = safe.replace(apiKey, "***");
        }
        safe = safe.replaceAll("\\s+", " ").trim();
        return safe.length() > 160 ? safe.substring(0, 160) : safe;
    }

    private Route resolve(String label, Long providerId, String model, BigDecimal temperature, Integer maxTokens) {
        if (providerId == null) {
            return null;
        }
        LlmProvider provider = providerRepository.findById(providerId).orElse(null);
        if (provider == null || provider.getEnabled() == null || provider.getEnabled() != 1) {
            return null;
        }
        if (isBlank(provider.getApiKey())) {
            return null;
        }
        if (isBlank(model)) {
            return null;
        }
        LlmConfig cfg = llmRoutingService.loadOrCreateConfig();
        int timeout = cfg.getTimeoutSeconds() == null || cfg.getTimeoutSeconds() <= 0 ? 30 : cfg.getTimeoutSeconds();
        double temp = temperature == null ? 0.7d : temperature.doubleValue();
        int tokens = maxTokens == null || maxTokens <= 0 ? 800 : maxTokens;
        return new Route(provider, model, temp, tokens, timeout);
    }

    private String resolveReason(String label, Long providerId, String model) {
        if (providerId == null) {
            return label + "未配置提供商";
        }
        LlmProvider provider = providerRepository.findById(providerId).orElse(null);
        if (provider == null || provider.getEnabled() == null || provider.getEnabled() != 1) {
            return label + "的提供商不存在或已禁用";
        }
        if (isBlank(provider.getApiKey())) {
            return label + "的提供商未配置 API Key";
        }
        if (isBlank(model)) {
            return label + "未配置模型";
        }
        return label + "暂时不可用";
    }

    private boolean enabled(Integer flag) {
        return flag != null && flag == 1;
    }

    private static final class Route {
        final LlmProvider provider;
        final String model;
        final double temperature;
        final int maxTokens;
        final int timeout;

        Route(LlmProvider provider, String model, double temperature, int maxTokens, int timeout) {
            this.provider = provider;
            this.model = model;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.timeout = timeout;
        }
    }

    /**
     * 维度分 + 区间名构造 prompt，绝不携带姓名/学号等 PII。
     */
    private String buildReportUserPrompt(AssessmentReport report) {
        Map<String, String> dimNames = resolveDimensionNames(report.getQuestionnaireVersionId());
        Map<String, BigDecimal> dimScores = parseDimensionScores(report.getDimensionScoresJson());

        StringBuilder sb = new StringBuilder();
        sb.append("以下是一名大学生心理测评的结果数据（已匿名化，不含任何个人身份信息）：\n");
        sb.append("总分：").append(plain(report.getTotalScore())).append("\n");
        sb.append("结果区间：").append(report.getLevelName() == null ? "未知" : report.getLevelName()).append("\n");
        sb.append("各维度得分：\n");
        if (dimScores.isEmpty()) {
            sb.append("- 暂无维度数据\n");
        } else {
            for (Map.Entry<String, BigDecimal> e : dimScores.entrySet()) {
                sb.append("- ").append(dimNames.getOrDefault(e.getKey(), e.getKey()))
                        .append("：").append(plain(e.getValue())).append("\n");
            }
        }
        sb.append("请基于以上数据，为该同学撰写一段支持性、非诊断的个性化解读（200~400字）。");
        return sb.toString();
    }

    /**
     * 完成率/区间分布/维度均分等群体聚合构造 prompt，绝不携带个体 PII。
     */
    private String buildCohortUserPrompt(ActivityStatisticsVO stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是一次校园心理测评活动的群体统计聚合数据（不含任何个体身份信息）：\n");
        sb.append("活动名称：").append(stats.getActivityTitle()).append("\n");
        sb.append("问卷：").append(stats.getQuestionnaireTitle()).append("\n");
        sb.append("参与人数：").append(stats.getParticipantCount())
                .append("，已完成：").append(stats.getCompletedCount())
                .append("，完成率：").append(plain(stats.getCompletionRate())).append("%\n");
        sb.append("报告状态：成功 ").append(stats.getSuccessReportCount())
                .append("，生成中 ").append(stats.getPendingReportCount())
                .append("，失败 ").append(stats.getFailedReportCount()).append("\n");
        sb.append("结果区间分布：");
        if (stats.getLevelDistribution() == null || stats.getLevelDistribution().isEmpty()) {
            sb.append("暂无");
        } else {
            sb.append(stats.getLevelDistribution().stream()
                    .map(item -> item.getLevelName() + " " + item.getCount() + "人")
                    .collect(Collectors.joining("；")));
        }
        sb.append("\n各维度平均分：");
        if (stats.getDimensionAverages() == null || stats.getDimensionAverages().isEmpty()) {
            sb.append("暂无");
        } else {
            sb.append(stats.getDimensionAverages().stream()
                    .map(item -> item.getDimensionName() + " " + plain(item.getAvgScore()))
                    .collect(Collectors.joining("；")));
        }
        sb.append("\n请基于以上群体数据，为管理者撰写一段群体趋势洞察与系统性工作建议（200~400字），不要涉及任何个体。");
        return sb.toString();
    }

    private Map<String, String> resolveDimensionNames(Long questionnaireVersionId) {
        if (questionnaireVersionId == null) {
            return Collections.emptyMap();
        }
        try {
            QuestionnaireVersion version = versionRepository.findById(questionnaireVersionId).orElse(null);
            if (version == null) {
                return Collections.emptyMap();
            }
            ScoringRuleConfig rule = questionnaireService.parseScoringRule(version.getScoringRuleSnapshot());
            if (rule == null || rule.getDimensions() == null) {
                return Collections.emptyMap();
            }
            Map<String, String> names = new LinkedHashMap<>();
            for (DimensionRule d : rule.getDimensions()) {
                names.put(d.getCode(), d.getName());
            }
            return names;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
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

    private AiInsightVO available(String content, String model, LocalDateTime generatedAt) {
        return AiInsightVO.builder()
                .available(true)
                .content(content)
                .model(model)
                .generatedAt(generatedAt == null ? LocalDateTime.now() : generatedAt)
                .disclaimer(DISCLAIMER)
                .build();
    }

    private AiInsightVO unavailable(String reason) {
        return AiInsightVO.builder()
                .available(false)
                .disclaimer(DISCLAIMER)
                .reason(reason)
                .build();
    }

    private String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
