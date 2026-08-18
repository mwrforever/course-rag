package com.commerce.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.service.ICourseQueryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CourseNameMapper 单元测试 —— 课程名语义标签 → course_id 确定性映射（spec §2.3）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CourseNameMapper 课程名映射测试")
class CourseNameMapperTest {

    @Mock
    private ICourseQueryService courseQueryService;

    @Test
    @DisplayName("mapCourseNames — 单课程名映射为 id 列表")
    void mapCourseNames_singleName_mapsToIds() {
        CourseInfo math = course(101L, "高等数学");
        when(courseQueryService.findByTitle("高等数学")).thenReturn(List.of(math));
        CourseNameMapper mapper = new CourseNameMapper(courseQueryService);

        List<String> ids = mapper.mapCourseNames(List.of("高等数学"));

        assertEquals(List.of("101"), ids);
    }

    @Test
    @DisplayName("mapCourseNames — 同名多课全注入（同名多期），多个课程名结果合并去重保序")
    void mapCourseNames_sameNameMultipleCourses_allInjected() {
        CourseInfo mathA = course(101L, "高等数学");
        CourseInfo mathB = course(102L, "高等数学");
        when(courseQueryService.findByTitle("高等数学")).thenReturn(List.of(mathA, mathB));
        when(courseQueryService.findByTitle("高等数学(周末班)")).thenReturn(List.of(mathB));
        CourseNameMapper mapper = new CourseNameMapper(courseQueryService);

        List<String> ids = mapper.mapCourseNames(List.of("高等数学", "高等数学(周末班)"));

        assertEquals(List.of("101", "102"), ids);
    }

    @Test
    @DisplayName("mapCourseNames — 无匹配课程降级空列表（调用方据此不设过滤，全局检索）")
    void mapCourseNames_noMatch_returnsEmpty() {
        when(courseQueryService.findByTitle("未知课程")).thenReturn(List.of());
        CourseNameMapper mapper = new CourseNameMapper(courseQueryService);

        assertEquals(List.of(), mapper.mapCourseNames(List.of("未知课程")));
    }

    @Test
    @DisplayName("mapCourseNames — 空/空白输入返回空列表，不查库")
    void mapCourseNames_blankInput_returnsEmptyWithoutQuery() {
        CourseNameMapper mapper = new CourseNameMapper(courseQueryService);

        assertEquals(List.of(), mapper.mapCourseNames(List.of()));
        assertEquals(List.of(), mapper.mapCourseNames(null));
    }

    private static CourseInfo course(long id, String title) {
        CourseInfo c = new CourseInfo();
        c.setId(id);
        c.setTitle(title);
        return c;
    }
}
