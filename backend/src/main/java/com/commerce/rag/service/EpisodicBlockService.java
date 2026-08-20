package com.commerce.rag.service;

import com.commerce.rag.constants.EpisodicTypes;
import com.commerce.rag.etl.TokenEstimator;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.EpisodicMemoryRef;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 经历记忆块组装 —— 召回引用 → &lt;episodic&gt; 文本（spec §8.7/§8.8）
 *
 * <p>状态标注：validity=active → 「类型(当前):内容」；superseded/merged/invalidated（仅
 * recall_history=true 召回）→ 「类型(历史记录):内容」；预算独立 1200 token（spec §8.8，
 * 与偏好块互不挤占），TokenEstimator 估算用完截断。
 *
 * @author commerce-rag
 */
@Service
public class EpisodicBlockService {

    private final MemoryProperties properties;

    public EpisodicBlockService(MemoryProperties properties) {
        this.properties = properties;
    }

    /**
     * 组装经历记忆块文本
     *
     * @param refs 召回引用（按召回分降序，可为空）
     * @return &lt;episodic&gt; 块文本；无引用返回空串（拦截器据此不注入）
     */
    public String build(List<EpisodicMemoryRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<episodic>\n");
        int tokens = 0;
        for (EpisodicMemoryRef ref : refs) {
            String label = EpisodicTypes.LABELS.getOrDefault(ref.type(), ref.type());
            // 状态标注（spec §8.7）：active=当前，其它=历史记录
            String tag = "active".equals(ref.validity()) ? "当前" : "历史记录";
            String line = label + "(" + tag + "):" + ref.content() + "\n";
            if (tokens + TokenEstimator.estimate(line)
                    > properties.getEpisodic().getTokenBudget()) {
                break; // 预算用完截断（spec §8.8 独立 1200）
            }
            sb.append(line);
            tokens += TokenEstimator.estimate(line);
        }
        if (sb.length() == "<episodic>\n".length()) {
            return "";
        }
        sb.append("</episodic>");
        return sb.toString();
    }
}
