package com.commerce.rag.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ContentHash 单元测试 —— 归一化规则与摘要（spec §4.4 定稿）
 *
 * @author commerce-rag
 */
class ContentHashTest {

    @Test
    @DisplayName("归一化 — 首尾空白去除 + 空白折叠为单空格（含全角空格）")
    void normalize_collapsesWhitespace() {
        String raw = "  检索   增强\u3000生成  ";
        assertEquals("检索 增强 生成", ContentHash.of(raw).normalizedText());
    }

    @Test
    @DisplayName("归一化 — 常见中英文标点删除")
    void normalize_removesPunctuation() {
        assertEquals("检索增强生成", ContentHash.of("检索。增强！生成？").normalizedText());
        assertEquals("hello world", ContentHash.of("hello, world.").normalizedText());
    }

    @Test
    @DisplayName("归一化 — 统一小写")
    void normalize_lowercases() {
        assertEquals("python 开发", ContentHash.of("Python 开发").normalizedText());
    }

    @Test
    @DisplayName("相同语义文本（标点/空白/大小写差异）— 哈希一致")
    void sameSemanticText_sameHash() {
        ContentHash a = ContentHash.of("Python  开发,基础！");
        ContentHash b = ContentHash.of("python 开发基础");

        assertEquals(a.sha256(), b.sha256());
    }

    @Test
    @DisplayName("不同内容 — 哈希不同；格式为 64 位十六进制")
    void differentContent_differentHash() {
        String hash = ContentHash.of("内容A").sha256();
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertNotEquals(hash, ContentHash.of("内容B").sha256());
    }

    @Test
    @DisplayName("sha256Hex(byte[]) — 与 JDK 摘要一致（确定性）")
    void sha256Hex_bytes_deterministic() {
        assertEquals(ContentHash.sha256Hex(new byte[] {1, 2, 3}), ContentHash.sha256Hex(new byte[] {1, 2, 3}));
        assertEquals(64, ContentHash.sha256Hex(new byte[] {1, 2, 3}).length());
    }
}
