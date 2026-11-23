package com.psych.assessment.controller;

import com.psych.assessment.common.Result;
import com.psych.assessment.dto.AuthDtos.DashboardSummaryVO;
import com.psych.assessment.dto.AuthDtos.UserVO;
import com.psych.assessment.service.ActivityService;
import com.psych.assessment.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminDashboardAndUserController {

    private final ActivityService activityService;
    private final AuthService authService;

    @GetMapping("/dashboard/summary")
    public Result<DashboardSummaryVO> dashboardSummary() {
        return Result.ok(activityService.getDashboardSummary());
    }

    @GetMapping("/users")
    public Result<List<UserVO>> listUsers(@RequestParam(value = "role", required = false) String role) {
        return Result.ok(authService.listActiveUsers(role));
    }
}
