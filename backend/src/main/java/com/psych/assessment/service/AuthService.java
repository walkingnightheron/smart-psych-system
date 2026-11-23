package com.psych.assessment.service;

import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.AuthDtos.LoginRequest;
import com.psych.assessment.dto.AuthDtos.LoginResponse;
import com.psych.assessment.dto.AuthDtos.UpdateProfileRequest;
import com.psych.assessment.dto.AuthDtos.UserVO;
import com.psych.assessment.entity.SysUser;
import com.psych.assessment.repository.SysUserRepository;
import com.psych.assessment.security.AuthContext;
import com.psych.assessment.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserRepository sysUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public LoginResponse login(LoginRequest request) {
        if (request == null || request.getUsername() == null || request.getUsername().isBlank()) {
            throw new BizException(400, "账号[username]不能为空");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BizException(400, "密码[password]不能为空");
        }
        SysUser user = sysUserRepository.findByUsername(request.getUsername().trim())
                .orElseThrow(() -> new BizException(401, "账号或密码错误"));
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BizException(401, "当前账号已被禁用，请联系管理员");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(401, "账号或密码错误");
        }
        String token = jwtTokenProvider.generateToken(user);
        return LoginResponse.builder()
                .token(token)
                .user(toUserVO(user))
                .build();
    }

    public UserVO getCurrentUser() {
        Long userId = AuthContext.requireUserId();
        SysUser user = sysUserRepository.findById(userId)
                .orElseThrow(() -> new BizException(401, "当前登录用户不存在"));
        return toUserVO(user);
    }

    public UserVO updateProfile(UpdateProfileRequest request) {
        if (request == null || request.getRealName() == null || request.getRealName().isBlank()) {
            throw new BizException(400, "姓名[realName]不能为空");
        }
        String realName = request.getRealName().trim();
        if (realName.length() > 64) {
            throw new BizException(400, "姓名长度不能超过64个字符");
        }
        String deptClass = request.getDeptClass() == null ? "" : request.getDeptClass().trim();
        String phone = request.getPhone() == null ? "" : request.getPhone().trim();

        Long userId = AuthContext.requireUserId();
        sysUserRepository.updateProfile(userId, realName, deptClass, phone);
        SysUser updated = sysUserRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "用户不存在"));
        return toUserVO(updated);
    }

    public List<UserVO> listActiveUsers(String role) {
        return sysUserRepository.findAllActive(role).stream()
                .map(this::toUserVO)
                .collect(Collectors.toList());
    }

    public UserVO toUserVO(SysUser user) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole())
                .deptClass(user.getDeptClass())
                .phone(user.getPhone())
                .build();
    }
}
