package com.commerce.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置
 *
 * <p>配置：
 * <ul>
 *   <li>禁用 CSRF（API 项目）</li>
 *   <li>启用 @PreAuthorize 注解（方法级权限）</li>
 *   <li>配置 BCryptPasswordEncoder</li>
 *   <li>放行 /api/v1/auth/** 路径</li>
 *   <li>无状态 Session（JWT 鉴权）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /**
     * 密码编码器 —— BCrypt
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 安全过滤链配置
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用 CSRF（API 项目，不使用表单）
                .csrf(csrf -> csrf.disable())
                // 无状态 Session（JWT 鉴权）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 放行认证端点
                // 鉴权由 AuthInterceptor（HandlerInterceptor）统一处理，SecurityConfig 放行所有请求
                // （P3-4: 已删除 /api/v1/public/** 死配置——AuthConfig 排除项已删，无端点指向该前缀）
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/auth/**")
                        .permitAll()
                        .anyRequest()
                        .permitAll())
                // 禁用默认登录表单 + HTTP Basic
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }
}
