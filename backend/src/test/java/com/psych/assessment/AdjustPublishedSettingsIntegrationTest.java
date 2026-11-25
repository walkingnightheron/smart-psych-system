package com.psych.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(properties = {
        "app.cache.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdjustPublishedSettingsIntegrationTest {

    private static final long SEEDED_ACTIVITY_1 = 1L;
    private static final long SEEDED_ACTIVITY_2 = 2L;
    private static final long SEEDED_VERSION_1 = 1L;

    private static final String SEED_TITLE_1 = "2026秋季学期软件2542班心理适应普查";
    private static final LocalDateTime SEED_START_TIME = LocalDateTime.of(2026, 11, 1, 0, 0, 0);
    private static final LocalDateTime SEED_END_TIME = LocalDateTime.of(2027, 12, 31, 23, 59, 59);
    private static final String SEED_AUTHORIZED_ADMINS_JSON = "[2]";
    private static final List<Long> SEED_ROSTER = List.of(4L, 5L, 6L);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Map<String, String> TOKEN_CACHE = new LinkedHashMap<>();

    private Long extraActivityId;

    @AfterEach
    void restoreSeededData() {
        jdbcTemplate.update(
                "UPDATE assessment_activity SET title = ?, start_time = ?, end_time = ?, status = 'PUBLISHED', authorized_admin_ids = ? WHERE id = ?",
                SEED_TITLE_1,
                Timestamp.valueOf(SEED_START_TIME),
                Timestamp.valueOf(SEED_END_TIME),
                SEED_AUTHORIZED_ADMINS_JSON,
                SEEDED_ACTIVITY_1);
        jdbcTemplate.update("DELETE FROM activity_participant WHERE activity_id = ?", SEEDED_ACTIVITY_1);
        for (Long userId : SEED_ROSTER) {
            jdbcTemplate.update("INSERT INTO activity_participant (activity_id, user_id) VALUES (?, ?)",
                    SEEDED_ACTIVITY_1, userId);
        }
        if (extraActivityId != null) {
            jdbcTemplate.update("DELETE FROM activity_participant WHERE activity_id = ?", extraActivityId);
            jdbcTemplate.update("DELETE FROM assessment_activity WHERE id = ?", extraActivityId);
            extraActivityId = null;
        }
    }

    // ==================================================================
    // 场景 1 — 已发布活动标题调整
    // ==================================================================

    @Test
    void adminRetitlePublishedActivityReturns200AndPersistsTitle() throws Exception {
        String admin = token("admin01");
        JsonNode before = activityDetail(admin, SEEDED_ACTIVITY_1);
        assertEquals("PUBLISHED", before.path("data").path("status").asText(), "前置条件：活动1必须为PUBLISHED状态");
        String originalTitle = before.path("data").path("title").asText();
        String newTitle = "2026秋季学期软件2542班心理适应普查（调整设置改名验收）";
        assertNotEquals(originalTitle, newTitle, "前置条件：新标题必须与原标题不同");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", newTitle);
        JsonNode ok = expectOk(putSettings(admin, SEEDED_ACTIVITY_1, body));
        assertEquals(0, ok.path("code").asInt(), "成功响应 code 应为 0: " + ok);
        assertEquals(newTitle, ok.path("data").path("title").asText(), "响应中的 title 必须是调整后的新标题");
        assertEquals("PUBLISHED", ok.path("data").path("status").asText(), "调整标题不得改变活动状态");

        JsonNode after = activityDetail(admin, SEEDED_ACTIVITY_1);
        assertEquals(newTitle, after.path("data").path("title").asText(), "调整后的标题必须落库并可回读");
        assertEquals(SEED_ROSTER, participantIds(after), "调整标题不得改动参与名单");
        assertEquals(66.67, after.path("data").path("completionRate").asDouble(), 0.0001,
                "调整标题不得改动完成率");
    }

    // ==================================================================
    // 场景 2 — 结束时间只允许延长
    // ==================================================================

    @Test
    void endTimeExtensionSucceedsButShorteningOrEqualReturns400() throws Exception {
        String admin = token("admin01");
        LocalDateTime originalEnd = parseTime(
                activityDetail(admin, SEEDED_ACTIVITY_1).path("data").path("endTime").asText());

        LocalDateTime extended = originalEnd.plusDays(30);
        assertTrue(extended.isAfter(originalEnd), "前置条件：延长值必须晚于原结束时间");
        ObjectNode extendBody = objectMapper.createObjectNode();
        extendBody.put("endTime", extended.format(FMT));
        JsonNode ok = expectOk(putSettings(admin, SEEDED_ACTIVITY_1, extendBody));
        assertEquals(extended, parseTime(ok.path("data").path("endTime").asText()),
                "延长成功后响应中的 endTime 必须等于请求的新结束时间");
        assertEquals(extended, parseTime(activityDetail(admin, SEEDED_ACTIVITY_1)
                        .path("data").path("endTime").asText()),
                "延长后的 endTime 必须落库并可回读");

        ObjectNode equalCurrentBody = objectMapper.createObjectNode();
        equalCurrentBody.put("endTime", extended.format(FMT));
        JsonNode equalCurrent = expectStatus(putSettings(admin, SEEDED_ACTIVITY_1, equalCurrentBody), 400);
        assertEquals(400, equalCurrent.path("code").asInt());
        assertTrue(equalCurrent.path("message").asText().contains("仅允许延长"),
                "等于当前结束时间必须以“仅允许延长”报错: " + equalCurrent.path("message").asText());

        ObjectNode equalOriginalBody = objectMapper.createObjectNode();
        equalOriginalBody.put("endTime", originalEnd.format(FMT));
        JsonNode equalOriginal = expectStatus(putSettings(admin, SEEDED_ACTIVITY_1, equalOriginalBody), 400);
        assertEquals(400, equalOriginal.path("code").asInt());
        assertTrue(equalOriginal.path("message").asText().contains("仅允许延长"),
                "等于原结束时间必须以“仅允许延长”报错: " + equalOriginal.path("message").asText());

        ObjectNode shorterBody = objectMapper.createObjectNode();
        shorterBody.put("endTime", originalEnd.minusDays(1).format(FMT));
        JsonNode shorter = expectStatus(putSettings(admin, SEEDED_ACTIVITY_1, shorterBody), 400);
        assertEquals(400, shorter.path("code").asInt());
        assertTrue(shorter.path("message").asText().contains("仅允许延长"),
                "缩短结束时间必须以“仅允许延长”报错: " + shorter.path("message").asText());

        assertEquals(extended, parseTime(activityDetail(admin, SEEDED_ACTIVITY_1)
                        .path("data").path("endTime").asText()),
                "被拒绝的缩短/等于请求不得改写 endTime");
    }

    // ==================================================================
    // 场景 3 — 追加学生：只增不减 + 完成率分母变化
    // ==================================================================

    @Test
    void appendStudentOnlyGrowsRosterAndCompletionRateBecomes50Percent() throws Exception {
        String admin = token("admin01");
        JsonNode before = activityDetail(admin, SEEDED_ACTIVITY_1);
        assertEquals(3, before.path("data").path("participantCount").asInt(), "前置条件：活动1名单为3人");
        assertEquals(2, before.path("data").path("completedCount").asInt(), "前置条件：活动1已完成2人");
        assertEquals(66.67, before.path("data").path("completionRate").asDouble(), 0.0001,
                "前置条件：调整前完成率为 66.67");
        assertEquals(SEED_ROSTER, participantIds(before), "前置条件：活动1名单必须是[4,5,6]");

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode add = body.putArray("addParticipantUserIds");
        add.add(7L);
        JsonNode ok = expectOk(putSettings(admin, SEEDED_ACTIVITY_1, body));

        assertEquals(List.of(4L, 5L, 6L, 7L), participantIds(ok),
                "名单只增不减：原[4,5,6]必须全部保留且成功追加7");
        assertEquals(4, ok.path("data").path("participantCount").asInt(), "追加后名单分母必须为4");
        assertEquals(2, ok.path("data").path("completedCount").asInt(), "追加名单不得改变已完成人数");
        assertEquals(50.00, ok.path("data").path("completionRate").asDouble(), 0.0001,
                "完成率必须变为 2/4 = 50.00");
        assertTrue(hasParticipant(ok, 4L), "已交卷学生4必须仍在名单内");
        assertTrue(hasParticipant(ok, 5L), "已交卷学生5必须仍在名单内");

        JsonNode stats = activityStatistics(admin, SEEDED_ACTIVITY_1);
        assertEquals(4, stats.path("data").path("participantCount").asInt(), "统计分母必须为4");
        assertEquals(2, stats.path("data").path("completedCount").asInt(), "统计完成数必须仍为2");
        assertEquals(50.00, stats.path("data").path("completionRate").asDouble(), 0.0001,
                "统计完成率必须为 50.00");

        assertEquals(2, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ?", SEEDED_ACTIVITY_1),
                "追加名单不得影响已有的2份答卷");
        assertEquals(1, countRows(
                        "SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                        SEEDED_ACTIVITY_1, 4L),
                "已交卷学生4的答卷必须保留");
        assertEquals(1, countRows(
                        "SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                        SEEDED_ACTIVITY_1, 5L),
                "已交卷学生5的答卷必须保留");
    }

    // ==================================================================
    // 场景 4 — 请求体无法改版本与开始时间
    // ==================================================================

    @Test
    void settingsBodyCannotChangeBoundVersionOrStartTime() throws Exception {
        String admin = token("admin01");
        JsonNode before = activityDetail(admin, SEEDED_ACTIVITY_1);
        long versionIdBefore = before.path("data").path("questionnaireVersionId").asLong();
        int versionNumBefore = before.path("data").path("versionNum").asInt();
        LocalDateTime startBefore = parseTime(before.path("data").path("startTime").asText());
        assertEquals(SEEDED_VERSION_1, versionIdBefore, "前置条件：活动1绑定版本1");

        ObjectNode bodyWithoutVersionFields = objectMapper.createObjectNode();
        bodyWithoutVersionFields.put("title", "调整设置验收-请求体不含版本与开始时间字段");
        JsonNode ok1 = expectOk(putSettings(admin, SEEDED_ACTIVITY_1, bodyWithoutVersionFields));
        assertEquals(versionIdBefore, ok1.path("data").path("questionnaireVersionId").asLong(),
                "请求体不含 questionnaireVersionId 时绑定版本不得改变");
        assertEquals(versionNumBefore, ok1.path("data").path("versionNum").asInt(),
                "请求体不含 questionnaireVersionId 时版本号不得改变");
        assertEquals(startBefore, parseTime(ok1.path("data").path("startTime").asText()),
                "请求体不含 startTime 时开始时间不得改变");

        ObjectNode bodyWithVersionFields = objectMapper.createObjectNode();
        bodyWithVersionFields.put("title", "调整设置验收-尝试夹带版本与开始时间字段");
        bodyWithVersionFields.put("questionnaireVersionId", 999999L);
        bodyWithVersionFields.put("startTime", "2020-01-01 00:00:00");
        JsonNode ok2 = expectOk(putSettings(admin, SEEDED_ACTIVITY_1, bodyWithVersionFields));
        assertEquals(versionIdBefore, ok2.path("data").path("questionnaireVersionId").asLong(),
                "夹带的 questionnaireVersionId 必须被忽略，绑定版本不得改变");
        assertEquals(versionNumBefore, ok2.path("data").path("versionNum").asInt(),
                "夹带的问卷版本不得改变版本号");
        assertEquals(startBefore, parseTime(ok2.path("data").path("startTime").asText()),
                "夹带的 startTime 必须被忽略，开始时间不得改变");

        JsonNode after = activityDetail(admin, SEEDED_ACTIVITY_1);
        assertEquals(versionIdBefore, after.path("data").path("questionnaireVersionId").asLong(),
                "回读时绑定问卷版本必须仍是原版本");
        assertEquals(versionNumBefore, after.path("data").path("versionNum").asInt(),
                "回读时版本号必须不变");
        assertEquals(startBefore, parseTime(after.path("data").path("startTime").asText()),
                "回读时开始时间必须不变");
        assertEquals("PUBLISHED", after.path("data").path("status").asText(),
                "调整设置不得改变活动状态");
    }

    // ==================================================================
    // 场景 5 — DRAFT / CLOSED 活动拒绝调整
    // ==================================================================

    @Test
    void draftAndClosedActivitiesRejectSettingsWith400() throws Exception {
        String admin = token("admin01");

        JsonNode draftBefore = activityDetail(admin, SEEDED_ACTIVITY_2);
        assertEquals("DRAFT", draftBefore.path("data").path("status").asText(), "前置条件：活动2为DRAFT");
        String draftTitle = draftBefore.path("data").path("title").asText();
        ObjectNode draftBody = objectMapper.createObjectNode();
        draftBody.put("title", "草稿活动非法调整标题");
        JsonNode draftRejected = expectStatus(putSettings(admin, SEEDED_ACTIVITY_2, draftBody), 400);
        assertEquals(400, draftRejected.path("code").asInt());
        assertTrue(draftRejected.path("message").asText().contains("当前状态为: DRAFT"),
                "草稿活动必须精确报出当前状态: " + draftRejected.path("message").asText());
        assertEquals(draftTitle, activityDetail(admin, SEEDED_ACTIVITY_2).path("data").path("title").asText(),
                "被拒绝的草稿调整不得改写标题");
        assertEquals("DRAFT", activityDetail(admin, SEEDED_ACTIVITY_2).path("data").path("status").asText(),
                "被拒绝的草稿调整不得改变状态");

        long closedId = createActivity(admin, "调整设置验收-已关闭活动", SEEDED_VERSION_1,
                List.of(4L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        extraActivityId = closedId;
        publishActivity(admin, closedId);
        closeActivity(admin, closedId);
        JsonNode closedDetail = activityDetail(admin, closedId);
        assertEquals("CLOSED", closedDetail.path("data").path("status").asText(), "前置条件：该活动已关闭");
        String closedTitle = closedDetail.path("data").path("title").asText();

        ObjectNode closedBody = objectMapper.createObjectNode();
        closedBody.put("title", "已关闭活动非法调整标题");
        JsonNode closedRejected = expectStatus(putSettings(admin, closedId, closedBody), 400);
        assertEquals(400, closedRejected.path("code").asInt());
        assertTrue(closedRejected.path("message").asText().contains("当前状态为: CLOSED"),
                "已关闭活动必须精确报出当前状态: " + closedRejected.path("message").asText());
        JsonNode closedAfter = activityDetail(admin, closedId);
        assertEquals("CLOSED", closedAfter.path("data").path("status").asText(),
                "被拒绝的调整不得改变已关闭状态");
        assertEquals(closedTitle, closedAfter.path("data").path("title").asText(),
                "被拒绝的调整不得改写已关闭活动标题");
    }

    // ==================================================================
    // 场景 6 — 未授权管理员 / 学生角色 403
    // ==================================================================

    @Test
    void unauthorizedAdminAndStudentRoleGet403() throws Exception {
        String owner = token("admin01");
        String titleBefore = activityDetail(owner, SEEDED_ACTIVITY_1).path("data").path("title").asText();

        ObjectNode adminBody = objectMapper.createObjectNode();
        adminBody.put("title", "未授权管理员越权改名");
        JsonNode adminRejected = expectStatus(
                putSettings(token("admin03"), SEEDED_ACTIVITY_1, adminBody), 403);
        assertEquals(403, adminRejected.path("code").asInt());
        assertTrue(adminRejected.path("message").asText().contains("无权访问他人未授权"),
                "未授权管理员403文案必须说明数据越权: " + adminRejected.path("message").asText());

        ObjectNode studentBody = objectMapper.createObjectNode();
        studentBody.put("title", "学生越权改名");
        JsonNode studentRejected = expectStatus(
                putSettings(token("20260001"), SEEDED_ACTIVITY_1, studentBody), 403);
        assertEquals(403, studentRejected.path("code").asInt());
        assertTrue(studentRejected.path("message").asText().contains("管理端"),
                "学生角色403文案必须说明角色越权: " + studentRejected.path("message").asText());

        assertEquals(titleBefore, activityDetail(owner, SEEDED_ACTIVITY_1).path("data").path("title").asText(),
                "越权请求不得改写活动标题");
        assertEquals(SEED_ROSTER, participantIds(activityDetail(owner, SEEDED_ACTIVITY_1)),
                "越权请求不得改动参与名单");
    }

    // ==================================================================
    // 场景 7 — 追加非学生角色 400
    // ==================================================================

    @Test
    void addingNonStudentUserToRosterReturns400() throws Exception {
        String admin = token("admin01");
        JsonNode before = activityDetail(admin, SEEDED_ACTIVITY_1);
        List<Long> idsBefore = participantIds(before);
        int countBefore = before.path("data").path("participantCount").asInt();

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode add = body.putArray("addParticipantUserIds");
        add.add(2L);
        JsonNode rejected = expectStatus(putSettings(admin, SEEDED_ACTIVITY_1, body), 400);
        assertEquals(400, rejected.path("code").asInt());
        assertTrue(rejected.path("message").asText().contains("不是学生角色"),
                "非学生入名单必须精确报错: " + rejected.path("message").asText());

        JsonNode after = activityDetail(admin, SEEDED_ACTIVITY_1);
        assertEquals(countBefore, after.path("data").path("participantCount").asInt(),
                "非学生入名单被拒后名单人数不得变化");
        assertEquals(idsBefore, participantIds(after),
                "非学生入名单被拒后名单成员不得变化");
        assertEquals(66.67, after.path("data").path("completionRate").asDouble(), 0.0001,
                "非学生入名单被拒后完成率不得变化");
    }

    // ==================================================================
    // 场景 8 — authorizedAdminIds 空列表清空 / null 保持
    // ==================================================================

    @Test
    void authorizedAdminIdsNullKeepsAccessAndEmptyListRevokesIt() throws Exception {
        String owner = token("admin01");
        String authorized = token("admin02");

        JsonNode before = activityDetail(owner, SEEDED_ACTIVITY_1);
        assertEquals(List.of(2L), authorizedAdminIds(before),
                "前置条件：活动1授权管理员必须包含admin02");
        expectOk(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1)
                .header("Authorization", "Bearer " + authorized)).andReturn());

        ObjectNode nullBody = objectMapper.createObjectNode();
        nullBody.putNull("authorizedAdminIds");
        JsonNode nullOk = expectOk(putSettings(owner, SEEDED_ACTIVITY_1, nullBody));
        assertEquals(0, nullOk.path("code").asInt());
        assertEquals(List.of(2L), authorizedAdminIds(nullOk),
                "authorizedAdminIds 传 null 必须保持原授权不变");
        expectOk(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1)
                .header("Authorization", "Bearer " + authorized)).andReturn());

        ObjectNode clearBody = objectMapper.createObjectNode();
        clearBody.putArray("authorizedAdminIds");
        JsonNode clearOk = expectOk(putSettings(owner, SEEDED_ACTIVITY_1, clearBody));
        assertEquals(0, clearOk.path("code").asInt());
        assertEquals(List.of(), authorizedAdminIds(clearOk),
                "authorizedAdminIds 传空列表必须清空授权");

        JsonNode revoked = expectStatus(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1)
                .header("Authorization", "Bearer " + authorized)).andReturn(), 403);
        assertEquals(403, revoked.path("code").asInt());
        assertTrue(revoked.path("message").asText().contains("无权访问他人未授权"),
                "授权被清空后admin02必须403: " + revoked.path("message").asText());

        expectOk(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1)
                .header("Authorization", "Bearer " + owner)).andReturn());
    }

    // ==================================================================
    // 通用辅助
    // ==================================================================

    private String token(String username) {
        String cached = TOKEN_CACHE.get(username);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode n = loginRaw(username);
            String t = n.path("data").path("token").asText();
            assertTrue(t.length() > 0, "登录未返回 token: " + n);
            TOKEN_CACHE.put(username, t);
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

    private JsonNode activityDetail(String token, long activityId) throws Exception {
        return expectOk(mockMvc.perform(get("/api/admin/activities/" + activityId)
                .header("Authorization", "Bearer " + token)).andReturn());
    }

    private JsonNode activityStatistics(String token, long activityId) throws Exception {
        return expectOk(mockMvc.perform(get("/api/admin/activities/" + activityId + "/statistics")
                .header("Authorization", "Bearer " + token)).andReturn());
    }

    private MvcResult putSettings(String token, long activityId, ObjectNode body) throws Exception {
        return mockMvc.perform(put("/api/admin/activities/" + activityId + "/settings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private long createActivity(String adminToken, String title, long versionId,
                                List<Long> participantIds, LocalDateTime start, LocalDateTime end) throws Exception {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("title", title);
        n.put("questionnaireVersionId", versionId);
        n.put("startTime", start.format(FMT));
        n.put("endTime", end.format(FMT));
        ArrayNode p = n.putArray("participantUserIds");
        for (Long id : participantIds) {
            p.add(id.longValue());
        }
        n.putArray("authorizedAdminIds");
        JsonNode resp = expectOk(mockMvc.perform(post("/api/admin/activities")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(n))).andReturn());
        long id = resp.path("data").path("id").asLong();
        assertTrue(id > 0, "活动创建失败: " + resp);
        return id;
    }

    private void publishActivity(String adminToken, long activityId) throws Exception {
        expectOk(mockMvc.perform(post("/api/admin/activities/" + activityId + "/publish")
                .header("Authorization", "Bearer " + adminToken)).andReturn());
    }

    private void closeActivity(String adminToken, long activityId) throws Exception {
        expectOk(mockMvc.perform(post("/api/admin/activities/" + activityId + "/close")
                .header("Authorization", "Bearer " + adminToken)).andReturn());
    }

    private int countRows(String sql, Object... args) {
        Integer c = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    private static List<Long> participantIds(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.path("data").path("participantUserIds").forEach(node -> ids.add(node.asLong()));
        ids.sort(Long::compareTo);
        return ids;
    }

    private static List<Long> authorizedAdminIds(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.path("data").path("authorizedAdminIds").forEach(node -> ids.add(node.asLong()));
        ids.sort(Long::compareTo);
        return ids;
    }

    private static boolean hasParticipant(JsonNode response, long userId) {
        for (JsonNode p : response.path("data").path("participants")) {
            if (p.path("id").asLong() == userId) {
                return true;
            }
        }
        return false;
    }

    private static LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            throw new AssertionError("时间字段为空，无法解析: [" + text + "]");
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (RuntimeException isoFailure) {
            return LocalDateTime.parse(text, FMT);
        }
    }
}
