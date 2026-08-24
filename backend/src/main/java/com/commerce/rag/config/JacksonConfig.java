package com.commerce.rag.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局序列化配置 —— Long/long 一律序列化为 JSON 字符串（R0）
 *
 * <p>核心职责：将 Long/long 统一序列化为字符串。雪花 ID 为 19 位数字，超出 JS
 * Number.MAX_SAFE_INTEGER（2^53-1），以 JSON number 下发必然导致前端精度丢失
 * （反馈 messageId、会话 id、分片 id 等全部错位）。影响范围为一切 Long 来源字段
 * （id/total/fileSize/learningCount/各统计计数），见
 * docs/backed/2026-08-24-后端功能调整.md §一（R0）。
 *
 * <p>实现方式（与 Spring Boot 3 自动配置的兼容性考量）：
 * <ul>
 *   <li>采用 {@link Jackson2ObjectMapperBuilderCustomizer} 定制 Boot 自动装配的构建器，
 *       不自建 ObjectMapper Bean——保留 Boot 全部默认行为（LocalDateTime ISO 格式、
 *       spring.jackson.* 属性、标准定制器与其它定制器的既有配置）</li>
 *   <li>以 {@code modulesToInstall(Consumer)} 追加式注册模块（Boot 标准定制器消费
 *       Module Bean 的同一手法）。禁止改用 {@code builder.modules(...)}——该方法会置
 *       findWellKnownModules=false，静默丢弃 JavaTimeModule/Jdk8Module 等已知模块，
 *       破坏 LocalDateTime 的 ISO 序列化</li>
 * </ul>
 *
 * <p>不受影响：Integer（page/size）与浮点/BigDecimal（rating/score/likeRate/price）；
 * SSE 事件 payload（worker/entry 手工 toJson，runId 本就是字符串）。
 * 依赖 Boot 的 Jackson 自动装配（spring-boot-starter-web 引入）。
 *
 * @author commerce-rag
 */
@Configuration
public class JacksonConfig {

    /**
     * 注册 Long/long → ToStringSerializer 的构建器定制器。
     *
     * <p>执行流程：Boot 自动装配创建 Jackson2ObjectMapperBuilder 后按序应用全部
     * customizer（Boot 标准定制器 order=0 先行关闭日期时间戳并注册 Module Bean，
     * 本定制器默认最低优先级后行追加），最终构建容器级 ObjectMapper；追加式注册
     * 不覆盖任何既有模块，序列化器按后注册优先生效。
     *
     * @return Long→String 定制器（由 Boot 的 Jackson 自动装配消费，不直接暴露给业务层）
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        // Long 包装类型与 long 原生类型分别注册：覆盖 VO 字段、Map 计数值与原生 long 三类来源
        SimpleModule module = new SimpleModule("LongToStringModule");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        // 追加式注册：保留已知模块（JavaTimeModule 等）与标准定制器已收集的 Module Bean
        return builder -> builder.modulesToInstall(modules -> modules.add(module));
    }
}
