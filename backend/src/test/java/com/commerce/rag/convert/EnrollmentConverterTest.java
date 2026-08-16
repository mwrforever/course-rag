package com.commerce.rag.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.SysUser;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EnrollmentConverter 转换器测试 —— 用户实体 + 选课记录到学生 DTO 字段映射正确性 */
@DisplayName("EnrollmentConverter 转换器测试")
class EnrollmentConverterTest {

    private final EnrollmentConverter converter = new EnrollmentConverterImpl();

    @Test
    @DisplayName("多源映射 — 5 字段完整映射（用户 3 字段 + 选课 2 字段）")
    void toDTO_mapsAllFields() {
        SysUser user = new SysUser();
        user.setId(10L);
        user.setUsername("stu001");
        user.setDisplayName("张三");

        CourseEnrollment enrollment = new CourseEnrollment();
        enrollment.setStudentId(10L);
        enrollment.setEnrolledAt(LocalDateTime.of(2026, 8, 1, 10, 30));
        enrollment.setStatus("ACTIVE");

        StudentDTO dto = converter.toDTO(user, enrollment);

        assertThat(dto.id()).isEqualTo(10L);
        assertThat(dto.username()).isEqualTo("stu001");
        assertThat(dto.displayName()).isEqualTo("张三");
        assertThat(dto.enrolledAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 30));
        assertThat(dto.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("选课记录为 null — 用户字段仍映射，enrolledAt/status 为 null")
    void toDTO_enrollmentNull_userFieldsStillMapped() {
        SysUser user = new SysUser();
        user.setId(20L);
        user.setUsername("stu002");
        user.setDisplayName("李四");

        StudentDTO dto = converter.toDTO(user, null);

        assertThat(dto.id()).isEqualTo(20L);
        assertThat(dto.username()).isEqualTo("stu002");
        assertThat(dto.displayName()).isEqualTo("李四");
        assertThat(dto.enrolledAt()).isNull();
        assertThat(dto.status()).isNull();
    }
}
