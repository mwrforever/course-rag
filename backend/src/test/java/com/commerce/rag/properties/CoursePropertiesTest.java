package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CourseProperties 默认值与启动期校验测试（与 application.yml course 段一致）
 *
 * <p>覆盖：默认绑定值（报名链接基址 / 封面白名单 / 大小上限）、
 * {@code @Validated} 启动期校验（enrollBaseUrl 缺失/空白、白名单为空、上限 &lt; 1MB 违规）。
 */
class CoursePropertiesTest {

    /** 与 application.yml course 段一致的默认配置 */
    private CourseProperties defaultProperties() {
        return new CourseProperties(
                "http://localhost:3000", new CourseProperties.Cover(List.of("jpg", "jpeg", "png", "webp"), 5));
    }

    @Test
    @DisplayName("默认值 — 报名基址 localhost:3000、封面白名单四扩展名、上限 5MB")
    void defaults() {
        CourseProperties p = defaultProperties();
        assertEquals("http://localhost:3000", p.enrollBaseUrl(), "报名链接基址应绑定 course.enroll-base-url");
        assertEquals(
                List.of("jpg", "jpeg", "png", "webp"),
                p.cover().allowedExtensions(),
                "白名单应绑定 course.cover.allowed-extensions");
        assertEquals(5, p.cover().maxSizeMb(), "大小上限应绑定 course.cover.max-size-mb");
    }

    @Test
    @DisplayName("启动期校验 — enrollBaseUrl 缺失/空白产生校验违规（配置缺失启动失败兜底）")
    void enrollBaseUrlMissing_validationFails() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        CourseProperties missing = new CourseProperties(null, new CourseProperties.Cover(List.of("png"), 5));
        CourseProperties blank = new CourseProperties("   ", new CourseProperties.Cover(List.of("png"), 5));

        assertTrue(
                validator.validate(missing).stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("enrollBaseUrl")),
                "enrollBaseUrl 缺失应产生校验违规（启动失败）");
        assertTrue(
                validator.validate(blank).stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("enrollBaseUrl")),
                "enrollBaseUrl 空白应产生校验违规");
    }

    @Test
    @DisplayName("启动期校验 — 封面白名单为空 / 上限小于 1MB 产生校验违规")
    void coverInvalid_validationFails() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        CourseProperties emptyExtList =
                new CourseProperties("http://localhost:3000", new CourseProperties.Cover(List.of(), 5));
        CourseProperties zeroMaxSize =
                new CourseProperties("http://localhost:3000", new CourseProperties.Cover(List.of("png"), 0));

        assertTrue(
                validator.validate(emptyExtList).stream()
                        .anyMatch(v -> v.getPropertyPath().toString().startsWith("cover.allowedExtensions")),
                "白名单为空应产生校验违规");
        assertTrue(
                validator.validate(zeroMaxSize).stream()
                        .anyMatch(v -> v.getPropertyPath().toString().startsWith("cover.maxSizeMb")),
                "上限小于 1MB 应产生校验违规");
    }
}
