package com.psych.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "app.cache.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class XlsxImportExportIntegrationTest {

    private static final long SEEDED_ACTIVITY_1 = 1L;
    private static final long SEEDED_ACTIVITY_2 = 2L;
    private static final long OUTSIDER_USER_ID = 7L;

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String SHEET_QUESTIONS = "题目列表";
    private static final String SHEET_DIMENSIONS = "维度配置";
    private static final String SHEET_LEVELS = "计分与区间";
    private static final String SHEET_STUDENTS = "学生名单";

    private static final String SHEET_SUMMARY = "活动汇总与统计";
    private static final String SHEET_DETAIL = "参与及评估结果明细";
    private static final String SHEET_ANSWERS = "作答选项明细";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Map<String, String> tokenCache = new LinkedHashMap<>();
    private final DataFormatter formatter = new DataFormatter();

    // ==================================================================
    // 7.2 标准模板下载
    // ==================================================================

    @Test
    void templateDownloadsReturnValidWorkbooksWithRequiredSheets() throws Exception {
        String admin = token("admin01");

        byte[] questionnaire = downloadXlsx("/api/admin/templates/questionnaire.xlsx", admin);
        try (XSSFWorkbook wb = openWorkbook(questionnaire)) {
            assertEquals(3, wb.getNumberOfSheets(), "题库模板必须包含3个工作表，实际=" + sheetNames(wb));
            Sheet questions = requireSheet(wb, SHEET_QUESTIONS);
            Sheet dimensions = requireSheet(wb, SHEET_DIMENSIONS);
            Sheet levels = requireSheet(wb, SHEET_LEVELS);

            assertEquals("题号", cellText(questions, 0, 0));
            assertEquals("题干", cellText(questions, 0, 1));
            assertEquals("归属维度编码", cellText(questions, 0, 2));
            assertEquals("选项A文案", cellText(questions, 0, 5));
            assertEquals("选项A分值", cellText(questions, 0, 6));
            assertTrue(questions.getLastRowNum() >= 6, "模板应至少含6行示例题目，实际=" + questions.getLastRowNum());

            assertEquals("维度编码", cellText(dimensions, 0, 0));
            assertEquals("维度名称", cellText(dimensions, 0, 1));
            assertEquals("聚合方式(SUM/AVG)", cellText(dimensions, 0, 2));
            assertTrue(dimensions.getLastRowNum() >= 3, "模板应至少含3行示例维度");

            assertEquals("区间编码", cellText(levels, 0, 0));
            assertEquals("最小分值", cellText(levels, 0, 2));
            assertEquals("最大分值", cellText(levels, 0, 3));
            assertTrue(levels.getLastRowNum() >= 4, "模板应至少含4行示例区间");
        }

        byte[] students = downloadXlsx("/api/admin/templates/students.xlsx", admin);
        try (XSSFWorkbook wb = openWorkbook(students)) {
            Sheet sheet = requireSheet(wb, SHEET_STUDENTS);
            assertEquals("学号", cellText(sheet, 0, 0));
            assertEquals("姓名", cellText(sheet, 0, 1));
            assertEquals("学院班级", cellText(sheet, 0, 2));
            assertEquals("手机号", cellText(sheet, 0, 3));
            assertTrue(sheet.getLastRowNum() >= 3, "模板应至少含3行示例学生");
        }
    }

    // ==================================================================
    // 7.3 题库 XLSX 解析与新建问卷
    // ==================================================================

    @Test
    void questionnaireTemplateParsesImportsAsDraftAndPublishesV1() throws Exception {
        String admin = token("admin01");
        byte[] template = downloadXlsx("/api/admin/templates/questionnaire.xlsx", admin);

        JsonNode parsed = expectOk(multipartPost("/api/admin/questionnaires/import/parse", admin,
                "questionnaire-template.xlsx", template));
        assertEquals(6, parsed.path("data").path("questionCount").asInt(),
                "标准模板应解析出6道题目，实际=" + parsed.path("data"));
        assertTrue(parsed.path("data").path("hasScoringRule").asBoolean(),
                "标准模板必须携带计分与区间规则，实际=" + parsed.path("data"));
        assertEquals(6, parsed.path("data").path("questions").size());

        String code = "XLSX_IMPORT_ACCEPT_01";
        assertEquals(0, countRows("SELECT COUNT(*) FROM questionnaire WHERE code = ?", code),
                "前置条件：验收问卷编码必须尚未存在");

        JsonNode created = expectOk(multipartPost("/api/admin/questionnaires/import", admin,
                "questionnaire-template.xlsx", template, "code", code, "title", "XLSX导入验收问卷"));
        long questionnaireId = created.path("data").path("id").asLong();
        assertTrue(questionnaireId > 0, "导入问卷失败: " + created);
        assertEquals(code, created.path("data").path("code").asText());
        assertEquals(0, created.path("data").path("latestVersion").asInt(), "导入结果必须是草稿(latestVersion=0)");
        assertEquals(6, created.path("data").path("questions").size());
        assertEquals(4, created.path("data").path("scoringRule").path("levels").size());
        assertEquals(1, countRows("SELECT COUNT(*) FROM questionnaire WHERE code = ?", code));

        JsonNode published = expectOk(authPostJson(
                "/api/admin/questionnaires/" + questionnaireId + "/publish", admin, "{}"));
        assertEquals(1, published.path("data").path("versionNum").asInt(), "导入的草稿必须可发布为v1");
        assertEquals(1, countRows(
                "SELECT COUNT(*) FROM questionnaire WHERE code = ? AND latest_version = 1", code));
        assertEquals(1, countRows(
                "SELECT COUNT(*) FROM questionnaire_version WHERE questionnaire_id = ? AND version_num = 1",
                questionnaireId));
    }

    @Test
    void invalidQuestionnaireXlsxRejectedWithPreciseErrorsAndZeroPersistence() throws Exception {
        String admin = token("admin01");
        byte[] template = downloadXlsx("/api/admin/templates/questionnaire.xlsx", admin);
        int questionnaireBefore = countRows("SELECT COUNT(*) FROM questionnaire");

        byte[] negativeScore = mutateWorkbook(template,
                wb -> cellIn(wb, SHEET_QUESTIONS, 1, 6).setCellValue("-1"));
        assertXlsxRowError(
                expectStatus(importQuestionnaire(admin, negativeScore, "XLSX_BAD_NEG_SCORE", "负分值"), 400),
                SHEET_QUESTIONS, 2, "选项A分值", "分值必须为0~100之间的数字");

        byte[] nonNumericScore = mutateWorkbook(template,
                wb -> cellIn(wb, SHEET_QUESTIONS, 1, 6).setCellValue("abc"));
        assertXlsxRowError(
                expectStatus(importQuestionnaire(admin, nonNumericScore, "XLSX_BAD_NAN_SCORE", "非数字分值"), 400),
                SHEET_QUESTIONS, 2, "选项A分值", "分值必须为0~100之间的数字");

        byte[] missingLabel = mutateWorkbook(template,
                wb -> cellIn(wb, SHEET_QUESTIONS, 1, 7).setCellValue(""));
        assertXlsxRowError(
                expectStatus(importQuestionnaire(admin, missingLabel, "XLSX_BAD_NO_LABEL", "缺失选项文案"), 400),
                SHEET_QUESTIONS, 2, "选项B文案", "选项文案与分值必须成对出现，文案不能为空");

        byte[] duplicateNo = mutateWorkbook(template,
                wb -> cellIn(wb, SHEET_QUESTIONS, 2, 0).setCellValue("1"));
        JsonNode duplicate = expectStatus(
                importQuestionnaire(admin, duplicateNo, "XLSX_BAD_DUP_NO", "重复题号"), 400);
        assertXlsxRowError(duplicate, SHEET_QUESTIONS, 3, "题号", "在文件内重复");
        assertTrue(firstError(duplicate).path("message").asText().contains("首次出现于第2行"),
                "重复题号必须回指首次出现行: " + firstError(duplicate));

        byte[] fakeZip = "not a zip".getBytes(StandardCharsets.UTF_8);
        JsonNode fake = expectStatus(importQuestionnaire(admin, fakeZip, "XLSX_BAD_FAKE_ZIP", "伪造后缀"), 400);
        assertTrue(fake.path("message").asText().contains(".xlsx"),
                "伪造后缀的纯文本必须被识别为非法xlsx: " + fake.path("message").asText());

        for (String badCode : List.of("XLSX_BAD_NEG_SCORE", "XLSX_BAD_NAN_SCORE",
                "XLSX_BAD_NO_LABEL", "XLSX_BAD_DUP_NO", "XLSX_BAD_FAKE_ZIP")) {
            assertEquals(0, countRows("SELECT COUNT(*) FROM questionnaire WHERE code = ?", badCode),
                    "非法导入必须零落库: " + badCode);
        }
        assertEquals(questionnaireBefore, countRows("SELECT COUNT(*) FROM questionnaire"),
                "非法导入后问卷总数不得变化");
    }

    // ==================================================================
    // 7.4 学生名册查询与 XLSX 批量建号
    // ==================================================================

    @Test
    void studentImportCreatesAccountsWithDerivedPasswordAndSkipsExisting() throws Exception {
        String admin = token("admin01");
        byte[] roster = studentsXlsx(new String[][]{
                {"2026990001", "测试新生甲", "软件2601班", "13900009901"},
                {"S123", "短学号生", "软件2602班", ""},
                {"20260001", "苏晴（已交卷学生A）", "软件2542班", "13900000001"}
        });

        JsonNode result = expectOk(multipartPost("/api/admin/users/import-students", admin,
                "students.xlsx", roster));
        assertEquals(3, result.path("data").path("totalRows").asInt(), "总行数应为3: " + result.path("data"));
        assertEquals(2, result.path("data").path("createdCount").asInt(), "新建账号应为2个: " + result.path("data"));
        assertEquals(0, result.path("data").path("updatedCount").asInt(), "默认不更新已有资料: " + result.path("data"));
        assertEquals(1, result.path("data").path("skippedCount").asInt(), "已有学生应被跳过: " + result.path("data"));
        assertTrue(result.path("data").path("passwordRuleHint").asText().contains("学号后6位"),
                "口令规则提示必须说明学号后6位: " + result.path("data"));

        assertEquals(1, countRows("SELECT COUNT(*) FROM sys_user WHERE username = ? AND role = 'STUDENT'",
                "2026990001"));
        assertEquals(1, countRows("SELECT COUNT(*) FROM sys_user WHERE username = ? AND role = 'STUDENT'",
                "S123"));

        JsonNode okLong = loginOk("2026990001", "990001");
        assertTrue(okLong.path("data").path("token").asText().length() > 0,
                "学号后6位口令必须可登录: " + okLong);
        JsonNode rejectedDefault = expectStatus(loginRequest("2026990001", "123456"), 401);
        assertEquals(401, rejectedDefault.path("code").asInt(),
                "默认口令123456必须被拒绝: " + rejectedDefault);

        loginOk("S123", "S123");
        JsonNode rejectedShort = expectStatus(loginRequest("S123", "123456"), 401);
        assertEquals(401, rejectedShort.path("code").asInt(),
                "短学号的默认口令必须被拒绝: " + rejectedShort);

        JsonNode rosterQuery = expectOk(authGet("/api/admin/students?keyword=2026990001", admin));
        JsonNode matched = null;
        for (JsonNode item : rosterQuery.path("data")) {
            if ("2026990001".equals(item.path("username").asText())) {
                matched = item;
            }
        }
        assertNotNull(matched, "名册查询必须能查出新生2026990001，实际=" + rosterQuery.path("data"));
        assertEquals("测试新生甲", matched.path("realName").asText());
        assertEquals(1, matched.path("status").asInt(), "新建学生必须处于启用状态");
    }

    @Test
    void studentImportRejectsAdminAccountWithoutAnyPersist() throws Exception {
        String admin = token("admin01");
        int usersBefore = countRows("SELECT COUNT(*) FROM sys_user");
        byte[] roster = studentsXlsx(new String[][]{
                {"2026990099", "不该落库的学生", "软件2699班", ""},
                {"admin01", "林知夏（中心主任）", "心理健康教育中心", ""}
        });

        JsonNode body = expectStatus(multipartPost("/api/admin/users/import-students", admin,
                "students.xlsx", roster), 400);
        assertXlsxRowError(body, SHEET_STUDENTS, 3, "学号", "管理员角色");

        assertEquals(usersBefore, countRows("SELECT COUNT(*) FROM sys_user"),
                "管理员账号冲突的导入必须零落库");
        assertEquals(0, countRows("SELECT COUNT(*) FROM sys_user WHERE username = ?", "2026990099"),
                "同文件中的合法新学生也不得落库");
        assertEquals("ADMIN", jdbcTemplate.queryForObject(
                "SELECT role FROM sys_user WHERE username = 'admin01'", String.class),
                "管理员账号角色不得被破坏");
        loginOk("admin01", "123456");
    }

    @Test
    void studentImportRejectsDuplicateUsernameWithPreciseRowAndZeroPersist() throws Exception {
        String admin = token("admin01");
        int usersBefore = countRows("SELECT COUNT(*) FROM sys_user");
        byte[] roster = studentsXlsx(new String[][]{
                {"2026990002", "重复学号甲", "软件2603班", ""},
                {"2026990002", "重复学号乙", "软件2603班", ""}
        });

        JsonNode body = expectStatus(multipartPost("/api/admin/users/import-students", admin,
                "students.xlsx", roster), 400);
        assertXlsxRowError(body, SHEET_STUDENTS, 3, "学号", "在文件内重复");
        assertTrue(firstError(body).path("message").asText().contains("首次出现于第2行"),
                "重复学号必须精确定位首次出现行: " + firstError(body));

        assertEquals(0, countRows("SELECT COUNT(*) FROM sys_user WHERE username = ?", "2026990002"),
                "重复学号文件必须零落库");
        assertEquals(usersBefore, countRows("SELECT COUNT(*) FROM sys_user"));
    }

    // ==================================================================
    // 7.5 评估活动目标名单 XLSX 导入
    // ==================================================================

    @Test
    void draftActivityParticipantImportAppendsRosterAndAutoCreatesStudent() throws Exception {
        String admin = token("admin01");
        byte[] roster = studentsXlsx(new String[][]{
                {"2026880011", "名单新增生", "软件2688班", ""},
                {"20260004", "赵子墨（名单外学生D）", "网络2501班", ""}
        });

        JsonNode result = expectOk(multipartPost(
                "/api/admin/activities/" + SEEDED_ACTIVITY_2 + "/participants/import", admin,
                "participants.xlsx", roster, "mode", "APPEND", "autoCreateMissing", "true"));
        assertEquals(SEEDED_ACTIVITY_2, result.path("data").path("activityId").asLong());
        assertEquals("APPEND", result.path("data").path("mode").asText());
        assertEquals(2, result.path("data").path("totalRows").asInt(), "总行数应为2: " + result.path("data"));
        assertEquals(1, result.path("data").path("createdUserCount").asInt(),
                "自动创建账号应为1个: " + result.path("data"));
        assertEquals(2, result.path("data").path("addedCount").asInt(),
                "新增入名单应为2人: " + result.path("data"));
        assertEquals(0, result.path("data").path("alreadyInRosterCount").asInt(),
                "原名单内无人: " + result.path("data"));
        assertEquals(4, result.path("data").path("currentParticipantCount").asInt(),
                "导入后名单总数应为4: " + result.path("data"));
        assertTrue(result.path("data").path("passwordRuleHint").asText().contains("学号后6位"),
                "自动建号必须提示学号后6位口令: " + result.path("data"));

        JsonNode newStudentLogin = loginOk("2026880011", "880011");
        assertTrue(newStudentLogin.path("data").path("token").asText().length() > 0,
                "新学号必须可用后6位口令登录: " + newStudentLogin);

        JsonNode detail = expectOk(authGet("/api/admin/activities/" + SEEDED_ACTIVITY_2, admin));
        assertEquals("DRAFT", detail.path("data").path("status").asText(), "活动2必须仍为草稿");
        assertEquals(4, detail.path("data").path("participantCount").asInt(),
                "活动2名单人数应为4: " + detail.path("data").path("participantCount"));
        Set<Long> participantIds = new HashSet<>();
        detail.path("data").path("participantUserIds").forEach(node -> participantIds.add(node.asLong()));
        Long newUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, "2026880011");
        assertNotNull(newUserId, "新学号2026880011必须已落库");
        assertTrue(participantIds.contains(newUserId),
                "活动2名单必须包含新学号2026880011，实际=" + participantIds);
        assertTrue(participantIds.contains(OUTSIDER_USER_ID),
                "活动2名单必须包含已有学号20260004，实际=" + participantIds);
    }

    @Test
    void participantImportRejectsPublishedActivityAndUnauthorizedAdmin() throws Exception {
        String owner = token("admin01");
        String unauthorized = token("admin03");
        byte[] roster = studentsXlsx(new String[][]{
                {"2026770001", "不该导入的学生", "软件2677班", ""}
        });

        JsonNode published = expectStatus(multipartPost(
                "/api/admin/activities/" + SEEDED_ACTIVITY_1 + "/participants/import", owner,
                "participants.xlsx", roster, "mode", "APPEND"), 400);
        assertTrue(published.path("message").asText().contains("仅草稿(DRAFT)状态"),
                "已发布活动必须禁止导入名单: " + published.path("message").asText());
        assertEquals(3, countRows("SELECT COUNT(*) FROM activity_participant WHERE activity_id = ?",
                SEEDED_ACTIVITY_1), "已发布活动的统计分母不得被改动");
        assertEquals(0, countRows("SELECT COUNT(*) FROM sys_user WHERE username = ?", "2026770001"),
                "被拒绝的导入不得自动建号");

        expectStatus(multipartPost(
                "/api/admin/activities/" + SEEDED_ACTIVITY_2 + "/participants/import", unauthorized,
                "participants.xlsx", roster, "mode", "APPEND"), 403);
        assertEquals(0, countRows("SELECT COUNT(*) FROM sys_user WHERE username = ?", "2026770001"),
                "未授权管理员的导入必须零落库");
    }

    @Test
    void studentRoleIsForbiddenOnEveryImportAndExportEndpoint() throws Exception {
        String student = token("20260001");
        String admin = token("admin01");
        byte[] roster = studentsXlsx(new String[][]{
                {"2026660001", "越权学生", "软件2666班", ""}
        });
        byte[] template = downloadXlsx("/api/admin/templates/questionnaire.xlsx", admin);

        expectStatus(multipartPost("/api/admin/users/import-students", student,
                "students.xlsx", roster), 403);
        expectStatus(multipartPost("/api/admin/activities/" + SEEDED_ACTIVITY_2 + "/participants/import",
                student, "participants.xlsx", roster, "mode", "APPEND"), 403);
        expectStatus(multipartPost("/api/admin/questionnaires/import/parse", student,
                "questionnaire.xlsx", template), 403);
        expectStatus(multipartPost("/api/admin/questionnaires/import", student,
                "questionnaire.xlsx", template, "code", "XLSX_STUDENT_FORBIDDEN",
                "title", "学生越权导入"), 403);
        expectStatus(authGet("/api/admin/templates/questionnaire.xlsx", student), 403);
        expectStatus(authGet("/api/admin/templates/students.xlsx", student), 403);
        expectStatus(authGet("/api/admin/students", student), 403);
        expectStatus(authGet("/api/admin/activities/" + SEEDED_ACTIVITY_1 + "/statistics/export.xlsx",
                student), 403);

        assertEquals(0, countRows("SELECT COUNT(*) FROM sys_user WHERE username = ?", "2026660001"),
                "学生越权导入必须零落库");
    }

    // ==================================================================
    // 7.6 评估结果多 Sheet XLSX 导出
    // ==================================================================

    @Test
    void statisticsExportProducesThreeSheetWorkbookForOwnerAndAuthorizedAdmin() throws Exception {
        for (String adminUser : List.of("admin01", "admin02")) {
            byte[] bytes = downloadXlsx(
                    "/api/admin/activities/" + SEEDED_ACTIVITY_1 + "/statistics/export.xlsx",
                    token(adminUser));
            try (XSSFWorkbook wb = openWorkbook(bytes)) {
                assertEquals(3, wb.getNumberOfSheets(),
                        adminUser + " 导出必须包含3个工作表，实际=" + sheetNames(wb));
                Sheet summary = requireSheet(wb, SHEET_SUMMARY);
                Sheet detail = requireSheet(wb, SHEET_DETAIL);
                Sheet answers = requireSheet(wb, SHEET_ANSWERS);

                assertTrue(containsText(summary, "66.67"),
                        adminUser + " Sheet1 必须包含完成率66.67%（或66.67）");
                assertTrue(containsText(summary, "完成率"), "Sheet1 必须包含完成率标签");
                assertTrue(containsText(summary, "目标人数"), "Sheet1 必须包含目标人数");
                assertTrue(containsText(summary, "已完成人数"), "Sheet1 必须包含已完成人数");
                assertTrue(containsText(summary, "各维度平均得分"), "Sheet1 必须包含维度平均分区块");

                List<String> detailHeaders = rowTexts(detail, 0);
                assertTrue(detailHeaders.contains("学号"), "Sheet2 表头必须含学号: " + detailHeaders);
                assertTrue(detailHeaders.contains("姓名"), "Sheet2 表头必须含姓名: " + detailHeaders);
                assertTrue(detailHeaders.contains("提交状态"), "Sheet2 表头必须含提交状态: " + detailHeaders);
                assertTrue(detailHeaders.contains("总分"), "Sheet2 表头必须含总分: " + detailHeaders);
                assertTrue(detailHeaders.contains("学习压力(STRESS)"),
                        "Sheet2 必须包含动态维度列[学习压力(STRESS)]: " + detailHeaders);
                assertTrue(detailHeaders.contains("情绪状态(MOOD)"),
                        "Sheet2 必须包含动态维度列[情绪状态(MOOD)]: " + detailHeaders);
                assertTrue(detailHeaders.contains("睡眠与作息(SLEEP)"),
                        "Sheet2 必须包含动态维度列[睡眠与作息(SLEEP)]: " + detailHeaders);

                assertEquals(3, detail.getLastRowNum(), "Sheet2 必须包含3名学生行");
                Set<String> detailUsers = new HashSet<>();
                int submittedRows = 0;
                for (int r = 1; r <= detail.getLastRowNum(); r++) {
                    detailUsers.add(cellText(detail, r, 0));
                    if ("已提交".equals(cellText(detail, r, 3))) {
                        submittedRows++;
                    }
                }
                assertEquals(Set.of("20260001", "20260002", "20260003"), detailUsers,
                        "Sheet2 学生行必须与名单一致");
                assertEquals(2, submittedRows, "Sheet2 必须有2行已提交");

                List<String> answerHeaders = rowTexts(answers, 0);
                assertTrue(answerHeaders.contains("答卷编号"), "Sheet3 表头必须含答卷编号: " + answerHeaders);
                assertTrue(answerHeaders.contains("学号"), "Sheet3 表头必须含学号: " + answerHeaders);
                for (int q = 1; q <= 6; q++) {
                    assertTrue(answerHeaders.contains("Q" + q),
                            "Sheet3 必须包含逐题作答列Q" + q + ": " + answerHeaders);
                }

                assertEquals(2, answers.getLastRowNum(), "Sheet3 必须包含2份有效答卷");
                Set<String> answerUsers = new HashSet<>();
                for (int r = 1; r <= answers.getLastRowNum(); r++) {
                    answerUsers.add(cellText(answers, r, 1));
                    for (int c = 5; c <= 10; c++) {
                        String value = cellText(answers, r, c);
                        assertTrue(value.matches("[A-F] \\(\\d+\\.\\d{2}\\)"),
                                "Sheet3 逐题作答格式非法: row=" + r + " col=" + c + " value=[" + value + "]");
                    }
                }
                assertEquals(Set.of("20260001", "20260002"), answerUsers,
                        "Sheet3 必须是2名已交卷学生的作答明细");
            }
        }

        JsonNode forbidden = expectStatus(authGet(
                "/api/admin/activities/" + SEEDED_ACTIVITY_1 + "/statistics/export.xlsx",
                token("admin03")), 403);
        assertTrue(forbidden.path("message").asText().length() > 0,
                "未授权管理员导出必须返回403且带错误信息: " + forbidden);
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
            JsonNode node = loginOk(username, "123456");
            String t = node.path("data").path("token").asText();
            assertTrue(t.length() > 0, "登录未返回token: " + node);
            tokenCache.put(username, t);
            return t;
        } catch (Exception ex) {
            throw new IllegalStateException("登录失败: " + username, ex);
        }
    }

    private MvcResult loginRequest(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private JsonNode loginOk(String username, String password) throws Exception {
        return expectOk(loginRequest(username, password));
    }

    private MvcResult authGet(String uri, String token) throws Exception {
        return mockMvc.perform(get(uri).header("Authorization", "Bearer " + token)).andReturn();
    }

    private MvcResult authPostJson(String uri, String token, String body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private MvcResult multipartPost(String uri, String token, String filename, byte[] content,
                                    String... params) throws Exception {
        MockMultipartHttpServletRequestBuilder multipartBuilder = multipart(uri);
        multipartBuilder.file(new MockMultipartFile("file", filename, XLSX_CONTENT_TYPE, content));
        for (int i = 0; i + 1 < params.length; i += 2) {
            multipartBuilder.param(params[i], params[i + 1]);
        }
        MockHttpServletRequestBuilder builder = multipartBuilder
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Authorization", "Bearer " + token);
        return mockMvc.perform(builder).andReturn();
    }

    private MvcResult importQuestionnaire(String token, byte[] content, String code, String title)
            throws Exception {
        return multipartPost("/api/admin/questionnaires/import", token, "questionnaire.xlsx",
                content, "code", code, "title", title);
    }

    private byte[] downloadXlsx(String uri, String token) throws Exception {
        MvcResult result = authGet(uri, token);
        byte[] bytes = result.getResponse().getContentAsByteArray();
        String contentType = result.getResponse().getContentType();
        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertEquals(200, result.getResponse().getStatus(),
                "期望HTTP 200，实际contentType=" + contentType + " body=" + describe(bytes));
        assertNotNull(contentType, "下载响应必须携带Content-Type");
        assertTrue(contentType.contains("spreadsheetml.sheet"),
                "下载响应Content-Type必须为xlsx: " + contentType);
        assertNotNull(disposition, "下载响应必须携带Content-Disposition");
        assertTrue(disposition.contains("attachment"), "下载响应必须是附件: " + disposition);
        return bytes;
    }

    private XSSFWorkbook openWorkbook(byte[] bytes) throws Exception {
        assertNotNull(bytes, "xlsx字节不得为空");
        assertTrue(bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B
                        && bytes[2] == 0x03 && bytes[3] == 0x04,
                "响应不是合法的OOXML(PK\\x03\\x04)文件，实际前4字节=" + describe(bytes));
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private Sheet requireSheet(XSSFWorkbook wb, String sheetName) {
        Sheet sheet = wb.getSheet(sheetName);
        assertNotNull(sheet, "缺少工作表[" + sheetName + "]，实际=" + sheetNames(wb));
        return sheet;
    }

    private List<String> sheetNames(XSSFWorkbook wb) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            names.add(wb.getSheetName(i));
        }
        return names;
    }

    private String cellText(Sheet sheet, int rowIndex, int colIndex) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(colIndex);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private List<String> rowTexts(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        List<String> values = new ArrayList<>();
        if (row == null) {
            return values;
        }
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        return values;
    }

    private boolean containsText(Sheet sheet, String needle) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (formatter.formatCellValue(cell).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Cell cellIn(XSSFWorkbook wb, String sheetName, int rowIndex, int colIndex) {
        Sheet sheet = wb.getSheet(sheetName);
        assertNotNull(sheet, "缺少工作表[" + sheetName + "]");
        Row row = sheet.getRow(rowIndex);
        assertNotNull(row, "缺少行[" + sheetName + "] row=" + rowIndex);
        Cell cell = row.getCell(colIndex);
        assertNotNull(cell, "缺少单元格[" + sheetName + "] row=" + rowIndex + " col=" + colIndex);
        return cell;
    }

    private byte[] studentsXlsx(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(SHEET_STUDENTS);
            String[] headers = {"学号", "姓名", "学院班级", "手机号"};
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                header.createCell(c).setCellValue(headers[c]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c] == null ? "" : rows[r][c]);
                }
            }
            return toBytes(wb);
        }
    }

    private byte[] mutateWorkbook(byte[] source, Consumer<XSSFWorkbook> mutator) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            mutator.accept(wb);
            return toBytes(wb);
        }
    }

    private byte[] toBytes(XSSFWorkbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    private JsonNode expectOk(MvcResult result) throws Exception {
        String body = utf8Body(result);
        assertEquals(200, result.getResponse().getStatus(), "期望HTTP 200，实际响应: " + body);
        return objectMapper.readTree(body);
    }

    private JsonNode expectStatus(MvcResult result, int status) throws Exception {
        String body = utf8Body(result);
        assertEquals(status, result.getResponse().getStatus(), "期望HTTP " + status + "，实际响应: " + body);
        return objectMapper.readTree(body);
    }

    private String utf8Body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertXlsxRowError(JsonNode body, String sheet, int row, String column,
                                    String messagePart) {
        assertEquals(400, body.path("code").asInt(), "业务码必须为400: " + body);
        int errorCount = body.path("data").path("errorCount").asInt();
        assertTrue(errorCount >= 1, "errorCount必须>=1: " + body);
        JsonNode errors = body.path("data").path("errors");
        assertTrue(errors.size() > 0, "errors数组不得为空: " + body);
        assertEquals(errorCount, errors.size(), "errors数组长度必须等于errorCount: " + body);

        JsonNode first = errors.get(0);
        assertEquals(sheet, first.path("sheet").asText(), "错误必须定位到工作表: " + first);
        assertEquals(row, first.path("row").asInt(), "错误必须定位到行号: " + first);
        assertEquals(column, first.path("column").asText(), "错误必须定位到列: " + first);
        String message = first.path("message").asText();
        assertFalse(message.isBlank(), "错误信息不得为空: " + first);
        assertTrue(message.contains(messagePart),
                "错误信息必须包含[" + messagePart + "]，实际: " + message);
    }

    private JsonNode firstError(JsonNode body) {
        JsonNode errors = body.path("data").path("errors");
        assertTrue(errors.size() > 0, "errors数组不得为空: " + body);
        return errors.get(0);
    }

    private int countRows(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private String describe(byte[] bytes) {
        StringBuilder sb = new StringBuilder("len=").append(bytes.length).append(" head=");
        for (int i = 0; i < Math.min(4, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString();
    }
}
