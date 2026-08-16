package com.commerce.rag.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.vo.ChatRunVO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ChatRunConverter 转换器测试 —— Run 实体 → 视图对象字段映射 */
@DisplayName("ChatRunConverter 转换器测试")
class ChatRunConverterTest {

    private final ChatRunConverter converter = new ChatRunConverterImpl();

    @Test
    @DisplayName("Run 实体 → 视图对象（业务字段映射，内部字段剔除）")
    void toVO_mapsBusinessFields() {
        ChatRun run = new ChatRun();
        run.setId(1L);
        run.setSessionId(2L);
        run.setUserId(3L);
        run.setStatus("ACTIVE");
        // 内部字段（应被剔除，不随 VO 出 service 边界）
        run.setModelCalls(5);
        run.setTraceId("trace-1");
        run.setErrorMessage("err");
        run.setMetaJson("{}");
        run.setDeleted(0L);
        run.setStartedAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        run.setEndedAt(LocalDateTime.of(2026, 8, 15, 10, 5));
        run.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 0));

        ChatRunVO vo = converter.toVO(run);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.sessionId()).isEqualTo(2L);
        assertThat(vo.userId()).isEqualTo(3L);
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 9, 0));
    }
}
