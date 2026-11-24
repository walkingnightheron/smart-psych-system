package com.psych.assessment.service;

import com.psych.assessment.common.BizException;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全 XLSX 编解码与标准模板生成工具（REQ 批量导入导出增强）。
 *
 * <p>安全约束：文件非空、大小不超过 5MB、扩展名 .xlsx（忽略大小写）、
 * 前 4 字节魔数必须为 PK\x03\x04，防止伪造扩展名上传非法文件。
 */
@Service
public class XlsxTemplateAndCodec {

    public static final String SHEET_QUESTIONS = "题目列表";
    public static final String SHEET_DIMENSIONS = "维度配置";
    public static final String SHEET_LEVELS = "计分与区间";
    public static final String SHEET_STUDENTS = "学生名单";

    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    public static final int MAX_QUESTION_ROWS = 500;
    public static final int MAX_STUDENT_ROWS = 5000;

    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] QUESTION_HEADERS = {
            "题号", "题干", "归属维度编码", "是否反向(0/1)", "是否必答(0/1)",
            "选项A文案", "选项A分值", "选项B文案", "选项B分值", "选项C文案", "选项C分值",
            "选项D文案", "选项D分值", "选项E文案", "选项E分值", "选项F文案", "选项F分值"
    };

    private static final String[] DIMENSION_HEADERS = {"维度编码", "维度名称", "聚合方式(SUM/AVG)"};

    private static final String[] LEVEL_HEADERS = {
            "区间编码", "区间名称", "最小分值", "最大分值", "含下界(true/false)", "含上界(true/false)", "反馈建议"
    };

    private static final String[] STUDENT_HEADERS = {"学号", "姓名", "学院班级", "手机号"};

    // ==================================================================
    // 安全校验与打开
    // ==================================================================

    public Workbook validateAndOpen(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件不是有效的 .xlsx 格式");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception ex) {
            throw new BizException(400, "文件不是有效的 .xlsx 格式");
        }
        if (bytes.length == 0) {
            throw new BizException(400, "文件不是有效的 .xlsx 格式");
        }
        if (bytes.length > MAX_FILE_SIZE) {
            throw new BizException(400, "上传文件大小不能超过5MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new BizException(400, "文件不是有效的 .xlsx 格式");
        }
        // ZIP/OOXML 魔数校验 PK\x03\x04，防止伪造扩展名
        if (bytes.length < 4
                || bytes[0] != (byte) 0x50 || bytes[1] != (byte) 0x4B
                || bytes[2] != (byte) 0x03 || bytes[3] != (byte) 0x04) {
            throw new BizException(400, "文件不是有效的 .xlsx 格式");
        }
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            throw new BizException(400, "文件不是有效的 .xlsx 格式");
        }
    }

    // ==================================================================
    // 单元格文本读取（数字型学号/手机号安全转换）
    // ==================================================================

    public static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    try {
                        LocalDateTime dt = cell.getLocalDateTimeCellValue();
                        return dt == null ? "" : dt.format(DATE_TIME_FMT);
                    } catch (Exception ex) {
                        return formatter.formatCellValue(cell).trim();
                    }
                }
                return plainNumber(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    /**
     * 数字单元格转纯字符串：20260001.0 -> "20260001"，绝无科学计数法与多余 .0。
     */
    public static String plainNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    public static boolean isRowEmpty(Row row, DataFormatter formatter, int lastCol) {
        if (row == null) {
            return true;
        }
        for (int c = 0; c <= lastCol; c++) {
            if (!cellText(row.getCell(c), formatter).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static Map<String, Integer> buildHeaderIndex(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> index = new LinkedHashMap<>();
        if (headerRow == null) {
            return index;
        }
        for (int c = headerRow.getFirstCellNum(); c < headerRow.getLastCellNum(); c++) {
            String text = normalizeHeader(cellText(headerRow.getCell(c), formatter));
            if (!text.isEmpty()) {
                index.putIfAbsent(text, c);
            }
        }
        return index;
    }

    public static String normalizeHeader(String header) {
        return header == null ? "" : header.replaceAll("\\s+", "");
    }

    public static Integer findColumn(Map<String, Integer> headerIndex, String... aliases) {
        for (String alias : aliases) {
            Integer col = headerIndex.get(normalizeHeader(alias));
            if (col != null) {
                return col;
            }
        }
        return null;
    }

    public static Sheet findSheet(Workbook workbook, String sheetName) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (sheetName.equals(sheet.getSheetName())) {
                return sheet;
            }
        }
        return null;
    }

    public static String textAt(Row row, Integer col, DataFormatter formatter) {
        if (row == null || col == null) {
            return "";
        }
        return cellText(row.getCell(col), formatter);
    }

    // ==================================================================
    // 模板生成：题库/问卷 3-Sheet 标准模板
    // ==================================================================

    public byte[] generateQuestionnaireTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle headerStyle = buildHeaderStyle(wb);
            CellStyle textStyle = buildTextStyle(wb);

            Sheet qSheet = wb.createSheet(SHEET_QUESTIONS);
            writeHeaderRow(qSheet, QUESTION_HEADERS, headerStyle);
            String[][] sampleQuestions = {
                    {"1", "近期面对课程任务与考核时，我感到难以负荷。", "STRESS", "0", "1",
                            "从不", "1", "偶尔", "2", "经常", "3", "总是", "4", "", "", "", ""},
                    {"2", "临近截止日期时，我会反复担忧尚未完成的学习任务而心神不宁。", "STRESS", "0", "1",
                            "从不", "1", "偶尔", "2", "经常", "3", "总是", "4", "", "", "", ""},
                    {"3", "最近我容易感到烦躁、紧张或注意力难以集中。", "MOOD", "0", "1",
                            "从不", "1", "偶尔", "2", "经常", "3", "总是", "4", "", "", "", ""},
                    {"4", "遇到困难时，我能够保持平稳心态并主动寻找解决办法。（反向计分示例）", "MOOD", "1", "1",
                            "从不", "1", "偶尔", "2", "经常", "3", "总是", "4", "", "", "", ""},
                    {"5", "近两周我出现入睡困难、睡眠质量差或经常熬夜的情况。", "SLEEP", "0", "1",
                            "从不", "1", "偶尔", "2", "经常", "3", "总是", "4", "", "", "", ""},
                    {"6", "每天清晨醒来，我感到精力充沛，能投入新一天的学习生活。（反向计分示例）", "SLEEP", "1", "1",
                            "从不", "1", "偶尔", "2", "经常", "3", "总是", "4", "", "", "", ""}
            };
            for (int i = 0; i < sampleQuestions.length; i++) {
                writeTextRow(qSheet, i + 1, sampleQuestions[i], textStyle);
            }
            setColumnWidths(qSheet, new int[]{8, 48, 16, 14, 14, 12, 10, 12, 10, 12, 10, 12, 10, 12, 10, 12, 10});

            Sheet dimSheet = wb.createSheet(SHEET_DIMENSIONS);
            writeHeaderRow(dimSheet, DIMENSION_HEADERS, headerStyle);
            String[][] sampleDimensions = {
                    {"STRESS", "学习压力", "SUM"},
                    {"MOOD", "情绪状态", "SUM"},
                    {"SLEEP", "睡眠与作息", "SUM"}
            };
            for (int i = 0; i < sampleDimensions.length; i++) {
                writeTextRow(dimSheet, i + 1, sampleDimensions[i], textStyle);
            }
            setColumnWidths(dimSheet, new int[]{16, 20, 18});

            Sheet lvSheet = wb.createSheet(SHEET_LEVELS);
            writeHeaderRow(lvSheet, LEVEL_HEADERS, headerStyle);
            String[][] sampleLevels = {
                    {"GOOD", "状态良好", "6", "10", "true", "true",
                            "整体状态良好，请继续保持规律作息与适度运动，关注自身情绪变化。"},
                    {"MILD", "轻度压力", "11", "15", "true", "true",
                            "存在轻度压力反应，建议合理规划学习任务，适当进行体育锻炼与放松调节。"},
                    {"MODERATE", "中度困扰", "16", "20", "true", "true",
                            "压力反应较为明显，建议主动与辅导员或心理老师沟通，必要时预约心理咨询。"},
                    {"HIGH", "重点关注", "21", "24", "true", "true",
                            "心理负荷较高，请尽快联系辅导员、心理中心或信任的家人朋友获取一对一支持。"}
            };
            for (int i = 0; i < sampleLevels.length; i++) {
                writeTextRow(lvSheet, i + 1, sampleLevels[i], textStyle);
            }
            setColumnWidths(lvSheet, new int[]{14, 14, 10, 10, 16, 16, 60});

            return toBytes(wb);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "生成问卷导入模板失败: " + ex.getMessage());
        }
    }

    // ==================================================================
    // 模板生成：学生名册/活动名单单 Sheet 模板
    // ==================================================================

    public byte[] generateStudentsTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle headerStyle = buildHeaderStyle(wb);
            CellStyle textStyle = buildTextStyle(wb);

            Sheet sheet = wb.createSheet(SHEET_STUDENTS);
            writeHeaderRow(sheet, STUDENT_HEADERS, headerStyle);
            String[][] sampleStudents = {
                    {"20260101", "张三（示例学生）", "软件2541班", "13900001111"},
                    {"20260102", "李四（示例学生）", "软件2542班", "13900002222"},
                    {"20260103", "王五（示例学生）", "软件2543班", "13900003333"}
            };
            for (int i = 0; i < sampleStudents.length; i++) {
                writeTextRow(sheet, i + 1, sampleStudents[i], textStyle);
            }
            setColumnWidths(sheet, new int[]{16, 22, 20, 18});

            return toBytes(wb);
        } catch (Exception ex) {
            throw new BizException(500, "生成学生名册模板失败: " + ex.getMessage());
        }
    }

    // ==================================================================
    // 样式与写入辅助
    // ==================================================================

    public static CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyThinBorder(style);
        return style;
    }

    public static CellStyle buildTextStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        applyThinBorder(style);
        return style;
    }

    public static CellStyle buildNumberStyle(Workbook wb, String format) {
        CellStyle style = wb.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setDataFormat(wb.createDataFormat().getFormat(format));
        applyThinBorder(style);
        return style;
    }

    public static CellStyle buildSectionStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    public static CellStyle buildLabelStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyThinBorder(style);
        return style;
    }

    private static void applyThinBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    public static void writeHeaderRow(Sheet sheet, String[] headers, CellStyle headerStyle) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(22f);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        sheet.createFreezePane(0, 1);
    }

    public static void writeTextRow(Sheet sheet, int rowIndex, String[] values, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i] == null ? "" : values[i]);
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    public static void setColumnWidths(Sheet sheet, int[] charWidths) {
        for (int i = 0; i < charWidths.length; i++) {
            sheet.setColumnWidth(i, Math.min(255 * 256, charWidths[i] * 256));
        }
    }

    public static void autoSizeColumns(Sheet sheet, int columnCount, int[] fallbackCharWidths) {
        for (int i = 0; i < columnCount; i++) {
            try {
                sheet.autoSizeColumn(i);
                int width = Math.min(255 * 256, (int) (sheet.getColumnWidth(i) * 1.15) + 512);
                sheet.setColumnWidth(i, width);
            } catch (Exception ex) {
                if (fallbackCharWidths != null && i < fallbackCharWidths.length) {
                    sheet.setColumnWidth(i, Math.min(255 * 256, fallbackCharWidths[i] * 256));
                }
            }
        }
    }

    private static byte[] toBytes(Workbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
