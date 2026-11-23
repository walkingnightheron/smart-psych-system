package com.psych.assessment.security;

import com.psych.assessment.common.BizException;
import com.psych.assessment.entity.SysUser;
import com.psych.assessment.repository.SysUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;

@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final SysUserRepository sysUserRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BizException(401, "未携带有效的身份认证令牌");
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            throw new BizException(401, "身份认证令牌为空");
        }

        AuthUser tokenUser = jwtTokenProvider.parseToken(token);
        SysUser dbUser = sysUserRepository.findById(tokenUser.getId())
                .orElseThrow(() -> new BizException(401, "账号不存在或登录状态已失效"));
        if (dbUser.getStatus() == null || dbUser.getStatus() == 0) {
            throw new BizException(401, "当前账号已被禁用");
        }

        AuthUser current = AuthUser.builder()
                .id(dbUser.getId())
                .username(dbUser.getUsername())
                .role(dbUser.getRole())
                .realName(dbUser.getRealName())
                .build();

        String uri = request.getRequestURI();
        if (uri.startsWith("/api/admin/") || "/api/admin".equals(uri)) {
            if (!"ADMIN".equals(current.getRole())) {
                throw new BizException(403, "权限不足：当前角色无权访问管理端接口");
            }
        } else if (uri.startsWith("/api/h5/") || "/api/h5".equals(uri)) {
            if (!"STUDENT".equals(current.getRole())) {
                throw new BizException(403, "权限不足：当前角色无权访问学生参与端接口");
            }
        }

        AuthContext.set(current);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
