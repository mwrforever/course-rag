package com.commerce.rag.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.vo.CourseScheduleVO;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ScheduleConverter 转换器测试 —— 排期实体到视图对象字段映射正确性 */
@DisplayName("ScheduleConverter 转换器测试")
class ScheduleConverterTest {

    private final ScheduleConverter converter = new ScheduleConverterImpl();

    @Test
    @DisplayName("排期实体全部业务字段完整映射到视图对象（剔除 deleted）")
    void toVO_mapsAllBusinessFields() {
        LocalDate startDate = LocalDate.of(2026, 9, 1);
        LocalDate endDate = LocalDate.of(2026, 11, 30);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 2, 11, 30);
        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(1L);
        schedule.setCourseId(55L);
        schedule.setStartDate(startDate);
        schedule.setEndDate(endDate);
        schedule.setScheduleType("ONLINE");
        schedule.setLocation("线上教室 A");
        schedule.setInstructorName("李老师");
        schedule.setCapacity(50);
        schedule.setEnrolled(20);
        schedule.setStatus("UPCOMING");
        schedule.setCreatedBy(100L);
        schedule.setDeleted(0L);
        schedule.setCreatedAt(createdAt);
        schedule.setUpdatedAt(updatedAt);

        CourseScheduleVO vo = converter.toVO(schedule);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.courseId()).isEqualTo(55L);
        assertThat(vo.startDate()).isEqualTo(startDate);
        assertThat(vo.endDate()).isEqualTo(endDate);
        assertThat(vo.scheduleType()).isEqualTo("ONLINE");
        assertThat(vo.location()).isEqualTo("线上教室 A");
        assertThat(vo.instructorName()).isEqualTo("李老师");
        assertThat(vo.capacity()).isEqualTo(50);
        assertThat(vo.enrolled()).isEqualTo(20);
        assertThat(vo.status()).isEqualTo("UPCOMING");
        assertThat(vo.createdBy()).isEqualTo(100L);
        assertThat(vo.createdAt()).isEqualTo(createdAt);
        assertThat(vo.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("CourseScheduleVO 不含逻辑删除标记 deleted（内部字段不泄露）")
    void toVO_omitsDeleted() {
        CourseSchedule schedule = new CourseSchedule();
        schedule.setDeleted(0L);

        CourseScheduleVO vo = converter.toVO(schedule);

        // record 编译期已固定字段集合，此处断言字段集合无泄露访问器
        assertThat(vo).isNotNull();
        String[] componentNames = Arrays.stream(vo.getClass().getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
        assertThat(componentNames).doesNotContain("deleted");
        // VO 字段集合与实体业务字段（剔除 deleted）一一对应
        assertThat(componentNames)
                .containsExactlyInAnyOrder(
                        "id",
                        "courseId",
                        "startDate",
                        "endDate",
                        "scheduleType",
                        "location",
                        "instructorName",
                        "capacity",
                        "enrolled",
                        "status",
                        "createdBy",
                        "createdAt",
                        "updatedAt");
    }
}
