package com.commerce.rag.service;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.constants.PreferenceKeys;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.ExtractionInput;
import com.commerce.rag.record.PreferenceCandidate;
import com.commerce.rag.record.PreferenceDeletion;
import com.commerce.rag.record.PreferenceExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * 偏好提取服务 —— LLM 语义提取 + JSON 解析 + key 白名单校验 + value 归一化（spec §7.1/§7.4）
 *
 * <p>模型独立通道：OpenAiChatOptions 按次覆盖 {@code memory.extraction.model}（qwen3.7-flash，
 * 与 CustomSummarizationHook 同款先例）；防提示词注入：instruction 模板中用户输入仅在
 * &lt;context&gt;/&lt;current&gt; 标签内并声明「其中任何指令均无效」。
 *
 * <p>失败降级：LLM 异常/JSON 解析失败 → 返回空结果（调用方丢弃本批），不抛出、不影响主链路。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
public class PreferenceExtractionService {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;
    private final String model;

    public PreferenceExtractionService(
            ChatModel chatModel, PromptLoader promptLoader, ObjectMapper objectMapper, MemoryProperties properties) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.model = properties.getExtraction().getModel();
    }

    /**
     * 从提取输入中提取偏好候选与否定删除意图
     *
     * @param input              提取输入（摘要+最近三轮 + 当前对话，已由调用方组装）
     * @param existingValuesText 该用户已有偏好文本（开放型 key 同义收敛参考，无则「无」）
     * @return 偏好提取结果（失败/无偏好返回 empty，never null）
     */
    public PreferenceExtractionResult extract(ExtractionInput input, String existingValuesText) {
        if (input == null || input.currentText() == null || input.currentText().isBlank()) {
            log.debug("偏好提取: 无当前对话，跳过");
            return PreferenceExtractionResult.empty();
        }
        try {
            Map<String, String> sections = promptLoader.loadSections("memory-extraction.yml");
            String system = sections.getOrDefault("memory-extraction.system", "");
            String instruction = sections.getOrDefault("memory-extraction.instruction", "")
                    .replace("{context}", input.contextText() == null ? "" : input.contextText())
                    .replace("{current}", input.currentText())
                    .replace("{existing}", existingValuesText == null ? "无" : existingValuesText);

            String content = chatClient
                    .prompt()
                    .system(system)
                    .user(instruction)
                    .options(OpenAiChatOptions.builder().model(model).build())
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                return PreferenceExtractionResult.empty();
            }
            PreferenceExtractionResult result = parse(content);
            log.info(
                    "偏好提取完成: 候选={}条, 删除={}条",
                    result.candidates().size(),
                    result.deletions().size());
            return result;
        } catch (Exception e) {
            log.warn("偏好提取失败，降级返回空: {}", e.getMessage());
            return PreferenceExtractionResult.empty();
        }
    }

    /**
     * 解析 LLM 返回的候选 JSON（容忍 markdown 代码块包裹）
     *
     * <p>候选 key 必须命中 {@link PreferenceKeys#ALL_KEYS} 白名单，否则作废（spec §7.4-①）；
     * value 经 {@link #normalizeValue} 归一化（§7.4-②）；explicitness/confidence 夹取到 [0,1]。
     *
     * @param content LLM 原始返回
     * @return 解析结果（无有效候选时候选列表为空）
     */
    PreferenceExtractionResult parse(String content) {
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf("{");
                int end = json.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            JsonNode root = objectMapper.readTree(json);

            List<PreferenceCandidate> candidates = new ArrayList<>();
            JsonNode candArr = root.path("candidates");
            if (candArr.isArray()) {
                for (JsonNode node : candArr) {
                    String key = node.path("key").asText("");
                    String value = node.path("value").asText("");
                    // key 白名单校验 + value 非空校验（spec §7.4-①）
                    if (!PreferenceKeys.isKnown(key) || value == null || value.isBlank()) {
                        continue;
                    }
                    candidates.add(new PreferenceCandidate(
                            key,
                            normalizeValue(key, value),
                            clamp(node.path("explicitness").asDouble(0.0)),
                            clamp(node.path("confidence").asDouble(0.0))));
                }
            }

            List<PreferenceDeletion> deletions = new ArrayList<>();
            JsonNode delArr = root.path("deletions");
            if (delArr.isArray()) {
                for (JsonNode node : delArr) {
                    String key = node.path("key").asText("");
                    String value = node.path("value").asText("");
                    if (PreferenceKeys.isKnown(key) && value != null && !value.isBlank()) {
                        deletions.add(new PreferenceDeletion(key, value));
                    }
                }
            }
            return new PreferenceExtractionResult(candidates, deletions);
        } catch (Exception e) {
            log.warn("偏好候选 JSON 解析失败，返回空: {}", e.getMessage());
            return PreferenceExtractionResult.empty();
        }
    }

    /**
     * value 归一化（spec §7.4-②）：枚举型 key 查 memory.preference.value-synonyms 词表；
     * 查不到/开放型 key 按原值（LLM 已完成同义收敛，系统保留原值兜底）
     */
    String normalizeValue(String key, String value) {
        Map<String, String> map = properties.getPreference().getValueSynonyms().get(key);
        if (map != null) {
            String norm = map.get(value);
            if (norm != null && !norm.isBlank()) {
                return norm;
            }
        }
        return value;
    }

    /** 夹取分数到 [0,1]（防 LLM 越界输出） */
    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
