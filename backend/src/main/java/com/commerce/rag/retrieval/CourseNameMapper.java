package com.commerce.rag.retrieval;

import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.service.ICourseQueryService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 课程名 → course_id 映射器 —— LLM 语义标签的确定性查库映射（spec §2.3）
 *
 * <p>设计原则：
 * <ul>
 *   <li>LLM 只输出课程中文名（语义标签），不产 ID、不猜 ID；本组件以课程名精确查库映射</li>
 *   <li>同名多课（同名多期）→ 全部 course_id 注入过滤</li>
 *   <li>匹配失败 → 空列表（调用方 RetrieveNode 据此不设过滤，降级全局检索——
 *       开放问答无权限语义，不过滤只是召回范围放宽）</li>
 * </ul>
 *
 * <p>查询经 ICourseQueryService.findByTitle（带 Caffeine 缓存）复用，不直接操作 mapper
 * （工程宪法：跨 service 复用查询）。
 *
 * @author commerce-rag
 */
@Service
public class CourseNameMapper {

    private static final Logger log = LoggerFactory.getLogger(CourseNameMapper.class);

    private final ICourseQueryService courseQueryService;

    public CourseNameMapper(ICourseQueryService courseQueryService) {
        this.courseQueryService = courseQueryService;
    }

    /**
     * 课程名列表 → course_id 列表（去重保序）
     *
     * @param courseNames LLM 输出的课程名语义标签（可为 null/空）
     * @return 匹配的 course_id 字符串列表（去重保序）；空输入/无匹配返回空列表
     */
    public List<String> mapCourseNames(List<String> courseNames) {
        if (courseNames == null || courseNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String name : courseNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            List<CourseInfo> matched = courseQueryService.findByTitle(name.trim());
            for (CourseInfo course : matched) {
                if (course != null && course.getId() != null) {
                    ids.add(String.valueOf(course.getId()));
                }
            }
        }
        if (ids.isEmpty()) {
            log.debug("课程名映射无匹配，降级全局检索: courseNames={}", courseNames);
        }
        return new ArrayList<>(ids);
    }
}
