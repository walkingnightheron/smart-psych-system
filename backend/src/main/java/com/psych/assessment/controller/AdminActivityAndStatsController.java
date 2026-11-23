package com.psych.assessment.controller;

import com.psych.assessment.common.Result;
import com.psych.assessment.dto.ActivityDtos.ActivityDetailVO;
import com.psych.assessment.dto.ActivityDtos.ActivityListItemVO;
import com.psych.assessment.dto.ActivityDtos.ActivityStatisticsVO;
import com.psych.assessment.dto.ActivityDtos.ActivityUpsertRequest;
import com.psych.assessment.dto.H5AndReportDtos.ReportDetailVO;
import com.psych.assessment.service.ActivityService;
import com.psych.assessment.service.ReportAndStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminActivityAndStatsController {

    private final ActivityService activityService;
    private final ReportAndStatsService reportAndStatsService;

    @GetMapping("/activities")
    public Result<List<ActivityListItemVO>> listActivities() {
        return Result.ok(activityService.listAuthorizedActivitiesForCurrentAdmin());
    }

    @GetMapping("/activities/{id}")
    public Result<ActivityDetailVO> getActivityDetail(@PathVariable("id") Long id) {
        return Result.ok(activityService.getActivityDetailForAdmin(id));
    }

    @PostMapping("/activities")
    public Result<ActivityDetailVO> createActivity(@RequestBody ActivityUpsertRequest request) {
        return Result.ok(activityService.createActivity(request));
    }

    @PutMapping("/activities/{id}")
    public Result<ActivityDetailVO> updateDraftActivity(
            @PathVariable("id") Long id,
            @RequestBody ActivityUpsertRequest request) {
        return Result.ok(activityService.updateDraftActivity(id, request));
    }

    @PostMapping("/activities/{id}/publish")
    public Result<ActivityDetailVO> publishActivity(@PathVariable("id") Long id) {
        return Result.ok(activityService.publishActivity(id));
    }

    @PostMapping("/activities/{id}/close")
    public Result<ActivityDetailVO> closeActivity(@PathVariable("id") Long id) {
        return Result.ok(activityService.closeActivity(id));
    }

    @GetMapping("/activities/{id}/statistics")
    public Result<ActivityStatisticsVO> getActivityStatistics(@PathVariable("id") Long id) {
        return Result.ok(reportAndStatsService.getActivityStatistics(id));
    }

    @GetMapping("/reports/{reportId}")
    public Result<ReportDetailVO> getReportDetail(@PathVariable("reportId") Long reportId) {
        return Result.ok(reportAndStatsService.getReportForAdmin(reportId));
    }

    @PostMapping("/reports/{reportId}/retry")
    public Result<ReportDetailVO> retryReport(
            @PathVariable("reportId") Long reportId,
            @RequestParam(value = "reset", defaultValue = "false") boolean reset) {
        return Result.ok(reportAndStatsService.retryReportForAdmin(reportId, reset));
    }
}
