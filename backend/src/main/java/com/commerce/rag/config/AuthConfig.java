package com.commerce.rag.config;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.properties.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证配置类 —— 注册 AuthProperties + AuthInterceptor
 *
 * @author commerce-rag
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/v1/**").excludePathPatterns("/api/v1/auth/**");
    }
}
