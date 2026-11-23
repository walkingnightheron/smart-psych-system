package com.psych.assessment.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthUser {
    private Long id;
    private String username;
    private String role;
    private String realName;
}
