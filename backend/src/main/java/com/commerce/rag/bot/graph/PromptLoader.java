package com.commerce.rag.bot.graph;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * YAML 提示词模板加载器 —— 从 classpath:prompts/ 加载并缓存
 *
 * <p>支持 {@code ${placeholder}} 占位符替换。
 * 每个模板文件应遵循 YAML 格式，顶层 key 为模板名称。
 *
 * @author commerce-rag
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    /** sections 展平结果缓存（模板为 classpath 静态资源，启动后不变，无一致性维护成本） */
    private final Map<String, Map<String, String>> sectionsCache = new ConcurrentHashMap<>();

    private final Yaml yaml = new Yaml();

    /**
     * 加载指定提示词模板文件的内容（flattenMap 展平模式）
     *
     * <p>适用于需要 extractSection 分段识别的场景（如 query-understanding.yml）。
     * 会保留 key 前缀（如 "prompt: "）作为分段标记。
     *
     * @param fileName 文件名（如 "query-understanding.yml"）
     * @return 模板内容字符串
     */
    public String load(String fileName) {
        return cache.computeIfAbsent(fileName, this::doLoad);
    }

    /**
     * 加载 YAML 单一叶子值的原始文本（不做 flattenMap 展平，不加 key 前缀）
     *
     * <p>适用于 YAML 结构为单一嵌套叶子字符串的提示词文件
     * （如 system-base.yml / agent-instruction.yml / dynamic-context.yml）。
     * 直接返回叶子值原始文本，避免 key 前缀（如 "prompt: "）泄露到提示词。
     *
     * @param fileName 文件名（如 "system-base.yml"）
     * @return 叶子值的原始文本
     */
    public String loadRaw(String fileName) {
        return cache.computeIfAbsent("raw:" + fileName, fn -> doLoadRaw(fileName));
    }

    /**
     * 加载 YAML 原始文本并替换占位符
     *
     * @param fileName     模板文件名
     * @param replacements 占位符替换映射，key 不含 ${} 包裹
     * @return 替换后的原始文本
     */
    public String loadRawAndReplace(String fileName, Map<String, String> replacements) {
        String template = loadRaw(fileName);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            template = template.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return template;
    }

    /**
     * 加载模板并替换占位符
     *
     * @param fileName    模板文件名
     * @param replacements 占位符替换映射，key 不含 ${} 包裹
     * @return 替换后的字符串
     */
    public String loadAndReplace(String fileName, Map<String, String> replacements) {
        String template = load(fileName);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            template = template.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return template;
    }

    /**
     * 加载 YAML 并返回全部叶子字符串（展平路径为 key）
     *
     * <p>适用于多分段提示词（如 caption.yml 的 caption.system / caption.instruction），
     * 供调用方按路径取用，避免各处重复 extractSection 解析。
     *
     * @param fileName 文件名（如 "caption.yml"）
     * @return 展平路径 → 叶子文本（加载失败返回空 Map）
     */
    public Map<String, String> loadSections(String fileName) {
        // 首次加载读盘 + YAML 解析后入缓存（QU 节点每轮对话路径，避免反复磁盘 IO；模板启动后不变）
        return sectionsCache.computeIfAbsent(fileName, this::doLoadSections);
    }

    private Map<String, String> doLoadSections(String fileName) {
        try (InputStream is = new ClassPathResource("prompts/" + fileName).getInputStream()) {
            Map<String, Object> data = yaml.load(is);
            Map<String, String> result = new LinkedHashMap<>();
            flattenLeaves(data, "", result);
            log.info("已加载提示词模板(sections): {} ({} 段)", fileName, result.size());
            // 不可变包装：缓存 Map 被多个调用方共享，防止某调用方修改污染共享缓存
            return Collections.unmodifiableMap(result);
        } catch (Exception e) {
            log.error("加载提示词模板(sections)失败: {}", fileName, e);
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenLeaves(Map<String, Object> map, String prefix, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String s) {
                result.put(key, s.trim());
            } else if (value instanceof Map) {
                flattenLeaves((Map<String, Object>) value, key, result);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String doLoad(String fileName) {
        try (InputStream is = new ClassPathResource("prompts/" + fileName).getInputStream()) {
            Map<String, Object> data = yaml.load(is);
            if (data == null || data.isEmpty()) {
                log.warn("提示词模板文件 {} 为空或格式不正确", fileName);
                return "";
            }
            // 拼接所有顶层值（保留 key 标记，供 extractSection 等方法分段识别）
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String s) {
                    sb.append(entry.getKey()).append(": ").append(s).append("\n");
                } else if (value instanceof Map) {
                    // 嵌套 Map — 递归展平为纯文本（保留 key:value 格式）
                    flattenMap((Map<String, Object>) value, sb);
                }
            }
            log.info("已加载提示词模板: {} ({} 字符)", fileName, sb.length());
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("加载提示词模板失败: {}", fileName, e);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenMap(Map<String, Object> map, StringBuilder sb) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String s) {
                // 保留 key: value 格式，使 extractSection 等方法能通过 key 标记分段
                sb.append(key).append(": ").append(s).append("\n");
            } else if (value instanceof Map) {
                flattenMap((Map<String, Object>) value, sb);
            } else if (value instanceof List<?> list) {
                sb.append(key).append(":\n");
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append("  - ").append(s).append("\n");
                    }
                }
            }
        }
    }

    /**
     * 加载 YAML 并递归查找唯一的叶子字符串值，直接返回原始文本
     *
     * <p>YAML 结构示例（system-base.yml）：
     * <pre>
     * base:
     *   prompt: |
     *     你是一个在线教育平台的 AI 学习助手...
     * </pre>
     * 此方法递归遍历嵌套 Map，找到唯一的 String 叶子值并返回，
     * 不添加任何 key 前缀。
     */
    @SuppressWarnings("unchecked")
    private String doLoadRaw(String fileName) {
        try (InputStream is = new ClassPathResource("prompts/" + fileName).getInputStream()) {
            Map<String, Object> data = yaml.load(is);
            if (data == null || data.isEmpty()) {
                log.warn("提示词模板文件 {} 为空或格式不正确", fileName);
                return "";
            }
            String result = findSingleLeafValue(data);
            log.info("已加载提示词模板(raw): {} ({} 字符)", fileName, result != null ? result.length() : 0);
            return result != null ? result.trim() : "";
        } catch (Exception e) {
            log.error("加载提示词模板(raw)失败: {}", fileName, e);
            return "";
        }
    }

    /**
     * 递归查找 Map 中唯一的叶子字符串值
     *
     * @param map YAML 解析后的 Map
     * @return 唯一叶子字符串值，或 null（如果不存在或存在多个叶子值）
     */
    @SuppressWarnings("unchecked")
    private String findSingleLeafValue(Map<String, Object> map) {
        String leafValue = null;
        for (Object value : map.values()) {
            if (value instanceof String s) {
                if (leafValue != null) {
                    log.warn("YAML 含多个叶子值，返回第一个");
                    break;
                }
                leafValue = s;
            } else if (value instanceof Map) {
                String nested = findSingleLeafValue((Map<String, Object>) value);
                if (nested != null) {
                    if (leafValue != null) {
                        log.warn("YAML 含多个叶子值，返回第一个");
                        break;
                    }
                    leafValue = nested;
                }
            }
        }
        return leafValue;
    }
}
