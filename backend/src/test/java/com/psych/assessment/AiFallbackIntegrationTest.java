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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AI 多提供商自动降级集成测试（demo profile + H2 + MockMvc，@MockBean LlmClient）。
 *
 * <p>备用路由字段（report/cohort_fallback_*、fallback_enabled）DDL 由 DDL 文件维护；
 * 本测试在用例前以 {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} 兜底补列，验证：
 * 主提供商失败后自动降级到备用 provider/model 再试一次，仍失败才 available=false，
 * 且 reason 说明“主服务失败，已尝试备用服务”且不泄漏上游密钥。
 */
@SpringBootTest(properties = {
        "app.cache.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@DirtiesContext(classMode = AFTER_CLASS)
class AiFallbackIntegrationTest {

    private static final String SECRET_KEY = "sk-fallback-secret-987654";
    private static final String BASE_URL = "https://llm.example.test/v1";
    private static final String PRIMARY_MODEL = "primary-model";
    private static final String FALLBACK_MODEL = "fallback-model";
    private static final String FALLBACK_REPLY = "备用服务生成的解读内容";

    private static final String ADMIN_REPORT_AI_URL = "/api/admin/reports/1/ai-interpretation";
    private static final String LLM_CONFIG_URL = "/api/admin/llm-config";
    private static final String PROVIDERS_URL = "/api/admin/llm-providers";

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
    void ensureFallbackColumnsAndReset() {
        jdbcTemplate.execute("ALTER TABLE llm_config ADD COLUMN IF NOT EXISTS report_fallback_provider_id BIGINT");
        jdbcTemplate.execute("ALTER TABLE llm_config ADD COLUMN IF NOT EXISTS report_fallback_model VARCHAR(128)");
        jdbcTemplate.execute("ALTER TABLE llm_config ADD COLUMN IF NOT EXISTS cohort_fallback_provider_id BIGINT");
        jdbcTemplate.execute("ALTER TABLE llm_config ADD COLUMN IF NOT EXISTS cohort_fallback_model VARCHAR(128)");
        jdbcTemplate.execute("ALTER TABLE llm_config ADD COLUMN IF NOT EXISTS fallback_enabled TINYINT DEFAULT 0");

        int updated = jdbcTemplate.update(
                "UPDATE llm_config SET report_insight_enabled = 1, report_provider_id = NULL, report_model = '', "
                        + "report_temperature = 0.70, report_max_tokens = 800, "
                        + "cohort_insight_enabled = 1, cohort_provider_id = NULL, cohort_model = '', "
                        + "cohort_temperature = 0.70, cohort_max_tokens = 800, timeout_seconds = 30, "
                        + "report_fallback_provider_id = NULL, report_fallback_model = '', "
                        + "cohort_fallback_provider_id = NULL, cohort_fallback_model = '', fallback_enabled = 0, "
                        + "updated_by = NULL WHERE id = 1");
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO llm_config (id, report_insight_enabled, report_provider_id, report_model, "
                            + "report_temperature, report_max_tokens, cohort_insight_enabled, cohort_provider_id, "
                            + "cohort_model, cohort_temperature, cohort_max_tokens, timeout_seconds, "
                            + "report_fallback_provider_id, report_fallback_model, cohort_fallback_provider_id, "
                            + "cohort_fallback_model, fallback_enabled) "
                            + "VALUES (1, 1, NULL, '', 0.70, 800, 1, NULL, '', 0.70, 800, 30, NULL, '', NULL, '', 0)");
        }
        jdbcTemplate.update("DELETE FROM llm_provider");
        jdbcTemplate.update("DELETE FROM ai_insight_cache");

        reset(llmClient);
        clearInvocations(llmClient);
    }

    @Test
    void primaryFailureFallsBackToFallbackProviderAndSucceeds() throws Exception {
        String admin = token("admin01");
        long primary = createProvider(admin, "主提供商", SECRET_KEY);
        long fallback = createProvider(admin, "备用提供商", SECRET_KEY);

        // 主失败、备用成功
        when(llmClient.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyInt(),
                anyString(), anyString())).thenAnswer(inv -> {
            String model = inv.getArgument(2);
            if (PRIMARY_MODEL.equals(model)) {
                throw new IllegalStateException("主服务不可用");
            }
            return FALLBACK_REPLY;
        });

        putRouting(admin, routingConfig(primary, fallback, true));

        JsonNode n = getData(admin, ADMIN_REPORT_AI_URL);
        assertTrue(n.path("available").asBoolean(), "主失败后应降级到备用服务并成功: " + n);
        assertEquals(FALLBACK_REPLY, n.path("content").asText(), "内容必须来自备用服务: " + n);
        assertEquals(FALLBACK_MODEL, n.path("model").asText(), "model 必须是备用模型: " + n);

        // 主 + 备用各调用一次
        verify(llmClient, times(2)).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void bothPrimaryAndFallbackFailReturnsUnavailableWithFallbackNote() throws Exception {
        String admin = token("admin01");
        long primary = createProvider(admin, "主提供商", SECRET_KEY);
        long fallback = createProvider(admin, "备用提供商", SECRET_KEY);

        // 主、备用都失败（异常消息故意夹带密钥，最终 reason 必须脱敏）
        when(llmClient.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyInt(),
                anyString(), anyString()))
                .thenThrow(new IllegalStateException("LLM 服务错误, apiKey=" + SECRET_KEY));

        putRouting(admin, routingConfig(primary, fallback, true));

        JsonNode n = getData(admin, ADMIN_REPORT_AI_URL);
        assertFalse(n.path("available").asBoolean(), "主备均失败必须降级: " + n);
        String reason = n.path("reason").asText();
        assertTrue(reason.contains("主服务失败，已尝试备用服务"),
                "reason 必须说明已尝试备用服务: " + reason);
        assertFalse(reason.contains(SECRET_KEY), "reason 不得泄漏上游密钥: " + reason);

        verify(llmClient, times(2)).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void fallbackDisabledKeepsPrimaryOnlyAndReasonOmitsFallbackNote() throws Exception {
        String admin = token("admin01");
        long primary = createProvider(admin, "主提供商", SECRET_KEY);

        when(llmClient.chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(), anyInt(),
                anyString(), anyString()))
                .thenThrow(new IllegalStateException("LLM 服务错误, apiKey=" + SECRET_KEY));

        // fallbackEnabled=false（默认关闭），无备用
        putRouting(admin, routingConfig(primary, null, false));

        JsonNode n = getData(admin, ADMIN_REPORT_AI_URL);
        assertFalse(n.path("available").asBoolean(), "主失败且无备用必须降级: " + n);
        String reason = n.path("reason").asText();
        assertFalse(reason.contains("备用服务"), "未启用备用时 reason 不得提及备用服务: " + reason);
        assertFalse(reason.contains(SECRET_KEY), "reason 不得泄漏上游密钥: " + reason);

        verify(llmClient, times(1)).chat(anyString(), anyString(), anyString(), anyDouble(), anyInt(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void fallbackRoutingSaveValidatesProviderEnabledAndModel() throws Exception {
        String admin = token("admin01");
        long primary = createProvider(admin, "主提供商", SECRET_KEY);

        // 启用备用路由但备用 provider 不存在 -> 400
        ObjectNode noProvider = routingConfig(primary, null, true);
        noProvider.put("reportFallbackProviderId", 999999L);
        noProvider.put("reportFallbackModel", FALLBACK_MODEL);
        expectPutStatus(admin, noProvider, 400, "备用路由");

        // 启用备用路由但 model 为空 -> 400
        ObjectNode emptyModel = routingConfig(primary, null, true);
        emptyModel.put("reportFallbackProviderId", primary);
        emptyModel.put("reportFallbackModel", "");
        expectPutStatus(admin, emptyModel, 400, "模型");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private ObjectNode routingConfig(Long primaryId, Long fallbackId, boolean fallbackEnabled) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("reportInsightEnabled", true);
        n.put("reportProviderId", primaryId);
        n.put("reportModel", PRIMARY_MODEL);
        n.put("reportTemperature", 0.7);
        n.put("reportMaxTokens", 800);
        n.put("cohortInsightEnabled", false);
        n.put("cohortTemperature", 0.7);
        n.put("cohortMaxTokens", 800);
        n.put("timeoutSeconds", 30);
        n.put("fallbackEnabled", fallbackEnabled);
        if (fallbackId != null) {
            n.put("reportFallbackProviderId", fallbackId);
            n.put("reportFallbackModel", FALLBACK_MODEL);
        }
        return n;
    }

    private void putRouting(String admin, ObjectNode payload) throws Exception {
        expectOk(mockMvc.perform(put(LLM_CONFIG_URL)
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn());
    }

    private void expectPutStatus(String admin, ObjectNode payload, int status, String messagePart) throws Exception {
        MvcResult r = mockMvc.perform(put(LLM_CONFIG_URL)
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn();
        JsonNode n = expectStatus(r, status);
        assertTrue(n.path("message").asText().contains(messagePart),
                "错误信息应包含 [" + messagePart + "]: " + n.path("message").asText());
    }

    private long createProvider(String admin, String name, String apiKey) throws Exception {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", name);
        n.put("baseUrl", BASE_URL);
        n.put("apiKey", apiKey);
        n.put("enabled", true);
        JsonNode data = expectOk(mockMvc.perform(post(PROVIDERS_URL)
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(n))).andReturn()).path("data");
        return data.path("id").asLong();
    }

    private String token(String username) {
        String cached = tokenCache.get(username);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode n = expectOk(mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}")).andReturn());
            String t = n.path("data").path("token").asText();
            tokenCache.put(username, t);
            return t;
        } catch (Exception e) {
            throw new IllegalStateException("登录失败: " + username, e);
        }
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
