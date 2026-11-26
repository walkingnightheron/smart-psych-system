package com.psych.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 操作审计日志集成测试（demo profile + H2 + MockMvc）。
 *
 * <p>audit_log 表 DDL 由 DDL 文件统一维护，本测试在用例前以 {@code CREATE TABLE IF NOT EXISTS}
 * 兜底建表，保证不依赖 schema-h2.sql 是否已补列；真实落库前先清空，断言确定性。
 */
@SpringBootTest(properties = {
        "app.cache.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class AuditLogIntegrationTest {

    private static final String SECRET_KEY = "sk-audit-secret-123456";
    private static final String AUDIT_URL = "/api/admin/audit-logs";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Map<String, String> tokenCache = new LinkedHashMap<>();

    @BeforeEach
    void ensureAuditTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS audit_log ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + "operator_id BIGINT,"
                + "operator_username VARCHAR(64),"
                + "operator_real_name VARCHAR(64),"
                + "action_type VARCHAR(64) NOT NULL,"
                + "resource_type VARCHAR(64) NOT NULL,"
                + "resource_id BIGINT,"
                + "summary VARCHAR(255),"
                + "detail_json CLOB,"
                + "ip_address VARCHAR(64),"
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.update("DELETE FROM audit_log");
    }

    @Test
    void auditRecordsStudentCreateAndMasksSensitiveDetail() throws Exception {
        String admin = token("admin01");

        // 新建学生（触发 STUDENT_CREATE 审计）
        ObjectNode student = objectMapper.createObjectNode();
        student.put("username", "99990001");
        student.put("realName", "审计测试学生");
        student.put("deptClass", "软件2542班");
        student.put("password", "secret-pass-123");
        expectOk(mockMvc.perform(post("/api/admin/students")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(student))).andReturn());

        // 新建 LLM 提供商（触发 LLM_PROVIDER_CREATE 审计，detailJson 不得泄漏 apiKey）
        ObjectNode provider = objectMapper.createObjectNode();
        provider.put("name", "审计提供商");
        provider.put("baseUrl", "https://audit.example.test/v1");
        provider.put("apiKey", SECRET_KEY);
        provider.put("enabled", true);
        expectOk(mockMvc.perform(post("/api/admin/llm-providers")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(provider))).andReturn());

        // 查询审计列表：应包含上述两类操作，且不得泄漏 apiKey/密码明文
        JsonNode page = getAuditLogs(admin, "keyword", "", "page", "1", "pageSize", "50");
        assertTrue(page.path("total").asLong() >= 2, "至少应记录 2 条审计: " + page);
        String body = utf8Body(mockMvc.perform(get(AUDIT_URL)
                .param("pageSize", "50")
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertFalse(body.contains(SECRET_KEY), "审计响应不得泄漏 apiKey 明文: " + body);
        assertFalse(body.contains("secret-pass-123"), "审计响应不得泄漏密码明文: " + body);
        assertFalse(body.contains("\"apiKey\""), "审计 detailJson 不得出现 apiKey 字段: " + body);
    }

    @Test
    void auditSupportsActionTypeAndResourceTypeFilters() throws Exception {
        String admin = token("admin01");

        // 触发一次学生创建
        ObjectNode student = objectMapper.createObjectNode();
        student.put("username", "99990002");
        student.put("realName", "筛选测试学生");
        expectOk(mockMvc.perform(post("/api/admin/students")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(student))).andReturn());

        JsonNode all = getAuditLogs(admin, "pageSize", "50");
        assertTrue(all.path("total").asLong() >= 1, "应至少 1 条审计: " + all);

        JsonNode byAction = getAuditLogs(admin, "actionType", "STUDENT_CREATE", "pageSize", "50");
        assertTrue(byAction.path("total").asLong() >= 1, "actionType=STUDENT_CREATE 应命中: " + byAction);
        for (JsonNode item : byAction.path("list")) {
            assertEquals("STUDENT_CREATE", item.path("actionType").asText());
        }

        JsonNode byResource = getAuditLogs(admin, "resourceType", "STUDENT", "pageSize", "50");
        assertTrue(byResource.path("total").asLong() >= 1, "resourceType=STUDENT 应命中: " + byResource);

        // 不存在的 actionType 应命中 0 条
        JsonNode none = getAuditLogs(admin, "actionType", "NO_SUCH_ACTION", "pageSize", "50");
        assertEquals(0, none.path("total").asLong(), "不存在的 actionType 应命中 0 条: " + none);
    }

    @Test
    void studentRoleDeniedForAuditEndpoint() throws Exception {
        String student = token("20260001");
        JsonNode n = expectStatus(mockMvc.perform(get(AUDIT_URL)
                .header("Authorization", "Bearer " + student)).andReturn(), 403);
        assertTrue(n.path("message").asText().contains("管理端"),
                "学生访问审计接口应 403 管理端越权: " + n.path("message").asText());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private JsonNode getAuditLogs(String admin, String... params) throws Exception {
        var req = get(AUDIT_URL).header("Authorization", "Bearer " + admin);
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return expectOk(mockMvc.perform(req).andReturn()).path("data");
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
