package com.psych.assessment.scoring;

import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.H5AndReportDtos.AnswerInput;
import com.psych.assessment.dto.H5AndReportDtos.DimensionScoreVO;
import com.psych.assessment.dto.QuestionnaireDtos.DimensionRule;
import com.psych.assessment.dto.QuestionnaireDtos.LevelRule;
import com.psych.assessment.dto.QuestionnaireDtos.OptionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure configuration-driven deterministic scoring engine (REQ07).
 * Same snapshot + same answers => identical item scores, dimension scores, total score, and level.
 */
@Component
public class ScoringEngine {

    public BigDecimal computeEffectiveItemScore(QuestionConfig question, OptionConfig selectedOption, ScoringRuleConfig rule) {
        BigDecimal rawOptScore = selectedOption.getScore();
        boolean reverse = question.getIsReverse() != null && question.getIsReverse() == 1;
        if (!reverse) {
            return rawOptScore.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal base;
        if (rule != null && rule.getReverseMaxPlusMin() != null) {
            base = rule.getReverseMaxPlusMin();
        } else {
            BigDecimal max = question.getOptions().stream()
                    .map(OptionConfig::getScore)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            BigDecimal min = question.getOptions().stream()
                    .map(OptionConfig::getScore)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            base = max.add(min);
        }
        return base.subtract(rawOptScore).setScale(2, RoundingMode.HALF_UP);
    }

    public ScoringResult calculate(
            List<QuestionConfig> questions,
            ScoringRuleConfig rule,
            List<AnswerInput> answers) {
        if (questions == null || questions.isEmpty()) {
            throw new BizException(400, "问卷快照题目为空，无法执行计分");
        }
        if (rule == null) {
            throw new BizException(400, "计分规则快照为空，无法执行计分");
        }

        Map<Integer, QuestionConfig> questionMap = new LinkedHashMap<>();
        for (QuestionConfig q : questions) {
            questionMap.put(q.getQuestionNo(), q);
        }

        Map<Integer, String> answerMap = new LinkedHashMap<>();
        if (answers != null) {
            for (AnswerInput ans : answers) {
                if (ans == null || ans.getQuestionNo() == null) {
                    throw new BizException(400, "作答明细存在空的题号[questionNo]");
                }
                if (!questionMap.containsKey(ans.getQuestionNo())) {
                    throw new BizException(400, "存在不属于当前问卷版本的未知题号: 第" + ans.getQuestionNo() + "题");
                }
                if (answerMap.containsKey(ans.getQuestionNo())) {
                    throw new BizException(400, "第" + ans.getQuestionNo() + "题存在重复提交答案");
                }
                if (ans.getSelectedOptCode() == null || ans.getSelectedOptCode().isBlank()) {
                    throw new BizException(400, "第" + ans.getQuestionNo() + "题所选选项编码不能为空");
                }
                answerMap.put(ans.getQuestionNo(), ans.getSelectedOptCode().trim());
            }
        }

        Map<Integer, BigDecimal> itemScores = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> dimensionBuckets = new LinkedHashMap<>();
        List<BigDecimal> allEffectiveScores = new ArrayList<>();

        if (rule.getDimensions() != null) {
            for (DimensionRule d : rule.getDimensions()) {
                dimensionBuckets.put(d.getCode(), new ArrayList<>());
            }
        }

        for (QuestionConfig q : questions) {
            String selectedCode = answerMap.get(q.getQuestionNo());
            boolean isRequired = q.getRequired() == null || q.getRequired() == 1;
            if (selectedCode == null) {
                if (isRequired) {
                    throw new BizException(400, "第" + q.getQuestionNo() + "题为必答题，尚未作答");
                }
                continue;
            }

            OptionConfig matchedOpt = q.getOptions().stream()
                    .filter(o -> o.getOptCode().equals(selectedCode))
                    .findFirst()
                    .orElseThrow(() -> new BizException(
                            400,
                            "第" + q.getQuestionNo() + "题所选选项[" + selectedCode + "]不属于该题有效选项"
                    ));

            BigDecimal effectiveScore = computeEffectiveItemScore(q, matchedOpt, rule);
            itemScores.put(q.getQuestionNo(), effectiveScore);
            allEffectiveScores.add(effectiveScore);

            String dimCode = q.getDimensionCode() == null ? "DEFAULT" : q.getDimensionCode();
            dimensionBuckets.computeIfAbsent(dimCode, k -> new ArrayList<>()).add(effectiveScore);
        }

        Map<String, String> dimNames = new LinkedHashMap<>();
        Map<String, String> dimFormulas = new LinkedHashMap<>();
        if (rule.getDimensions() != null) {
            for (DimensionRule d : rule.getDimensions()) {
                dimNames.put(d.getCode(), d.getName());
                dimFormulas.put(d.getCode(), d.getFormula() == null ? "SUM" : d.getFormula().toUpperCase());
            }
        }

        Map<String, BigDecimal> dimensionScores = new LinkedHashMap<>();
        List<DimensionScoreVO> dimensionScoreList = new ArrayList<>();
        for (Map.Entry<String, List<BigDecimal>> entry : dimensionBuckets.entrySet()) {
            String dimCode = entry.getKey();
            List<BigDecimal> vals = entry.getValue();
            String dimFormula = dimFormulas.getOrDefault(dimCode, "SUM");
            BigDecimal dimScore = aggregate(vals, dimFormula);
            dimensionScores.put(dimCode, dimScore);
            dimensionScoreList.add(DimensionScoreVO.builder()
                    .code(dimCode)
                    .name(dimNames.getOrDefault(dimCode, dimCode))
                    .score(dimScore)
                    .build());
        }

        String totalFormula = rule.getFormula() == null ? "SUM" : rule.getFormula().trim().toUpperCase();
        BigDecimal totalScore;
        if ("SUM_DIMENSIONS".equals(totalFormula)) {
            totalScore = aggregate(new ArrayList<>(dimensionScores.values()), "SUM");
        } else {
            totalScore = aggregate(allEffectiveScores, totalFormula);
        }

        LevelRule matchedLevel = matchLevel(totalScore, rule.getLevels());
        String levelCode = matchedLevel != null ? matchedLevel.getCode() : "UNCLASSIFIED";
        String levelName = matchedLevel != null ? matchedLevel.getName() : "未匹配区间";
        String advice = matchedLevel != null ? matchedLevel.getAdvice() : "已完成本次心理健康模拟评估。";

        return ScoringResult.builder()
                .itemScores(itemScores)
                .dimensionScores(dimensionScores)
                .dimensions(dimensionScoreList)
                .totalScore(totalScore)
                .levelCode(levelCode)
                .levelName(levelName)
                .advice(advice)
                .ruleVersion(rule.getRuleVersion())
                .build();
    }

    private BigDecimal aggregate(List<BigDecimal> values, String formula) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        if ("AVG".equalsIgnoreCase(formula)) {
            return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private LevelRule matchLevel(BigDecimal score, List<LevelRule> levels) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        for (LevelRule lv : levels) {
            boolean minInc = lv.getMinInclusive() == null || lv.getMinInclusive();
            boolean maxInc = lv.getMaxInclusive() == null || lv.getMaxInclusive();
            int cmpMin = score.compareTo(lv.getMin());
            int cmpMax = score.compareTo(lv.getMax());
            boolean lowerOk = minInc ? cmpMin >= 0 : cmpMin > 0;
            boolean upperOk = maxInc ? cmpMax <= 0 : cmpMax < 0;
            if (lowerOk && upperOk) {
                return lv;
            }
        }
        return null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoringResult {
        private Map<Integer, BigDecimal> itemScores;
        private Map<String, BigDecimal> dimensionScores;
        private List<DimensionScoreVO> dimensions;
        private BigDecimal totalScore;
        private String levelCode;
        private String levelName;
        private String advice;
        private String ruleVersion;
    }
}
