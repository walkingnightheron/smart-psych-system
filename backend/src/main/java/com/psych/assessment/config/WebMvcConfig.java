package com.psych.assessment.config;

import com.psych.assessment.repository.SysUserRepository;
import com.psych.assessment.security.AuthInterceptor;
import com.psych.assessment.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<JwtTokenProvider> jwtTokenProvider;
    private final ObjectProvider<SysUserRepository> sysUserRepository;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173",
                        "http://localhost:5174",
                        "http://127.0.0.1:5173",
                        "http://127.0.0.1:5174"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        JwtTokenProvider jwt = jwtTokenProvider.getIfAvailable();
        SysUserRepository users = sysUserRepository.getIfAvailable();
        if (jwt != null && users != null) {
            registry.addInterceptor(new AuthInterceptor(jwt, users))
                    .addPathPatterns("/api/**")
                    .excludePathPatterns("/api/health", "/api/auth/login");
        }
    }
}
