package com.psych.assessment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.psych.assessment.cache.CacheService;
import com.psych.assessment.cache.CacheService.CacheMetricsVO;
import com.psych.assessment.dto.H5AndReportDtos.AnswerInput;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireDetailVO;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.entity.AnswerSheet;
import com.psych.assessment.entity.AssessmentActivity;
import com.psych.assessment.entity.AssessmentReport;
import com.psych.assessment.entity.QuestionnaireVersion;
import com.psych.assessment.mq.MqFaultInjector;
import com.psych.assessment.mq.ReportConsumerService;
import com.psych.assessment.mq.ReportTaskDispatcher;
import com.psych.assessment.repository.ActivityParticipantRepository;
import com.psych.assessment.repository.AnswerItemRepository;
import com.psych.assessment.repository.AnswerSheetRepository;
import com.psych.assessment.repository.AssessmentActivityRepository;
import com.psych.assessment.repository.AssessmentReportRepository;
import com.psych.assessment.repository.QuestionnaireVersionRepository;
import com.psych.assessment.scoring.ScoringEngine;
import com.psych.assessment.scoring.ScoringEngine.ScoringResult;
import com.psych.assessment.service.QuestionnaireService;
import com.psych.assessment.service.SubmissionFaultInjector;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 全链路验收测试（demo profile + H2 内存库 + MockMvc）。
 *
 * <p>覆盖任务书 8 大核心验收场景：
 * <pre>
 * REQ01  身份与数据范围越权（401/403）
 * REQ02  非法问卷配置精确定位报错
 * REQ03  已发布版本快照与草稿隔离
 * REQ04  活动状态机 / 空名单 / 时间窗
 * REQ05  H5 动态问卷脱敏、非法作答拒绝
 * REQ06  明细故障整单事务回滚 + 并发重复提交 409
 * REQ07  3 人名单 2 份答卷 = 66.67%
 * REQ08  空名单 0.00% 防除零；PENDING/FAILED 不进入区间分布
 * REQ09  缓存命中回源计数 + 关闭活动防旧缓存穿透
 * REQ10  MQ 投递失败保留答卷 / 重复消费幂等 / 受控重试 3 次上限
 * </pre>
 *
 * <p>测试隔离策略：所有会产生写入的场景各自新建独立活动（种子活动 1 保持只读，
 * 专供 REQ07/REQ09 断言）；所有故障注入器与 MQ 传输模式在 {@link #resetFaults()} 中强制复位。
 */
@SpringBootTest(properties = {
        // 测试使用纯内存缓存，剔除外部 Redis 依赖，保证 REQ09 命中/回源计数确定性
        "app.cache.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class SystemAcceptanceIntegrationTest {

    private static final long SEEDED_ACTIVITY_1 = 1L;
    private static final long SEEDED_VERSION_1 = 1L;

    private static final String[] SEED_A_ANSWERS = {"A", "B", "A", "D", "B", "C"}; // 9.00 GOOD
    private static final String[] SEED_B_ANSWERS = {"C", "C", "B", "B", "C", "B"}; // 17.00 MODERATE
    private static final String[] ALL_A_ANSWERS = {"A", "A", "A", "A", "A", "A"}; // 12.00 MILD

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheService cacheService;
    @Autowired
    private MqFaultInjector mqFaultInjector;
    @Autowired
    private SubmissionFaultInjector submissionFaultInjector;
    @Autowired
    private ReportTaskDispatcher reportTaskDispatcher;
    @Autowired
    private ReportConsumerService reportConsumerService;

    @Autowired
    private AnswerSheetRepository sheetRepository;
    @Autowired
    private AnswerItemRepository itemRepository;
    @Autowired
    private AssessmentReportRepository reportRepository;
    @Autowired
    private AssessmentActivityRepository activityRepository;
    @Autowired
    private ActivityParticipantRepository participantRepository;
    @Autowired
    private QuestionnaireVersionRepository versionRepository;
    @Autowired
    private QuestionnaireService questionnaireService;
    @Autowired
    private ScoringEngine scoringEngine;

    private final Map<String, String> tokenCache = new LinkedHashMap<>();

    private String originalTransport;

    @BeforeEach
    void setUp() {
        originalTransport = reportTaskDispatcher.getTransportMode();
        reportConsumerService.resetIdempotentSkipCount();
    }

    @AfterEach
    void resetFaults() {
        // 任何断言失败都必须复位故障注入器与传输模式，避免污染后续场景
        submissionFaultInjector.reset();
        mqFaultInjector.resetAll();
        reportTaskDispatcher.setTransportMode(originalTransport);
    }

    // ==================================================================
    // 场景 1 — REQ01 身份认证与数据权限
    // ==================================================================

    @Test
    void req01_missingOrInvalidTokenReturns401() throws Exception {
        expectStatus(mockMvc.perform(get("/api/auth/me")).andReturn(), 401);
        expectStatus(mockMvc.perform(get("/api/admin/dashboard/summary")).andReturn(), 401);
        expectStatus(mockMvc.perform(get("/api/admin/activities")).andReturn(), 401);
        expectStatus(mockMvc.perform(get("/api/h5/activities")).andReturn(), 401);
        expectStatus(mockMvc.perform(get("/api/h5/records")).andReturn(), 401);

        MockHttpServletRequestBuilder bad = get("/api/auth/me").header("Authorization", "Bearer not-a-valid-jwt");
        JsonNode n = expectStatus(mockMvc.perform(bad).andReturn(), 401);
        assertEquals(401, n.path("code").asInt());

        MockHttpServletRequestBuilder empty = get("/api/auth/me").header("Authorization", "Bearer    ");
        expectStatus(mockMvc.perform(empty).andReturn(), 401);

        // 登录接口无需令牌，仍可正常访问
        assertEquals("admin01", loginRaw("admin01").path("data").path("user").path("username").asText());
    }

    @Test
    void req01_roleBoundaryReturns403() throws Exception {
        String student = token("20260001");
        String admin = token("admin01");

        JsonNode n1 = expectStatus(mockMvc.perform(get("/api/admin/dashboard/summary")
                .header("Authorization", "Bearer " + student)).andReturn(), 403);
        assertTrue(n1.path("message").asText().contains("管理端"), "403 文案应说明角色越权: " + n1.path("message").asText());
        expectStatus(mockMvc.perform(get("/api/admin/activities")
                .header("Authorization", "Bearer " + student)).andReturn(), 403);
        expectStatus(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1)
                .header("Authorization", "Bearer " + student)).andReturn(), 403);
        expectStatus(mockMvc.perform(get("/api/admin/questionnaires")
                .header("Authorization", "Bearer " + student)).andReturn(), 403);

        JsonNode n2 = expectStatus(mockMvc.perform(get("/api/h5/activities")
                .header("Authorization", "Bearer " + admin)).andReturn(), 403);
        assertTrue(n2.path("message").asText().contains("学生参与端"), "403 文案应说明角色越权: " + n2.path("message").asText());
        expectStatus(mockMvc.perform(get("/api/h5/records")
                .header("Authorization", "Bearer " + admin)).andReturn(), 403);
        expectStatus(mockMvc.perform(get("/api/h5/activities/" + SEEDED_ACTIVITY_1 + "/detail")
                .header("Authorization", "Bearer " + admin)).andReturn(), 403);
    }

    @Test
    void req01_adminDataScopeEnforcedForUnauthorizedAdmin() throws Exception {
        String owner = token("admin01");
        String authorized = token("admin02");
        String unauthorized = token("admin03");

        // 未授权管理员：活动详情 / 统计 / 报告 全部 403
        expectStatus(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1)
                .header("Authorization", "Bearer " + unauthorized)).andReturn(), 403);
        expectStatus(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1 + "/statistics")
                .header("Authorization", "Bearer " + unauthorized)).andReturn(), 403);
        expectStatus(mockMvc.perform(get("/api/admin/reports/1")
                .header("Authorization", "Bearer " + unauthorized)).andReturn(), 403);

        // 已授权管理员（authorized_admin_ids 包含 admin02=2）与 owner 均可访问
        assertEquals(SEEDED_ACTIVITY_1, expectOk(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1)
                .header("Authorization", "Bearer " + authorized)).andReturn()).path("data").path("id").asLong());
        JsonNode statsAuthorized = expectOk(mockMvc.perform(
                get("/api/admin/activities/" + SEEDED_ACTIVITY_1 + "/statistics")
                        .header("Authorization", "Bearer " + authorized)).andReturn());
        assertEquals(66.67, statsAuthorized.path("data").path("completionRate").asDouble(), 0.0001);
        expectOk(mockMvc.perform(get("/api/admin/reports/1")
                .header("Authorization", "Bearer " + authorized)).andReturn());

        JsonNode ownerDetail = expectOk(mockMvc.perform(get("/api/admin/activities/" + SEEDED_ACTIVITY_1)
                .header("Authorization", "Bearer " + owner)).andReturn());
        assertEquals(3, ownerDetail.path("data").path("participantCount").asInt());
    }

    @Test
    void req01_studentCrossUserRecordReturns403() throws Exception {
        String studentA = token("20260001");
        String studentB = token("20260002");

        // 学生 20260001 只能读自己的 sheet 1 / report 1
        expectOk(mockMvc.perform(get("/api/h5/records/1")
                .header("Authorization", "Bearer " + studentA)).andReturn());
        expectOk(mockMvc.perform(get("/api/h5/reports/1")
                .header("Authorization", "Bearer " + studentA)).andReturn());

        // 越权读取学生 20260002 的 sheet 2 / report 2 -> 403
        JsonNode n1 = expectStatus(mockMvc.perform(get("/api/h5/records/2")
                .header("Authorization", "Bearer " + studentA)).andReturn(), 403);
        assertTrue(n1.path("message").asText().contains("无权"), "403 文案应为无权访问: " + n1.path("message").asText());
        expectStatus(mockMvc.perform(get("/api/h5/reports/2")
                .header("Authorization", "Bearer " + studentA)).andReturn(), 403);

        // 反向：20260002 越权读 20260001 的记录同样 403
        expectStatus(mockMvc.perform(get("/api/h5/records/1")
                .header("Authorization", "Bearer " + studentB)).andReturn(), 403);
    }

    // ==================================================================
    // 场景 2 — REQ02 非法配置精确报错 & REQ03 版本快照隔离
    // ==================================================================

    @Test
    void req02_invalidQuestionnaireDraftRejectedWithPreciseErrors() throws Exception {
        String admin = token("admin01");

        // 1) 空题干 -> 定位到第1题[stem]
        String codeStem = "ACCEPT_BAD_STEM";
        JsonNode r1 = expectStatus(postQuestionnaire(admin, withBlankStem(validPayload(codeStem))), 400);
        assertTrue(r1.path("message").asText().contains("第1题[stem]"),
                "错误信息必须定位到题号: " + r1.path("message").asText());
        assertTrue(r1.path("message").asText().contains("题干内容不能为空"),
                "错误信息必须说明原因: " + r1.path("message").asText());

        // 2) 单选项 -> 定位到第1题[options]
        String codeOpt = "ACCEPT_BAD_OPTION";
        JsonNode r2 = expectStatus(postQuestionnaire(admin, withSingleOption(validPayload(codeOpt))), 400);
        assertTrue(r2.path("message").asText().contains("第1题[options]"),
                "错误信息必须定位到题号: " + r2.path("message").asText());
        assertTrue(r2.path("message").asText().contains("至少需要配置2个选项"),
                "错误信息必须说明原因: " + r2.path("message").asText());

        // 3) 负分值 -> 定位到第1题选项A[score]
        String codeScore = "ACCEPT_BAD_SCORE";
        JsonNode r3 = expectStatus(postQuestionnaire(admin, withNegativeScore(validPayload(codeScore))), 400);
        assertTrue(r3.path("message").asText().contains("第1题选项A[score]"),
                "错误信息必须定位到选项: " + r3.path("message").asText());
        assertTrue(r3.path("message").asText().contains("分值不能小于0"),
                "错误信息必须说明原因: " + r3.path("message").asText());

        // 4) 重叠区间 -> 定位到区间编码
        String codeLevel = "ACCEPT_BAD_LEVEL";
        JsonNode r4 = expectStatus(postQuestionnaire(admin, withOverlappingLevels(validPayload(codeLevel))), 400);
        assertTrue(r4.path("message").asText().contains("区间"),
                "错误信息必须定位到区间: " + r4.path("message").asText());
        assertTrue(r4.path("message").asText().contains("分值边界存在重叠"),
                "错误信息必须描述重叠: " + r4.path("message").asText());

        // 4 个非法草稿均未落库
        for (String code : List.of(codeStem, codeOpt, codeScore, codeLevel)) {
            assertEquals(0, countRows("SELECT COUNT(*) FROM questionnaire WHERE code = ?", code),
                    "非法问卷草稿不应落库: " + code);
        }
    }

    @Test
    void req03_publishedVersionSnapshotIsolatedFromLaterDraftAndPublish() throws Exception {
        String admin = token("admin01");
        String student = token("20260001");

        String code = "ACCEPT_SNAPSHOT_ISO";
        ObjectNode base = validPayload(code);
        String originalStem1 = base.path("questions").get(0).path("stem").asText();

        long questionnaireId = expectOk(postQuestionnaire(admin, base)).path("data").path("id").asLong();
        assertTrue(questionnaireId > 0, "问卷草稿创建失败");

        JsonNode v1 = expectOk(publishVersion(admin, questionnaireId));
        long v1Id = v1.path("data").path("id").asLong();
        assertEquals(1, v1.path("data").path("versionNum").asInt());
        assertEquals("R-TEST.1", v1.path("data").path("ruleVersion").asText());
        assertEquals(originalStem1, v1.path("data").path("questions").get(0).path("stem").asText());

        long activityId = createActivity(admin, "REQ03 版本快照隔离验收活动", v1Id,
                List.of(4L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        // 发布 v2 之前：活动绑定的快照为 v1
        JsonNode detailBefore = expectOk(h5Detail(student, activityId));
        assertEquals(1, detailBefore.path("data").path("versionNum").asInt());
        assertEquals(originalStem1, detailBefore.path("data").path("questions").get(0).path("stem").asText());

        // 修改草稿（题干、标题、选项分值全部变化）并再次发布 -> v2
        ObjectNode modified = base.deepCopy();
        modified.put("title", "REQ03 已修订标题（V2 草稿）");
        ArrayNode newQuestions = modified.putArray("questions");
        for (int i = 1; i <= 2; i++) {
            ObjectNode q = newQuestions.addObject();
            q.put("questionNo", i);
            q.put("stem", "【V2修订】第" + i + "题题干");
            q.put("questionType", "SINGLE");
            q.put("dimensionCode", i == 1 ? "STRESS" : "MOOD");
            q.put("isReverse", 0);
            q.put("required", 1);
            ArrayNode opts = q.putArray("options");
            for (int j = 0; j < 4; j++) {
                ObjectNode o = opts.addObject();
                o.put("optCode", String.valueOf((char) ('A' + j)));
                o.put("label", "选项" + (char) ('A' + j));
                o.put("score", 10 + j); // 分值也大幅变化
                o.put("sort", j + 1);
            }
        }
        expectOk(putQuestionnaire(admin, questionnaireId, modified));
        JsonNode v2 = expectOk(publishVersion(admin, questionnaireId));
        assertEquals(2, v2.path("data").path("versionNum").asInt());
        assertNotEquals("R-TEST.1", v2.path("data").path("ruleVersion").asText());

        // H5 绑定快照必须仍是 v1 的原始题干与规则版本
        JsonNode detailAfter = expectOk(h5Detail(student, activityId));
        assertEquals(1, detailAfter.path("data").path("versionNum").asInt(), "活动绑定版本号不得随草稿变化");
        assertEquals("R-TEST.1", detailAfter.path("data").path("ruleVersion").asText());
        assertEquals(originalStem1, detailAfter.path("data").path("questions").get(0).path("stem").asText(),
                "历史活动读取到的题目快照必须保持不变");
        assertNotEquals("【V2修订】第1题题干",
                detailAfter.path("data").path("questions").get(0).path("stem").asText());

        // v1 不可变版本详情接口同样保持原样，v2 才是新题干
        JsonNode snapV1 = expectOk(mockMvc.perform(get("/api/admin/questionnaire-versions/" + v1Id)
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals(1, snapV1.path("data").path("versionNum").asInt());
        assertEquals(originalStem1, snapV1.path("data").path("questions").get(0).path("stem").asText());
        assertEquals(0, new BigDecimal("1").compareTo(
                snapV1.path("data").path("questions").get(0).path("options").get(0).path("score").decimalValue()),
                "v1 快照里的选项分值不得被草稿修改污染");

        long v2Id = v2.path("data").path("id").asLong();
        JsonNode snapV2 = expectOk(mockMvc.perform(get("/api/admin/questionnaire-versions/" + v2Id)
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals("【V2修订】第1题题干", snapV2.path("data").path("questions").get(0).path("stem").asText());

        // 版本列表包含 2 个版本
        JsonNode versions = expectOk(mockMvc.perform(get("/api/admin/questionnaires/" + questionnaireId + "/versions")
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals(2, versions.path("data").size());

        // 活动详情（管理端）也仍指向 v1
        JsonNode adminDetail = expectOk(mockMvc.perform(get("/api/admin/activities/" + activityId)
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals(1, adminDetail.path("data").path("versionNum").asInt());

        // 草稿回读已是 v2 内容
        JsonNode draftNode = expectOk(mockMvc.perform(get("/api/admin/questionnaires/" + questionnaireId)
                .header("Authorization", "Bearer " + admin)).andReturn()).path("data");
        QuestionnaireDetailVO draft = objectMapper.treeToValue(draftNode, QuestionnaireDetailVO.class);
        assertEquals("【V2修订】第1题题干", draft.getQuestions().get(0).getStem());
    }

    // ==================================================================
    // 场景 3 — REQ04 活动状态机 / 名单 / 时间窗
    // ==================================================================

    @Test
    void req04_publishRejectedWhenRosterEmpty() throws Exception {
        String admin = token("admin01");
        long activityId = createActivity(admin, "REQ04 空名单验收活动", SEEDED_VERSION_1,
                List.of(), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        assertEquals(0, participantRepository.countByActivityId(activityId));

        JsonNode rejected = expectStatus(mockMvc.perform(post("/api/admin/activities/" + activityId + "/publish")
                .header("Authorization", "Bearer " + admin)).andReturn(), 400);
        assertTrue(rejected.path("message").asText().contains("名单不能为空"),
                "空名单发布必须精确报错: " + rejected.path("message").asText());

        // 发布被拒后状态仍为 DRAFT
        JsonNode detail = expectOk(mockMvc.perform(get("/api/admin/activities/" + activityId)
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals("DRAFT", detail.path("data").path("status").asText());
        assertEquals(0, detail.path("data").path("participantCount").asInt());
    }

    @Test
    void req04_illegalStateTransitionsRejected() throws Exception {
        String admin = token("admin01");
        long activityId = createActivity(admin, "REQ04 非法状态流转验收活动", SEEDED_VERSION_1,
                List.of(4L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));

        // DRAFT -> CLOSE（非法）
        JsonNode closeDraft = expectStatus(mockMvc.perform(post("/api/admin/activities/" + activityId + "/close")
                .header("Authorization", "Bearer " + admin)).andReturn(), 400);
        assertTrue(closeDraft.path("message").asText().contains("仅已发布(PUBLISHED)状态可关闭"),
                "草稿直接关闭必须被拒绝: " + closeDraft.path("message").asText());

        // DRAFT -> PUBLISHED（合法） -> CLOSED（合法） -> 再次 PUBLISHED（非法）
        expectOk(mockMvc.perform(post("/api/admin/activities/" + activityId + "/publish")
                .header("Authorization", "Bearer " + admin)).andReturn());
        expectOk(mockMvc.perform(post("/api/admin/activities/" + activityId + "/close")
                .header("Authorization", "Bearer " + admin)).andReturn());
        JsonNode reopen = expectStatus(mockMvc.perform(post("/api/admin/activities/" + activityId + "/publish")
                .header("Authorization", "Bearer " + admin)).andReturn(), 400);
        assertTrue(reopen.path("message").asText().contains("仅草稿(DRAFT)状态可发布"),
                "关闭后重发必须被拒绝: " + reopen.path("message").asText());

        // 已发布/已关闭活动不允许修改核心绑定（仅 DRAFT 允许）
        ObjectNode update = objectMapper.createObjectNode();
        update.put("title", "REQ04 非法修改标题");
        update.put("questionnaireVersionId", SEEDED_VERSION_1);
        update.put("startTime", LocalDateTime.now().minusDays(1).format(FMT));
        update.put("endTime", LocalDateTime.now().plusDays(30).format(FMT));
        ArrayNode participants = update.putArray("participantUserIds");
        participants.add(4L);
        JsonNode editRejected = expectStatus(mockMvc.perform(put("/api/admin/activities/" + activityId)
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update))).andReturn(), 400);
        assertTrue(editRejected.path("message").asText().contains("仅草稿(DRAFT)状态的活动允许修改"),
                "已发布活动修改核心绑定必须被拒绝: " + editRejected.path("message").asText());
    }

    @Test
    void req04_studentOutsideRosterRejected403() throws Exception {
        String outsider = token("20260004");

        JsonNode detail = expectStatus(h5Detail(outsider, SEEDED_ACTIVITY_1), 403);
        assertTrue(detail.path("message").asText().contains("名单"), "403 文案应提示名单外: " + detail.path("message").asText());

        MvcResult submit = mockMvc.perform(post("/api/h5/activities/" + SEEDED_ACTIVITY_1 + "/submit")
                .header("Authorization", "Bearer " + outsider)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(SEED_A_ANSWERS))).andReturn();
        expectStatus(submit, 403);

        assertEquals(0, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                SEEDED_ACTIVITY_1, 7L), "名单外学生的提交不得产生任何答卷");
        assertEquals(0, countRows("SELECT COUNT(*) FROM activity_participant WHERE activity_id = ? AND user_id = ?",
                SEEDED_ACTIVITY_1, 7L), "不得把名单外学生补进名单");
    }

    @Test
    void req04_submitOutsideTimeWindowRejected400() throws Exception {
        String admin = token("admin01");
        String student = token("20260003");

        long notStarted = createActivity(admin, "REQ04 未开始验收活动", SEEDED_VERSION_1,
                List.of(6L), LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(3));
        publishActivity(admin, notStarted);
        MvcResult r1 = mockMvc.perform(post("/api/h5/activities/" + notStarted + "/submit")
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(ALL_A_ANSWERS))).andReturn();
        JsonNode n1 = expectStatus(r1, 400);
        assertTrue(n1.path("message").asText().contains("活动未开始"),
                "时间窗外必须精确报错: " + n1.path("message").asText());

        long expired = createActivity(admin, "REQ04 已截止验收活动", SEEDED_VERSION_1,
                List.of(6L), LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(2));
        publishActivity(admin, expired);
        MvcResult r2 = mockMvc.perform(post("/api/h5/activities/" + expired + "/submit")
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(ALL_A_ANSWERS))).andReturn();
        JsonNode n2 = expectStatus(r2, 400);
        assertTrue(n2.path("message").asText().contains("活动已截止"),
                "过期活动必须精确报错: " + n2.path("message").asText());

        assertEquals(0, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                notStarted, 6L));
        assertEquals(0, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                expired, 6L));
    }

    // ==================================================================
    // 场景 4 — REQ05 动态问卷脱敏 / 非法作答拒绝；REQ06 事务原子回滚
    // ==================================================================

    @Test
    void req05_h5DetailNeverExposesOptionScoreOrReverseFlag() throws Exception {
        String student = token("20260001");
        MvcResult r = h5Detail(student, SEEDED_ACTIVITY_1);
        String body = utf8Body(r);
        JsonNode n = expectOk(r);

        assertEquals(6, n.path("data").path("questions").size(), "题目数应为 6");
        assertTrue(body.contains("\"optCode\""), "响应必须包含选项编码");
        assertTrue(body.contains("\"label\""), "响应必须包含选项文本");
        assertTrue(body.contains("\"sort\""), "响应必须包含选项排序");
        assertFalse(body.contains("\"score\""), "H5 填写端严禁暴露选项分值 score，响应: " + body);
        assertFalse(body.contains("\"isReverse\""), "H5 填写端严禁暴露正反向标记 isReverse，响应: " + body);
        assertFalse(body.contains("\"dimensionCode\""), "H5 填写端不应暴露维度编码，响应: " + body);

        // 结构层再校验一次（脱敏题目对象仅含 questionNo/stem/questionType/required/options）
        JsonNode q0 = n.path("data").path("questions").get(0);
        assertTrue(q0.has("questionNo") && q0.has("stem") && q0.has("options"));
        JsonNode opt0 = q0.path("options").get(0);
        assertEquals(3, opt0.size(), "脱敏后的选项对象字段数应为 3（optCode/label/sort），实际: " + opt0);
        assertTrue(n.path("data").has("cacheSource"), "响应必须附带 cacheSource 供 REQ09 观测");
    }

    @Test
    void req05_invalidAnswersRejectedWithoutPersisting() throws Exception {
        String admin = token("admin01");
        String student = token("20260003");
        long activityId = createActivity(admin, "REQ05 非法作答验收活动", SEEDED_VERSION_1,
                List.of(6L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        // 必答题漏答（缺第 6 题）
        String[] fiveAnswers = {"A", "A", "A", "A", "A"};
        JsonNode miss = expectStatus(mockMvc.perform(post("/api/h5/activities/" + activityId + "/submit")
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(fiveAnswers))).andReturn(), 400);
        assertTrue(miss.path("message").asText().contains("第6题为必答题"),
                "漏答必须定位到题号: " + miss.path("message").asText());

        // 非法选项编码
        String[] badCode = {"A", "A", "A", "A", "A", "Z"};
        JsonNode illegal = expectStatus(mockMvc.perform(post("/api/h5/activities/" + activityId + "/submit")
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(badCode))).andReturn(), 400);
        assertTrue(illegal.path("message").asText().contains("第6题所选选项[Z]不属于该题有效选项"),
                "非法选项必须定位到题号与选项: " + illegal.path("message").asText());

        // 两次非法提交均不得产生答卷/报告
        assertEquals(0, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                activityId, 6L), "非法作答不得写入任何答卷");
        assertEquals(0, countRows("SELECT COUNT(*) FROM assessment_report WHERE activity_id = ? AND user_id = ?",
                activityId, 6L), "非法作答不得写入任何报告");

        // 校验通过后仍然可正常提交
        JsonNode ok = expectOk(mockMvc.perform(post("/api/h5/activities/" + activityId + "/submit")
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(ALL_A_ANSWERS))).andReturn());
        assertTrue(ok.path("data").path("sheetId").asLong() > 0, "合法提交必须返回 sheetId");
        assertTrue(ok.path("data").path("reportId").asLong() > 0, "合法提交必须返回 reportId");
        assertEquals("PENDING", ok.path("data").path("taskStatus").asText());
    }

    @Test
    void req06_submissionFaultInjectorRollsBackWholeOrder() throws Exception {
        String admin = token("admin01");
        String student = token("20260003");
        long activityId = createActivity(admin, "REQ06 事务回滚验收活动", SEEDED_VERSION_1,
                List.of(6L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        try {
            submissionFaultInjector.setFailAtItemIndex(2); // 第 3 条明细写入前抛异常
            MvcResult r = mockMvc.perform(post("/api/h5/activities/" + activityId + "/submit")
                    .header("Authorization", "Bearer " + student)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(answersJson(SEED_A_ANSWERS))).andReturn();
            String body = utf8Body(r);
            assertTrue(r.getResponse().getStatus() >= 500,
                    "明细写入中途异常应导致整单失败，实际状态=" + r.getResponse().getStatus() + " body=" + body);
        } finally {
            submissionFaultInjector.reset();
        }

        assertEquals(0, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                activityId, 6L), "answer_sheet 必须整单回滚，不得残留主表记录");
        assertEquals(0, countRows("SELECT COUNT(*) FROM assessment_report WHERE activity_id = ? AND user_id = ?",
                activityId, 6L), "assessment_report 必须整单回滚");
        assertEquals(0, countRows("SELECT COUNT(*) FROM answer_item WHERE sheet_id NOT IN (SELECT id FROM answer_sheet)"),
                "answer_item 不得残留孤儿明细行");

        // 注入器复位后同一活动可正常提交
        JsonNode ok = expectOk(mockMvc.perform(post("/api/h5/activities/" + activityId + "/submit")
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(SEED_A_ANSWERS))).andReturn());
        assertTrue(ok.path("data").path("sheetId").asLong() > 0, "复位后应可正常提交");
    }

    // ==================================================================
    // 场景 5 — REQ06 并发重复提交
    // ==================================================================

    @Test
    void req06_concurrentSubmitOnlyOneSuccessOthers409WithOriginalRecord() throws Exception {
        String admin = token("admin01");
        String student = token("20260003");
        long activityId = createActivity(admin, "REQ06 并发提交验收活动", SEEDED_VERSION_1,
                List.of(6L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        final int threads = 9;
        final String submitBody = answersJson(SEED_A_ANSWERS);
        record Outcome(int status, String body) {
        }
        List<Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    startGate.await();
                    MvcResult r = mockMvc.perform(post("/api/h5/activities/" + activityId + "/submit")
                            .header("Authorization", "Bearer " + student)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submitBody)).andReturn();
                    outcomes.add(new Outcome(r.getResponse().getStatus(), utf8Body(r)));
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Integer> summary = new ArrayList<>();
        outcomes.forEach(o -> summary.add(o.status()));
        long okCount = outcomes.stream().filter(o -> o.status() == 200).count();
        long conflictCount = outcomes.stream().filter(o -> o.status() == 409).count();

        assertEquals(threads, outcomes.size(), "全部并发请求都必须有响应");
        assertEquals(1, okCount, "并发提交必须且只能有 1 个 200，实际状态分布=" + summary);
        assertEquals(threads - 1, conflictCount, "其余请求必须全部 409，实际状态分布=" + summary);
        assertEquals(threads, okCount + conflictCount, "不允许出现 500/其他状态，实际状态分布=" + summary);

        // 胜出者
        Outcome winnerOutcome = outcomes.stream().filter(o -> o.status() == 200).findFirst().orElseThrow();
        JsonNode winner = objectMapper.readTree(winnerOutcome.body());
        long winnerSheetId = winner.path("data").path("sheetId").asLong();
        long winnerReportId = winner.path("data").path("reportId").asLong();
        assertTrue(winnerSheetId > 0 && winnerReportId > 0, "胜出响应必须携带 sheetId/reportId: " + winner);

        // 每个 409 响应的 data 都必须指向该学生本人的原记录
        for (Outcome o : outcomes) {
            if (o.status() != 409) {
                continue;
            }
            JsonNode conflict = objectMapper.readTree(o.body());
            assertEquals(409, conflict.path("code").asInt());
            assertTrue(conflict.path("message").asText().contains("请勿重复提交"),
                    "409 文案应引导原记录: " + conflict.path("message").asText());
            assertEquals(winnerSheetId, conflict.path("data").path("sheetId").asLong(),
                    "409 data.sheetId 必须指向本人原答卷: " + conflict);
            assertEquals(winnerReportId, conflict.path("data").path("reportId").asLong(),
                    "409 data.reportId 必须指向本人原报告: " + conflict);
            assertEquals(activityId, conflict.path("data").path("activityId").asLong());
        }

        // 数据库仅 1 份有效答卷
        assertEquals(1, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                activityId, 6L), "并发提交后数据库必须且只能保留 1 份答卷");
        AnswerSheet survivor = sheetRepository.findByActivityAndUser(activityId, 6L)
                .orElseThrow(() -> new AssertionError("唯一答卷缺失"));
        assertEquals(winnerSheetId, survivor.getId().longValue());
        assertEquals(1, countRows("SELECT COUNT(*) FROM assessment_report WHERE sheet_id = ?", winnerSheetId));
        assertTrue(reportRepository.findBySheetId(winnerSheetId).isPresent());
    }

    // ==================================================================
    // 场景 6 — REQ07/REQ08 完成率与区间分布
    // ==================================================================

    @Test
    void req07_threeRosterTwoSheetsYields66_67Completion() throws Exception {
        String admin = token("admin01");
        JsonNode d = expectOk(mockMvc.perform(
                get("/api/admin/activities/" + SEEDED_ACTIVITY_1 + "/statistics")
                        .header("Authorization", "Bearer " + admin)).andReturn()).path("data");

        assertEquals(SEEDED_ACTIVITY_1, d.path("activityId").asLong());
        assertEquals("PUBLISHED", d.path("status").asText());
        assertEquals(3, d.path("participantCount").asInt(), "3 人名单");
        assertEquals(2, d.path("completedCount").asInt(), "2 份有效答卷");
        assertEquals(66.67, d.path("completionRate").asDouble(), 0.0001, "完成率必须为 66.67");
        assertEquals(2, d.path("successReportCount").asInt());
        assertEquals(0, d.path("pendingReportCount").asInt());
        assertEquals(0, d.path("failedReportCount").asInt());

        Map<String, Long> dist = new LinkedHashMap<>();
        d.path("levelDistribution").forEach(item -> dist.put(item.path("levelCode").asText(),
                item.path("count").asLong()));
        assertEquals(4, dist.size(), "区间分布应覆盖全部 4 个区间: " + dist);
        assertEquals(Long.valueOf(1), dist.get("GOOD"), "GOOD=1（学生1 9.00 分）");
        assertEquals(Long.valueOf(0), dist.get("MILD"));
        assertEquals(Long.valueOf(1), dist.get("MODERATE"), "MODERATE=1（学生2 17.00 分）");
        assertEquals(Long.valueOf(0), dist.get("HIGH"));
        assertEquals(2L, dist.values().stream().mapToLong(Long::longValue).sum(),
                "区间分布计数之和必须等于成功报告数");

        Map<String, BigDecimal> dimAvg = new LinkedHashMap<>();
        d.path("dimensionAverages").forEach(item -> dimAvg.put(item.path("dimensionCode").asText(),
                item.path("avgScore").decimalValue()));
        assertEquals(0, new BigDecimal("4.50").compareTo(dimAvg.get("STRESS")), "STRESS 均值 (3+6)/2");
        assertEquals(0, new BigDecimal("3.50").compareTo(dimAvg.get("MOOD")), "MOOD 均值 (2+5)/2");
        assertEquals(0, new BigDecimal("5.00").compareTo(dimAvg.get("SLEEP")), "SLEEP 均值 (4+6)/2");

        assertEquals(3, d.path("participantDetails").size());
        long submittedCount = 0;
        for (JsonNode p : d.path("participantDetails")) {
            if (p.path("submitted").asBoolean()) {
                submittedCount++;
                assertEquals("SUCCESS", p.path("taskStatus").asText());
            }
        }
        assertEquals(2L, submittedCount);

        // 明细与报告与 ScoringEngine 同步复算一致
        assertMatchesSyncScoring(1L, 1L);
        assertMatchesSyncScoring(2L, 2L);
    }

    @Test
    void req08_emptyRosterStatisticsReturnsZeroRateWithoutDivideByZero() throws Exception {
        String admin = token("admin01");
        long activityId = createActivity(admin, "REQ08 空名单防除零验收活动", SEEDED_VERSION_1,
                List.of(), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));

        JsonNode d = expectOk(mockMvc.perform(
                get("/api/admin/activities/" + activityId + "/statistics")
                        .header("Authorization", "Bearer " + admin)).andReturn()).path("data");
        assertEquals(0, d.path("participantCount").asInt());
        assertEquals(0, d.path("completedCount").asInt());
        assertEquals(0.00, d.path("completionRate").asDouble(), 0.0001,
                "空名单完成率必须为 0.00 且不得抛出除零异常");
        assertEquals(0, d.path("successReportCount").asInt());
        assertEquals(4, d.path("levelDistribution").size(), "区间骨架仍应完整返回");
        d.path("levelDistribution").forEach(it -> assertEquals(0, it.path("count").asInt(),
                "空名单活动不应有任何区间计数: " + it));

        // 活动列表同样返回 0.00
        JsonNode list = expectOk(mockMvc.perform(get("/api/admin/activities")
                .header("Authorization", "Bearer " + admin)).andReturn());
        boolean found = false;
        for (JsonNode a : list.path("data")) {
            if (a.path("id").asLong() == activityId) {
                found = true;
                assertEquals(0.00, a.path("completionRate").asDouble(), 0.0001);
            }
        }
        assertTrue(found, "新建的空名单活动应出现在管理端活动列表中");
    }

    @Test
    void req08_pendingAndFailedReportsExcludedFromLevelDistribution() throws Exception {
        String admin = token("admin01");
        reportTaskDispatcher.setTransportMode("sync");
        long activityId = createActivity(admin, "REQ08 三态报告验收活动", SEEDED_VERSION_1,
                List.of(4L, 5L, 6L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        // (1) 正常提交 -> SUCCESS
        JsonNode ok4 = submitAs("20260001", activityId, SEED_A_ANSWERS);
        long sheet4 = ok4.path("data").path("sheetId").asLong();
        awaitReportStatus(sheet4, "SUCCESS", 15_000);

        // (2) MQ 投递失败 -> PENDING（保留答卷，last_error 可查）
        mqFaultInjector.setSimulateSendFailure(true);
        try {
            JsonNode ok5 = submitAs("20260002", activityId, SEED_B_ANSWERS);
            assertEquals("PENDING", ok5.path("data").path("taskStatus").asText());
        } finally {
            mqFaultInjector.setSimulateSendFailure(false);
        }
        long sheet5 = sheetIdOf(activityId, 5L);
        awaitLastError(sheet5, 10_000);

        // (3) 消费计算失败 -> 受控重试 3 次后 FAILED
        mqFaultInjector.setSimulateConsumeFailure(true);
        try {
            JsonNode ok6 = submitAs("20260003", activityId, ALL_A_ANSWERS);
            assertEquals("PENDING", ok6.path("data").path("taskStatus").asText());
        } finally {
            mqFaultInjector.setSimulateConsumeFailure(false);
        }
        long sheet6 = sheetIdOf(activityId, 6L);
        AssessmentReport failedReport = awaitReportStatus(sheet6, "FAILED", 15_000);
        assertEquals(3, failedReport.getRetryCount().intValue(), "重试必须受控于 maxRetries=3");

        // 统计：完成 3/3 = 100.00，三态分别计数
        JsonNode d = expectOk(mockMvc.perform(
                get("/api/admin/activities/" + activityId + "/statistics")
                        .header("Authorization", "Bearer " + admin)).andReturn()).path("data");
        assertEquals(3, d.path("participantCount").asInt());
        assertEquals(3, d.path("completedCount").asInt());
        assertEquals(100.00, d.path("completionRate").asDouble(), 0.0001);
        assertEquals(1, d.path("successReportCount").asInt());
        assertEquals(1, d.path("pendingReportCount").asInt());
        assertEquals(1, d.path("failedReportCount").asInt());

        // PENDING / FAILED 不得计入任何 levelDistribution 分数区间
        Map<String, Long> dist = new LinkedHashMap<>();
        d.path("levelDistribution").forEach(item -> dist.put(item.path("levelCode").asText(),
                item.path("count").asLong()));
        assertEquals(1L, dist.values().stream().mapToLong(Long::longValue).sum(),
                "区间分布总和必须等于成功报告数（PENDING/FAILED 不入区间）: " + dist);
        assertEquals(Long.valueOf(1), dist.get("GOOD"), "仅 SUCCESS 的 9.00 分进入 GOOD 区间");
        assertEquals(Long.valueOf(0), dist.get("MILD"));
        assertEquals(Long.valueOf(0), dist.get("MODERATE"));
        assertEquals(Long.valueOf(0), dist.get("HIGH"));

        // 明细：PENDING/FAILED 不带分数与区间
        Map<Long, JsonNode> byUser = new LinkedHashMap<>();
        d.path("participantDetails").forEach(p -> byUser.put(p.path("userId").asLong(), p));
        JsonNode p5 = byUser.get(5L);
        assertNotNull(p5, "应包含学生 5 的明细");
        assertEquals("PENDING", p5.path("taskStatus").asText());
        assertTrue(p5.path("totalScore").isMissingNode() || p5.path("totalScore").isNull(),
                "PENDING 报告不得给出分数: " + p5);
        assertTrue(p5.path("lastError").asText().length() > 0, "PENDING 报告 lastError 必须可查: " + p5);

        JsonNode p6 = byUser.get(6L);
        assertNotNull(p6, "应包含学生 6 的明细");
        assertEquals("FAILED", p6.path("taskStatus").asText());
        assertEquals(3, p6.path("retryCount").asInt());
        assertTrue(p6.path("totalScore").isMissingNode() || p6.path("totalScore").isNull(),
                "FAILED 报告不得给出分数: " + p6);

        JsonNode p4 = byUser.get(4L);
        assertNotNull(p4, "应包含学生 4 的明细");
        assertEquals("SUCCESS", p4.path("taskStatus").asText());
        assertEquals(0, new BigDecimal("9.00").compareTo(p4.path("totalScore").decimalValue()));
        assertEquals("GOOD", p4.path("levelCode").asText());
    }

    // ==================================================================
    // 场景 7 — REQ09 缓存
    // ==================================================================

    @Test
    void req09_firstReadMissesSecondReadHits() throws Exception {
        String student = token("20260001");
        cacheService.evictKey(CacheService.activityKey(SEEDED_ACTIVITY_1));
        cacheService.evictKey(CacheService.versionKey(SEEDED_VERSION_1));
        cacheService.resetMetrics();

        JsonNode first = expectOk(h5Detail(student, SEEDED_ACTIVITY_1));
        CacheMetricsVO afterFirst = cacheService.getMetrics();
        assertEquals(0L, afterFirst.getHitCount(), "首次读取不应命中缓存: " + afterFirst);
        assertTrue(afterFirst.getMissCount() >= 1, "首次读取必须回源（missCount 增加）: " + afterFirst);
        assertEquals("MISS_DB", first.path("data").path("activityCacheSource").asText());
        assertEquals("MISS_DB", first.path("data").path("versionCacheSource").asText());
        assertEquals("MISS_DB", first.path("data").path("cacheSource").asText());

        JsonNode second = expectOk(h5Detail(student, SEEDED_ACTIVITY_1));
        CacheMetricsVO afterSecond = cacheService.getMetrics();
        assertTrue(afterSecond.getHitCount() >= 1, "二次读取必须命中缓存（hitCount 增加）: " + afterSecond);
        assertTrue(afterSecond.getHitCount() > afterFirst.getHitCount(),
                "hitCount 必须递增: " + afterFirst.getHitCount() + " -> " + afterSecond.getHitCount());
        assertEquals(afterFirst.getMissCount(), afterSecond.getMissCount(), "二次读取不应再次回源");
        assertEquals("HIT", second.path("data").path("cacheSource").asText());

        // 管理端缓存指标接口可观测
        JsonNode metrics = expectOk(mockMvc.perform(get("/api/admin/cache/metrics")
                .header("Authorization", "Bearer " + token("admin01"))).andReturn());
        assertTrue(metrics.path("data").path("hitCount").asLong() >= 1);
        assertTrue(metrics.path("data").path("missCount").asLong() >= 1);
        assertEquals("memory", metrics.path("data").path("configuredMode").asText());
        assertTrue(metrics.path("data").path("recentEvents").size() > 0, "应有缓存操作日志");

        // 手动失效接口
        expectOk(mockMvc.perform(delete("/api/admin/cache/evict")
                .param("key", CacheService.versionKey(SEEDED_VERSION_1))
                .header("Authorization", "Bearer " + token("admin01"))).andReturn());
    }

    @Test
    void req09_closedActivityIsRejectedEvenWhenStaleCacheStillExists() throws Exception {
        String admin = token("admin01");
        String student = token("20260001");
        long activityId = createActivity(admin, "REQ09 关闭防穿透验收活动", SEEDED_VERSION_1,
                List.of(4L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        // 预热：让学生端读取一次，把 PUBLISHED 状态写入活动缓存
        expectOk(h5Detail(student, activityId));
        AssessmentActivity stale = activityRepository.findById(activityId).orElseThrow();
        assertEquals("PUBLISHED", stale.getStatus());

        // 关闭活动（接口内部会失效缓存）
        JsonNode closed = expectOk(mockMvc.perform(post("/api/admin/activities/" + activityId + "/close")
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals("CLOSED", closed.path("data").path("status").asText());

        // 人为把“关闭前”的旧缓存写回去，模拟旧缓存未失效/被穿透的最坏情况
        cacheService.put(CacheService.activityKey(activityId), stale);

        // 读取接口此时确实读到了旧状态（证明旧缓存仍然存在）
        JsonNode poisoned = expectOk(h5Detail(student, activityId));
        assertEquals("PUBLISHED", poisoned.path("data").path("status").asText(),
                "此断言用于证明旧缓存确实存在");
        assertEquals("HIT", poisoned.path("data").path("activityCacheSource").asText(),
                "活动缓存必须是命中状态，否则本用例无法证明防穿透");

        // 提交必须被服务端实时状态校验拦截（不依赖缓存）
        MvcResult submit = mockMvc.perform(post("/api/h5/activities/" + activityId + "/submit")
                .header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(SEED_A_ANSWERS))).andReturn();
        JsonNode rejected = expectStatus(submit, 400);
        assertTrue(rejected.path("message").asText().contains("活动已关闭"),
                "关闭后提交必须被实时拦截: " + rejected.path("message").asText());

        assertEquals(0, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                activityId, 4L), "关闭活动后不得写入任何答卷");
    }

    // ==================================================================
    // 场景 8 — REQ10 异步投递 / 幂等 / 受控重试
    // ==================================================================

    @Test
    void req10_sendFailureKeepsSheetAndRecordsLastError() throws Exception {
        String admin = token("admin01");
        reportTaskDispatcher.setTransportMode("sync");
        mqFaultInjector.setSimulateSendFailure(true);

        long activityId = createActivity(admin, "REQ10 投递失败验收活动", SEEDED_VERSION_1,
                List.of(4L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        JsonNode ok = submitAs("20260001", activityId, SEED_A_ANSWERS);
        long sheetId = ok.path("data").path("sheetId").asLong();
        long reportId = ok.path("data").path("reportId").asLong();
        assertEquals("PENDING", ok.path("data").path("taskStatus").asText());

        // 答卷必须保留
        assertEquals(1, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                activityId, 4L), "MQ 投递失败不得回滚答卷");
        assertEquals(6, countRows("SELECT COUNT(*) FROM answer_item WHERE sheet_id = ?", sheetId),
                "MQ 投递失败不得回滚作答明细");

        // 报告 PENDING 且 last_error 可查
        awaitLastError(sheetId, 10_000);
        AssessmentReport report = reportRepository.findBySheetId(sheetId).orElseThrow();
        assertEquals("PENDING", report.getTaskStatus(), "投递失败后报告应保持 PENDING");
        assertEquals(0, report.getRetryCount().intValue());
        assertNotNull(report.getLastError(), "last_error 必须被记录");
        assertTrue(report.getLastError().contains("MQ消息投递失败"),
                "last_error 内容错误: " + report.getLastError());
        assertNull(report.getTotalScore(), "PENDING 报告不得有分数");

        // 管理端报告详情与统计可观测
        JsonNode detail = expectOk(mockMvc.perform(get("/api/admin/reports/" + reportId)
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals("PENDING", detail.path("data").path("taskStatus").asText());
        assertTrue(detail.path("data").path("lastError").asText().contains("MQ消息投递失败"));
        JsonNode stats = expectOk(mockMvc.perform(
                get("/api/admin/activities/" + activityId + "/statistics")
                        .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals(1, stats.path("data").path("pendingReportCount").asInt());

        // 复位后受控重试可成功，且与同步计分一致
        mqFaultInjector.setSimulateSendFailure(false);
        JsonNode retried = expectOk(mockMvc.perform(post("/api/admin/reports/" + reportId + "/retry")
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals("SUCCESS", retried.path("data").path("taskStatus").asText());
        awaitReportStatus(sheetId, "SUCCESS", 15_000);
        assertMatchesSyncScoring(sheetId, reportId);
    }

    @Test
    void req10_consumeFailureRetriesUpToMaxThreeThenBoundedFailed() throws Exception {
        String admin = token("admin01");
        reportTaskDispatcher.setTransportMode("sync");
        mqFaultInjector.setSimulateConsumeFailure(true);

        long activityId = createActivity(admin, "REQ10 受控重试验收活动", SEEDED_VERSION_1,
                List.of(5L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        JsonNode ok = submitAs("20260002", activityId, SEED_B_ANSWERS);
        long sheetId = ok.path("data").path("sheetId").asLong();
        long reportId = ok.path("data").path("reportId").asLong();

        AssessmentReport failed = reportRepository.findBySheetId(sheetId).orElseThrow();
        assertEquals("FAILED", failed.getTaskStatus(), "达到上限后必须转 FAILED");
        assertEquals(3, failed.getRetryCount().intValue(), "retryCount 必须等于 maxRetries=3");
        assertEquals(3, failed.getMaxRetries().intValue());
        assertNotNull(failed.getLastError());
        assertTrue(failed.getLastError().contains("3/3"),
                "last_error 应体现第 3/3 次失败: " + failed.getLastError());

        // 超出上限后拒绝继续无限制重试
        boolean consumed = reportConsumerService.consumeReportTask(sheetId);
        assertFalse(consumed, "达到上限后的重复消费应被拒绝");
        AssessmentReport afterSkip = reportRepository.findBySheetId(sheetId).orElseThrow();
        assertEquals(3, afterSkip.getRetryCount().intValue(), "超出上限后 retryCount 不得继续增长");
        assertEquals("FAILED", afterSkip.getTaskStatus());

        // 管理端受控重试（未 reset）必须 400
        JsonNode rejected = expectStatus(mockMvc.perform(post("/api/admin/reports/" + reportId + "/retry")
                .header("Authorization", "Bearer " + admin)).andReturn(), 400);
        assertTrue(rejected.path("message").asText().contains("已达到最大重试次数上限"),
                "超过上限的重试必须精确报错: " + rejected.path("message").asText());
        assertEquals(3, reportRepository.findBySheetId(sheetId).orElseThrow().getRetryCount().intValue());

        // 恢复正常后 reset=true 重试 -> 成功，且结果与 ScoringEngine 同步计算完全一致
        mqFaultInjector.setSimulateConsumeFailure(false);
        JsonNode retried = expectOk(mockMvc.perform(post("/api/admin/reports/" + reportId + "/retry?reset=true")
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals("SUCCESS", retried.path("data").path("taskStatus").asText());
        assertEquals(0, retried.path("data").path("retryCount").asInt());
        assertMatchesSyncScoring(sheetId, reportId);
    }

    @Test
    void req10_duplicateConsumeIsIdempotentAndMatchesSyncScoring() throws Exception {
        String admin = token("admin01");
        // demo profile 默认 MQ 传输必须是 async，本用例走真实异步线程池
        assertEquals("async", originalTransport, "demo profile 默认 MQ 传输应为 async");
        reportTaskDispatcher.setTransportMode("async");

        long activityId = createActivity(admin, "REQ10 幂等消费验收活动", SEEDED_VERSION_1,
                List.of(6L), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
        publishActivity(admin, activityId);

        JsonNode ok = submitAs("20260003", activityId, ALL_A_ANSWERS);
        long sheetId = ok.path("data").path("sheetId").asLong();
        long reportId = ok.path("data").path("reportId").asLong();
        assertEquals("PENDING", ok.path("data").path("taskStatus").asText());

        // 等待异步报告生成
        AssessmentReport success = awaitReportStatus(sheetId, "SUCCESS", 30_000);
        assertEquals(0, new BigDecimal("12.00").compareTo(success.getTotalScore()),
                "异步报告总分应为 12.00，实际 " + success.getTotalScore());
        assertEquals("MILD", success.getLevelCode());
        assertMatchesSyncScoring(sheetId, reportId);

        // 同步 ScoringEngine 复算与异步报告完全一致
        ScoringResult expected = syncScoringForSheet(sheetId);
        assertEquals(0, new BigDecimal("12.00").compareTo(expected.getTotalScore()));

        // 重复投递同一 sheetId -> 幂等：不报错、不重复生成
        reportConsumerService.resetIdempotentSkipCount();
        assertTrue(reportConsumerService.consumeReportTask(sheetId), "重复消费应幂等跳过并返回成功");
        assertTrue(reportConsumerService.consumeReportTask(sheetId), "重复消费不得抛异常");
        assertEquals(2L, reportConsumerService.getIdempotentSkipCount(),
                "两次重复消费应计数 2 次幂等跳过");

        AssessmentReport after = reportRepository.findById(reportId).orElseThrow();
        assertEquals("SUCCESS", after.getTaskStatus(), "重复消费不得改写状态");
        assertEquals(0, success.getTotalScore().compareTo(after.getTotalScore()), "重复消费不得改写分数");
        assertEquals(success.getGeneratedAt(), after.getGeneratedAt(), "重复消费不得重新生成报告");
        assertEquals(success.getRetryCount(), after.getRetryCount());
        assertEquals(1, countRows("SELECT COUNT(*) FROM assessment_report WHERE sheet_id = ?", sheetId),
                "同一 sheetId 只允许 1 份报告");
        assertEquals(1, countRows("SELECT COUNT(*) FROM answer_sheet WHERE activity_id = ? AND user_id = ?",
                activityId, 6L), "重复消费不得产生额外答卷");

        // MQ 状态可观测
        JsonNode mqStatus = expectOk(mockMvc.perform(get("/api/admin/mq/status")
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertEquals("async", mqStatus.path("data").path("transportMode").asText());
        assertFalse(mqStatus.path("data").path("simulateSendFailure").asBoolean());
        assertFalse(mqStatus.path("data").path("simulateConsumeFailure").asBoolean());
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

    private MvcResult h5Detail(String studentToken, long activityId) throws Exception {
        return mockMvc.perform(get("/api/h5/activities/" + activityId + "/detail")
                .header("Authorization", "Bearer " + studentToken)).andReturn();
    }

    private JsonNode submitAs(String username, long activityId, String[] answers) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/h5/activities/" + activityId + "/submit")
                .header("Authorization", "Bearer " + token(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answersJson(answers))).andReturn();
        return expectOk(r);
    }

    private String answersJson(String[] optCodes) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("answers");
        for (int i = 0; i < optCodes.length; i++) {
            ObjectNode a = arr.addObject();
            a.put("questionNo", i + 1);
            a.put("selectedOptCode", optCodes[i]);
        }
        return objectMapper.writeValueAsString(root);
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

    private MvcResult postQuestionnaire(String adminToken, ObjectNode payload) throws Exception {
        return mockMvc.perform(post("/api/admin/questionnaires")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn();
    }

    private MvcResult putQuestionnaire(String adminToken, long id, ObjectNode payload) throws Exception {
        return mockMvc.perform(put("/api/admin/questionnaires/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn();
    }

    private MvcResult publishVersion(String adminToken, long questionnaireId) throws Exception {
        return mockMvc.perform(post("/api/admin/questionnaires/" + questionnaireId + "/publish")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn();
    }

    private ObjectNode validPayload(String code) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("code", code);
        root.put("title", "自动化验收测试问卷");
        root.put("description", "系统验收测试用问卷草稿");

        ObjectNode rule = root.putObject("scoringRule");
        rule.put("ruleVersion", "R-TEST.1");
        rule.put("formula", "SUM");
        rule.put("reverseMaxPlusMin", 5);
        ArrayNode dims = rule.putArray("dimensions");
        dims.addObject().put("code", "STRESS").put("name", "学习压力").put("formula", "SUM");
        dims.addObject().put("code", "MOOD").put("name", "情绪状态").put("formula", "SUM");
        ArrayNode levels = rule.putArray("levels");
        levels.addObject().put("code", "GOOD").put("name", "状态良好")
                .put("min", 6).put("max", 10)
                .put("minInclusive", true).put("maxInclusive", true).put("advice", "继续保持");
        levels.addObject().put("code", "MILD").put("name", "轻度压力")
                .put("min", 11).put("max", 20)
                .put("minInclusive", true).put("maxInclusive", true).put("advice", "适当调整");

        ArrayNode qs = root.putArray("questions");
        for (int i = 1; i <= 2; i++) {
            ObjectNode q = qs.addObject();
            q.put("questionNo", i);
            q.put("stem", "第" + i + "题题干");
            q.put("questionType", "SINGLE");
            q.put("dimensionCode", i == 1 ? "STRESS" : "MOOD");
            q.put("isReverse", 0);
            q.put("required", 1);
            ArrayNode opts = q.putArray("options");
            for (int j = 0; j < 4; j++) {
                ObjectNode o = opts.addObject();
                o.put("optCode", String.valueOf((char) ('A' + j)));
                o.put("label", "选项" + (char) ('A' + j));
                o.put("score", j + 1);
                o.put("sort", j + 1);
            }
        }
        return root;
    }

    private ObjectNode withBlankStem(ObjectNode base) {
        ObjectNode copy = base.deepCopy();
        ((ObjectNode) copy.path("questions").get(0)).put("stem", "   ");
        return copy;
    }

    private ObjectNode withSingleOption(ObjectNode base) {
        ObjectNode copy = base.deepCopy();
        ArrayNode opts = (ArrayNode) copy.path("questions").get(0).path("options");
        while (opts.size() > 1) {
            opts.remove(opts.size() - 1);
        }
        return copy;
    }

    private ObjectNode withNegativeScore(ObjectNode base) {
        ObjectNode copy = base.deepCopy();
        ((ObjectNode) copy.path("questions").get(0).path("options").get(0)).put("score", -1);
        return copy;
    }

    private ObjectNode withOverlappingLevels(ObjectNode base) {
        ObjectNode copy = base.deepCopy();
        ((ObjectNode) copy.path("scoringRule").path("levels").get(1)).put("min", 9);
        return copy;
    }

    private int countRows(String sql, Object... args) {
        Integer c = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    private long sheetIdOf(long activityId, long userId) {
        return sheetRepository.findByActivityAndUser(activityId, userId)
                .orElseThrow(() -> new AssertionError("未找到答卷 activityId=" + activityId + " userId=" + userId))
                .getId();
    }

    private AssessmentReport awaitReportStatus(long sheetId, String status, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AssessmentReport last = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = reportRepository.findBySheetId(sheetId);
            if (opt.isPresent()) {
                last = opt.get();
                if (status.equals(last.getTaskStatus())) {
                    return last;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待报告状态超时: sheetId=" + sheetId + " 期望=" + status
                + " 最终=" + (last == null ? "无报告"
                : last.getTaskStatus() + "/retry=" + last.getRetryCount() + "/error=" + last.getLastError()));
    }

    private void awaitLastError(long sheetId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var opt = reportRepository.findBySheetId(sheetId);
            if (opt.isPresent() && opt.get().getLastError() != null && !opt.get().getLastError().isBlank()) {
                return;
            }
            Thread.sleep(100);
        }
        AssessmentReport r = reportRepository.findBySheetId(sheetId).orElse(null);
        throw new AssertionError("等待 last_error 超时: sheetId=" + sheetId
                + " lastError=" + (r == null ? "无报告" : r.getLastError()));
    }

    private ScoringResult syncScoringForSheet(long sheetId) {
        AnswerSheet sheet = sheetRepository.findById(sheetId)
                .orElseThrow(() -> new AssertionError("答卷不存在: " + sheetId));
        QuestionnaireVersion version = versionRepository.findById(sheet.getQuestionnaireVersionId())
                .orElseThrow(() -> new AssertionError("版本不存在"));
        List<QuestionConfig> questions = questionnaireService.parseQuestionsSnapshot(version.getQuestionsSnapshot());
        ScoringRuleConfig rule = questionnaireService.parseScoringRule(version.getScoringRuleSnapshot());
        List<AnswerInput> inputs = new ArrayList<>();
        for (var item : itemRepository.findBySheetId(sheetId)) {
            inputs.add(new AnswerInput(item.getQuestionNo(), item.getSelectedOptCode()));
        }
        return scoringEngine.calculate(questions, rule, inputs);
    }

    private void assertMatchesSyncScoring(long sheetId, long reportId) {
        AssessmentReport report = reportRepository.findById(reportId).orElseThrow();
        assertEquals("SUCCESS", report.getTaskStatus(), "报告尚未成功，无法比对同步计分: reportId=" + reportId);
        ScoringResult expected = syncScoringForSheet(sheetId);

        assertEquals(0, expected.getTotalScore().compareTo(report.getTotalScore()),
                "异步总分与 ScoringEngine 同步计算不一致: async=" + report.getTotalScore()
                        + " sync=" + expected.getTotalScore());
        assertEquals(expected.getLevelCode(), report.getLevelCode(), "异步区间与同步计算不一致");
        assertEquals(expected.getLevelName(), report.getLevelName(), "异步区间名称与同步计算不一致");
        assertEquals(expected.getAdvice(), report.getFeedbackText(), "异步建议与同步计算不一致");

        Map<String, BigDecimal> actual;
        try {
            actual = objectMapper.readValue(report.getDimensionScoresJson(),
                    new TypeReference<LinkedHashMap<String, BigDecimal>>() {
                    });
        } catch (Exception ex) {
            throw new AssertionError("维度分 JSON 解析失败: " + report.getDimensionScoresJson(), ex);
        }
        assertNotNull(actual, "维度分不得为空");
        assertEquals(expected.getDimensionScores().keySet(), actual.keySet(), "维度集合不一致");
        for (Map.Entry<String, BigDecimal> e : expected.getDimensionScores().entrySet()) {
            assertEquals(0, e.getValue().compareTo(actual.get(e.getKey())),
                    "维度 " + e.getKey() + " 分值不一致: sync=" + e.getValue() + " async=" + actual.get(e.getKey()));
        }
    }
}
