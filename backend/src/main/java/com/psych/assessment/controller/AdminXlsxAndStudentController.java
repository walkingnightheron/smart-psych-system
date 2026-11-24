package com.psych.assessment.controller;

import com.psych.assessment.common.Result;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireDetailVO;
import com.psych.assessment.dto.XlsxDtos.CreateStudentRequest;
import com.psych.assessment.dto.XlsxDtos.ParsedQuestionnaireXlsxVO;
import com.psych.assessment.dto.XlsxDtos.ParticipantImportResultVO;
import com.psych.assessment.dto.XlsxDtos.ResetPasswordResultVO;
import com.psych.assessment.dto.XlsxDtos.ResetStudentPasswordRequest;
import com.psych.assessment.dto.XlsxDtos.StudentImportResultVO;
import com.psych.assessment.dto.XlsxDtos.StudentRosterItemVO;
import com.psych.assessment.dto.XlsxDtos.UpdateStudentRequest;
import com.psych.assessment.dto.XlsxDtos.UpdateStudentStatusRequest;
import com.psych.assessment.service.StudentAndRosterService;
import com.psych.assessment.service.XlsxExportService;
import com.psych.assessment.service.XlsxImportService;
import com.psych.assessment.service.XlsxTemplateAndCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 学生名册与 XLSX 导入/导出接口（契约 7.2 ~ 7.6，仅限 ADMIN，由 AuthInterceptor 统一拦截）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminXlsxAndStudentController {

    private final XlsxTemplateAndCodec xlsxTemplateAndCodec;
    private final XlsxImportService xlsxImportService;
    private final StudentAndRosterService studentAndRosterService;
    private final XlsxExportService xlsxExportService;

    // ==================================================================
    // 7.2 标准模板下载
    // ==================================================================

    @GetMapping("/templates/questionnaire.xlsx")
    public ResponseEntity<byte[]> downloadQuestionnaireTemplate() {
        return xlsxDownload(xlsxTemplateAndCodec.generateQuestionnaireTemplate(),
                "questionnaire-template.xlsx", "问卷题库导入模板.xlsx");
    }

    @GetMapping("/templates/students.xlsx")
    public ResponseEntity<byte[]> downloadStudentsTemplate() {
        return xlsxDownload(xlsxTemplateAndCodec.generateStudentsTemplate(),
                "students-template.xlsx", "学生名册导入模板.xlsx");
    }

    // ==================================================================
    // 7.3 题库/问卷草稿 XLSX 导入
    // ==================================================================

    @PostMapping("/questionnaires/import/parse")
    public Result<ParsedQuestionnaireXlsxVO> parseQuestionnaireXlsx(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "formula", required = false) String formula,
            @RequestParam(value = "reverseMaxPlusMin", required = false) Integer reverseMaxPlusMin,
            @RequestParam(value = "ruleVersion", required = false) String ruleVersion) {
        return Result.ok(xlsxImportService.parseQuestionnaireXlsx(file, formula, reverseMaxPlusMin, ruleVersion));
    }

    @PostMapping("/questionnaires/import")
    public Result<QuestionnaireDetailVO> importQuestionnaire(
            @RequestPart("file") MultipartFile file,
            @RequestParam("code") String code,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "formula", required = false, defaultValue = "SUM") String formula,
            @RequestParam(value = "reverseMaxPlusMin", required = false) Integer reverseMaxPlusMin,
            @RequestParam(value = "ruleVersion", required = false, defaultValue = "R-draft") String ruleVersion) {
        return Result.ok(xlsxImportService.importQuestionnaireAsDraft(
                file, code, title, description, formula, reverseMaxPlusMin, ruleVersion));
    }

    // ==================================================================
    // 7.4 学生名册查询与批量导入
    // ==================================================================

    @GetMapping("/students")
    public Result<List<StudentRosterItemVO>> listStudents(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "deptClass", required = false) String deptClass) {
        return Result.ok(studentAndRosterService.listStudents(keyword, deptClass));
    }

    @PostMapping("/users/import-students")
    public Result<StudentImportResultVO> importStudents(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "updateExisting", required = false, defaultValue = "false") boolean updateExisting) {
        return Result.ok(studentAndRosterService.importStudents(file, updateExisting));
    }

    // ==================================================================
    // 7.4 单个学生账号管理（新增 / 修改 / 启停 / 重置密码 / 删除）
    // ==================================================================

    @PostMapping("/students")
    public Result<StudentRosterItemVO> createStudent(@RequestBody CreateStudentRequest req) {
        return Result.ok(studentAndRosterService.createStudent(req));
    }

    @PutMapping("/students/{id}")
    public Result<StudentRosterItemVO> updateStudent(
            @PathVariable("id") Long id,
            @RequestBody UpdateStudentRequest req) {
        return Result.ok(studentAndRosterService.updateStudent(id, req));
    }

    @PutMapping("/students/{id}/status")
    public Result<StudentRosterItemVO> updateStudentStatus(
            @PathVariable("id") Long id,
            @RequestBody(required = false) UpdateStudentStatusRequest req) {
        return Result.ok(studentAndRosterService.updateStudentStatus(id, req == null ? null : req.getStatus()));
    }

    @PostMapping("/students/{id}/reset-password")
    public Result<ResetPasswordResultVO> resetStudentPassword(
            @PathVariable("id") Long id,
            @RequestBody(required = false) ResetStudentPasswordRequest req) {
        return Result.ok(studentAndRosterService.resetStudentPassword(id, req));
    }

    @DeleteMapping("/students/{id}")
    public Result<Void> deleteStudent(@PathVariable("id") Long id) {
        studentAndRosterService.deleteStudent(id);
        return Result.ok();
    }

    // ==================================================================
    // 7.5 评估活动目标名单 XLSX 导入
    // ==================================================================

    @PostMapping("/activities/{id}/participants/import")
    public Result<ParticipantImportResultVO> importParticipants(
            @PathVariable("id") Long id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "mode", required = false, defaultValue = "APPEND") String mode,
            @RequestParam(value = "autoCreateMissing", required = false, defaultValue = "true") boolean autoCreateMissing) {
        return Result.ok(studentAndRosterService.importActivityParticipants(id, file, mode, autoCreateMissing));
    }

    // ==================================================================
    // 7.6 评估结果统计 XLSX 导出
    // ==================================================================

    @GetMapping("/activities/{id}/statistics/export.xlsx")
    public ResponseEntity<byte[]> exportActivityStatistics(@PathVariable("id") Long id) {
        byte[] content = xlsxExportService.exportActivityStatisticsXlsx(id);
        return xlsxDownload(content,
                "activity-statistics-" + id + ".xlsx", "活动统计导出-" + id + ".xlsx");
    }

    // ==================================================================
    // 下载响应头：Content-Type + 双 filename（含 RFC 5987 UTF-8 编码）
    // ==================================================================

    private ResponseEntity<byte[]> xlsxDownload(byte[] content, String asciiFilename, String utf8Filename) {
        String encoded = URLEncoder.encode(utf8Filename, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XlsxTemplateAndCodec.XLSX_CONTENT_TYPE));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + asciiFilename + "\"; filename*=UTF-8''" + encoded);
        headers.setCacheControl("no-store");
        headers.setContentLength(content.length);
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }
}
