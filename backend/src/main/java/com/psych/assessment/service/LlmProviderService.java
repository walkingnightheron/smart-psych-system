package com.psych.assessment.service;

import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.AiDtos.LlmModelsResultVO;
import com.psych.assessment.dto.AiDtos.LlmProviderUpsertRequest;
import com.psych.assessment.dto.AiDtos.LlmProviderVO;
import com.psych.assessment.entity.LlmConfig;
import com.psych.assessment.entity.LlmProvider;
import com.psych.assessment.repository.LlmConfigRepository;
import com.psych.assessment.repository.LlmProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 提供商管理（契约 8.1，多提供商）。
 * 安全红线：列表/详情一律脱敏（hasApiKey + apiKeyTail 尾4位），绝不返回完整 Key，不写日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderService {

    private final LlmProviderRepository providerRepository;
    private final LlmConfigRepository configRepository;
    private final LlmClient llmClient;

    public List<LlmProviderVO> list() {
        return providerRepository.findAll().stream()
                .map(this::toMaskedVO)
                .collect(Collectors.toList());
    }

    public LlmProviderVO get(Long id) {
        return toMaskedVO(requireProvider(id));
    }

    public LlmProviderVO create(LlmProviderUpsertRequest req, Long adminId) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        String name = trim(req.getName());
        String baseUrl = trim(req.getBaseUrl());
        if (name.isEmpty()) {
            throw new BizException(400, "name 不能为空");
        }
        if (baseUrl.isEmpty()) {
            throw new BizException(400, "baseUrl 不能为空");
        }
        LlmProvider p = LlmProvider.builder()
                .name(name)
                .baseUrl(baseUrl)
                .apiKey(req.getApiKey() == null ? "" : req.getApiKey().trim())
                .enabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1)
                .updatedBy(adminId)
                .build();
        LlmProvider saved = providerRepository.insert(p);
        log.info("LLM provider created: id={}, name={}, enabled={}", saved.getId(), saved.getName(), saved.getEnabled());
        return toMaskedVO(reload(saved.getId()));
    }

    public LlmProviderVO update(Long id, LlmProviderUpsertRequest req, Long adminId) {
        LlmProvider current = requireProvider(id);
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        String name = req.getName() == null ? current.getName() : trim(req.getName());
        String baseUrl = req.getBaseUrl() == null ? current.getBaseUrl() : trim(req.getBaseUrl());
        if (name.isEmpty()) {
            throw new BizException(400, "name 不能为空");
        }
        if (baseUrl.isEmpty()) {
            throw new BizException(400, "baseUrl 不能为空");
        }
        // apiKey 留空/不传 → 保留原值；非空 → 覆盖
        String apiKey = isBlank(req.getApiKey()) ? current.getApiKey() : req.getApiKey().trim();
        int enabled = req.getEnabled() == null
                ? (current.getEnabled() == null ? 1 : current.getEnabled())
                : (req.getEnabled() ? 1 : 0);

        LlmProvider updated = LlmProvider.builder()
                .id(id)
                .name(name)
                .baseUrl(baseUrl)
                .apiKey(apiKey == null ? "" : apiKey)
                .enabled(enabled)
                .updatedBy(adminId)
                .build();
        providerRepository.update(updated);
        log.info("LLM provider updated: id={}, name={}, enabled={}, hasApiKey={}",
                id, name, enabled, !isBlank(apiKey));
        return toMaskedVO(reload(id));
    }

    public void delete(Long id) {
        requireProvider(id);
        LlmConfig cfg = configRepository.findSingle().orElse(null);
        if (cfg != null
                && (id.equals(cfg.getReportProviderId()) || id.equals(cfg.getCohortProviderId()))) {
            throw new BizException(400, "该提供商正被功能使用，请先切换");
        }
        providerRepository.delete(id);
        log.info("LLM provider deleted: id={}", id);
    }

    /**
     * 拉取该提供商的模型列表（契约 8.1：GET {baseUrl}/models）。
     * 失败时 ok=false + 友好 error，绝不抛 500。
     */
    public LlmModelsResultVO listModels(Long id) {
        LlmProvider p = requireProvider(id);
        if (isBlank(p.getApiKey())) {
            return LlmModelsResultVO.builder().ok(false).models(null).error("该提供商未配置 API Key，无法拉取模型列表").build();
        }
        int timeout = defaultTimeout();
        try {
            List<String> models = llmClient.listModels(p.getBaseUrl(), p.getApiKey(), timeout);
            return LlmModelsResultVO.builder().ok(true).models(models).build();
        } catch (Exception ex) {
            log.warn("LLM listModels failed for provider={}, errorType={}", id, ex.getClass().getSimpleName());
            return LlmModelsResultVO.builder().ok(false).models(null).error(friendlyError(ex, p.getApiKey())).build();
        }
    }

    private int defaultTimeout() {
        LlmConfig cfg = configRepository.findSingle().orElse(null);
        return cfg == null || cfg.getTimeoutSeconds() == null || cfg.getTimeoutSeconds() <= 0 ? 30 : cfg.getTimeoutSeconds();
    }

    private LlmProvider requireProvider(Long id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "LLM 提供商不存在: id=" + id));
    }

    private LlmProvider reload(Long id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "LLM 提供商不存在: id=" + id));
    }

    private LlmProviderVO toMaskedVO(LlmProvider p) {
        String key = p.getApiKey();
        boolean hasApiKey = !isBlank(key);
        String apiKeyTail = "";
        if (hasApiKey) {
            apiKeyTail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        }
        return LlmProviderVO.builder()
                .id(p.getId())
                .name(p.getName())
                .baseUrl(p.getBaseUrl())
                .hasApiKey(hasApiKey)
                .apiKeyTail(apiKeyTail)
                .enabled(p.getEnabled() != null && p.getEnabled() == 1)
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private String friendlyError(Exception ex, String apiKey) {
        String message = isBlank(ex.getMessage()) ? ex.getClass().getSimpleName() : ex.getMessage();
        if (!isBlank(apiKey)) {
            message = message.replace(apiKey, "***");
        }
        message = message.trim();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
