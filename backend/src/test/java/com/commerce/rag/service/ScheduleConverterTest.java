package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.controller.dto.ScheduleDTO;
import com.commerce.rag.entity.CourseSchedule;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ScheduleConverter 转换器测试 —— 排期实体到 DTO 字段映射正确性 */
@DisplayName("ScheduleConverter 转换器测试")
class ScheduleConverterTest {

    private final ScheduleConverter converter = new ScheduleConverterImpl();

    @Test
    @DisplayName("排期实体 11 字段完整映射到 DTO")
    void toDTO_mapsAllFields() {
        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(1L);
        schedule.setCourseId(55L);
        schedule.setStartDate(LocalDate.of(2026, 9, 1));
        schedule.setEndDate(LocalDate.of(2026, 11, 30));
        schedule.setScheduleType("ONLINE");
        schedule.setLocation("线上教室 A");
        schedule.setInstructorName("李老师");
        schedule.setCapacity(50);
        schedule.setEnrolled(20);
        schedule.setStatus("UPCOMING");
        schedule.setCreatedBy(100L);

        ScheduleDTO dto = converter.toDTO(schedule);

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.courseId()).isEqualTo(55L);
        assertThat(dto.startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(dto.endDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(dto.scheduleType()).isEqualTo("ONLINE");
        assertThat(dto.location()).isEqualTo("线上教室 A");
        assertThat(dto.instructorName()).isEqualTo("李老师");
        assertThat(dto.capacity()).isEqualTo(50);
        assertThat(dto.enrolled()).isEqualTo(20);
        assertThat(dto.status()).isEqualTo("UPCOMING");
        assertThat(dto.createdBy()).isEqualTo(100L);
    }
}
