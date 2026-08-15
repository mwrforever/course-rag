package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * F3Config 单元测试 —— 验证 F#3 防护层配置注册（三组 @ConfigurationProperties）
 *
 * @author commerce-rag
 */
@DisplayName("F3Config 配置注册测试")
class F3ConfigTest {

    @Test
    @DisplayName("F3Config 可实例化且声明注册三组防护配置类")
    void f3Config_instantiatesAndRegistersProperties() {
        F3Config config = new F3Config();
        assertNotNull(config);

        // @EnableConfigurationProperties 声明的注册清单应包含三组防护配置
        EnableConfigurationProperties annotation = F3Config.class.getAnnotation(EnableConfigurationProperties.class);
        assertNotNull(annotation);
        assertEquals(3, annotation.value().length);
    }
}
