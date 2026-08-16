package com.commerce.rag.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ConcurrentRunException 单元测试 —— 两个构造器均需固定携带 CONFLICT 错误码
 *
 * <p>并发 run 冲突异常继承 {@link BizException}，无论使用哪个构造器，
 * 错误码必须恒为 CONFLICT（409），由全局异常处理器统一转换。
 *
 * @author commerce-rag
 */
@DisplayName("ConcurrentRunException 并发冲突异常测试")
class ConcurrentRunExceptionTest {

    @Test
    @DisplayName("单参构造器：携带 CONFLICT 错误码与自定义消息")
    void singleArgConstructor_carriesConflictCode() {
        ConcurrentRunException ex = new ConcurrentRunException("会话存在活跃 run");

        assertEquals("会话存在活跃 run", ex.getMessage());
        assertSame(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("双参构造器：保留 cause 且错误码仍为 CONFLICT")
    void twoArgConstructor_preservesCauseAndConflictCode() {
        IllegalStateException cause = new IllegalStateException("DB 唯一索引冲突");
        ConcurrentRunException ex = new ConcurrentRunException("并发 run 冲突", cause);

        assertSame(cause, ex.getCause());
        assertEquals("并发 run 冲突", ex.getMessage());
        assertSame(ErrorCode.CONFLICT, ex.getErrorCode());
    }
}
