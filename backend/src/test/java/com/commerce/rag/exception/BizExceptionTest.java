package com.commerce.rag.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BizException 单元测试 —— 三个构造器与错误码 getter 的完整覆盖
 *
 * <p>覆盖单参（ErrorCode 默认消息）、双参（自定义消息）、三参（携带 cause）三条构造路径，
 * 并验证 getCode/getErrorCode 与 ErrorCode 枚举的映射契约。
 *
 * @author commerce-rag
 */
@DisplayName("BizException 业务异常测试")
class BizExceptionTest {

    @Test
    @DisplayName("单参构造器：使用 ErrorCode 默认消息，getCode/getErrorCode 映射正确")
    void singleArgConstructor_usesErrorCodeDefaultMessage() {
        BizException ex = new BizException(ErrorCode.BAD_REQUEST);

        assertEquals(ErrorCode.BAD_REQUEST.getMessage(), ex.getMessage());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        assertSame(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("双参构造器：自定义消息覆盖 ErrorCode 默认消息")
    void twoArgConstructor_usesCustomMessage() {
        BizException ex = new BizException(ErrorCode.NOT_FOUND, "课程不存在");

        assertEquals("课程不存在", ex.getMessage());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        assertSame(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("三参构造器：保留 cause 与自定义消息，错误码映射正确")
    void threeArgConstructor_preservesCauseAndMessage() {
        IllegalStateException cause = new IllegalStateException("底层原因");
        BizException ex = new BizException(ErrorCode.CONFLICT, "资源状态冲突", cause);

        assertEquals("资源状态冲突", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertSame(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }
}
