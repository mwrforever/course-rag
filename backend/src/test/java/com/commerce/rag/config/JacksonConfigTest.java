package com.commerce.rag.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * JacksonConfig 序列化行为测试 —— R0 Long→String 全局契约
 *
 * <p>以 Boot 自动装配同款路径构造 ObjectMapper（json() 构建器 + Boot 默认关闭时间戳 +
 * 应用本配置的 customizer），验证三点：
 * <ul>
 *   <li>Long/long 序列化为字符串（雪花 ID 19 位防 JS 精度丢失），Integer 与浮点不受影响</li>
 *   <li>null 的 Long 字段输出 null 字面量（而非省略或字符串 "null"）</li>
 *   <li>LocalDateTime 保持 ISO-8601 格式（追加式模块注册不得禁用 JavaTimeModule）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@DisplayName("JacksonConfig Long→String 序列化测试")
class JacksonConfigTest {

    /** 经 customizer 定制后的 ObjectMapper（模拟 Boot 自动装配路径） */
    private final ObjectMapper mapper = buildMapper();

    /**
     * 构建与生产一致的 ObjectMapper。
     *
     * <p>说明：WRITE_DATES_AS_TIMESTAMPS=false 是 Boot 标准定制器（JacksonAutoConfiguration）
     * 的默认行为，单测无 Boot 上下文故手工复刻，保证 ISO 日期断言与生产行为同构。
     *
     * @return 定制后的 ObjectMapper
     */
    private ObjectMapper buildMapper() {
        Jackson2ObjectMapperBuilder builder =
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }

    /** 记录样例：混合 Long/long/Integer/double/LocalDateTime 字段 */
    record Sample(Long id, long count, int page, double score, LocalDateTime createdAt) {}

    @Test
    @DisplayName("Long/long 序列化为字符串，Integer 与浮点保持数字（雪花 ID 防 JS 精度丢失）")
    void long字段序列化为字符串() throws Exception {
        String json = mapper.writeValueAsString(
                new Sample(1912345678901234567L, 42L, 1, 0.87, LocalDateTime.of(2026, 8, 24, 10, 15, 30)));

        // 19 位雪花 ID 与原生 long 均以字符串下发
        assertThat(json).contains("\"id\":\"1912345678901234567\"");
        assertThat(json).contains("\"count\":\"42\"");
        // Integer 与 double 不受影响（JS 可安全表示）
        assertThat(json).contains("\"page\":1");
        assertThat(json).contains("\"score\":0.87");
    }

    @Test
    @DisplayName("null 的 Long 字段输出 null 字面量而非省略")
    void null的Long字段输出null字符串而非省略() throws Exception {
        String json = mapper.writeValueAsString(new Sample(null, 0L, 1, 0.0, null));

        assertThat(json).contains("\"id\":null");
    }

    @Test
    @DisplayName("LocalDateTime 保持 ISO-8601 格式（追加式模块注册不破坏 JavaTimeModule）")
    void localDateTime保持ISO格式() throws Exception {
        String json = mapper.writeValueAsString(new Sample(1L, 1L, 1, 1.0, LocalDateTime.of(2026, 8, 24, 10, 15, 30)));

        assertThat(json).contains("\"createdAt\":\"2026-08-24T10:15:30\"");
    }
}
