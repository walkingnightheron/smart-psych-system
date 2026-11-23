package com.psych.assessment;

import com.psych.assessment.dto.H5AndReportDtos.AnswerInput;
import com.psych.assessment.dto.QuestionnaireDtos.DimensionRule;
import com.psych.assessment.dto.QuestionnaireDtos.LevelRule;
import com.psych.assessment.dto.QuestionnaireDtos.OptionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.scoring.ScoringEngine;
import com.psych.assessment.scoring.ScoringEngine.ScoringResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 单元测试：教学模拟问卷（6题，Q4/Q6 反向计分）固定计分规则的 7 组区间边界样例、
 * 正反向转换、维度分与 AVG 均值公式核验，以及 seed.sql 口令哈希核验。
 *
 * <p>计分规则快照与 seed.sql / DemoDataInitializer 完全一致：
 * <ul>
 *   <li>选项分值 A=1 B=2 C=3 D=4，反向计分常数 reverseMaxPlusMin = 5</li>
 *   <li>GOOD [6,10] / MILD [11,15] / MODERATE [16,20] / HIGH [21,24]（均为闭区间）</li>
 *   <li>维度 STRESS(Q1,Q2) / MOOD(Q3,Q4) / SLEEP(Q5,Q6)，公式 SUM，总分公式 SUM</li>
 * </ul>
 * 问卷总分理论值域 = 4*1 + 2*1 = 6 到 4*4 + 2*4 = 24。
 */
class ScoringEngineSevenSamplesTest {

    private static final String RULE_VERSION = "R-2026.1";

    private final ScoringEngine engine = new ScoringEngine();

    // ------------------------------------------------------------------
    // 固定问卷快照构造（与 seed.sql / DemoDataInitializer 保持一致）
    // ------------------------------------------------------------------

    private static List<OptionConfig> standardOptions() {
        return List.of(
                new OptionConfig("A", "从不", new BigDecimal("1"), 1),
                new OptionConfig("B", "偶尔", new BigDecimal("2"), 2),
                new OptionConfig("C", "经常", new BigDecimal("3"), 3),
                new OptionConfig("D", "总是", new BigDecimal("4"), 4));
    }

    private static QuestionConfig question(int no, String dim, int reverse) {
        return QuestionConfig.builder()
                .questionNo(no)
                .stem("第" + no + "题题干")
                .questionType("SINGLE")
                .dimensionCode(dim)
                .isReverse(reverse)
                .required(1)
                .options(standardOptions())
                .build();
    }

    private static List<QuestionConfig> sixQuestions() {
        return List.of(
                question(1, "STRESS", 0),
                question(2, "STRESS", 0),
                question(3, "MOOD", 0),
                question(4, "MOOD", 1),
                question(5, "SLEEP", 0),
                question(6, "SLEEP", 1));
    }

    private static ScoringRuleConfig sumRule() {
        return ScoringRuleConfig.builder()
                .ruleVersion(RULE_VERSION)
                .formula("SUM")
                .reverseMaxPlusMin(new BigDecimal("5"))
                .dimensions(List.of(
                        new DimensionRule("STRESS", "学习压力", "SUM"),
                        new DimensionRule("MOOD", "情绪状态", "SUM"),
                        new DimensionRule("SLEEP", "睡眠与作息", "SUM")))
                .levels(List.of(
                        level("GOOD", "状态良好", "6", "10", "建议继续保持规律作息与积极社交。"),
                        level("MILD", "轻度压力", "11", "15", "建议合理规划学习节奏。"),
                        level("MODERATE", "中度困扰", "16", "20", "建议主动参与校园心理沙龙。"),
                        level("HIGH", "重点关注", "21", "24", "建议尽快联系辅导员获取支持。")))
                .build();
    }

    private static LevelRule level(String code, String name, String min, String max, String advice) {
        return new LevelRule(code, name, new BigDecimal(min), new BigDecimal(max), true, true, advice);
    }

    private static List<AnswerInput> answers(String... optCodes) {
        List<AnswerInput> list = new ArrayList<>();
        for (int i = 0; i < optCodes.length; i++) {
            list.add(new AnswerInput(i + 1, optCodes[i]));
        }
        return list;
    }

    private static BigDecimal rawScoreOf(String optCode) {
        return switch (optCode) {
            case "A" -> new BigDecimal("1");
            case "B" -> new BigDecimal("2");
            case "C" -> new BigDecimal("3");
            case "D" -> new BigDecimal("4");
            default -> throw new IllegalArgumentException("未知选项编码: " + optCode);
        };
    }

    // ------------------------------------------------------------------
    // 7 组固定计分与区间边界样例
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "样例{index}: 总分{0} -> {1} ({2})")
    @CsvSource({
            // expectedTotal, expectedLevel, expectedLevelName, q1..q6
            " 6.00, GOOD,     状态良好, A, A, A, D, A, D",
            "10.00, GOOD,     状态良好, B, A, A, A, A, D",
            "11.00, MILD,     轻度压力, B, B, A, A, A, D",
            "15.00, MILD,     轻度压力, C, C, B, B, B, C",
            "16.00, MODERATE, 中度困扰, C, C, C, B, B, C",
            "20.00, MODERATE, 中度困扰, D, D, D, C, D, C",
            "24.00, HIGH,     重点关注, D, D, D, A, D, A"
    })
    void sevenFixedBoundarySamples(String expectedTotal,
                                   String expectedLevel,
                                   String expectedLevelName,
                                   String q1, String q2, String q3, String q4, String q5, String q6) {
        String[] codes = {q1, q2, q3, q4, q5, q6};
        ScoringResult result = engine.calculate(sixQuestions(), sumRule(), answers(codes));

        // 1) 总分精确命中
        assertEquals(0, new BigDecimal(expectedTotal).compareTo(result.getTotalScore()),
                () -> "期望总分 " + expectedTotal + "，实际 " + result.getTotalScore()
                        + "，作答=" + String.join(",", codes));
        // 2) 结果区间（含名称）
        assertEquals(expectedLevel, result.getLevelCode(), "区间编码不匹配");
        assertEquals(expectedLevelName, result.getLevelName(), "区间名称不匹配");
        assertEquals(RULE_VERSION, result.getRuleVersion(), "规则版本快照未透传");
        assertNotNull(result.getAdvice());
        assertFalse(result.getAdvice().isBlank(), "区间建议 advice 不能为空");

        // 3) 维度分（STRESS/MOOD/SLEEP），且三维度之和必须等于总分（总分公式 SUM）
        BigDecimal stress = result.getDimensionScores().get("STRESS");
        BigDecimal mood = result.getDimensionScores().get("MOOD");
        BigDecimal sleep = result.getDimensionScores().get("SLEEP");
        assertNotNull(stress, "缺少维度 STRESS 分");
        assertNotNull(mood, "缺少维度 MOOD 分");
        assertNotNull(sleep, "缺少维度 SLEEP 分");
        BigDecimal dimSum = stress.add(mood).add(sleep);
        assertEquals(0, dimSum.compareTo(result.getTotalScore()),
                "维度分之和 " + dimSum + " 必须等于总分 " + result.getTotalScore());

        // 4) 正向题原样计分，反向题走 5 - raw 转换
        String[] dims = {"STRESS", "STRESS", "MOOD", "MOOD", "SLEEP", "SLEEP"};
        for (int i = 0; i < 6; i++) {
            BigDecimal raw = rawScoreOf(codes[i]);
            BigDecimal expected = (i == 3 || i == 5)
                    ? new BigDecimal("5").subtract(raw)
                    : raw;
            BigDecimal actual = result.getItemScores().get(i + 1);
            assertNotNull(actual, "缺少第" + (i + 1) + "题的有效分");
            assertEquals(0, expected.compareTo(actual),
                    "第" + (i + 1) + "题(" + dims[i] + ")有效分应为 " + expected + "，实际 " + actual);
        }
        // 复核样例总分的手算式（防止题目/选项构造漂移）
        BigDecimal manual = BigDecimal.ZERO;
        for (int i = 0; i < 6; i++) {
            BigDecimal raw = rawScoreOf(codes[i]);
            manual = manual.add((i == 3 || i == 5) ? new BigDecimal("5").subtract(raw) : raw);
        }
        assertEquals(0, manual.compareTo(new BigDecimal(expectedTotal)), "手算总分与引擎总分不一致");
    }

    @Test
    void levelBoundariesAreInclusiveAtBothEnds() {
        // 7 组样例的上下界端点必须闭区间命中（6/10/11/15/16/20/24 分别落在各自区间）
        Map<String, String> endpointToLevel = new LinkedHashMap<>();
        endpointToLevel.put("6", "GOOD");
        endpointToLevel.put("10", "GOOD");
        endpointToLevel.put("11", "MILD");
        endpointToLevel.put("15", "MILD");
        endpointToLevel.put("16", "MODERATE");
        endpointToLevel.put("20", "MODERATE");
        endpointToLevel.put("24", "HIGH");

        for (Map.Entry<String, String> e : endpointToLevel.entrySet()) {
            BigDecimal endpoint = new BigDecimal(e.getKey());
            LevelRule rule = sumRule().getLevels().stream()
                    .filter(l -> l.getCode().equals(e.getValue()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(Boolean.TRUE.equals(rule.getMinInclusive()) && Boolean.TRUE.equals(rule.getMaxInclusive()),
                    "区间 " + rule.getCode() + " 必须为闭区间");
            boolean hit = endpoint.compareTo(rule.getMin()) >= 0 && endpoint.compareTo(rule.getMax()) <= 0;
            assertTrue(hit, "端点 " + endpoint + " 应落入区间 " + rule.getCode()
                    + "[" + rule.getMin() + "," + rule.getMax() + "]");
        }
        // 区间之间不得重叠，也不得留缝（覆盖 6~24 全值域）
        List<LevelRule> levels = sumRule().getLevels();
        for (int i = 0; i + 1 < levels.size(); i++) {
            LevelRule a = levels.get(i);
            LevelRule b = levels.get(i + 1);
            assertEquals(0, a.getMax().add(BigDecimal.ONE).compareTo(b.getMin()),
                    "区间 " + a.getCode() + " 与 " + b.getCode() + " 应首尾相接无缝隙");
        }
    }

    @Test
    void exclusiveBoundsAndUnclassifiedGapAreRespected() {
        ScoringRuleConfig rule = ScoringRuleConfig.builder()
                .ruleVersion("R-EXCL")
                .formula("SUM")
                .reverseMaxPlusMin(new BigDecimal("5"))
                .dimensions(List.of(new DimensionRule("STRESS", "学习压力", "SUM")))
                .levels(List.of(
                        new LevelRule("LOW", "低", new BigDecimal("6"), new BigDecimal("10"), true, false, "x"),
                        new LevelRule("HIGH", "高", new BigDecimal("11"), new BigDecimal("24"), true, true, "y")))
                .build();

        // 10 分落在 maxInclusive=false 的 LOW 上界之外 -> 无区间命中 -> UNCLASSIFIED
        ScoringResult r10 = engine.calculate(sixQuestions(), rule, answers("B", "A", "A", "A", "A", "D"));
        assertEquals(0, new BigDecimal("10").compareTo(r10.getTotalScore()));
        assertNotEquals("LOW", r10.getLevelCode(), "10 分不应命中 maxInclusive=false 的 LOW 区间");
        assertEquals("UNCLASSIFIED", r10.getLevelCode(), "10 分应落入空档，返回 UNCLASSIFIED");

        // 11 分（minInclusive=true 下界）应命中 HIGH
        ScoringResult r11 = engine.calculate(sixQuestions(), rule, answers("B", "B", "A", "A", "A", "D"));
        assertEquals(0, new BigDecimal("11").compareTo(r11.getTotalScore()));
        assertEquals("HIGH", r11.getLevelCode());
    }

    // ------------------------------------------------------------------
    // 正反向计分转换
    // ------------------------------------------------------------------

    @Test
    void forwardAndReverseScoreConversion() {
        QuestionConfig forward = question(1, "STRESS", 0);
        QuestionConfig reverse = question(4, "MOOD", 1);

        // 正向：直接取选项原始分，保留 2 位小数
        for (OptionConfig opt : forward.getOptions()) {
            BigDecimal actual = engine.computeEffectiveItemScore(forward, opt, sumRule());
            assertEquals(0, opt.getScore().compareTo(actual), "正向题应原样计分");
            assertEquals(2, actual.scale(), "有效分必须统一保留 2 位小数");
        }

        // 反向：effective = reverseMaxPlusMin(5) - raw
        Map<String, String> expected = Map.of("A", "4.00", "B", "3.00", "C", "2.00", "D", "1.00");
        for (OptionConfig opt : reverse.getOptions()) {
            BigDecimal actual = engine.computeEffectiveItemScore(reverse, opt, sumRule());
            assertEquals(0, new BigDecimal(expected.get(opt.getOptCode())).compareTo(actual),
                    "反向题 " + opt.getOptCode() + " 转换结果错误");
        }

        // 规则缺省时回退为题目选项 max+min（4+1=5），与 reverseMaxPlusMin=5 等价
        for (OptionConfig opt : reverse.getOptions()) {
            BigDecimal withRule = engine.computeEffectiveItemScore(reverse, opt, sumRule());
            BigDecimal withoutRule = engine.computeEffectiveItemScore(reverse, opt, null);
            assertEquals(0, withRule.compareTo(withoutRule),
                    "规则缺省时应按 max+min 回退且结果一致");
        }

        // 显式 reverseMaxPlusMin=7 时按 7 - raw 转换
        ScoringRuleConfig custom = ScoringRuleConfig.builder()
                .ruleVersion("R-7")
                .formula("SUM")
                .reverseMaxPlusMin(new BigDecimal("7"))
                .dimensions(List.of(new DimensionRule("MOOD", "情绪状态", "SUM")))
                .levels(List.of(level("GOOD", "状态良好", "1", "100", "a")))
                .build();
        OptionConfig optA = reverse.getOptions().get(0);
        assertEquals(0, new BigDecimal("6.00").compareTo(engine.computeEffectiveItemScore(reverse, optA, custom)),
                "reverseMaxPlusMin=7 时 A(1) 应转换为 6.00");
    }

    // ------------------------------------------------------------------
    // 维度分与预置答卷复算（seed.sql 学生1 / 学生2）
    // ------------------------------------------------------------------

    @Test
    void dimensionScoresForSeededAnswerSheets() {
        // 学生1：A,B,A,D,B,C => STRESS=3, MOOD=2, SLEEP=4, total=9.00 GOOD
        ScoringResult s1 = engine.calculate(sixQuestions(), sumRule(), answers("A", "B", "A", "D", "B", "C"));
        assertEquals(0, new BigDecimal("3.00").compareTo(s1.getDimensionScores().get("STRESS")));
        assertEquals(0, new BigDecimal("2.00").compareTo(s1.getDimensionScores().get("MOOD")));
        assertEquals(0, new BigDecimal("4.00").compareTo(s1.getDimensionScores().get("SLEEP")));
        assertEquals(0, new BigDecimal("9.00").compareTo(s1.getTotalScore()));
        assertEquals("GOOD", s1.getLevelCode());

        // 学生2：C,C,B,B,C,B => STRESS=6, MOOD=5, SLEEP=6, total=17.00 MODERATE
        ScoringResult s2 = engine.calculate(sixQuestions(), sumRule(), answers("C", "C", "B", "B", "C", "B"));
        assertEquals(0, new BigDecimal("6.00").compareTo(s2.getDimensionScores().get("STRESS")));
        assertEquals(0, new BigDecimal("5.00").compareTo(s2.getDimensionScores().get("MOOD")));
        assertEquals(0, new BigDecimal("6.00").compareTo(s2.getDimensionScores().get("SLEEP")));
        assertEquals(0, new BigDecimal("17.00").compareTo(s2.getTotalScore()));
        assertEquals("MODERATE", s2.getLevelCode());

        // 维度 VO 列表（含名称）与 map 一致
        assertEquals(3, s1.getDimensions().size());
        assertEquals("学习压力", s1.getDimensions().get(0).getName());
        assertEquals(0, new BigDecimal("3.00").compareTo(s1.getDimensions().get(0).getScore()));
    }

    // ------------------------------------------------------------------
    // AVG 均值公式核验
    // ------------------------------------------------------------------

    @Test
    void avgTotalFormulaDividesEffectiveScoreSumByAnswerCount() {
        // 作答 B,A,A,A,A,D => 2+1+1+4+1+1 = 10.00，AVG = 10/6 = 1.6666... -> HALF_UP 1.67
        ScoringRuleConfig avgRule = ScoringRuleConfig.builder()
                .ruleVersion("R-AVG")
                .formula("AVG")
                .reverseMaxPlusMin(new BigDecimal("5"))
                .dimensions(List.of(
                        new DimensionRule("STRESS", "学习压力", "SUM"),
                        new DimensionRule("MOOD", "情绪状态", "SUM"),
                        new DimensionRule("SLEEP", "睡眠与作息", "SUM")))
                .levels(List.of(
                        level("LOW", "低分", "0", "2", "低分建议"),
                        level("HIGH_SCORE", "高分", "2.01", "4", "高分建议")))
                .build();

        ScoringResult result = engine.calculate(sixQuestions(), avgRule, answers("B", "A", "A", "A", "A", "D"));
        assertEquals(0, new BigDecimal("10.00").compareTo(
                result.getDimensionScores().get("STRESS")
                        .add(result.getDimensionScores().get("MOOD"))
                        .add(result.getDimensionScores().get("SLEEP"))),
                "三维度之和应为 10.00");
        assertEquals(0, new BigDecimal("1.67").compareTo(result.getTotalScore()),
                "AVG 公式应为 sum/6 并按 HALF_UP 保留 2 位（10/6 -> 1.67），实际 " + result.getTotalScore());
        assertEquals("LOW", result.getLevelCode(), "1.67 应命中 [0,2] 区间");
    }

    @Test
    void avgDimensionFormulaComputesPerDimensionMean() {
        ScoringRuleConfig rule = ScoringRuleConfig.builder()
                .ruleVersion("R-DIM-AVG")
                .formula("SUM")
                .reverseMaxPlusMin(new BigDecimal("5"))
                .dimensions(List.of(
                        new DimensionRule("STRESS", "学习压力", "AVG"),
                        new DimensionRule("MOOD", "情绪状态", "SUM"),
                        new DimensionRule("SLEEP", "睡眠与作息", "SUM")))
                .levels(List.of(level("GOOD", "状态良好", "1", "100", "a")))
                .build();

        // Q1=A(1), Q2=B(2) -> STRESS AVG = 1.50
        ScoringResult result = engine.calculate(sixQuestions(), rule, answers("A", "B", "A", "D", "B", "C"));
        assertEquals(0, new BigDecimal("1.50").compareTo(result.getDimensionScores().get("STRESS")),
                "STRESS 维度 AVG 公式应为 (1+2)/2 = 1.50");
        assertEquals(0, new BigDecimal("2.00").compareTo(result.getDimensionScores().get("MOOD")));
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getDimensionScores().get("SLEEP")));
        // 总分公式 SUM：仍按全部有效分求和 = 9.00
        assertEquals(0, new BigDecimal("9.00").compareTo(result.getTotalScore()));
    }

    @Test
    void sumDimensionsFormulaAggregatesDimensionScores() {
        ScoringRuleConfig rule = ScoringRuleConfig.builder()
                .ruleVersion("R-SUMDIM")
                .formula("SUM_DIMENSIONS")
                .reverseMaxPlusMin(new BigDecimal("5"))
                .dimensions(List.of(
                        new DimensionRule("STRESS", "学习压力", "SUM"),
                        new DimensionRule("MOOD", "情绪状态", "SUM"),
                        new DimensionRule("SLEEP", "睡眠与作息", "SUM")))
                .levels(List.of(
                        level("GOOD", "状态良好", "6", "10", "a"),
                        level("MILD", "轻度压力", "11", "15", "b"),
                        level("MODERATE", "中度困扰", "16", "20", "c"),
                        level("HIGH", "重点关注", "21", "24", "d")))
                .build();

        ScoringResult result = engine.calculate(sixQuestions(), rule, answers("A", "B", "A", "D", "B", "C"));
        BigDecimal dimSum = result.getDimensionScores().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, dimSum.compareTo(result.getTotalScore()), "SUM_DIMENSIONS 应等于维度分之和");
        assertEquals(0, new BigDecimal("9.00").compareTo(result.getTotalScore()));
        assertEquals("GOOD", result.getLevelCode());
    }

    // ------------------------------------------------------------------
    // 非法作答的精确定位报错（计分引擎层）
    // ------------------------------------------------------------------

    @Test
    void illegalAnswersAreRejectedWithPreciseLocation() {
        // 必答题漏答
        BizAssert.expect(() -> engine.calculate(sixQuestions(), sumRule(),
                        answers("A", "A", "A", "A", "A")),
                "第6题为必答题");

        // 未知题号（不属于当前问卷版本）
        List<AnswerInput> unknownNo = new ArrayList<>(answers("A", "A", "A", "A", "A", "A"));
        unknownNo.add(new AnswerInput(7, "A"));
        BizAssert.expect(() -> engine.calculate(sixQuestions(), sumRule(), unknownNo),
                "未知题号: 第7题");

        // 重复题号
        List<AnswerInput> duplicated = new ArrayList<>(answers("A", "A", "A", "A", "A", "A"));
        duplicated.add(new AnswerInput(3, "B"));
        BizAssert.expect(() -> engine.calculate(sixQuestions(), sumRule(), duplicated),
                "第3题存在重复提交答案");

        // 非法选项编码
        BizAssert.expect(() -> engine.calculate(sixQuestions(), sumRule(),
                        answers("A", "A", "A", "A", "A", "Z")),
                "第6题所选选项[Z]不属于该题有效选项");

        // 空选项编码
        BizAssert.expect(() -> engine.calculate(sixQuestions(), sumRule(),
                        answers("A", "A", "A", "A", "A", " ")),
                "第6题所选选项编码不能为空");

        // 空题干问卷快照
        BizAssert.expect(() -> engine.calculate(List.of(), sumRule(), answers("A")),
                "问卷快照题目为空");
    }

    // ------------------------------------------------------------------
    // seed.sql 口令哈希核验（报告项：不匹配时输出可直接替换的合法哈希）
    // ------------------------------------------------------------------

    @Test
    void seedSqlPasswordHashMatchesPlain123456() throws Exception {
        Path seedSql = locateSeedSql();
        assumeTrue(seedSql != null, "未找到 seed.sql，跳过口令哈希核验（报告项）");

        String text = Files.readString(seedSql, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("'(\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53})'").matcher(text);
        List<String> hashes = new ArrayList<>();
        while (m.find()) {
            if (!hashes.contains(m.group(1))) {
                hashes.add(m.group(1));
            }
        }
        assertFalse(hashes.isEmpty(), "seed.sql 中未找到任何 BCrypt 口令哈希，无法核验");
        System.out.println("[seed.sql 口令核验] 发现 " + hashes.size() + " 个唯一 BCrypt 哈希，逐一校验是否匹配 \"123456\"...");

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        List<String> mismatched = new ArrayList<>();
        for (String h : hashes) {
            boolean ok = encoder.matches("123456", h);
            System.out.println("  " + h + "  =>  matches(\"123456\") = " + ok);
            if (!ok) {
                mismatched.add(h);
            }
        }

        if (mismatched.isEmpty()) {
            System.out.println("[seed.sql 口令核验] PASS：全部哈希均匹配 \"123456\"");
            return;
        }

        String replacement = encoder.encode("123456");
        System.out.println("========================================================================");
        System.out.println("[seed.sql 口令核验] FAIL：以下哈希与文档口令 \"123456\" 不匹配 -> " + mismatched);
        System.out.println("[seed.sql 口令核验] 请用下面这个**实测匹配 \"123456\"** 的 BCrypt 哈希替换 seed.sql 中的对应字面量：");
        System.out.println("    " + replacement);
        System.out.println("[seed.sql 口令核验] 参考：seed.sql 第8行注释中的");
        System.out.println("    $2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi");
        System.out.println("  经实测同样 matches(\"123456\") = true，可直接复用。");
        System.out.println("========================================================================");

        // 自检：新生成的哈希必须确实匹配 "123456"，且与待替换的旧哈希不同
        assertTrue(encoder.matches("123456", replacement), "测试内部错误：生成的替代哈希未匹配 123456");
        assertNotEquals(mismatched.get(0), replacement);

        org.junit.jupiter.api.Assertions.fail("seed.sql 中的 BCrypt 哈希与口令 \"123456\" 不匹配（真实缺陷）。\n"
                + "不匹配哈希: " + mismatched + "\n"
                + "请将 seed.sql 中上述哈希替换为以下实测匹配 \"123456\" 的哈希:\n"
                + "    " + replacement + "\n"
                + "（seed.sql 第8行注释里的 $2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi 亦实测可匹配 \"123456\"）");
    }

    private Path locateSeedSql() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("seed.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** 捕获 BizException 并校验错误信息包含精确定位片段。 */
    private static final class BizAssert {
        static void expect(ThrowingRunnable run, String messageFragment) {
            try {
                run.run();
                throw new AssertionError("期望抛出业务异常，但未抛出，期望包含: " + messageFragment);
            } catch (com.psych.assessment.common.BizException ex) {
                assertEquals(400, ex.getCode(), "业务异常码应为 400");
                assertTrue(ex.getMessage() != null && ex.getMessage().contains(messageFragment),
                        "错误信息应包含 [" + messageFragment + "]，实际: " + ex.getMessage());
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError("抛出了非预期异常类型: " + e, e);
            }
        }

        @FunctionalInterface
        interface ThrowingRunnable {
            void run() throws Exception;
        }
    }
}
