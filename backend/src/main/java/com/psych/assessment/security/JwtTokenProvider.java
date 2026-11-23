package com.psych.assessment.security;

import com.psych.assessment.common.BizException;
import com.psych.assessment.entity.SysUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expireHours;

    public JwtTokenProvider(
            @Value("${app.jwt.secret:psych-assessment-teaching-dev-jwt-secret-key-2026}") String secret,
            @Value("${app.jwt.expire-hours:24}") long expireHours) {
        this.secretKey = deriveKey(secret);
        this.expireHours = expireHours;
    }

    private SecretKey deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public String generateToken(SysUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expireHours, ChronoUnit.HOURS);
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("role", user.getRole())
                .claim("realName", user.getRealName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public String generateCustomExpiryToken(SysUser user, Instant expiry) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("role", user.getRole())
                .claim("realName", user.getRealName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public AuthUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.parseLong(claims.getSubject());
            return AuthUser.builder()
                    .id(userId)
                    .username(claims.get("username", String.class))
                    .role(claims.get("role", String.class))
                    .realName(claims.get("realName", String.class))
                    .build();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(401, "登录令牌无效或已过期，请重新登录");
        }
    }
}
