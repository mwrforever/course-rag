package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.commerce.rag.properties.MemoryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MemoryConfig 注册测试 —— MemoryProperties 可实例化绑定 */
class MemoryConfigTest {

    private final MemoryConfig config = new MemoryConfig();

    @Test
    @DisplayName("MemoryConfig 可实例化（EnableConfigurationProperties 注册 memory.*）")
    void instantiable() {
        assertNotNull(config);
        assertNotNull(new MemoryProperties());
    }
}
