package com.psych.assessment.controller;

import com.psych.assessment.common.Result;
import com.psych.assessment.dto.AiDtos.AiInsightVO;
import com.psych.assessment.dto.H5AndReportDtos.H5ActivityDetailVO;
import com.psych.assessment.dto.H5AndReportDtos.H5ActivityItemVO;
import com.psych.assessment.dto.H5AndReportDtos.H5RecordDetailVO;
import com.psych.assessment.dto.H5AndReportDtos.H5RecordItemVO;
import com.psych.assessment.dto.H5AndReportDtos.ReportDetailVO;
import com.psych.assessment.dto.H5AndReportDtos.SubmitSheetRequest;
import com.psych.assessment.dto.H5AndReportDtos.SubmitSheetResponse;
import com.psych.assessment.security.AuthContext;
import com.psych.assessment.service.AiInsightService;
import com.psych.assessment.service.ReportAndStatsService;
import com.psych.assessment.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/h5")
@RequiredArgsConstructor
public class H5AssessmentController {

    private final SubmissionService submissionService;
    private final ReportAndStatsService reportAndStatsService;
    private final AiInsightService aiInsightService;

    @GetMapping("/activities")
    public Result<List<H5ActivityItemVO>> listMyActivities() {
        return Result.ok(submissionService.listMyActivities());
    }

    @GetMapping("/activities/{activityId}/detail")
    public Result<H5ActivityDetailVO> getActivityDetail(@PathVariable("activityId") Long activityId) {
        return Result.ok(submissionService.getH5ActivityDetail(activityId));
    }

    @PostMapping("/activities/{activityId}/submit")
    public Result<SubmitSheetResponse> submitSheet(
            @PathVariable("activityId") Long activityId,
            @RequestBody SubmitSheetRequest request) {
        return Result.ok(submissionService.submitAnswerSheet(activityId, request));
    }

    @GetMapping("/records")
    public Result<List<H5RecordItemVO>> listMyRecords() {
        return Result.ok(submissionService.listMyRecords());
    }

    @GetMapping("/records/{sheetId}")
    public Result<H5RecordDetailVO> getMyRecordDetail(@PathVariable("sheetId") Long sheetId) {
        return Result.ok(submissionService.getMyRecordDetail(sheetId));
    }

    @GetMapping("/reports/{reportId}")
    public Result<ReportDetailVO> getMyReport(@PathVariable("reportId") Long reportId) {
        return Result.ok(reportAndStatsService.getReportForStudent(reportId));
    }

    @PostMapping("/reports/{reportId}/retry")
    public Result<ReportDetailVO> retryMyReport(@PathVariable("reportId") Long reportId) {
        return Result.ok(reportAndStatsService.retryReportForStudent(reportId));
    }

    @GetMapping("/reports/{reportId}/ai-interpretation")
    public Result<AiInsightVO> getMyReportAiInterpretation(@PathVariable("reportId") Long reportId) {
        return Result.ok(aiInsightService.reportInterpretation(reportId, AuthContext.requireUserId(), false));
    }
}
