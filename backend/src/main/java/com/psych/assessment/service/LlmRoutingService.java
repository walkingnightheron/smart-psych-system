package com.psych.assessment.service;

import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.AiDtos.LlmConfigUpdateRequest;
import com.psych.assessment.dto.AiDtos.LlmConfigVO;
import com.psych.assessment.dto.AiDtos.LlmTestRequest;
import com.psych.assessment.dto.AiDtos.LlmTestResultVO;
import com.psych.assessment.entity.LlmConfig;
import com.psych.assessment.entity.LlmProvider;
import com.psych.assessment.repository.LlmConfigRepository;
import com.psych.assessment.repository.LlmProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * AI 功能路由配置（契约 8.1b，单行）：读取（附提供商名）、保存校验、连通性测试。
 * 校验红线：temperature 0~2、maxTokens 1~4096、timeoutSeconds 5~120；
 * 启用某功能时其 providerId 必须存在且 enabled、model 非空，否则 400。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmRoutingService {

    private static final String TEST_SYSTEM_PROMPT = "你是连通性测试助手";
    private static final String TEST_USER_PROMPT = "回复：连接正常";
    private static final int TEST_MAX_TOKENS = 256;
    private static final double TEST_TEMPERATURE = 0;
    private static final int REPLY_MAX_LENGTH = 500;
    private static final int ERROR_MAX_LENGTH = 300;

    private final LlmConfigRepository llmConfigRepository;
    private final LlmProviderRepository providerRepository;
    private final LlmClient llmClient;

    public LlmConfigVO getConfig() {
        return toVO(loadOrCreateConfig());
    }

    public LlmConfigVO updateConfig(LlmConfigUpdateRequest req, Long adminId) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        LlmConfig current = loadOrCreateConfig();

        int reportEnabled = req.getReportInsightEnabled() == null
                ? flag(current.getReportInsightEnabled(), 1)
                : (req.getReportInsightEnabled() ? 1 : 0);
        int cohortEnabled = req.getCohortInsightEnabled() == null
                ? flag(current.getCohortInsightEnabled(), 1)
                : (req.getCohortInsightEnabled() ? 1 : 0);

        Long reportProviderId = req.getReportProviderId();
        Long cohortProviderId = req.getCohortProviderId();
        String reportModel = req.getReportModel() == null ? current.getReportModel() : req.getReportModel().trim();
        String cohortModel = req.getCohortModel() == null ? current.getCohortModel() : req.getCohortModel().trim();

        BigDecimal reportTemp = req.getReportTemperature() == null
                ? orDefault(current.getReportTemperature(), new BigDecimal("0.70"))
                : req.getReportTemperature();
        BigDecimal cohortTemp = req.getCohortTemperature() == null
                ? orDefault(current.getCohortTemperature(), new BigDecimal("0.70"))
                : req.getCohortTemperature();
        Integer reportMaxTokens = req.getReportMaxTokens() == null
                ? orDefault(current.getReportMaxTokens(), 800)
                : req.getReportMaxTokens();
        Integer cohortMaxTokens = req.getCohortMaxTokens() == null
                ? orDefault(current.getCohortMaxTokens(), 800)
                : req.getCohortMaxTokens();
        Integer timeoutSeconds = req.getTimeoutSeconds() == null
                ? orDefault(current.getTimeoutSeconds(), 30)
                : req.getTimeoutSeconds();

        validateTemperature("reportTemperature", reportTemp);
        validateTemperature("cohortTemperature", cohortTemp);
        validateMaxTokens("reportMaxTokens", reportMaxTokens);
        validateMaxTokens("cohortMaxTokens", cohortMaxTokens);
        validateTimeout(timeoutSeconds);

        if (reportEnabled == 1) {
            validateRouting("报告解读", reportProviderId, reportModel);
        }
        if (cohortEnabled == 1) {
            validateRouting("群体洞察", cohortProviderId, cohortModel);
        }

        LlmConfig updated = LlmConfig.builder()
                .id(LlmConfigRepository.SINGLETON_ID)
                .reportInsightEnabled(reportEnabled)
                .reportProviderId(reportProviderId)
                .reportModel(reportModel == null ? "" : reportModel)
                .reportTemperature(reportTemp.setScale(2, RoundingMode.HALF_UP))
                .reportMaxTokens(reportMaxTokens)
                .cohortInsightEnabled(cohortEnabled)
                .cohortProviderId(cohortProviderId)
                .cohortModel(cohortModel == null ? "" : cohortModel)
                .cohortTemperature(cohortTemp.setScale(2, RoundingMode.HALF_UP))
                .cohortMaxTokens(cohortMaxTokens)
                .timeoutSeconds(timeoutSeconds)
                .updatedBy(adminId)
                .build();
        llmConfigRepository.upsert(updated);
        log.info("LLM routing config saved: adminId={}, reportEnabled={}, cohortEnabled={}",
                adminId, reportEnabled, cohortEnabled);
        return toVO(llmConfigRepository.findSingle().orElse(updated));
    }

    /**
     * 测试连接：{ providerId, model }，用该提供商发极小 prompt，返回 ok/latencyMs/model/reply/error。
     */
    public LlmTestResultVO testConnection(LlmTestRequest req) {
        LlmConfig cfg = loadOrCreateConfig();
        if (req == null || req.getProviderId() == null) {
            return fail(null, "缺少 providerId，无法发起连通性测试");
        }
        LlmProvider provider = providerRepository.findById(req.getProviderId()).orElse(null);
        if (provider == null) {
            return fail(null, "提供商不存在");
        }
        if (provider.getEnabled() == null || provider.getEnabled() != 1) {
            return fail(null, "提供商已禁用");
        }
        if (isBlank(provider.getApiKey())) {
            return fail(null, "该提供商未配置 API Key");
        }
        String model = isBlank(req.getModel()) ? "" : req.getModel().trim();
        if (model.isEmpty()) {
            return fail(null, "缺少 model，无法发起连通性测试");
        }

        int timeout = cfg.getTimeoutSeconds() == null || cfg.getTimeoutSeconds() <= 0 ? 30 : cfg.getTimeoutSeconds();
        long start = System.currentTimeMillis();
        try {
            String reply = llmClient.chat(provider.getBaseUrl(), provider.getApiKey(), model,
                    TEST_TEMPERATURE, TEST_MAX_TOKENS, timeout, TEST_SYSTEM_PROMPT, TEST_USER_PROMPT);
            long latencyMs = System.currentTimeMillis() - start;
            log.info("LLM connection test ok: providerId={}, model={}, latencyMs={}", provider.getId(), model, latencyMs);
            return LlmTestResultVO.builder()
                    .ok(true)
                    .latencyMs(latencyMs)
                    .model(model)
                    .reply(reply != null && reply.length() > REPLY_MAX_LENGTH ? reply.substring(0, REPLY_MAX_LENGTH) : reply)
                    .build();
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("LLM connection test failed: providerId={}, model={}, latencyMs={}, errorType={}",
                    provider.getId(), model, latencyMs, ex.getClass().getSimpleName());
            return fail(model, friendlyError(ex, provider.getApiKey()), latencyMs);
        }
    }

    /**
     * 读取 id=1 配置行；异常库态缺行时按 schema 默认值补建，保证接口永不因缺行 500。
     */
    public LlmConfig loadOrCreateConfig() {
        Optional<LlmConfig> existing = llmConfigRepository.findSingle();
        if (existing.isPresent()) {
            return existing.get();
        }
        LlmConfig fallback = LlmConfig.builder()
                .id(LlmConfigRepository.SINGLETON_ID)
                .reportInsightEnabled(1)
                .reportProviderId(null)
                .reportModel("")
                .reportTemperature(new BigDecimal("0.70"))
                .reportMaxTokens(800)
                .cohortInsightEnabled(1)
                .cohortProviderId(null)
                .cohortModel("")
                .cohortTemperature(new BigDecimal("0.70"))
                .cohortMaxTokens(800)
                .timeoutSeconds(30)
                .build();
        llmConfigRepository.upsert(fallback);
        return llmConfigRepository.findSingle().orElse(fallback);
    }

    private void validateTemperature(String field, BigDecimal value) {
        if (value == null
                || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(new BigDecimal("2")) > 0) {
            throw new BizException(400, field + " 必须在 0~2 之间");
        }
    }

    private void validateMaxTokens(String field, Integer value) {
        if (value == null || value < 1 || value > 4096) {
            throw new BizException(400, field + " 必须在 1~4096 之间");
        }
    }

    private void validateTimeout(Integer value) {
        if (value == null || value < 5 || value > 120) {
            throw new BizException(400, "timeoutSeconds 必须在 5~120 之间");
        }
    }

    private void validateRouting(String label, Long providerId, String model) {
        if (providerId == null) {
            throw new BizException(400, "启用" + label + "时必须绑定提供商(providerId)");
        }
        LlmProvider p = providerRepository.findById(providerId).orElse(null);
        if (p == null || p.getEnabled() == null || p.getEnabled() != 1) {
            throw new BizException(400, "启用" + label + "时绑定的提供商不存在或已禁用");
        }
        if (isBlank(model)) {
            throw new BizException(400, "启用" + label + "时必须配置模型(model)");
        }
    }

    private LlmConfigVO toVO(LlmConfig cfg) {
        return LlmConfigVO.builder()
                .reportInsightEnabled(cfg.getReportInsightEnabled() == null || cfg.getReportInsightEnabled() == 1)
                .reportProviderId(cfg.getReportProviderId())
                .reportProviderName(providerName(cfg.getReportProviderId()))
                .reportModel(cfg.getReportModel())
                .reportTemperature(cfg.getReportTemperature())
                .reportMaxTokens(cfg.getReportMaxTokens())
                .cohortInsightEnabled(cfg.getCohortInsightEnabled() == null || cfg.getCohortInsightEnabled() == 1)
                .cohortProviderId(cfg.getCohortProviderId())
                .cohortProviderName(providerName(cfg.getCohortProviderId()))
                .cohortModel(cfg.getCohortModel())
                .cohortTemperature(cfg.getCohortTemperature())
                .cohortMaxTokens(cfg.getCohortMaxTokens())
                .timeoutSeconds(cfg.getTimeoutSeconds())
                .updatedAt(cfg.getUpdatedAt())
                .build();
    }

    private String providerName(Long id) {
        if (id == null) {
            return null;
        }
        return providerRepository.findById(id).map(LlmProvider::getName).orElse(null);
    }

    private LlmTestResultVO fail(String model, String error) {
        return fail(model, error, 0L);
    }

    private LlmTestResultVO fail(String model, String error, long latencyMs) {
        return LlmTestResultVO.builder()
                .ok(false)
                .latencyMs(latencyMs)
                .model(model)
                .error(error)
                .build();
    }

    private String friendlyError(Exception ex, String apiKey) {
        String message = isBlank(ex.getMessage()) ? ex.getClass().getSimpleName() : ex.getMessage();
        if (!isBlank(apiKey)) {
            message = message.replace(apiKey, "***");
        }
        message = message.trim();
        return message.length() > ERROR_MAX_LENGTH ? message.substring(0, ERROR_MAX_LENGTH) : message;
    }

    private int flag(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private BigDecimal orDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Integer orDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
