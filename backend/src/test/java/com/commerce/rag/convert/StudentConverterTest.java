package com.commerce.rag.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.vo.ChunkBriefVO;
import com.commerce.rag.vo.ChunkContextVO;
import com.commerce.rag.vo.ChunkVO;
import com.commerce.rag.vo.SessionVO;
import com.commerce.rag.vo.StudentCourseVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** StudentConverter 转换器测试 —— 学生端实体 → 视图对象字段映射（含关联分片空安全） */
@DisplayName("StudentConverter 转换器测试")
class StudentConverterTest {

    private final StudentConverter converter = new StudentConverterImpl();

    private CourseInfo course() {
        CourseInfo c = new CourseInfo();
        c.setId(1L);
        c.setTitle("Java 入门");
        c.setCoverImage("cover.png");
        c.setCategory("编程");
        c.setInstructorName("张老师");
        c.setDuration("10h");
        c.setRating(new BigDecimal("4.5"));
        c.setLearningCount(100);
        return c;
    }

    private DocumentChunk chunk(Long id) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setDocId(1L);
        c.setKbId(1L);
        c.setContent("内容-" + id);
        c.setHeadingPath("第一章");
        c.setChunkIndex(1);
        c.setParentTitle("小节");
        c.setStartPage(1);
        c.setEndPage(2);
        c.setCourseId("DEFAULT");
        return c;
    }

    @Test
    @DisplayName("课程实体 → 学生课程视图（剔除价格/标签等内部字段）")
    void toCourseVO_mapsAllFields() {
        StudentCourseVO vo = converter.toCourseVO(course());

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.title()).isEqualTo("Java 入门");
        assertThat(vo.coverImage()).isEqualTo("cover.png");
        assertThat(vo.category()).isEqualTo("编程");
        assertThat(vo.instructorName()).isEqualTo("张老师");
        assertThat(vo.duration()).isEqualTo("10h");
        assertThat(vo.rating()).isEqualByComparingTo(new BigDecimal("4.5"));
        assertThat(vo.learningCount()).isEqualTo(100);
        assertThat(vo)
                .isEqualTo(new StudentCourseVO(
                        1L, "Java 入门", "cover.png", "编程", "张老师", "10h", new BigDecimal("4.5"), 100));
        assertThat(vo.toString()).contains("Java 入门");
    }

    @Test
    @DisplayName("分片实体 → 资料分片视图（含起止页）")
    void toChunkVO_mapsAllFields() {
        ChunkVO vo = converter.toChunkVO(chunk(1L));

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.content()).isEqualTo("内容-1");
        assertThat(vo.headingPath()).isEqualTo("第一章");
        assertThat(vo.chunkIndex()).isEqualTo(1);
        assertThat(vo.parentTitle()).isEqualTo("小节");
        assertThat(vo.startPage()).isEqualTo(1);
        assertThat(vo.endPage()).isEqualTo(2);
        assertThat(vo).isEqualTo(new ChunkVO(1L, "内容-1", "第一章", 1, "小节", 1, 2));
        assertThat(vo.toString()).contains("内容-1");
    }

    @Test
    @DisplayName("分片实体 → 简略视图（列表最小字段集）")
    void toChunkBriefVO_mapsAllFields() {
        ChunkBriefVO vo = converter.toChunkBriefVO(chunk(2L));

        assertThat(vo.id()).isEqualTo(2L);
        assertThat(vo.content()).isEqualTo("内容-2");
        assertThat(vo.headingPath()).isEqualTo("第一章");
        assertThat(vo.chunkIndex()).isEqualTo(1);
        assertThat(vo.parentTitle()).isEqualTo("小节");
        assertThat(vo).isEqualTo(new ChunkBriefVO(2L, "内容-2", "第一章", 1, "小节"));
        assertThat(vo.toString()).contains("内容-2");
    }

    @Test
    @DisplayName("分片 + 父/前/后 → 上下文视图（关联分片映射为简略视图）")
    void toChunkContextVO_mapsAllFields() {
        DocumentChunk main = chunk(1L);
        main.setParentChunkId(100L);
        main.setPrevChunkId(101L);
        main.setNextChunkId(102L);

        ChunkContextVO vo = converter.toChunkContextVO(main, chunk(100L), chunk(101L), chunk(102L));

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.docId()).isEqualTo(1L);
        assertThat(vo.kbId()).isEqualTo(1L);
        assertThat(vo.courseId()).isEqualTo("DEFAULT");
        assertThat(vo.parentChunkId()).isEqualTo(100L);
        assertThat(vo.prevChunkId()).isEqualTo(101L);
        assertThat(vo.nextChunkId()).isEqualTo(102L);
        assertThat(vo.parent().id()).isEqualTo(100L);
        assertThat(vo.prev().id()).isEqualTo(101L);
        assertThat(vo.next().id()).isEqualTo(102L);
        assertThat(vo.parent().content()).isEqualTo("内容-100");
        assertThat(vo.toString()).contains("content");
    }

    @Test
    @DisplayName("上下文视图：关联分片为 null 时映射为 null（不抛异常）")
    void toChunkContextVO_nullNeighbors() {
        ChunkContextVO vo = converter.toChunkContextVO(chunk(1L), null, null, null);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.parent()).isNull();
        assertThat(vo.prev()).isNull();
        assertThat(vo.next()).isNull();
    }

    @Test
    @DisplayName("会话实体 → 会话视图（剔除 userId/model 等内部字段）")
    void toSessionVO_mapsAllFields() {
        ChatSession s = new ChatSession();
        s.setId(1L);
        s.setUserId(5L);
        s.setTitle("会话一");
        s.setStatus("ACTIVE");
        s.setLastMessageAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        s.setModel("qwen3.8-max");
        s.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 0));

        SessionVO vo = converter.toSessionVO(s);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.title()).isEqualTo("会话一");
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.lastMessageAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 10, 0));
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 9, 0));
        assertThat(vo)
                .isEqualTo(new SessionVO(
                        1L,
                        "会话一",
                        "ACTIVE",
                        LocalDateTime.of(2026, 8, 15, 10, 0),
                        LocalDateTime.of(2026, 8, 15, 9, 0)));
        assertThat(vo.toString()).contains("会话一");
    }
}
