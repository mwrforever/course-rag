package com.commerce.rag.config;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.properties.AdminSeedProperties;
import com.commerce.rag.properties.AuthBlacklistProperties;
import com.commerce.rag.properties.AuthProperties;
import com.commerce.rag.properties.RegisterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证配置类 —— 注册 AuthProperties/AuthBlacklistProperties/AdminSeedProperties/RegisterProperties + AuthInterceptor
 *
 * @author commerce-rag
 */
@Configuration
@EnableConfigurationProperties({
    AuthProperties.class,
    AuthBlacklistProperties.class,
    AdminSeedProperties.class,
    RegisterProperties.class
})
public class AuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 公开前缀（/api/v1/public/**）与认证端点一并免拦截：无 token 可访问的 C 端浏览接口
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/public/**");
    }
}
