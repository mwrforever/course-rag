package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.properties.MemoryProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 偏好块组装测试 —— guaranteed 保底先注 + 扩展按 write_score 降序 + token 预算截断 */
class PreferenceBlockServiceTest {

    private final MemoryProperties props = new MemoryProperties();
    private final PreferenceBlockService service = new PreferenceBlockService(props);

    private static UserPreference pref(String key, String value, double score) {
        UserPreference r = new UserPreference();
        r.setKey(key);
        r.setValue(value);
        r.setWriteScore(BigDecimal.valueOf(score));
        r.setStatus("active");
        return r;
    }

    @Test
    @DisplayName("单值+多值混合 — guaranteed 先注、多值并列「、」、扩展按 write_score 降序")
    void build_ordersByGuaranteedThenScore() {
        List<UserPreference> rows = List.of(
                pref("course_direction", "数据分析", 0.8),
                pref("tech_stack", "Java", 0.9),
                pref("response_language", "中文", 0.95),
                pref("course_direction", "Python 开发", 0.85),
                pref("response_verbosity", "简洁", 0.9));
        String block = service.build(rows);

        assertTrue(block.startsWith("<preference>"));
        assertTrue(block.endsWith("</preference>"));
        // guaranteed 段（回答语言/回答详细度）在扩展段之前
        assertTrue(block.indexOf("回答语言:中文") < block.indexOf("课程方向:"));
        // 多值 key 并存
        assertTrue(block.contains("课程方向:Python 开发、数据分析"));
        // 扩展按 write_score 降序（tech_stack 0.9 先于 course_direction 0.85）
        assertTrue(block.indexOf("技术栈:Java") < block.indexOf("课程方向:"));
    }

    @Test
    @DisplayName("空 active 列表 — 返回空串（不注入）")
    void build_emptyReturnsBlank() {
        assertEquals("", service.build(List.of()));
    }

    @Test
    @DisplayName("token 预算截断 — 故意超长扩展 key 被跳过（guaranteed 不受影响）")
    void build_respectsTokenBudget() {
        MemoryProperties tiny = new MemoryProperties();
        tiny.getPreference().setTokenExtended(10); // 极小预算强制截断
        PreferenceBlockService small = new PreferenceBlockService(tiny);
        String block = small.build(
                List.of(pref("response_language", "中文", 0.9), pref("tech_stack", "一个超长技术栈描述会造成预算超限被截断", 0.9)));
        assertTrue(block.contains("回答语言:中文"));
        assertFalse(block.contains("技术栈:"), "超预算扩展 key 应被截断");
    }
}
