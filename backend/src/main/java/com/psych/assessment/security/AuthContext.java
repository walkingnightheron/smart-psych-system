package com.psych.assessment.security;

import com.psych.assessment.common.BizException;

public final class AuthContext {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthUser user) {
        HOLDER.set(user);
    }

    public static AuthUser get() {
        return HOLDER.get();
    }

    public static AuthUser requireUser() {
        AuthUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(401, "未登录或登录状态已失效");
        }
        return user;
    }

    public static Long requireUserId() {
        return requireUser().getId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
