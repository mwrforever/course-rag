package com.commerce.rag.bot.prompt;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词模板管理服务
 *
 * 统一管理 system prompt（静态，支持前缀缓存）和 user prompt（动态，每轮变化）。
 * - 系统提示词从 resources/prompts/system-base.st 加载，启动后常驻内存
 * - 各模式 user prompt 从 resources/prompts/{mode}.st 加载，支持变量替换
 * - 动态上下文（日期等）通过 <system-reminder> 注入到 user message，不破坏前缀缓存
 */
@Slf4j
@Service
public class PromptTemplateService {

    /** 缓存已加载的模板文件内容，避免重复 IO */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    /** 全局静态系统提示词（启动时加载，永不变 -> 命中前缀缓存） */
    private String systemBasePrompt;

    @PostConstruct
    void init() {
        // 预加载所有模板文件
        systemBasePrompt = loadTemplate("prompts/system-base.st");
        loadTemplate("prompts/qa-answer.st");
        loadTemplate("prompts/course-inquiry.st");
        loadTemplate("prompts/heuristic-answer.st");
        loadTemplate("prompts/intent-router.st");
        loadTemplate("prompts/query-rewrite.st");
        log.info("提示词模板加载完成，共 {} 个模板", templateCache.size());
    }

    /**
     * 获取全局静态系统提示词
     *
     * 内容稳定不变，DashScope 可命中前缀缓存，降低首 token 延迟。
     *
     * @return 系统提示词文本
     */
    public String getSystemPrompt() {
        return systemBasePrompt;
    }

    /**
     * 获取指定模式的 user prompt（动态注入变量）
     *
     * @param mode     模式名称（qa-answer / course-inquiry / heuristic-answer）
     * @param variables 变量映射，如 {query -> "用户问题", context -> "检索结果", history -> "历史摘要"}
     * @return 替换变量后的 user prompt 文本
     */
    public String getUserPrompt(String mode, Map<String, String> variables) {
        String template = templateCache.get("prompts/" + mode + ".st");
        if (template == null) {
            throw new IllegalArgumentException("未找到提示词模板: prompts/" + mode + ".st");
        }

        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}",
                    entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    /**
     * 构建带动态日期注入的完整 user prompt
     *
     * 将 <system-reminder> 日期注入到 user prompt 头部，
     * 这样 system prompt 保持静态（可缓存），动态信息走 user message。
     *
     * @param mode      模式名称
     * @param variables 变量映射
     * @return 包含 <system-reminder> 的完整 user prompt
     */
    public String getUserPromptWithReminder(String mode, Map<String, String> variables) {
        String userPrompt = getUserPrompt(mode, variables);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd, EEEE"));
        return "<system-reminder>\n<current_date>" + today + "</current_date>\n</system-reminder>\n\n" + userPrompt;
    }

    /**
     * 从 classpath 加载模板文件并缓存
     */
    private String loadTemplate(String path) {
        try {
            String content = new String(
                    new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            templateCache.put(path, content);
            return content;
        } catch (IOException e) {
            log.warn("加载提示词模板失败: {}", path, e);
            return "";
        }
    }
}
