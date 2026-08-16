package com.commerce.rag;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

/**
 * CommerceRagApplication 单元测试 —— main 方法启动委托验证
 *
 * <p>静态 mock {@link SpringApplication#run}，验证启动类 main 将应用入口委托给
 * Spring Boot 启动器（不真实拉起 Spring 容器，避免与集成测试重复建连）。
 *
 * @author commerce-rag
 */
@DisplayName("CommerceRagApplication 启动类测试")
class CommerceRagApplicationTest {

    @Test
    @DisplayName("main 方法委托 SpringApplication.run 启动应用")
    void main_delegatesToSpringApplicationRun() {
        try (var mocked = mockStatic(SpringApplication.class)) {
            mocked.when(() -> SpringApplication.run(CommerceRagApplication.class, new String[] {}))
                    .thenReturn(null);

            CommerceRagApplication.main(new String[] {});

            mocked.verify(() -> SpringApplication.run(CommerceRagApplication.class, new String[] {}));
        }
    }
}
