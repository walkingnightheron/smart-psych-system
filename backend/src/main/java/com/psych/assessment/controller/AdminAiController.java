package com.psych.assessment.controller;

import com.psych.assessment.common.Result;
import com.psych.assessment.dto.AiDtos.AiInsightVO;
import com.psych.assessment.dto.AiDtos.LlmConfigUpdateRequest;
import com.psych.assessment.dto.AiDtos.LlmConfigVO;
import com.psych.assessment.dto.AiDtos.LlmModelsResultVO;
import com.psych.assessment.dto.AiDtos.LlmProviderUpsertRequest;
import com.psych.assessment.dto.AiDtos.LlmProviderVO;
import com.psych.assessment.dto.AiDtos.LlmTestRequest;
import com.psych.assessment.dto.AiDtos.LlmTestResultVO;
import com.psych.assessment.security.AuthContext;
import com.psych.assessment.service.AiInsightService;
import com.psych.assessment.service.LlmProviderService;
import com.psych.assessment.service.LlmRoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端智能分析配置与 AI 洞察接口（契约 8.1/8.1b/8.2/8.3），经 AuthInterceptor 强制 ADMIN 角色，数据范围在 Service 内校验。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAiController {

    private final LlmProviderService llmProviderService;
    private final LlmRoutingService llmRoutingService;
    private final AiInsightService aiInsightService;

    // ---------- 8.1 LLM 提供商管理 ----------

    @GetMapping("/llm-providers")
    public Result<List<LlmProviderVO>> listLlmProviders() {
        return Result.ok(llmProviderService.list());
    }

    @GetMapping("/llm-providers/{id}")
    public Result<LlmProviderVO> getLlmProvider(@PathVariable("id") Long id) {
        return Result.ok(llmProviderService.get(id));
    }

    @PostMapping("/llm-providers")
    public Result<LlmProviderVO> createLlmProvider(@RequestBody(required = false) LlmProviderUpsertRequest request) {
        return Result.ok(llmProviderService.create(request, AuthContext.requireUserId()));
    }

    @PutMapping("/llm-providers/{id}")
    public Result<LlmProviderVO> updateLlmProvider(@PathVariable("id") Long id,
                                                   @RequestBody(required = false) LlmProviderUpsertRequest request) {
        return Result.ok(llmProviderService.update(id, request, AuthContext.requireUserId()));
    }

    @DeleteMapping("/llm-providers/{id}")
    public Result<Void> deleteLlmProvider(@PathVariable("id") Long id) {
        llmProviderService.delete(id);
        return Result.ok();
    }

    @GetMapping("/llm-providers/{id}/models")
    public Result<LlmModelsResultVO> listLlmProviderModels(@PathVariable("id") Long id) {
        return Result.ok(llmProviderService.listModels(id));
    }

    // ---------- 8.1b AI 功能路由配置 ----------

    @GetMapping("/llm-config")
    public Result<LlmConfigVO> getLlmConfig() {
        return Result.ok(llmRoutingService.getConfig());
    }

    @PutMapping("/llm-config")
    public Result<LlmConfigVO> updateLlmConfig(@RequestBody(required = false) LlmConfigUpdateRequest request) {
        return Result.ok(llmRoutingService.updateConfig(request, AuthContext.requireUserId()));
    }

    @PostMapping("/llm-config/test")
    public Result<LlmTestResultVO> testLlmConnection(@RequestBody(required = false) LlmTestRequest request) {
        return Result.ok(llmRoutingService.testConnection(request));
    }

    // ---------- 8.2 / 8.3 AI 洞察 ----------

    @GetMapping("/activities/{id}/ai-insight")
    public Result<AiInsightVO> getActivityAiInsight(@PathVariable("id") Long id) {
        return Result.ok(aiInsightService.cohortInsight(id, AuthContext.requireUserId()));
    }

    @GetMapping("/reports/{reportId}/ai-interpretation")
    public Result<AiInsightVO> getReportAiInterpretationForAdmin(@PathVariable("reportId") Long reportId) {
        return Result.ok(aiInsightService.reportInterpretation(reportId, AuthContext.requireUserId(), true));
    }
}
