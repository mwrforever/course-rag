package com.commerce.rag.config;

import com.commerce.rag.properties.CorsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 跨域配置 —— 允许前端跨域访问
 *
 * <p>与 httpOnly cookie 协同需 allowCredentials=true，
 * 此时 allowedOrigins 不能用 "*"，必须指定具体来源。
 * 来源列表经 {@link CorsProperties}（cors.allowed-origins）强类型注入
 * （BUG-12 @Value 收敛，宪法 A.2.2）。
 *
 * @author commerce-rag
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** 允许的跨域来源列表（经 CorsProperties 强类型注入） */
    private final String[] allowedOrigins;

    public CorsConfig(CorsProperties corsProperties) {
        this.allowedOrigins = corsProperties.allowedOrigins();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
        log.info("CORS 配置已加载: allowedOrigins={}", String.join(",", allowedOrigins));
    }
}
