package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.vo.UserFeedbackVO;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** UserFeedbackConverter 转换器测试 —— 实体到 VO 字段映射正确性 + 敏感字段不泄露 */
@DisplayName("UserFeedbackConverter 转换器测试")
class UserFeedbackConverterTest {

    private final UserFeedbackConverter converter = new UserFeedbackConverterImpl();

    @Test
    @DisplayName("实体全部业务字段完整映射到 VO")
    void toVO_mapsAllBusinessFields() {
        UserFeedback feedback = new UserFeedback();
        feedback.setId(1L);
        feedback.setSessionId(10L);
        feedback.setMessageId(100L);
        feedback.setUserId(200L);
        feedback.setIsLiked(true);
        feedback.setIntentType("TECHNICAL_QA");
        feedback.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));

        UserFeedbackVO vo = converter.toVO(feedback);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.sessionId()).isEqualTo(10L);
        assertThat(vo.messageId()).isEqualTo(100L);
        assertThat(vo.userId()).isEqualTo(200L);
        assertThat(vo.isLiked()).isTrue();
        assertThat(vo.intentType()).isEqualTo("TECHNICAL_QA");
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
    }

    @Test
    @DisplayName("UserFeedbackVO 不含逻辑删除标记 deleted（内部字段不泄露）")
    void toVO_omitsDeleted() {
        UserFeedback feedback = new UserFeedback();
        feedback.setDeleted(0L);
        UserFeedbackVO vo = converter.toVO(feedback);

        // record 编译期已固定字段集合，此处断言字段集合无泄露访问器
        assertThat(vo).isNotNull();
        String[] componentNames = Arrays.stream(vo.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
        assertThat(componentNames).doesNotContain("deleted");
        // VO 字段集合与实体业务字段（剔除 deleted）一一对应
        assertThat(componentNames)
                .containsExactlyInAnyOrder(
                        "id", "sessionId", "messageId", "userId", "isLiked", "intentType", "createdAt");
    }
}
