package com.psych.assessment.controller;

import com.psych.assessment.common.Result;
import com.psych.assessment.dto.AuthDtos.LoginRequest;
import com.psych.assessment.dto.AuthDtos.LoginResponse;
import com.psych.assessment.dto.AuthDtos.UpdateProfileRequest;
import com.psych.assessment.dto.AuthDtos.UserVO;
import com.psych.assessment.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.getCurrentUser());
    }

    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@RequestBody UpdateProfileRequest request) {
        return Result.ok(authService.updateProfile(request));
    }
}
