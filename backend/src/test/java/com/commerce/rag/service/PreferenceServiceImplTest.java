package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.enums.PreferenceActionType;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.PreferenceAction;
import com.commerce.rag.service.impl.PreferenceServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 偏好服务纯函数测试 —— 注：this.lambdaQuery() 不可 Mockito 直测（MP 实证），
 * SQL 获取/写库段由 Testcontainers 集成覆盖（见 Step 5）；本类只测纯函数。
 */
class PreferenceServiceImplTest {

    private final MemoryProperties props = new MemoryProperties();
    private final PreferenceServiceImpl service = new PreferenceServiceImpl(new PreferenceDecisionEngine(props));

    @Test
    @DisplayName("toExistingValuesText — active 偏好转「标签:值」行，空返回「无」")
    void existingValuesText_buildsLines() {
        UserPreference a = active("response_verbosity", "简洁", 0.8);
        UserPreference b = active("course_direction", "Python 开发", 0.75);
        String text = service.toExistingValuesText(List.of(a, b));
        assertTrue(text.contains("回答详细度:简洁"));
        assertTrue(text.contains("课程方向:Python 开发"));
    }

    @Test
    @DisplayName("toExistingValuesText — 空列表返回「无」")
    void existingValuesText_emptyReturnsNone() {
        assertEquals("无", service.toExistingValuesText(List.of()));
    }

    @Test
    @DisplayName("collectWrites — IGNORE 不计入写数，其余各计 1")
    void collectWrites_counts() {
        List<PreferenceAction> actions = List.of(
                action(PreferenceActionType.IGNORE),
                action(PreferenceActionType.CREATE_ACTIVE),
                action(PreferenceActionType.REINFORCE));
        assertEquals(2, service.collectWrites(actions));
    }

    @Test
    @DisplayName("toWriteRow — CREATE 动作构造完整行（分数/计数/版本/来源，含 explicitness/confidence 审计）")
    void toWriteRow_buildsEntity() {
        PreferenceAction a = action(PreferenceActionType.CREATE_ACTIVE);
        UserPreference row = service.toWriteRow(7L, a, "active", "explicit");
        assertEquals(7L, row.getUserId());
        assertEquals("response_language", row.getKey());
        assertEquals("中文", row.getValue());
        assertEquals("active", row.getStatus());
        assertEquals("explicit", row.getSource());
        assertEquals(0, new BigDecimal("0.900").compareTo(row.getWriteScore()));
        assertEquals(0, new BigDecimal("0.900").compareTo(row.getExplicitness()));
        assertEquals(0, new BigDecimal("0.900").compareTo(row.getConfidence()));
        assertEquals(0, new BigDecimal("0.700").compareTo(row.getStability()));
        assertEquals(Integer.valueOf(5), row.getObservationCount());
        assertEquals(Integer.valueOf(1), row.getVersion());
    }

    private static UserPreference active(String key, String value, double score) {
        UserPreference r = new UserPreference();
        r.setKey(key);
        r.setValue(value);
        r.setStatus("active");
        r.setWriteScore(BigDecimal.valueOf(score));
        return r;
    }

    private static PreferenceAction action(PreferenceActionType type) {
        return new PreferenceAction(type, "response_language", "中文", 1L, null, 0.9, 0.9, 0.9, 0.7, 5, 1);
    }
}
