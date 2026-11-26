package com.psych.assessment.service;

import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.QuestionnaireDtos.DimensionRule;
import com.psych.assessment.dto.QuestionnaireDtos.LevelRule;
import com.psych.assessment.dto.QuestionnaireDtos.OptionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireDetailVO;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireUpsertRequest;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.dto.XlsxDtos.ParsedQuestionnaireXlsxVO;
import com.psych.assessment.dto.XlsxDtos.XlsxErrorPayload;
import com.psych.assessment.dto.XlsxDtos.XlsxRowError;
import com.psych.assessment.scoring.QuestionnaireValidator;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.psych.assessment.service.XlsxTemplateAndCodec.MAX_QUESTION_ROWS;
import static com.psych.assessment.service.XlsxTemplateAndCodec.SHEET_DIMENSIONS;
import static com.psych.assessment.service.XlsxTemplateAndCodec.SHEET_LEVELS;
import static com.psych.assessment.service.XlsxTemplateAndCodec.SHEET_QUESTIONS;
import static com.psych.assessment.service.XlsxTemplateAndCodec.buildHeaderIndex;
import static com.psych.assessment.service.XlsxTemplateAndCodec.findColumn;
import static com.psych.assessment.service.XlsxTemplateAndCodec.findSheet;
import static com.psych.assessment.service.XlsxTemplateAndCodec.isRowEmpty;
import static com.psych.assessment.service.XlsxTemplateAndCodec.textAt;

/**
 * 题库/问卷 XLSX 解析与草稿导入（契约 7.3）。
 *
 * <p>全量收集行级错误后一次性返回 400 + {@code {errorCount, errors}}，任何错误都零落库。
 */
@Service
@RequiredArgsConstructor
public class XlsxImportService {

    private static final BigDecimal MAX_OPTION_SCORE = new BigDecimal("100");
    private static final char[] OPTION_LETTERS = {'A', 'B', 'C', 'D', 'E', 'F'};

    private final XlsxTemplateAndCodec codec;
    private final QuestionnaireValidator validator;
    private final QuestionnaireService questionnaireService;
    private final AuditLogService auditLogService;

    // ==================================================================
    // 7.3 解析（不落库，编辑器回填/预览）
    // ==================================================================

    public ParsedQuestionnaireXlsxVO parseQuestionnaireXlsx(
            MultipartFile file, String formula, Integer reverseMaxPlusMin, String ruleVersion) {
        QuestionnaireParseResult parsed = doParse(file, formula, reverseMaxPlusMin, ruleVersion, false);
        return ParsedQuestionnaireXlsxVO.builder()
                .questionCount(parsed.questions.size())
                .hasScoringRule(parsed.scoringRule != null)
                .questions(parsed.questions)
                .scoringRule(parsed.scoringRule)
                .build();
    }

    // ==================================================================
    // 7.3 单事务导入为问卷草稿（latestVersion = 0，任何错误零落库）
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public QuestionnaireDetailVO importQuestionnaireAsDraft(
            MultipartFile file,
            String code,
            String title,
            String description,
            String formula,
            Integer reverseMaxPlusMin,
            String ruleVersion) {
        if (code == null || code.isBlank()) {
            throw new BizException(400, "问卷编码[code]不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new BizException(400, "问卷标题[title]不能为空");
        }
        try {
            QuestionnaireParseResult parsed = doParse(file, formula, reverseMaxPlusMin, ruleVersion, true);
            QuestionnaireUpsertRequest req = QuestionnaireUpsertRequest.builder()
                    .code(code)
                    .title(title)
                    .description(description)
                    .scoringRule(parsed.scoringRule)
                    .questions(parsed.questions)
                    .build();
            // 复用 QuestionnaireService.createQuestionnaire 的全量校验 + 原子落库
            QuestionnaireDetailVO vo = questionnaireService.createQuestionnaire(req);
            java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("code", code);
            detail.put("title", title);
            detail.put("questionCount", parsed.questions.size());
            auditLogService.recordSuccess(AuditLogService.ACT_XLSX_IMPORT,
                    AuditLogService.RES_QUESTIONNAIRE, vo.getId(), "XLSX导入问卷[" + code + "]", detail);
            return vo;
        } catch (BizException ex) {
            auditLogService.recordFailure(AuditLogService.ACT_XLSX_IMPORT,
                    AuditLogService.RES_QUESTIONNAIRE, null, "XLSX导入问卷失败[" + code + "]: " + ex.getMessage(), null);
            throw ex;
        }
    }

    // ==================================================================
    // 全量解析核心
    // ==================================================================

    private QuestionnaireParseResult doParse(
            MultipartFile file, String formula, Integer reverseMaxPlusMin, String ruleVersion, boolean requireFullRule) {
        List<XlsxRowError> errors = new ArrayList<>();
        List<QuestionConfig> questions;
        List<DimensionRule> dimensions = null;
        List<LevelRule> levels = null;
        boolean hasDimsSheet;
        boolean hasLevelsSheet;

        try (Workbook workbook = codec.validateAndOpen(file)) {
            DataFormatter formatter = new DataFormatter();
            hasDimsSheet = findSheet(workbook, SHEET_DIMENSIONS) != null;
            hasLevelsSheet = findSheet(workbook, SHEET_LEVELS) != null;

            if (requireFullRule) {
                if (!hasLevelsSheet) {
                    throw new BizException(400, "导入问卷必须包含[计分与区间]Sheet，请下载标准模板补全后重新上传");
                }
                if (!hasDimsSheet) {
                    throw new BizException(400, "导入问卷必须包含[维度配置]Sheet，请下载标准模板补全后重新上传");
                }
            }

            questions = parseQuestionSheet(workbook, formatter, errors);
            if (hasDimsSheet) {
                dimensions = parseDimensionSheet(workbook, formatter, errors);
            }
            if (hasLevelsSheet) {
                levels = parseLevelSheet(workbook, formatter, errors);
            }

            ScoringRuleConfig rule = null;
            if (levels != null && !levels.isEmpty()) {
                rule = ScoringRuleConfig.builder()
                        .ruleVersion(normalizeRuleVersion(ruleVersion))
                        .formula(normalizeFormula(formula))
                        .reverseMaxPlusMin(resolveReverseMaxPlusMin(reverseMaxPlusMin, questions))
                        .dimensions(dimensions == null || dimensions.isEmpty() ? null : dimensions)
                        .levels(levels)
                        .build();
            }

            // 全部行级错误清零后，再执行深度业务校验（区间重叠、维度归属、题号连续性等）
            if (rule != null && errors.isEmpty()) {
                try {
                    validator.validateQuestionsAndRule(new ArrayList<>(questions), rule);
                } catch (BizException ex) {
                    errors.add(XlsxRowError.builder()
                            .sheet("全文件")
                            .row(null)
                            .column(null)
                            .message(ex.getMessage())
                            .build());
                }
            }

            if (!errors.isEmpty()) {
                throw new BizException(
                        400,
                        "XLSX校验未通过，共发现 " + errors.size() + " 处错误，请修正后重新上传",
                        XlsxErrorPayload.of(errors));
            }
            return new QuestionnaireParseResult(questions, rule, hasDimsSheet, hasLevelsSheet);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(400, "文件不是有效的 .xlsx 格式");
        }
    }

    // ==================================================================
    // 题目列表 Sheet
    // ==================================================================

    private List<QuestionConfig> parseQuestionSheet(Workbook workbook, DataFormatter formatter, List<XlsxRowError> errors) {
        Sheet sheet = findSheet(workbook, SHEET_QUESTIONS);
        String sheetName = SHEET_QUESTIONS;
        if (sheet == null) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BizException(400, "文件中没有任何工作表");
            }
            sheet = workbook.getSheetAt(0);
            sheetName = sheet.getSheetName();
        }

        Map<String, Integer> header = buildHeaderIndex(sheet.getRow(0), formatter);
        Integer colNo = findColumn(header, "题号");
        Integer colStem = findColumn(header, "题干");
        Integer colDim = findColumn(header, "归属维度编码", "归属维度", "维度编码");
        Integer colReverse = findColumn(header, "是否反向(0/1)", "是否反向（0/1）", "是否反向");
        Integer colRequired = findColumn(header, "是否必答(0/1)", "是否必答（0/1）", "是否必答");
        Integer[] labelCols = new Integer[OPTION_LETTERS.length];
        Integer[] scoreCols = new Integer[OPTION_LETTERS.length];
        for (int i = 0; i < OPTION_LETTERS.length; i++) {
            labelCols[i] = findColumn(header, "选项" + OPTION_LETTERS[i] + "文案");
            scoreCols[i] = findColumn(header, "选项" + OPTION_LETTERS[i] + "分值");
        }

        List<String> missing = new ArrayList<>();
        if (colNo == null) {
            missing.add("题号");
        }
        if (colStem == null) {
            missing.add("题干");
        }
        if (colDim == null) {
            missing.add("归属维度编码");
        }
        if (labelCols[0] == null) {
            missing.add("选项A文案");
        }
        if (scoreCols[0] == null) {
            missing.add("选项A分值");
        }
        if (labelCols[1] == null) {
            missing.add("选项B文案");
        }
        if (scoreCols[1] == null) {
            missing.add("选项B分值");
        }
        if (!missing.isEmpty()) {
            throw new BizException(400, "工作表[" + sheetName + "]缺少核心表头列: "
                    + String.join("、", missing) + "，请使用标准模板重新填写");
        }

        int lastCol = Math.max(header.values().stream().mapToInt(Integer::intValue).max().orElse(0), colStem);
        List<QuestionConfig> questions = new ArrayList<>();
        Map<Integer, Integer> firstRowOfNo = new HashMap<>();
        int dataRows = 0;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (isRowEmpty(row, formatter, lastCol)) {
                continue; // 空行自动跳过
            }
            dataRows++;
            if (dataRows > MAX_QUESTION_ROWS) {
                throw new BizException(400, "题库行数超过上限(" + MAX_QUESTION_ROWS + "行)，请拆分文件后重新上传");
            }
            int excelRow = r + 1;

            Integer questionNo = parseQuestionNo(textAt(row, colNo, formatter), sheetName, excelRow, errors, firstRowOfNo);
            String stem = textAt(row, colStem, formatter);
            if (stem.isEmpty()) {
                errors.add(error(sheetName, excelRow, "题干", "题干不能为空"));
            } else if (stem.length() > 500) {
                errors.add(error(sheetName, excelRow, "题干", "题干长度不能超过500个字符"));
            }
            String dimCode = textAt(row, colDim, formatter);
            if (dimCode.isEmpty()) {
                errors.add(error(sheetName, excelRow, "归属维度编码", "归属维度编码不能为空"));
            } else if (dimCode.length() > 64) {
                errors.add(error(sheetName, excelRow, "归属维度编码", "维度编码长度不能超过64个字符"));
            }

            int isReverse = parseFlag(textAt(row, colReverse, formatter), sheetName, excelRow, "是否反向(0/1)", 0, errors);
            int required = parseFlag(textAt(row, colRequired, formatter), sheetName, excelRow, "是否必答(0/1)", 1, errors);

            List<OptionConfig> options = parseOptions(row, formatter, sheetName, excelRow, labelCols, scoreCols, errors);

            questions.add(QuestionConfig.builder()
                    .questionNo(questionNo)
                    .stem(stem)
                    .questionType("SINGLE")
                    .dimensionCode(dimCode)
                    .isReverse(isReverse)
                    .required(required)
                    .options(options)
                    .build());
        }

        if (questions.isEmpty()) {
            errors.add(error(sheetName, null, null, "未解析到任何题目数据行，请至少填写1道题目"));
        }
        return questions;
    }

    private Integer parseQuestionNo(
            String text,
            String sheetName,
            int excelRow,
            List<XlsxRowError> errors,
            Map<Integer, Integer> firstRowOfNo) {
        if (text.isEmpty()) {
            errors.add(error(sheetName, excelRow, "题号", "题号不能为空，必须为正整数"));
            return null;
        }
        Integer questionNo = null;
        try {
            BigDecimal decimal = new BigDecimal(text);
            long value = decimal.longValueExact();
            if (value < 1 || decimal.stripTrailingZeros().scale() > 0) {
                errors.add(error(sheetName, excelRow, "题号", "题号必须为正整数"));
            } else {
                questionNo = (int) value;
            }
        } catch (NumberFormatException | ArithmeticException ex) {
            errors.add(error(sheetName, excelRow, "题号", "题号必须为正整数"));
        }
        if (questionNo != null) {
            Integer firstRow = firstRowOfNo.putIfAbsent(questionNo, excelRow);
            if (firstRow != null) {
                errors.add(error(sheetName, excelRow, "题号",
                        "题号[" + questionNo + "]在文件内重复（首次出现于第" + firstRow + "行）"));
            }
        }
        return questionNo;
    }

    private List<OptionConfig> parseOptions(
            Row row,
            DataFormatter formatter,
            String sheetName,
            int excelRow,
            Integer[] labelCols,
            Integer[] scoreCols,
            List<XlsxRowError> errors) {
        List<OptionConfig> options = new ArrayList<>();
        boolean gapSeen = false;
        for (int i = 0; i < OPTION_LETTERS.length; i++) {
            char letter = OPTION_LETTERS[i];
            String label = textAt(row, labelCols[i], formatter);
            String scoreText = textAt(row, scoreCols[i], formatter);
            if (label.isEmpty() && scoreText.isEmpty()) {
                gapSeen = true;
                continue;
            }
            if (gapSeen) {
                errors.add(error(sheetName, excelRow, "选项" + letter + "文案",
                        "选项必须从A开始连续填写，不能跳空"));
                continue;
            }
            if (label.isEmpty()) {
                errors.add(error(sheetName, excelRow, "选项" + letter + "文案",
                        "选项文案与分值必须成对出现，文案不能为空"));
                continue;
            }
            if (scoreText.isEmpty()) {
                errors.add(error(sheetName, excelRow, "选项" + letter + "分值",
                        "选项文案与分值必须成对出现，分值不能为空"));
                continue;
            }
            BigDecimal score = null;
            try {
                score = new BigDecimal(scoreText);
            } catch (NumberFormatException ignored) {
            }
            if (score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(MAX_OPTION_SCORE) > 0) {
                errors.add(error(sheetName, excelRow, "选项" + letter + "分值",
                        "分值必须为0~100之间的数字"));
                continue;
            }
            options.add(OptionConfig.builder()
                    .optCode(String.valueOf(letter))
                    .label(label)
                    .score(score)
                    .sort(options.size() + 1)
                    .build());
        }
        if (options.size() < 2) {
            errors.add(error(sheetName, excelRow, "选项",
                    "每题至少需要2个有效选项（A~F支持2~6个，文案与分值须成对出现）"));
        }
        return options;
    }

    // ==================================================================
    // 维度配置 Sheet
    // ==================================================================

    private List<DimensionRule> parseDimensionSheet(Workbook workbook, DataFormatter formatter, List<XlsxRowError> errors) {
        Sheet sheet = findSheet(workbook, SHEET_DIMENSIONS);
        Map<String, Integer> header = buildHeaderIndex(sheet.getRow(0), formatter);
        Integer colCode = findColumn(header, "维度编码");
        Integer colName = findColumn(header, "维度名称");
        Integer colFormula = findColumn(header, "聚合方式(SUM/AVG)", "聚合方式（SUM/AVG）", "聚合方式");

        List<String> missing = new ArrayList<>();
        if (colCode == null) {
            missing.add("维度编码");
        }
        if (colName == null) {
            missing.add("维度名称");
        }
        if (!missing.isEmpty()) {
            throw new BizException(400, "工作表[" + SHEET_DIMENSIONS + "]缺少核心表头列: "
                    + String.join("、", missing) + "，请使用标准模板重新填写");
        }

        List<DimensionRule> dimensions = new ArrayList<>();
        Map<String, Integer> firstRowOfCode = new HashMap<>();
        int lastCol = Math.max(colFormula == null ? 0 : colFormula, Math.max(colCode, colName));
        int dataRows = 0;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (isRowEmpty(row, formatter, lastCol)) {
                continue;
            }
            dataRows++;
            if (dataRows > MAX_QUESTION_ROWS) {
                throw new BizException(400, "维度配置行数超过上限(" + MAX_QUESTION_ROWS + "行)");
            }
            int excelRow = r + 1;
            String code = textAt(row, colCode, formatter);
            String name = textAt(row, colName, formatter);
            String formulaText = textAt(row, colFormula, formatter);

            if (code.isEmpty()) {
                errors.add(error(SHEET_DIMENSIONS, excelRow, "维度编码", "维度编码不能为空"));
            } else if (code.length() > 64) {
                errors.add(error(SHEET_DIMENSIONS, excelRow, "维度编码", "维度编码长度不能超过64个字符"));
            } else {
                Integer firstRow = firstRowOfCode.putIfAbsent(code, excelRow);
                if (firstRow != null) {
                    errors.add(error(SHEET_DIMENSIONS, excelRow, "维度编码",
                            "维度编码[" + code + "]重复（首次出现于第" + firstRow + "行）"));
                }
            }
            if (name.isEmpty()) {
                errors.add(error(SHEET_DIMENSIONS, excelRow, "维度名称", "维度名称不能为空"));
            } else if (name.length() > 64) {
                errors.add(error(SHEET_DIMENSIONS, excelRow, "维度名称", "维度名称长度不能超过64个字符"));
            }
            String dimFormula = "SUM";
            if (!formulaText.isEmpty()) {
                String upper = formulaText.trim().toUpperCase();
                if (!"SUM".equals(upper) && !"AVG".equals(upper)) {
                    errors.add(error(SHEET_DIMENSIONS, excelRow, "聚合方式(SUM/AVG)",
                            "聚合方式仅支持SUM或AVG"));
                } else {
                    dimFormula = upper;
                }
            }
            dimensions.add(DimensionRule.builder()
                    .code(code)
                    .name(name)
                    .formula(dimFormula)
                    .build());
        }
        if (dimensions.isEmpty()) {
            errors.add(error(SHEET_DIMENSIONS, null, null, "未解析到任何维度数据行"));
        }
        return dimensions;
    }

    // ==================================================================
    // 计分与区间 Sheet
    // ==================================================================

    private List<LevelRule> parseLevelSheet(Workbook workbook, DataFormatter formatter, List<XlsxRowError> errors) {
        Sheet sheet = findSheet(workbook, SHEET_LEVELS);
        Map<String, Integer> header = buildHeaderIndex(sheet.getRow(0), formatter);
        Integer colCode = findColumn(header, "区间编码");
        Integer colName = findColumn(header, "区间名称");
        Integer colMin = findColumn(header, "最小分值");
        Integer colMax = findColumn(header, "最大分值");
        Integer colMinInc = findColumn(header, "含下界(true/false)", "含下界（true/false）", "含下界");
        Integer colMaxInc = findColumn(header, "含上界(true/false)", "含上界（true/false）", "含上界");
        Integer colAdvice = findColumn(header, "反馈建议");

        List<String> missing = new ArrayList<>();
        if (colCode == null) {
            missing.add("区间编码");
        }
        if (colName == null) {
            missing.add("区间名称");
        }
        if (colMin == null) {
            missing.add("最小分值");
        }
        if (colMax == null) {
            missing.add("最大分值");
        }
        if (!missing.isEmpty()) {
            throw new BizException(400, "工作表[" + SHEET_LEVELS + "]缺少核心表头列: "
                    + String.join("、", missing) + "，请使用标准模板重新填写");
        }

        List<LevelRule> levels = new ArrayList<>();
        Map<String, Integer> firstRowOfCode = new HashMap<>();
        int lastCol = maxCol(colCode, colName, colMin, colMax, colMinInc, colMaxInc, colAdvice);
        int dataRows = 0;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (isRowEmpty(row, formatter, lastCol)) {
                continue;
            }
            dataRows++;
            if (dataRows > MAX_QUESTION_ROWS) {
                throw new BizException(400, "计分与区间行数超过上限(" + MAX_QUESTION_ROWS + "行)");
            }
            int excelRow = r + 1;
            String code = textAt(row, colCode, formatter);
            String name = textAt(row, colName, formatter);
            String minText = textAt(row, colMin, formatter);
            String maxText = textAt(row, colMax, formatter);

            if (code.isEmpty()) {
                errors.add(error(SHEET_LEVELS, excelRow, "区间编码", "区间编码不能为空"));
            } else if (code.length() > 64) {
                errors.add(error(SHEET_LEVELS, excelRow, "区间编码", "区间编码长度不能超过64个字符"));
            } else {
                Integer firstRow = firstRowOfCode.putIfAbsent(code, excelRow);
                if (firstRow != null) {
                    errors.add(error(SHEET_LEVELS, excelRow, "区间编码",
                            "区间编码[" + code + "]重复（首次出现于第" + firstRow + "行）"));
                }
            }
            if (name.isEmpty()) {
                errors.add(error(SHEET_LEVELS, excelRow, "区间名称", "区间名称不能为空"));
            } else if (name.length() > 64) {
                errors.add(error(SHEET_LEVELS, excelRow, "区间名称", "区间名称长度不能超过64个字符"));
            }

            BigDecimal min = parseDecimal(minText);
            BigDecimal max = parseDecimal(maxText);
            if (min == null) {
                errors.add(error(SHEET_LEVELS, excelRow, "最小分值", "最小分值必须为数字"));
            }
            if (max == null) {
                errors.add(error(SHEET_LEVELS, excelRow, "最大分值", "最大分值必须为数字"));
            }
            if (min != null && max != null && min.compareTo(max) > 0) {
                errors.add(error(SHEET_LEVELS, excelRow, "最小分值",
                        "最小分值(" + minText + ")不能大于最大分值(" + maxText + ")"));
            }

            Boolean minInclusive = parseBooleanFlag(textAt(row, colMinInc, formatter),
                    SHEET_LEVELS, excelRow, "含下界(true/false)", true, errors);
            Boolean maxInclusive = parseBooleanFlag(textAt(row, colMaxInc, formatter),
                    SHEET_LEVELS, excelRow, "含上界(true/false)", true, errors);

            String advice = textAt(row, colAdvice, formatter);
            levels.add(LevelRule.builder()
                    .code(code)
                    .name(name)
                    .min(min)
                    .max(max)
                    .minInclusive(minInclusive)
                    .maxInclusive(maxInclusive)
                    .advice(advice.isEmpty() ? null : advice)
                    .build());
        }
        if (levels.isEmpty()) {
            errors.add(error(SHEET_LEVELS, null, null, "未解析到任何区间数据行，至少需配置1个结果区间"));
        }
        return levels;
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private int parseFlag(String text, String sheetName, int excelRow, String column, int defaultValue, List<XlsxRowError> errors) {
        if (text.isEmpty()) {
            return defaultValue;
        }
        String v = text.trim().toLowerCase();
        if (v.equals("1") || v.equals("是") || v.equals("true") || v.equals("yes") || v.equals("y")) {
            return 1;
        }
        if (v.equals("0") || v.equals("否") || v.equals("false") || v.equals("no") || v.equals("n")) {
            return 0;
        }
        errors.add(error(sheetName, excelRow, column, column + "仅允许填写0/1或是/否"));
        return defaultValue;
    }

    private Boolean parseBooleanFlag(
            String text, String sheetName, int excelRow, String column, boolean defaultValue, List<XlsxRowError> errors) {
        if (text.isEmpty()) {
            return defaultValue;
        }
        String v = text.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("是") || v.equals("yes") || v.equals("y")) {
            return Boolean.TRUE;
        }
        if (v.equals("false") || v.equals("0") || v.equals("否") || v.equals("no") || v.equals("n")) {
            return Boolean.FALSE;
        }
        errors.add(error(sheetName, excelRow, column, column + "仅允许填写true/false或是/否"));
        return defaultValue;
    }

    private static BigDecimal parseDecimal(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeFormula(String formula) {
        return formula == null || formula.isBlank() ? "SUM" : formula.trim().toUpperCase();
    }

    private String normalizeRuleVersion(String ruleVersion) {
        return ruleVersion == null || ruleVersion.isBlank() ? "R-draft" : ruleVersion.trim();
    }

    /**
     * reverseMaxPlusMin 缺省按题目选项自动推算：取全部反向题选项的最大值+最小值。
     */
    private BigDecimal resolveReverseMaxPlusMin(Integer param, List<QuestionConfig> questions) {
        if (param != null) {
            return BigDecimal.valueOf(param);
        }
        BigDecimal max = null;
        BigDecimal min = null;
        for (QuestionConfig q : questions) {
            if (q.getIsReverse() == null || q.getIsReverse() != 1 || q.getOptions() == null) {
                continue;
            }
            for (OptionConfig opt : q.getOptions()) {
                if (opt.getScore() == null) {
                    continue;
                }
                max = max == null ? opt.getScore() : max.max(opt.getScore());
                min = min == null ? opt.getScore() : min.min(opt.getScore());
            }
        }
        return max == null || min == null ? null : max.add(min);
    }

    private static XlsxRowError error(String sheet, Integer row, String column, String message) {
        return XlsxRowError.builder()
                .sheet(sheet)
                .row(row)
                .column(column)
                .message(message)
                .build();
    }

    private static int maxCol(Integer... cols) {
        int max = 0;
        for (Integer col : cols) {
            if (col != null && col > max) {
                max = col;
            }
        }
        return max;
    }

    private static class QuestionnaireParseResult {
        private final List<QuestionConfig> questions;
        private final ScoringRuleConfig scoringRule;
        private final boolean hasDimsSheet;
        private final boolean hasLevelsSheet;

        QuestionnaireParseResult(List<QuestionConfig> questions, ScoringRuleConfig scoringRule,
                                 boolean hasDimsSheet, boolean hasLevelsSheet) {
            this.questions = questions;
            this.scoringRule = scoringRule;
            this.hasDimsSheet = hasDimsSheet;
            this.hasLevelsSheet = hasLevelsSheet;
        }
    }
}
