package com.psych.assessment.scoring;

import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.QuestionnaireDtos.DimensionRule;
import com.psych.assessment.dto.QuestionnaireDtos.LevelRule;
import com.psych.assessment.dto.QuestionnaireDtos.OptionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireUpsertRequest;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class QuestionnaireValidator {

    private static final BigDecimal MAX_OPTION_SCORE = new BigDecimal("100");

    public void validateUpsertRequest(QuestionnaireUpsertRequest req) {
        if (req == null) {
            throw new BizException(400, "问卷配置请求体不能为空");
        }
        if (req.getCode() == null || req.getCode().isBlank()) {
            throw new BizException(400, "问卷编码[code]不能为空");
        }
        if (req.getCode().trim().length() > 64) {
            throw new BizException(400, "问卷编码[code]长度不能超过64个字符");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(400, "问卷标题[title]不能为空");
        }
        if (req.getTitle().trim().length() > 128) {
            throw new BizException(400, "问卷标题[title]长度不能超过128个字符");
        }

        validateQuestionsAndRule(req.getQuestions(), req.getScoringRule());
    }

    public void validateQuestionsAndRule(List<QuestionConfig> questions, ScoringRuleConfig rule) {
        if (questions == null || questions.isEmpty()) {
            throw new BizException(400, "题目列表[questions]不能为空，至少需配置1道题目");
        }
        if (rule == null) {
            throw new BizException(400, "计分规则[scoringRule]不能为空");
        }

        validateScoringRuleStructure(rule);

        Set<String> declaredDimensions = new HashSet<>();
        if (rule.getDimensions() != null) {
            for (DimensionRule d : rule.getDimensions()) {
                declaredDimensions.add(d.getCode().trim());
            }
        }

        Set<Integer> seenNos = new HashSet<>();
        for (int i = 0; i < questions.size(); i++) {
            QuestionConfig q = questions.get(i);
            int displayIdx = i + 1;
            if (q == null) {
                throw new BizException(400, "第" + displayIdx + "题配置对象不能为空");
            }
            Integer qNo = q.getQuestionNo();
            if (qNo == null || qNo < 1) {
                throw new BizException(400, "第" + displayIdx + "题[questionNo]: 题号必须为大于等于1的整数");
            }
            if (!seenNos.add(qNo)) {
                throw new BizException(400, "第" + qNo + "题[questionNo]: 题号重复，请保持题号唯一且递增");
            }
            if (q.getStem() == null || q.getStem().isBlank()) {
                throw new BizException(400, "第" + qNo + "题[stem]: 题干内容不能为空");
            }
            String qType = q.getQuestionType() == null ? "SINGLE" : q.getQuestionType().trim().toUpperCase();
            if (!"SINGLE".equals(qType)) {
                throw new BizException(400, "第" + qNo + "题[questionType]: 当前仅支持单选题(SINGLE)，非法值: " + q.getQuestionType());
            }
            q.setQuestionType(qType);

            if (q.getDimensionCode() == null || q.getDimensionCode().isBlank()) {
                throw new BizException(400, "第" + qNo + "题[dimensionCode]: 归属维度编码不能为空");
            }
            String dimCode = q.getDimensionCode().trim();
            if (!declaredDimensions.isEmpty() && !declaredDimensions.contains(dimCode)) {
                throw new BizException(400, "第" + qNo + "题[dimensionCode]: 维度编码[" + dimCode + "]未在计分规则dimensions中定义");
            }

            Integer isRev = q.getIsReverse() == null ? 0 : q.getIsReverse();
            if (isRev != 0 && isRev != 1) {
                throw new BizException(400, "第" + qNo + "题[isReverse]: 正反向计分标识仅允许0或1");
            }
            q.setIsReverse(isRev);

            Integer reqFlag = q.getRequired() == null ? 1 : q.getRequired();
            if (reqFlag != 0 && reqFlag != 1) {
                throw new BizException(400, "第" + qNo + "题[required]: 必答标识仅允许0或1");
            }
            q.setRequired(reqFlag);

            List<OptionConfig> opts = q.getOptions();
            if (opts == null || opts.size() < 2) {
                throw new BizException(400, "第" + qNo + "题[options]: 每道单选题至少需要配置2个选项");
            }

            Set<String> optCodes = new HashSet<>();
            for (int j = 0; j < opts.size(); j++) {
                OptionConfig opt = opts.get(j);
                String optLoc = "第" + qNo + "题第" + (j + 1) + "个选项";
                if (opt == null) {
                    throw new BizException(400, optLoc + ": 选项对象不能为空");
                }
                if (opt.getOptCode() == null || opt.getOptCode().isBlank()) {
                    throw new BizException(400, optLoc + "[optCode]: 选项编码不能为空");
                }
                String optCode = opt.getOptCode().trim();
                opt.setOptCode(optCode);
                String codeLoc = "第" + qNo + "题选项" + optCode;
                if (!optCodes.add(optCode)) {
                    throw new BizException(400, codeLoc + "[optCode]: 同一题内选项编码[" + optCode + "]重复");
                }
                if (opt.getLabel() == null || opt.getLabel().isBlank()) {
                    throw new BizException(400, codeLoc + "[label]: 选项文本不能为空");
                }
                if (opt.getScore() == null) {
                    throw new BizException(400, codeLoc + "[score]: 选项分值不能为空");
                }
                if (opt.getScore().compareTo(BigDecimal.ZERO) < 0) {
                    throw new BizException(400, codeLoc + "[score]: 分值不能小于0");
                }
                if (opt.getScore().compareTo(MAX_OPTION_SCORE) > 0) {
                    throw new BizException(400, codeLoc + "[score]: 分值不能大于100");
                }
                if (opt.getSort() == null) {
                    opt.setSort(j + 1);
                }

                if (isRev == 1 && rule.getReverseMaxPlusMin() != null) {
                    BigDecimal reversed = rule.getReverseMaxPlusMin().subtract(opt.getScore());
                    if (reversed.compareTo(BigDecimal.ZERO) < 0) {
                        throw new BizException(400, codeLoc + "[score]: 反向计分转换后分值异常(" + reversed + " < 0)，请检查reverseMaxPlusMin与选项分值");
                    }
                }
            }
        }

        List<Integer> sortedNos = new ArrayList<>(seenNos);
        sortedNos.sort(Comparator.naturalOrder());
        for (int idx = 0; idx < sortedNos.size(); idx++) {
            int expected = idx + 1;
            if (sortedNos.get(idx) != expected) {
                throw new BizException(400, "题目顺序校验失败: 缺少第" + expected + "题[questionNo]，题号应从1连续编号");
            }
        }
    }

    private void validateScoringRuleStructure(ScoringRuleConfig rule) {
        if (rule.getFormula() == null || rule.getFormula().isBlank()) {
            throw new BizException(400, "计分规则[scoringRule.formula]: 总分计算公式不能为空(仅支持SUM或AVG)");
        }
        String formula = rule.getFormula().trim().toUpperCase();
        if (!"SUM".equals(formula) && !"AVG".equals(formula) && !"SUM_DIMENSIONS".equals(formula)) {
            throw new BizException(400, "计分规则[scoringRule.formula]: 不支持的公式[" + rule.getFormula() + "]，仅支持SUM或AVG");
        }
        rule.setFormula(formula);

        if (rule.getReverseMaxPlusMin() != null && rule.getReverseMaxPlusMin().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "计分规则[scoringRule.reverseMaxPlusMin]: 反向计分常数必须大于0");
        }

        if (rule.getDimensions() != null) {
            Set<String> dimCodes = new HashSet<>();
            for (int i = 0; i < rule.getDimensions().size(); i++) {
                DimensionRule dim = rule.getDimensions().get(i);
                String dimLoc = "计分规则第" + (i + 1) + "个维度";
                if (dim == null || dim.getCode() == null || dim.getCode().isBlank()) {
                    throw new BizException(400, dimLoc + "[code]: 维度编码不能为空");
                }
                String code = dim.getCode().trim();
                if (!dimCodes.add(code)) {
                    throw new BizException(400, dimLoc + "[code]: 维度编码[" + code + "]重复");
                }
                if (dim.getName() == null || dim.getName().isBlank()) {
                    throw new BizException(400, "维度[" + code + "][name]: 维度名称不能为空");
                }
                String dimFormula = dim.getFormula() == null || dim.getFormula().isBlank()
                        ? "SUM" : dim.getFormula().trim().toUpperCase();
                if (!"SUM".equals(dimFormula) && !"AVG".equals(dimFormula)) {
                    throw new BizException(400, "维度[" + code + "][formula]: 公式仅支持SUM或AVG");
                }
                dim.setFormula(dimFormula);
            }
        }

        List<LevelRule> levels = rule.getLevels();
        if (levels == null || levels.isEmpty()) {
            throw new BizException(400, "计分规则[scoringRule.levels]: 结果等级区间不能为空，至少需配置1个区间");
        }

        Set<String> levelCodes = new HashSet<>();
        for (int i = 0; i < levels.size(); i++) {
            LevelRule lv = levels.get(i);
            String lvLoc = "计分规则第" + (i + 1) + "个结果区间";
            if (lv == null) {
                throw new BizException(400, lvLoc + ": 区间对象不能为空");
            }
            if (lv.getCode() == null || lv.getCode().isBlank()) {
                throw new BizException(400, lvLoc + "[code]: 区间编码不能为空");
            }
            String lvCode = lv.getCode().trim();
            if (!levelCodes.add(lvCode)) {
                throw new BizException(400, "结果区间[" + lvCode + "][code]: 区间编码重复");
            }
            if (lv.getName() == null || lv.getName().isBlank()) {
                throw new BizException(400, "结果区间[" + lvCode + "][name]: 区间名称不能为空");
            }
            if (lv.getMin() == null || lv.getMax() == null) {
                throw new BizException(400, "结果区间[" + lvCode + "][min/max]: 区间上下界分值不能为空");
            }
            if (lv.getMin().compareTo(lv.getMax()) > 0) {
                throw new BizException(400, "结果区间[" + lvCode + "][min/max]: 下界(" + lv.getMin() + ")不能大于上界(" + lv.getMax() + ")");
            }
            if (lv.getMinInclusive() == null) {
                lv.setMinInclusive(true);
            }
            if (lv.getMaxInclusive() == null) {
                lv.setMaxInclusive(true);
            }
        }

        for (int i = 0; i < levels.size(); i++) {
            for (int j = i + 1; j < levels.size(); j++) {
                LevelRule a = levels.get(i);
                LevelRule b = levels.get(j);
                if (intervalsOverlap(a, b)) {
                    throw new BizException(400, "结果区间[" + a.getCode() + "]与区间[" + b.getCode() + "]分值边界存在重叠");
                }
            }
        }
    }

    private boolean intervalsOverlap(LevelRule a, LevelRule b) {
        int cmpAb = a.getMax().compareTo(b.getMin());
        if (cmpAb < 0) {
            return false;
        }
        if (cmpAb == 0 && (!Boolean.TRUE.equals(a.getMaxInclusive()) || !Boolean.TRUE.equals(b.getMinInclusive()))) {
            return false;
        }
        int cmpBa = b.getMax().compareTo(a.getMin());
        if (cmpBa < 0) {
            return false;
        }
        if (cmpBa == 0 && (!Boolean.TRUE.equals(b.getMaxInclusive()) || !Boolean.TRUE.equals(a.getMinInclusive()))) {
            return false;
        }
        return true;
    }
}
