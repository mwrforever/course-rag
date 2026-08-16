package com.commerce.rag.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.dto.UserDTO;
import com.commerce.rag.entity.SysUser;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SysUserConverter 转换器测试 —— 实体到 DTO 字段映射正确性 */
@DisplayName("SysUserConverter 转换器测试")
class SysUserConverterTest {

    private final SysUserConverter converter = new SysUserConverterImpl();

    @Test
    @DisplayName("实体字段完整映射到 DTO")
    void toDTO_mapsAllFields() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("student1");
        user.setDisplayName("学生一");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        UserDTO dto = converter.toDTO(user);
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.username()).isEqualTo("student1");
        assertThat(dto.displayName()).isEqualTo("学生一");
        assertThat(dto.role()).isEqualTo("STUDENT");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
    }
}
