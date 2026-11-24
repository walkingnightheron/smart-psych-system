package com.psych.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.ActivityDtos.ActivityStatisticsVO;
import com.psych.assessment.dto.ActivityDtos.DimensionAverageItem;
import com.psych.assessment.dto.ActivityDtos.LevelDistributionItem;
import com.psych.assessment.dto.ActivityDtos.ParticipantDetailItem;
import com.psych.assessment.dto.QuestionnaireDtos.DimensionRule;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionConfig;
import com.psych.assessment.dto.QuestionnaireDtos.ScoringRuleConfig;
import com.psych.assessment.entity.AnswerItem;
import com.psych.assessment.entity.AnswerSheet;
import com.psych.assessment.entity.AssessmentActivity;
import com.psych.assessment.entity.AssessmentReport;
import com.psych.assessment.entity.QuestionnaireVersion;
import com.psych.assessment.entity.SysUser;
import com.psych.assessment.repository.AnswerItemRepository;
import com.psych.assessment.repository.AnswerSheetRepository;
import com.psych.assessment.repository.AssessmentActivityRepository;
import com.psych.assessment.repository.AssessmentReportRepository;
import com.psych.assessment.repository.QuestionnaireVersionRepository;
import com.psych.assessment.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.psych.assessment.service.XlsxTemplateAndCodec.autoSizeColumns;
import static com.psych.assessment.service.XlsxTemplateAndCodec.buildHeaderStyle;
import static com.psych.assessment.service.XlsxTemplateAndCodec.buildLabelStyle;
import static com.psych.assessment.service.XlsxTemplateAndCodec.buildNumberStyle;
import static com.psych.assessment.service.XlsxTemplateAndCodec.buildSectionStyle;
import static com.psych.assessment.service.XlsxTemplateAndCodec.buildTextStyle;

/**
 * 评估结果多 Sheet XLSX 导出（契约 7.6，REQ08 增强）。
 *
 * <p>3 个工作表：活动汇总与统计 / 参与及评估结果明细 / 作答选项明细。
 * 权限：复用 {@code ReportAndStatsService.getActivityStatistics} 内置的管理员数据范围校验（越权 403）。
 */
@Service
@RequiredArgsConstructor
public class XlsxExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReportAndStatsService reportAndStatsService;
    private final AssessmentActivityRepository activityRepository;
    private final QuestionnaireVersionRepository versionRepository;
    private final QuestionnaireService questionnaireService;
    private final AnswerSheetRepository sheetRepository;
    private final AnswerItemRepository itemRepository;
    private final AssessmentReportRepository reportRepository;
    private final SysUserRepository userRepository;
    private final ObjectMapper objectMapper;

    public byte[] exportActivityStatisticsXlsx(Long activityId) {
        // 内部完成 404 与管理员数据范围 403 校验
        ActivityStatisticsVO stats = reportAndStatsService.getActivityStatistics(activityId);
        AssessmentActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));
        QuestionnaireVersion version = versionRepository.findById(activity.getQuestionnaireVersionId())
                .orElseThrow(() -> new BizException(404, "活动绑定的问卷版本不存在"));

        List<QuestionConfig> questions = questionnaireService.parseQuestionsSnapshot(version.getQuestionsSnapshot());
        ScoringRuleConfig rule = questionnaireService.parseScoringRule(version.getScoringRuleSnapshot());

        List<AnswerSheet> sheets = sheetRepository.findByActivityId(activityId);
        Map<Long, AssessmentReport> reportByUserId = reportRepository.findByActivityId(activityId).stream()
                .collect(Collectors.toMap(AssessmentReport::getUserId, Function.identity(), (a, b) -> a));

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle headerStyle = buildHeaderStyle(wb);
            CellStyle textStyle = buildTextStyle(wb);
            CellStyle labelStyle = buildLabelStyle(wb);
            CellStyle sectionStyle = buildSectionStyle(wb);
            CellStyle scoreStyle = buildNumberStyle(wb, "0.00");
            CellStyle intStyle = buildNumberStyle(wb, "0");

            buildSummarySheet(wb, stats, activity, version, headerStyle, textStyle, labelStyle, sectionStyle, scoreStyle, intStyle);
            buildParticipantDetailSheet(wb, stats, rule, reportByUserId,
                    headerStyle, textStyle, scoreStyle, intStyle);
            buildAnswerOptionSheet(wb, questions, sheets, headerStyle, textStyle);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new BizException(500, "导出评估结果XLSX失败: " + ex.getMessage());
        }
    }

    // ==================================================================
    // Sheet 1: 活动汇总与统计
    // ==================================================================

    private void buildSummarySheet(
            XSSFWorkbook wb,
            ActivityStatisticsVO stats,
            AssessmentActivity activity,
            QuestionnaireVersion version,
            CellStyle headerStyle,
            CellStyle textStyle,
            CellStyle labelStyle,
            CellStyle sectionStyle,
            CellStyle scoreStyle,
            CellStyle intStyle) {
        Sheet sheet = wb.createSheet("活动汇总与统计");
        int r = 0;
        r = writeSectionTitle(sheet, r, "一、活动概览", sectionStyle);
        r = writeKeyValueRow(sheet, r, "活动名称", stats.getActivityTitle(), labelStyle, textStyle);
        r = writeKeyValueRow(sheet, r, "绑定问卷及版本",
                nullToEmpty(stats.getQuestionnaireTitle()) + "（V" + stats.getVersionNum() + "）", labelStyle, textStyle);
        r = writeKeyValueRow(sheet, r, "规则版本", nullToEmpty(stats.getRuleVersion()), labelStyle, textStyle);
        r = writeKeyValueRow(sheet, r, "活动状态",
                nullToEmpty(stats.getStatus()) + "（" + nullToEmpty(stats.getStatusText()) + "）", labelStyle, textStyle);
        r = writeKeyValueRow(sheet, r, "时间窗",
                formatTime(activity.getStartTime()) + " ~ " + formatTime(activity.getEndTime()), labelStyle, textStyle);
        r = writeKeyValueNumberRow(sheet, r, "目标人数", stats.getParticipantCount(), labelStyle, intStyle);
        r = writeKeyValueNumberRow(sheet, r, "已完成人数", stats.getCompletedCount(), labelStyle, intStyle);
        r = writeKeyValueRow(sheet, r, "完成率",
                (stats.getCompletionRate() == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : stats.getCompletionRate()) + "%",
                labelStyle, textStyle);
        r = writeKeyValueNumberRow(sheet, r, "成功报告数", stats.getSuccessReportCount(), labelStyle, intStyle);
        r = writeKeyValueNumberRow(sheet, r, "待生成报告数", stats.getPendingReportCount(), labelStyle, intStyle);
        r = writeKeyValueNumberRow(sheet, r, "失败报告数", stats.getFailedReportCount(), labelStyle, intStyle);

        r++; // 空行
        r = writeSectionTitle(sheet, r, "二、结果区间分布（仅统计SUCCESS成功报告）", sectionStyle);
        Row noteRow = sheet.createRow(r++);
        Cell noteCell = noteRow.createCell(0);
        noteCell.setCellValue("注：待生成及失败报告不计入分数区间。");
        String[] distHeaders = {"区间编码", "区间名称", "人数"};
        Row distHeader = sheet.createRow(r++);
        for (int i = 0; i < distHeaders.length; i++) {
            Cell cell = distHeader.createCell(i);
            cell.setCellValue(distHeaders[i]);
            cell.setCellStyle(headerStyle);
        }
        List<LevelDistributionItem> distribution = stats.getLevelDistribution() == null
                ? Collections.emptyList() : stats.getLevelDistribution();
        for (LevelDistributionItem item : distribution) {
            Row row = sheet.createRow(r++);
            createStyledCell(row, 0, nullToEmpty(item.getLevelCode()), textStyle);
            createStyledCell(row, 1, nullToEmpty(item.getLevelName()), textStyle);
            Cell countCell = row.createCell(2);
            countCell.setCellValue(item.getCount());
            countCell.setCellStyle(intStyle);
        }

        r++; // 空行
        r = writeSectionTitle(sheet, r, "三、各维度平均得分", sectionStyle);
        String[] dimHeaders = {"维度编码", "维度名称", "平均得分"};
        Row dimHeader = sheet.createRow(r++);
        for (int i = 0; i < dimHeaders.length; i++) {
            Cell cell = dimHeader.createCell(i);
            cell.setCellValue(dimHeaders[i]);
            cell.setCellStyle(headerStyle);
        }
        List<DimensionAverageItem> averages = stats.getDimensionAverages() == null
                ? Collections.emptyList() : stats.getDimensionAverages();
        for (DimensionAverageItem item : averages) {
            Row row = sheet.createRow(r++);
            createStyledCell(row, 0, nullToEmpty(item.getDimensionCode()), textStyle);
            createStyledCell(row, 1, nullToEmpty(item.getDimensionName()), textStyle);
            Cell avgCell = row.createCell(2);
            avgCell.setCellValue(item.getAvgScore() == null ? 0d : item.getAvgScore().doubleValue());
            avgCell.setCellStyle(scoreStyle);
        }

        XlsxTemplateAndCodec.setColumnWidths(sheet, new int[]{22, 46, 14, 14, 14, 14});
    }

    // ==================================================================
    // Sheet 2: 参与及评估结果明细
    // ==================================================================

    private void buildParticipantDetailSheet(
            XSSFWorkbook wb,
            ActivityStatisticsVO stats,
            ScoringRuleConfig rule,
            Map<Long, AssessmentReport> reportByUserId,
            CellStyle headerStyle,
            CellStyle textStyle,
            CellStyle scoreStyle,
            CellStyle intStyle) {
        Sheet sheet = wb.createSheet("参与及评估结果明细");

        List<DimensionRule> dimensions = rule == null || rule.getDimensions() == null
                ? Collections.emptyList() : rule.getDimensions();

        List<String> headers = new ArrayList<>(List.of(
                "学号", "姓名", "学院班级", "提交状态", "提交时间", "答卷编号", "报告状态", "总分"));
        for (DimensionRule dim : dimensions) {
            headers.add(nullToEmpty(dim.getName()) + "(" + nullToEmpty(dim.getCode()) + ")");
        }
        headers.addAll(List.of("结果区间", "反馈建议", "重试次数", "最近异常原因"));

        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(22f);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }
        sheet.createFreezePane(0, 1);

        int r = 1;
        List<ParticipantDetailItem> details = stats.getParticipantDetails() == null
                ? Collections.emptyList() : stats.getParticipantDetails();
        for (ParticipantDetailItem p : details) {
            Row row = sheet.createRow(r++);
            int c = 0;
            createStyledCell(row, c++, nullToEmpty(p.getUsername()), textStyle);
            createStyledCell(row, c++, nullToEmpty(p.getRealName()), textStyle);
            createStyledCell(row, c++, nullToEmpty(p.getDeptClass()), textStyle);
            createStyledCell(row, c++, p.isSubmitted() ? "已提交" : "未提交", textStyle);
            createStyledCell(row, c++, formatTime(p.getSubmittedAt()), textStyle);
            createStyledCell(row, c++, p.getSheetId() == null ? "" : String.valueOf(p.getSheetId()), textStyle);
            createStyledCell(row, c++, reportStatusText(p.getTaskStatus()), textStyle);

            boolean success = "SUCCESS".equals(p.getTaskStatus());
            AssessmentReport report = p.getUserId() == null ? null : reportByUserId.get(p.getUserId());

            if (success && p.getTotalScore() != null) {
                Cell cell = row.createCell(c);
                cell.setCellValue(p.getTotalScore().doubleValue());
                cell.setCellStyle(scoreStyle);
            } else {
                createStyledCell(row, c, "", textStyle);
            }
            c++;

            Map<String, BigDecimal> dimScores = success && report != null
                    ? parseDimensionScores(report.getDimensionScoresJson()) : Collections.emptyMap();
            for (DimensionRule dim : dimensions) {
                BigDecimal score = dimScores.get(dim.getCode());
                if (success && score != null) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(score.doubleValue());
                    cell.setCellStyle(scoreStyle);
                } else {
                    createStyledCell(row, c, "", textStyle);
                }
                c++;
            }

            createStyledCell(row, c++, success && p.getLevelCode() != null
                    ? nullToEmpty(p.getLevelName()) + "(" + p.getLevelCode() + ")" : "", textStyle);
            createStyledCell(row, c++, success && report != null ? nullToEmpty(report.getFeedbackText()) : "", textStyle);
            if (p.getRetryCount() != null) {
                Cell cell = row.createCell(c);
                cell.setCellValue(p.getRetryCount());
                cell.setCellStyle(intStyle);
            } else {
                createStyledCell(row, c, "", textStyle);
            }
            c++;
            createStyledCell(row, c, nullToEmpty(p.getLastError()), textStyle);
        }

        autoSizeColumns(sheet, headers.size(),
                new int[]{14, 16, 16, 10, 20, 10, 14, 10, 14, 14, 14, 14, 40, 10, 30});
    }

    // ==================================================================
    // Sheet 3: 作答选项明细
    // ==================================================================

    private void buildAnswerOptionSheet(
            XSSFWorkbook wb,
            List<QuestionConfig> questions,
            List<AnswerSheet> sheets,
            CellStyle headerStyle,
            CellStyle textStyle) {
        Sheet sheet = wb.createSheet("作答选项明细");

        List<String> headers = new ArrayList<>(List.of("答卷编号", "学号", "姓名", "学院班级", "提交时间"));
        for (QuestionConfig q : questions) {
            headers.add("Q" + q.getQuestionNo());
        }
        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(22f);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }
        sheet.createFreezePane(0, 1);

        List<Long> userIds = sheets.stream().map(AnswerSheet::getUserId).distinct().collect(Collectors.toList());
        Map<Long, SysUser> userMap = userRepository.findByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));

        int r = 1;
        for (AnswerSheet s : sheets) {
            SysUser user = userMap.get(s.getUserId());
            Map<Integer, AnswerItem> itemByQuestionNo = new LinkedHashMap<>();
            for (AnswerItem item : itemRepository.findBySheetId(s.getId())) {
                itemByQuestionNo.put(item.getQuestionNo(), item);
            }
            Row row = sheet.createRow(r++);
            int c = 0;
            createStyledCell(row, c++, String.valueOf(s.getId()), textStyle);
            createStyledCell(row, c++, user == null ? "" : nullToEmpty(user.getUsername()), textStyle);
            createStyledCell(row, c++, user == null ? "" : nullToEmpty(user.getRealName()), textStyle);
            createStyledCell(row, c++, user == null ? "" : nullToEmpty(user.getDeptClass()), textStyle);
            createStyledCell(row, c++, formatTime(s.getSubmittedAt()), textStyle);
            for (QuestionConfig q : questions) {
                AnswerItem item = itemByQuestionNo.get(q.getQuestionNo());
                String value = "";
                if (item != null) {
                    BigDecimal raw = item.getRawScore() == null
                            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                            : item.getRawScore().setScale(2, RoundingMode.HALF_UP);
                    value = nullToEmpty(item.getSelectedOptCode()) + " (" + raw + ")";
                }
                createStyledCell(row, c++, value, textStyle);
            }
        }

        autoSizeColumns(sheet, headers.size(),
                new int[]{10, 14, 16, 16, 20, 10, 10, 10, 10, 10, 10});
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private Map<String, BigDecimal> parseDimensionScores(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, BigDecimal> map = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, BigDecimal>>() {});
            return map == null ? Collections.emptyMap() : map;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private static String reportStatusText(String taskStatus) {
        if (taskStatus == null) {
            return "";
        }
        switch (taskStatus) {
            case "SUCCESS":
                return "成功(SUCCESS)";
            case "PENDING":
                return "待生成(PENDING)";
            case "FAILED":
                return "失败(FAILED)";
            default:
                return taskStatus;
        }
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(FMT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int writeSectionTitle(Sheet sheet, int rowIndex, String title, CellStyle sectionStyle) {
        Row row = sheet.createRow(rowIndex++);
        row.setHeightInPoints(20f);
        Cell cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(sectionStyle);
        return rowIndex;
    }

    private static int writeKeyValueRow(Sheet sheet, int rowIndex, String label, String value,
                                        CellStyle labelStyle, CellStyle textStyle) {
        Row row = sheet.createRow(rowIndex++);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);
        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value == null ? "" : value);
        valueCell.setCellStyle(textStyle);
        return rowIndex;
    }

    private static int writeKeyValueNumberRow(Sheet sheet, int rowIndex, String label, long value,
                                              CellStyle labelStyle, CellStyle numberStyle) {
        Row row = sheet.createRow(rowIndex++);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);
        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value);
        valueCell.setCellStyle(numberStyle);
        return rowIndex;
    }

    private static void createStyledCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }
}
