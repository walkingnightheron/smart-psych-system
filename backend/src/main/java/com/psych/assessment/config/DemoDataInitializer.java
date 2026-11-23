package com.psych.assessment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.dto.QuestionnaireDtos.DimensionRule;
import com.psych.assessment.dto.QuestionnaireDtos.LevelRule;
import com.psych.assessment.dto.QuestionnaireDtos.OptionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds exact teaching demo dataset matching seed.sql when app.seed.enabled=true.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @Value("${app.seed.enabled:false}")
    private boolean seedEnabled;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) throws Exception {
        if (!seedEnabled) {
            return;
        }
        Long existingUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
        if (existingUsers != null && existingUsers > 0) {
            log.info("DemoDataInitializer skipped: database already contains {} users", existingUsers);
            return;
        }

        log.info("Initializing teaching demo dataset (seed.sql parity)...");
        String pwdHash = passwordEncoder.encode("123456");

        // 1. 7 Users (3 Admins: 1 owner, 2 authorized, 3 unauthorized; 4 Students: 4,5,6 in roster, 7 outside roster)
        insertUser(1L, "admin01", pwdHash, "林知夏（中心主任）", "ADMIN", "心理健康教育中心", "13800000001");
        insertUser(2L, "admin02", pwdHash, "周怀瑾（授权辅导员）", "ADMIN", "计算机与软件学院", "13800000002");
        insertUser(3L, "admin03", pwdHash, "陈未授权（其他院系）", "ADMIN", "机械工程学院", "13800000003");
        insertUser(4L, "20260001", pwdHash, "苏晴（已交卷学生A）", "STUDENT", "软件2542班", "13900000001");
        insertUser(5L, "20260002", pwdHash, "陆远（已交卷学生B）", "STUDENT", "软件2542班", "13900000002");
        insertUser(6L, "20260003", pwdHash, "许星河（名单内待填写C）", "STUDENT", "软件2542班", "13900000003");
        insertUser(7L, "20260004", pwdHash, "赵子墨（名单外学生D）", "STUDENT", "网络2501班", "13900000004");

        // 2. Questionnaire 1 & ScoringRule
        List<OptionConfig> standardOptions = List.of(
                new OptionConfig("A", "从不", new BigDecimal("1"), 1),
                new OptionConfig("B", "偶尔", new BigDecimal("2"), 2),
                new OptionConfig("C", "经常", new BigDecimal("3"), 3),
                new OptionConfig("D", "总是", new BigDecimal("4"), 4)
        );
        String optionsJson = objectMapper.writeValueAsString(standardOptions);

        List<QuestionConfig> questions = List.of(
                new QuestionConfig(1, "近期面对课程任务与考核时，我感到难以负荷。", "SINGLE", "STRESS", 0, 1, standardOptions),
                new QuestionConfig(2, "课余时间我仍会反复担忧未完成的学习事项，难以放松。", "SINGLE", "STRESS", 0, 1, standardOptions),
                new QuestionConfig(3, "近期我容易感到烦躁、低落或提不起精神。", "SINGLE", "MOOD", 0, 1, standardOptions),
                new QuestionConfig(4, "遇到困难时，我能够保持平和心态并找到积极的排解方式。（反向计分）", "SINGLE", "MOOD", 1, 1, standardOptions),
                new QuestionConfig(5, "近期我存在入睡困难、夜间易醒或早醒的情况。", "SINGLE", "SLEEP", 0, 1, standardOptions),
                new QuestionConfig(6, "早晨醒来后我感到精力充沛，能够投入新一天学习。（反向计分）", "SINGLE", "SLEEP", 1, 1, standardOptions)
        );

        ScoringRuleConfig scoringRule = ScoringRuleConfig.builder()
                .ruleVersion("R-2026.1")
                .formula("SUM")
                .reverseMaxPlusMin(new BigDecimal("5"))
                .dimensions(List.of(
                        new DimensionRule("STRESS", "学习压力", "SUM"),
                        new DimensionRule("MOOD", "情绪状态", "SUM"),
                        new DimensionRule("SLEEP", "睡眠与作息", "SUM")
                ))
                .levels(List.of(
                        new LevelRule("GOOD", "状态良好", new BigDecimal("6"), new BigDecimal("10"), true, true,
                                "您近期整体心理适应与作息状态良好，建议继续保持规律作息与积极社交。"),
                        new LevelRule("MILD", "轻度压力", new BigDecimal("11"), new BigDecimal("15"), true, true,
                                "您近期存在一定学习或生活压力，建议合理规划学习节奏，适当进行户外运动与倾诉。"),
                        new LevelRule("MODERATE", "中度困扰", new BigDecimal("16"), new BigDecimal("20"), true, true,
                                "您近期情绪或睡眠受到较明显困扰，建议主动参与校园心理沙龙或预约中心老师沟通。"),
                        new LevelRule("HIGH", "重点关注", new BigDecimal("21"), new BigDecimal("24"), true, true,
                                "您近期心理负荷较高，建议尽快联系辅导员或学校心理健康教育中心获取一对一支持。")
                ))
                .build();

        String scoringRuleJson = objectMapper.writeValueAsString(scoringRule);
        String questionsSnapshotJson = objectMapper.writeValueAsString(questions);

        jdbcTemplate.update(
                "INSERT INTO questionnaire (id, code, title, description, scoring_rule_json, latest_version, creator_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                1L,
                "CAMPUS_PSYCH_2026",
                "大学生心理适应与压力评估问卷（教学模拟卷）",
                "面向高校学生的教学模拟心理适应评估问卷，涵盖学习压力、情绪状态、睡眠与作息三个维度，共6道单选题（含2道反向计分题）。结果仅供软件工程实训功能验证，不作医学诊断。",
                scoringRuleJson,
                1,
                1L
        );

        for (int i = 0; i < questions.size(); i++) {
            QuestionConfig q = questions.get(i);
            jdbcTemplate.update(
                    "INSERT INTO questionnaire_question (id, questionnaire_id, question_no, stem, question_type, dimension_code, is_reverse, required, options_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (long) (i + 1), 1L, q.getQuestionNo(), q.getStem(), q.getQuestionType(), q.getDimensionCode(), q.getIsReverse(), q.getRequired(), optionsJson
            );
        }

        // 3. Immutable Questionnaire Version 1
        jdbcTemplate.update(
                "INSERT INTO questionnaire_version (id, questionnaire_id, version_num, rule_version, title, description, questions_snapshot, scoring_rule_snapshot, published_by, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L,
                1L,
                1,
                "R-2026.1",
                "大学生心理适应与压力评估问卷（教学模拟卷）",
                "面向高校学生的教学模拟心理适应评估问卷，涵盖学习压力、情绪状态、睡眠与作息三个维度，共6道单选题（含2道反向计分题）。",
                questionsSnapshotJson,
                scoringRuleJson,
                1L,
                Timestamp.valueOf(LocalDateTime.of(2026, 11, 23, 9, 0, 0))
        );

        // 4. Activity 1 (PUBLISHED, owner=1, authorized=[2]) & Activity 2 (DRAFT, owner=1)
        jdbcTemplate.update(
                "INSERT INTO assessment_activity (id, title, questionnaire_version_id, status, start_time, end_time, owner_id, authorized_admin_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                1L,
                "2026秋季学期软件2542班心理适应普查",
                1L,
                "PUBLISHED",
                Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 0, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2027, 12, 31, 23, 59, 59)),
                1L,
                "[2]"
        );
        jdbcTemplate.update(
                "INSERT INTO assessment_activity (id, title, questionnaire_version_id, status, start_time, end_time, owner_id, authorized_admin_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                2L,
                "2026冬季期末考前压力摸底（草稿待发布）",
                1L,
                "DRAFT",
                Timestamp.valueOf(LocalDateTime.of(2026, 12, 1, 0, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 12, 31, 23, 59, 59)),
                1L,
                "[]"
        );

        // 5. Participants: Activity 1 -> [4, 5, 6] (3 students -> 2 submitted = 66.67%); Activity 2 -> [4, 6]
        jdbcTemplate.update("INSERT INTO activity_participant (id, activity_id, user_id) VALUES (1, 1, 4)");
        jdbcTemplate.update("INSERT INTO activity_participant (id, activity_id, user_id) VALUES (2, 1, 5)");
        jdbcTemplate.update("INSERT INTO activity_participant (id, activity_id, user_id) VALUES (3, 1, 6)");
        jdbcTemplate.update("INSERT INTO activity_participant (id, activity_id, user_id) VALUES (4, 2, 4)");
        jdbcTemplate.update("INSERT INTO activity_participant (id, activity_id, user_id) VALUES (5, 2, 6)");

        // 6. Answer Sheets & Items for Student 4 (score=9.00 GOOD) and Student 5 (score=17.00 MODERATE)
        jdbcTemplate.update(
                "INSERT INTO answer_sheet (id, activity_id, questionnaire_version_id, user_id, submitted_at) VALUES (?, ?, ?, ?, ?)",
                1L, 1L, 1L, 4L, Timestamp.valueOf(LocalDateTime.of(2026, 11, 23, 10, 15, 0))
        );
        jdbcTemplate.update(
                "INSERT INTO answer_sheet (id, activity_id, questionnaire_version_id, user_id, submitted_at) VALUES (?, ?, ?, ?, ?)",
                2L, 1L, 1L, 5L, Timestamp.valueOf(LocalDateTime.of(2026, 11, 23, 10, 30, 0))
        );

        insertItem(1L, 1, "A", "1.00", "STRESS");
        insertItem(1L, 2, "B", "2.00", "STRESS");
        insertItem(1L, 3, "A", "1.00", "MOOD");
        insertItem(1L, 4, "D", "1.00", "MOOD");
        insertItem(1L, 5, "B", "2.00", "SLEEP");
        insertItem(1L, 6, "C", "2.00", "SLEEP");

        insertItem(2L, 1, "C", "3.00", "STRESS");
        insertItem(2L, 2, "C", "3.00", "STRESS");
        insertItem(2L, 3, "B", "2.00", "MOOD");
        insertItem(2L, 4, "B", "3.00", "MOOD");
        insertItem(2L, 5, "C", "3.00", "SLEEP");
        insertItem(2L, 6, "B", "3.00", "SLEEP");

        // 7. Assessment Reports 1 & 2
        Map<String, BigDecimal> dim1 = new LinkedHashMap<>();
        dim1.put("STRESS", new BigDecimal("3.00"));
        dim1.put("MOOD", new BigDecimal("2.00"));
        dim1.put("SLEEP", new BigDecimal("4.00"));

        Map<String, BigDecimal> dim2 = new LinkedHashMap<>();
        dim2.put("STRESS", new BigDecimal("6.00"));
        dim2.put("MOOD", new BigDecimal("5.00"));
        dim2.put("SLEEP", new BigDecimal("6.00"));

        jdbcTemplate.update(
                "INSERT INTO assessment_report (id, sheet_id, activity_id, user_id, questionnaire_version_id, rule_version, task_status, total_score, dimension_scores_json, level_code, level_name, feedback_text, retry_count, max_retries, generated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, 4L, 1L, "R-2026.1", "SUCCESS", new BigDecimal("9.00"),
                objectMapper.writeValueAsString(dim1),
                "GOOD", "状态良好",
                "您近期整体心理适应与作息状态良好，建议继续保持规律作息与积极社交。",
                0, 3, Timestamp.valueOf(LocalDateTime.of(2026, 11, 23, 10, 15, 2))
        );
        jdbcTemplate.update(
                "INSERT INTO assessment_report (id, sheet_id, activity_id, user_id, questionnaire_version_id, rule_version, task_status, total_score, dimension_scores_json, level_code, level_name, feedback_text, retry_count, max_retries, generated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, 1L, 5L, 1L, "R-2026.1", "SUCCESS", new BigDecimal("17.00"),
                objectMapper.writeValueAsString(dim2),
                "MODERATE", "中度困扰",
                "您近期情绪或睡眠受到较明显困扰，建议主动参与校园心理沙龙或预约中心老师沟通。",
                0, 3, Timestamp.valueOf(LocalDateTime.of(2026, 11, 23, 10, 30, 2))
        );

        // Advance H2 / MySQL auto-increment sequences past explicit IDs so subsequent inserts never collide!
        advanceAutoIncrementSequences();
        log.info("DemoDataInitializer completed: 7 users, 1 questionnaire (v1), 2 activities, 2 sheets & reports ready.");
    }

    private void insertUser(Long id, String username, String pwdHash, String realName, String role, String deptClass, String phone) {
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, username, password_hash, real_name, role, dept_class, phone, status) VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                id, username, pwdHash, realName, role, deptClass, phone
        );
    }

    private void insertItem(Long sheetId, int qNo, String optCode, String rawScore, String dimCode) {
        jdbcTemplate.update(
                "INSERT INTO answer_item (sheet_id, question_no, selected_opt_code, raw_score, dimension_code) VALUES (?, ?, ?, ?, ?)",
                sheetId, qNo, optCode, new BigDecimal(rawScore), dimCode
        );
    }

    private void advanceAutoIncrementSequences() {
        resetIdentityIfH2("sys_user", 10L);
        resetIdentityIfH2("questionnaire", 10L);
        resetIdentityIfH2("questionnaire_question", 20L);
        resetIdentityIfH2("questionnaire_version", 10L);
        resetIdentityIfH2("assessment_activity", 10L);
        resetIdentityIfH2("activity_participant", 20L);
        resetIdentityIfH2("answer_sheet", 10L);
        resetIdentityIfH2("answer_item", 50L);
        resetIdentityIfH2("assessment_report", 10L);
    }

    private void resetIdentityIfH2(String table, long restartWith) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + restartWith);
        } catch (Exception ignored) {
            // MySQL automatically advances AUTO_INCREMENT on explicit ID insert; H2 uses ALTER COLUMN id RESTART WITH
        }
    }
}
