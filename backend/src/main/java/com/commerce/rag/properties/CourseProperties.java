package com.commerce.rag.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 课程域配置 —— 报名链接生成基址 + 封面上传限制（契约 A.2.1 / D.2.1）
 *
 * <p>承载两类配置：
 * <ul>
 *   <li>{@code course.enroll-base-url}：C 端报名链接生成基址——createCourse 落库后同事务生成
 *       {@code {enrollBaseUrl}/courses/{courseId}} 写回 enrollment_link（服务端管理字段，
 *       前端传入不再采信）；</li>
 *   <li>{@code course.cover.*}：B 端封面上传白名单与大小上限，上传校验与公开访问端点的
 *       objectKey 白名单正则同源于此（扩展名集合一致联动）。</li>
 * </ul>
 *
 * <p>{@code @Validated} 启动期校验：enrollBaseUrl 缺失/空白、白名单为空、上限 &lt; 1MB
 * 任一命中即启动失败（fail-fast，对齐 AttachmentProperties 范式）。
 *
 * @param enrollBaseUrl 报名链接生成基址（如 http://localhost:3000；不得含尾部斜杠以外的格式约束，
 *                      尾部斜杠由服务端归一化剥离）
 * @param cover         封面上传限制配置（扩展名白名单 + 单文件大小上限）
 */
@Validated
@ConfigurationProperties(prefix = "course")
public record CourseProperties(@NotBlank String enrollBaseUrl, @Valid Cover cover) {

    /**
     * 封面上传限制（契约 D.2.1）
     *
     * @param allowedExtensions 允许的图片扩展名白名单（小写，不含点号；默认 jpg,jpeg,png,webp）
     * @param maxSizeMb         单文件大小上限（MB，≥1）
     */
    public record Cover(@NotEmpty List<String> allowedExtensions, @Min(1) int maxSizeMb) {}
}
