package com.psych.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psych.assessment.service.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 多提供商 + 功能级路由的 AI 配置与洞察集成测试（契约 8.1/8.1b/8.2/8.3），demo profile + H2 + MockMvc。
 *
 * <p>{@code @MockBean LlmClient} 桩化 {@code chat(...)} 与 {@code listModels(...)}，全程绝不发起真实外网 LLM 调用。
 * 覆盖：提供商 CRUD 脱敏、apiKey 留空保留、删除被引用提供商 400、listModels 成功/失败、
 * 路由保存校验（启用但 provider 缺失/model 空/参数越界 400）、连通性测试成功/失败脱敏、
 * 报告解读按 report 路由 + 缓存命中（chat 仅 1 次）、群体洞察按 cohort 路由、
 * 关闭/未绑定提供商降级不 500、报告/群体洞察数据范围 403、学生访问管理端 403。
 *
 * <p>隔离策略：{@code @DirtiesContext(AFTER_CLASS)} 隔离 @MockBean 造成的上下文缓存；
 * 每个用例前复位 llm_config 为 schema 默认路由、清空 llm_provider 与 ai_insight_cache，
 * 保证用例执行顺序无关且断言不被上一个用例污染。
 */
@SpringBootTest(properties = {
        "app.cache.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@DirtiesContext(classMode = AFTER_CLASS)
class AiConfigAndInsightIntegrationTest {

    private static final String SECRET_KEY = "sk-test-secret-123456";
    private static final String MOCK_REPLY = "这是测试解读内容";
    private static final String BASE_URL = "https://llm.example.test/v1";
    private static final String REPORT_MODEL = "report-model";
    private static final String COHORT_MODEL = "cohort-model";

    private static final String PROVIDERS_URL = "/api/admin/llm-providers";
    private static final String LLM_CONFIG_URL = "/api/admin/llm-config";
    private static final String LLM_TEST_URL = "/api/admin/llm-config/test";
    private static final String STUDENT_REPORT_AI_URL = "/api/h5/reports/1/ai-interpretation";
    private static final String STUDENT_OTHER_REPORT_AI_URL = "/api/h5/reports/2/ai-interpretation";
    private static final String ADMIN_REPORT_AI_URL = "/api/admin/reports/1/ai-interpretation";
    private static final String ADMIN_COHORT_AI_URL = "/api/admin/activities/1/ai-insight";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private LlmClient llmClient;

    private final Map<String, String> tokenCache = new LinkedHashMap<>();

    @BeforeEach
    void resetStateAndStubLlm() {
        int updated = jdbcTemplate.update(
                "UPDATE llm_config SET report_insight_enabled = 1, report_provider_id = NULL, report_model = '', "
                        + "report_temperature = 0.70, report_max_tokens = 800, "
                        + "cohort_insight_enabled = 1, cohort_provider_id = NULL, cohort_model = '', "
                        + "cohort_temperature = 0.70, cohort_max_tokens = 800, timeout_seconds = 30, updated_by = NULL "
                        + "WHERE id = 1");
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO llm_config (id, report_insight_enabled, report_provider_id, report_model, "
                            + "report_temperature, report_max_tokens, cohort_insight_enabled, cohort_provider_id, "
                            + "cohort_model, cohort_temperature, cohort_max_tokens, timeout_seconds) "
                            + "VALUES (1, 1, NULL, '', 0.70, 800, 1, NULL, '', 0.70, 800, 30)");
        }
        jdbcTemplate.update("DELETE FROM llm_provider");
        jdbcTemplate.update("DELETE FROM ai_insight_cache");

        reset(llmClient);
        when(llmClient.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyInt(),
                anyString(), anyString())).thenReturn(MOCK_REPLY);
        when(llmClient.listModels(anyString(), anyString(), anyInt()))
                .thenReturn(List.of("qwen-plus", "deepseek-chat"));
        clearInvocations(llmClient);
    }

    // ==================================================================
    // 8.1 LLM 提供商：CRUD 脱敏 / 留空保留 / 删除引用校验 / 模型列表
    // ==================================================================

    @Test
    void providerCrudNeverEchoesFullApiKey() throws Exception {
        String admin = token("admin01");
        long id = createProvider(admin, "阿里百炼", BASE_URL, SECRET_KEY, true);

        // 列表脱敏
        JsonNode listData = expectOk(mockMvc.perform(get(PROVIDERS_URL)
                .header("Authorization", "Bearer " + admin)).andReturn());
        String listBody = utf8Body(mockMvc.perform(get(PROVIDERS_URL)
                .header("Authorization", "Bearer " + admin)).andReturn());
        JsonNode list = listData.path("data");
        assertEquals(1, list.size(), "应有一条提供商记录: " + list);
        assertFalse(listBody.contains(SECRET_KEY), "列表响应不得泄露完整密钥: " + listBody);
        assertFalse(listBody.contains("\"apiKey\""), "列表响应不得出现 apiKey 字段: " + listBody);

        // 详情脱敏
        String getBody = utf8Body(mockMvc.perform(get(PROVIDERS_URL + "/" + id)
                .header("Authorization", "Bearer " + admin)).andReturn());
        JsonNode detail = expectOk(mockMvc.perform(get(PROVIDERS_URL + "/" + id)
                .header("Authorization", "Bearer " + admin)).andReturn()).path("data");
        assertEquals(id, detail.path("id").asLong());
        assertEquals("阿里百炼", detail.path("name").asText());
        assertTrue(detail.path("hasApiKey").asBoolean(), "有 Key 时 hasApiKey 应为 true: " + detail);
        assertEquals("3456", detail.path("apiKeyTail").asText(), "apiKeyTail 必须是尾 4 位: " + detail);
        assertTrue(detail.path("enabled").asBoolean(), "默认 enabled 应为 true: " + detail);
        assertFalse(getBody.contains(SECRET_KEY), "详情响应不得泄露完整密钥: " + getBody);
        assertFalse(getBody.contains("\"apiKey\""), "详情响应不得出现 apiKey 字段: " + getBody);
    }

    @Test
    void providerUpdateWithBlankApiKeyKeepsExistingKey() throws Exception {
        String admin = token("admin01");
        long id = createProvider(admin, "阿里百炼", BASE_URL, SECRET_KEY, true);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", "阿里百炼-改");
        payload.put("baseUrl", BASE_URL);
        payload.put("apiKey", "");
        payload.put("enabled", true);
        expectOk(mockMvc.perform(put(PROVIDERS_URL + "/" + id)
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn());

        JsonNode detail = getData(admin, PROVIDERS_URL + "/" + id);
        assertTrue(detail.path("hasApiKey").asBoolean(), "apiKey 留空必须保留原 Key: " + detail);
        assertEquals("3456", detail.path("apiKeyTail").asText(), "保留的仍是同一把 Key: " + detail);
        assertEquals("阿里百炼-改", detail.path("name").asText());
    }

    @Test
    void deleteReferencedProviderRejected400() throws Exception {
        String admin = token("admin01");
        long referenced = createProvider(admin, "被引用提供商", BASE_URL, SECRET_KEY, true);

        ObjectNode routing = objectMapper.createObjectNode();
        routing.put("reportInsightEnabled", true);
        routing.put("reportProviderId", referenced);
        routing.put("reportModel", REPORT_MODEL);
        routing.put("reportTemperature", 0.7);
        routing.put("reportMaxTokens", 800);
        routing.put("cohortInsightEnabled", false);
        routing.put("cohortTemperature", 0.7);
        routing.put("cohortMaxTokens", 800);
        routing.put("timeoutSeconds", 30);
        putRouting(admin, routing);

        JsonNode denied = expectStatus(mockMvc.perform(delete(PROVIDERS_URL + "/" + referenced)
                .header("Authorization", "Bearer " + admin)).andReturn(), 400);
        assertTrue(denied.path("message").asText().contains("该提供商正被功能使用"),
                "删除被引用提供商必须提示先切换: " + denied.path("message").asText());

        // 未被引用的提供商可正常删除
        long free = createProvider(admin, "未被引用提供商", BASE_URL, SECRET_KEY, true);
        JsonNode ok = expectOk(mockMvc.perform(delete(PROVIDERS_URL + "/" + free)
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals(0, ok.path("code").asInt());
    }

    @Test
    void listModelsSuccessSortedDedupedAndFailureFriendly() throws Exception {
        String admin = token("admin01");
        long id = createProvider(admin, "模型提供商", BASE_URL, SECRET_KEY, true);

        // 成功：ok=true 且模型列表透传（去重排序由 LlmClient 生产逻辑负责）
        JsonNode ok = expectOk(mockMvc.perform(get(PROVIDERS_URL + "/" + id + "/models")
                .header("Authorization", "Bearer " + admin)).andReturn()).path("data");
        assertTrue(ok.path("ok").asBoolean(), "listModels 成功时 ok 应为 true: " + ok);
        List<String> models = List.of(
                ok.path("models").get(0).asText(),
                ok.path("models").get(1).asText());
        assertEquals(List.of("qwen-plus", "deepseek-chat"), models,
                "模型列表应与上游一致: " + models);

        // 失败：ok=false + 友好 error，不含 Key
        when(llmClient.listModels(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("LLM 服务返回错误(HTTP 401): apiKey=" + SECRET_KEY));
        JsonNode fail = expectOk(mockMvc.perform(get(PROVIDERS_URL + "/" + id + "/models")
                .header("Authorization", "Bearer " + admin)).andReturn()).path("data");
        assertFalse(fail.path("ok").asBoolean(), "listModels 失败时 ok 应为 false: " + fail);
        String error = fail.path("error").asText();
        assertFalse(error.isBlank(), "失败必须给出 error: " + fail);
        assertFalse(error.contains(SECRET_KEY), "error 不得泄露密钥明文: " + error);
    }

    // ==================================================================
    // 8.1b 路由配置：保存校验
    // ==================================================================

    @Test
    void routingSaveValidationRejectsMissingProviderOrModelOrOutOfRange() throws Exception {
        String admin = token("admin01");
        long provider = createProvider(admin, "路由提供商", BASE_URL, SECRET_KEY, true);

        ObjectNode base = routingConfig(provider, provider, true, true);

        // 启用报告解读但 provider 缺失
        ObjectNode noProvider = base.deepCopy();
        noProvider.remove("reportProviderId");
        expectPutStatus(admin, noProvider, 400, "提供商");

        // 启用报告解读但 model 为空
        ObjectNode emptyModel = base.deepCopy();
        emptyModel.put("reportModel", "");
        expectPutStatus(admin, emptyModel, 400, "模型");

        // 启用报告解读但 provider 已禁用
        long disabled = createProvider(admin, "已禁用提供商", BASE_URL, SECRET_KEY, false);
        ObjectNode disabledProvider = base.deepCopy();
        disabledProvider.put("reportProviderId", disabled);
        expectPutStatus(admin, disabledProvider, 400, "提供商");

        // temperature 越界
        ObjectNode badTemp = base.deepCopy();
        badTemp.put("reportTemperature", 3);
        expectPutStatus(admin, badTemp, 400, "reportTemperature");

        ObjectNode badCohortTemp = base.deepCopy();
        badCohortTemp.put("cohortTemperature", -1);
        expectPutStatus(admin, badCohortTemp, 400, "cohortTemperature");

        // maxTokens 越界
        ObjectNode badTokens = base.deepCopy();
        badTokens.put("cohortMaxTokens", 0);
        expectPutStatus(admin, badTokens, 400, "cohortMaxTokens");

        // timeoutSeconds 越界
        ObjectNode badTimeout = base.deepCopy();
        badTimeout.put("timeoutSeconds", 1);
        expectPutStatus(admin, badTimeout, 400, "timeoutSeconds");

        // 全部 400 均未落库（仍为默认无绑定态）
        JsonNode cfg = getLlmConfig(admin);
        assertTrue(cfg.path("reportProviderId").isNull(), "400 的配置不得写入 providerId: " + cfg);
    }

    @Test
    void testConnectionReturnsOkWithLatencyAndMasksKeyOnFailure() throws Exception {
        String admin = token("admin01");
        long id = createProvider(admin, "连接测试提供商", BASE_URL, SECRET_KEY, true);

        ObjectNode req = objectMapper.createObjectNode();
        req.put("providerId", id);
        req.put("model", REPORT_MODEL);

        // 成功
        JsonNode ok = expectOk(mockMvc.perform(post(LLM_TEST_URL)
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andReturn()).path("data");
        assertTrue(ok.path("ok").asBoolean(), "mock 正常时 ok 必须为 true: " + ok);
        assertTrue(ok.path("latencyMs").asLong() >= 0, "latencyMs 不得为负: " + ok);
        assertEquals(REPORT_MODEL, ok.path("model").asText());
        assertEquals(MOCK_REPLY, ok.path("reply").asText());

        // 失败：异常消息故意携带密钥 -> error 脱敏
        when(llmClient.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyInt(),
                anyString(), anyString()))
                .thenThrow(new IllegalStateException("LLM 调用失败: simulated failure, apiKey=" + SECRET_KEY));
        JsonNode fail = expectOk(mockMvc.perform(post(LLM_TEST_URL)
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andReturn()).path("data");
        assertFalse(fail.path("ok").asBoolean(), "mock 抛异常时 ok 必须为 false: " + fail);
        String error = fail.path("error").asText();
        assertFalse(error.isBlank(), "失败必须给出 error: " + fail);
        assertFalse(error.contains(SECRET_KEY), "error 不得包含密钥明文: " + error);
        assertTrue(error.contains("***"), "error 应将密钥脱敏为 ***: " + error);
    }

    // ==================================================================
    // 8.2 / 8.3 AI 洞察：路由 / 缓存 / 降级 / 数据范围
    // ==================================================================

    @Test
    void reportInterpretationRoutesViaReportProviderAndSecondCallHitsCache() throws Exception {
        String admin = token("admin01");
        long reportProv = createProvider(admin, "报告提供商", BASE_URL, SECRET_KEY, true);
        long cohortProv = createProvider(admin, "群体提供商", BASE_URL, SECRET_KEY, true);
        putRouting(admin, routingConfig(reportProv, cohortProv, true, true));
        String student = token("20260001");

        JsonNode first = getData(student, STUDENT_REPORT_AI_URL);
        assertTrue(first.path("available").asBoolean(), "首次解读应可用: " + first);
        assertEquals(MOCK_REPLY, first.path("content").asText(), "content 必须是 LLM 桩返回值: " + first);
        assertEquals(REPORT_MODEL, first.path("model").asText(), "报告解读必须按 report 路由: " + first);
        assertFalse(first.path("disclaimer").asText().isBlank(), "disclaimer 不得为空: " + first);

        JsonNode second = getData(student, STUDENT_REPORT_AI_URL);
        assertTrue(second.path("available").asBoolean(), "缓存命中后仍应可用: " + second);
        assertEquals(MOCK_REPLY, second.path("content").asText());

        verify(llmClient, times(1)).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void cohortInsightRoutesViaCohortProvider() throws Exception {
        String admin = token("admin01");
        long reportProv = createProvider(admin, "报告提供商", BASE_URL, SECRET_KEY, true);
        long cohortProv = createProvider(admin, "群体提供商", BASE_URL, SECRET_KEY, true);
        putRouting(admin, routingConfig(reportProv, cohortProv, true, true));

        JsonNode n = getData(admin, ADMIN_COHORT_AI_URL);
        assertTrue(n.path("available").asBoolean(), "admin01 群体洞察应可用: " + n);
        assertEquals(MOCK_REPLY, n.path("content").asText());
        assertEquals(COHORT_MODEL, n.path("model").asText(), "群体洞察必须按 cohort 路由: " + n);
        assertFalse(n.path("disclaimer").asText().isBlank(), "disclaimer 不得为空: " + n);
    }

    @Test
    void disablingRoutingDegradesInsightInsteadOf500() throws Exception {
        String admin = token("admin01");
        long provider = createProvider(admin, "提供商", BASE_URL, SECRET_KEY, true);
        putRouting(admin, routingConfig(provider, provider, true, true));

        ObjectNode disabled = objectMapper.createObjectNode();
        disabled.put("reportInsightEnabled", false);
        disabled.put("cohortInsightEnabled", false);
        disabled.put("reportTemperature", 0.7);
        disabled.put("reportMaxTokens", 800);
        disabled.put("cohortTemperature", 0.7);
        disabled.put("cohortMaxTokens", 800);
        disabled.put("timeoutSeconds", 30);
        putRouting(admin, disabled);

        JsonNode report = getData(token("20260001"), STUDENT_REPORT_AI_URL);
        assertFalse(report.path("available").asBoolean(), "关闭报告解读必须降级: " + report);
        assertTrue(report.path("reason").asText().contains("未启用"),
                "降级 reason 必须说明未启用: " + report.path("reason").asText());

        JsonNode cohort = getData(admin, ADMIN_COHORT_AI_URL);
        assertFalse(cohort.path("available").asBoolean(), "关闭群体洞察必须降级: " + cohort);
        assertTrue(cohort.path("reason").asText().contains("未启用"),
                "降级 reason 必须说明未启用: " + cohort.path("reason").asText());

        verify(llmClient, times(0)).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void defaultUnboundProviderDegradesFriendlyWithoutLlmCall() throws Exception {
        // 默认路由：子开关开启但未绑定任何提供商（provider_id 为空）
        JsonNode report = getData(token("20260001"), STUDENT_REPORT_AI_URL);
        assertFalse(report.path("available").asBoolean(), "未绑定提供商必须降级: " + report);
        assertTrue(report.path("reason").asText().contains("提供商"),
                "降级 reason 应说明未配置提供商: " + report.path("reason").asText());

        verify(llmClient, times(0)).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void insightEnforcesOwnershipAndAdminDataScope() throws Exception {
        // 数据范围校验先于提供商解析，无需配置提供商
        JsonNode studentOther = expectStatus(mockMvc.perform(get(STUDENT_OTHER_REPORT_AI_URL)
                .header("Authorization", "Bearer " + token("20260001"))).andReturn(), 403);
        assertTrue(studentOther.path("message").asText().contains("无权"),
                "403 文案应说明无权: " + studentOther.path("message").asText());

        JsonNode admin3Report = expectStatus(mockMvc.perform(get(ADMIN_REPORT_AI_URL)
                .header("Authorization", "Bearer " + token("admin03"))).andReturn(), 403);
        assertTrue(admin3Report.path("message").asText().contains("无权"),
                "403 文案应说明无权: " + admin3Report.path("message").asText());

        JsonNode admin3Cohort = expectStatus(mockMvc.perform(get(ADMIN_COHORT_AI_URL)
                .header("Authorization", "Bearer " + token("admin03"))).andReturn(), 403);
        assertTrue(admin3Cohort.path("message").asText().contains("无权"),
                "403 文案应说明无权: " + admin3Cohort.path("message").asText());
    }

    @Test
    void studentRoleDeniedForAdminLlmEndpoints() throws Exception {
        String student = token("20260001");

        JsonNode getConfig = expectStatus(mockMvc.perform(get(LLM_CONFIG_URL)
                .header("Authorization", "Bearer " + student)).andReturn(), 403);
        assertTrue(getConfig.path("message").asText().contains("管理端"),
                "403 文案应说明管理端越权: " + getConfig.path("message").asText());

        expectStatus(mockMvc.perform(put(LLM_CONFIG_URL)
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn(), 403);

        expectStatus(mockMvc.perform(post(LLM_TEST_URL)
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn(), 403);

        expectStatus(mockMvc.perform(get(PROVIDERS_URL)
                .header("Authorization", "Bearer " + student)).andReturn(), 403);

        expectStatus(mockMvc.perform(post(PROVIDERS_URL)
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn(), 403);
    }

    // ==================================================================
    // 通用辅助
    // ==================================================================

    private String token(String username) {
        String cached = tokenCache.get(username);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode n = loginRaw(username);
            String t = n.path("data").path("token").asText();
            assertTrue(t.length() > 0, "登录未返回 token: " + n);
            tokenCache.put(username, t);
            return t;
        } catch (Exception e) {
            throw new IllegalStateException("登录失败: " + username, e);
        }
    }

    private JsonNode loginRaw(String username) throws Exception {
        return expectOk(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}")).andReturn());
    }

    private long createProvider(String admin, String name, String baseUrl, String apiKey, boolean enabled)
            throws Exception {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", name);
        n.put("baseUrl", baseUrl);
        if (apiKey != null) {
            n.put("apiKey", apiKey);
        }
        n.put("enabled", enabled);
        JsonNode data = expectOk(mockMvc.perform(post(PROVIDERS_URL)
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(n))).andReturn()).path("data");
        return data.path("id").asLong();
    }

    private ObjectNode routingConfig(Long reportProviderId, Long cohortProviderId,
                                     boolean reportEnabled, boolean cohortEnabled) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("reportInsightEnabled", reportEnabled);
        if (reportProviderId != null) {
            n.put("reportProviderId", reportProviderId);
        }
        n.put("reportModel", REPORT_MODEL);
        n.put("reportTemperature", 0.7);
        n.put("reportMaxTokens", 800);
        n.put("cohortInsightEnabled", cohortEnabled);
        if (cohortProviderId != null) {
            n.put("cohortProviderId", cohortProviderId);
        }
        n.put("cohortModel", COHORT_MODEL);
        n.put("cohortTemperature", 0.7);
        n.put("cohortMaxTokens", 800);
        n.put("timeoutSeconds", 30);
        return n;
    }

    private void putRouting(String adminToken, ObjectNode payload) throws Exception {
        expectOk(mockMvc.perform(put(LLM_CONFIG_URL)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn());
    }

    private JsonNode expectPutStatus(String adminToken, ObjectNode payload, int status,
                                     String... messageParts) throws Exception {
        MvcResult r = mockMvc.perform(put(LLM_CONFIG_URL)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn();
        JsonNode n = expectStatus(r, status);
        for (String part : messageParts) {
            assertTrue(n.path("message").asText().contains(part),
                    "错误信息应包含 [" + part + "]: " + n.path("message").asText());
        }
        return n;
    }

    private JsonNode getLlmConfig(String adminToken) throws Exception {
        return getData(adminToken, LLM_CONFIG_URL);
    }

    private JsonNode getData(String token, String path) throws Exception {
        MvcResult r = mockMvc.perform(get(path)
                .header("Authorization", "Bearer " + token)).andReturn();
        return expectOk(r).path("data");
    }

    private JsonNode expectOk(MvcResult r) throws Exception {
        String body = utf8Body(r);
        assertEquals(200, r.getResponse().getStatus(), "期望 HTTP 200，实际响应: " + body);
        return objectMapper.readTree(body);
    }

    private JsonNode expectStatus(MvcResult r, int status) throws Exception {
        String body = utf8Body(r);
        assertEquals(status, r.getResponse().getStatus(), "期望 HTTP " + status + "，实际响应: " + body);
        return objectMapper.readTree(body);
    }

    private String utf8Body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
