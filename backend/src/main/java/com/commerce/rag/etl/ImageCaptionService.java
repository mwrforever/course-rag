package com.commerce.rag.etl;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.EtlProperties;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.ThinkingPusher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * 图片描述（caption）服务 —— 调用 VLM 生成适合向量检索的中文图片描述
 *
 * <p>模型走 OpenAiChatOptions 按次覆盖（etl.caption-model，qwen3.7-flash）；
 * 图片以 Media 字节传入——OpenAI 兼容端点原生支持视觉（media 自动转 image_url data URL，
 * 本地 MinIO 对 DashScope 云不可达，不能传 URL）。
 *
 * <p>流式思考推送（2026-08-28 对话流式时间线改版 Task 4）：SSE 链路的会话附件 caption 经
 * {@link #captionStreaming} 走 chatModel.stream 聚合，qwen3.7-flash 混合思考默认开启，
 * reasoning 片段实时推 attachments 阶段（QueryUnderstandingService.streamContent 同款模式）；
 * 聚合完整文本语义与同步 {@link #caption} 完全一致。ETL 离线批量链路无 SSE 通道，恒走同步路径。
 *
 * @author commerce-rag
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCaptionService {

    private final ChatModel chatModel;
    private final PromptLoader promptLoader;
    private final EtlProperties etlProperties;

    /**
     * 生成图片中文描述（同步路径，行为零变化）
     *
     * <p>模型调用失败上抛——调用方（ETL 图片分片 / 会话附件无 SSE 通道场景）按
     * 「该图片跳过，不阻断整体」处理（spec §4.2）。
     *
     * @param imageBytes 图片字节（不允许为空）
     * @param mimeType   图片 MIME（如 image/png）
     * @return 100~200 字中文描述
     */
    public String caption(byte[] imageBytes, String mimeType) {
        return chatModel
                .call(buildCaptionPrompt(imageBytes, mimeType))
                .getResult()
                .getOutput()
                .getText();
    }

    /**
     * 生成图片中文描述（流式聚合版）—— reasoning 片段实时推 SSE attachments 阶段
     *
     * <p>核心流程（QueryUnderstandingService.streamContent 同款模式，2026-08-28 时间线改版 Task 4）：
     * <ol>
     *   <li>{@code chatModel.stream(Prompt)} 逐 chunk 聚合：每 chunk 的
     *       reasoningContent 非空即经 pusher 实时推 attachments 阶段思考（qwen3.7-flash 混合思考
     *       默认开启，reasoning_content 经 OpenAI 兼容流式 chunk 的
     *       AssistantMessage.metadata['reasoningContent'] 返回，spring-ai-openai 1.1.2 已映射）</li>
     *   <li>思考→回答边界（首个 content chunk）CAS 补一次 pusher.end，与 THINKING 成对；
     *       多图并行时每次调用各自持有局部 CAS 标志，互不干扰</li>
     *   <li>聚合完整 content 文本返回——caption 最终文本语义与同步 {@link #caption} 完全一致</li>
     *   <li>流式硬超时自界（etl.image-executor.process-timeout-seconds）：响应式栈 chunk 间
     *       静默无 transport idle 保护，超时/流异常先补 end 关思考态再上抛，由调用方
     *       （captionOne 兜底）走既有「该图跳过」降级，不阻断对话</li>
     * </ol>
     *
     * <p>并发说明：多图并行 caption 时多线程可并发调用同一 pusher——ThinkingPusher 内部
     * pushLock 保证「取号+入队+累加缓冲」原子，本方法无需再加锁。
     *
     * @param imageBytes 图片字节（不允许为空）
     * @param mimeType   图片 MIME（如 image/png）
     * @param pusher     per-run 思考推送通道（非空——空指针场景调用方应走同步 {@link #caption}）
     * @return 聚合后的完整 caption 文本（100~200 字中文描述，不含 reasoning）
     */
    public String captionStreaming(byte[] imageBytes, String mimeType, ThinkingPusher pusher) {
        Prompt prompt = buildCaptionPrompt(imageBytes, mimeType);
        StringBuilder contentBuf = new StringBuilder();
        // 思考→回答边界只推一次 end；并记录是否已推过 reasoning（异常兜底关态判断用）
        AtomicBoolean thinkingEnded = new AtomicBoolean(false);
        AtomicBoolean reasoningSeen = new AtomicBoolean(false);
        // 硬超时复用 ETL 单图 caption 超时配置（会话附件场景经调用方传入的同款秒级预算）
        Duration timeout = Duration.ofSeconds(etlProperties.imageExecutor().processTimeoutSeconds());
        try {
            chatModel.stream(prompt)
                    .doOnNext(chatResponse -> {
                        Generation generation = chatResponse.getResult();
                        if (generation == null || generation.getOutput() == null) {
                            return;
                        }
                        AssistantMessage message = generation.getOutput();
                        // 1. reasoning 片段实时推送（DashScope 思考内容在 metadata['reasoningContent']）
                        String reasoning = extractReasoningContent(message);
                        if (reasoning != null && !reasoning.isEmpty()) {
                            reasoningSeen.set(true);
                            pusher.push(SseEventTransformer.STAGE_ATTACHMENTS, reasoning);
                        }
                        // 2. content 片段聚合；首个 content chunk = 思考结束边界，补一次 THINKING_END
                        //    （仅在此前确实推过 reasoning 时——成对契约，非思考响应不发孤儿 end）
                        String text = message.getText();
                        if (text != null && !text.isEmpty()) {
                            if (reasoningSeen.get() && thinkingEnded.compareAndSet(false, true)) {
                                pusher.end(SseEventTransformer.STAGE_ATTACHMENTS);
                            }
                            contentBuf.append(text);
                        }
                    })
                    .blockLast(timeout);
        } catch (RuntimeException e) {
            // 流异常/超时：已推过 reasoning 但尚未关态 → 补 end 避免前端停留「思考中」，再上抛走既有降级
            if (reasoningSeen.get() && thinkingEnded.compareAndSet(false, true)) {
                pusher.end(SseEventTransformer.STAGE_ATTACHMENTS);
            }
            log.warn("VLM caption 流式调用失败（上抛由调用方按图跳过降级）: mimeType={}, error={}", mimeType, e.getMessage());
            throw e;
        }
        // 流正常结束但全程无 content（纯 reasoning / 空响应）：若已推 reasoning 仍须关思考态
        if (reasoningSeen.get() && thinkingEnded.compareAndSet(false, true)) {
            pusher.end(SseEventTransformer.STAGE_ATTACHMENTS);
        }
        return contentBuf.toString();
    }

    /**
     * 组装 caption Prompt（同步/流式共用）：caption.yml 系统规则 + 指令 + 图片 Media 字节。
     *
     * @param imageBytes 图片字节
     * @param mimeType   图片 MIME
     * @return 完整 Prompt（options 按次覆盖 etl.caption-model）
     */
    private Prompt buildCaptionPrompt(byte[] imageBytes, String mimeType) {
        Map<String, String> sections = promptLoader.loadSections("caption.yml");
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                .data((Object) imageBytes)
                .build();
        UserMessage userMessage = UserMessage.builder()
                .text(sections.getOrDefault("caption.instruction", ""))
                .media(List.of(media))
                .build();
        return new Prompt(
                List.of(new SystemMessage(sections.getOrDefault("caption.system", "")), userMessage),
                OpenAiChatOptions.builder()
                        .model(etlProperties.captionModel())
                        // OpenAI 兼容端点原生支持视觉（media 自动转 image_url），无需多模态路由开关
                        .build());
    }

    /**
     * 从 AssistantMessage.metadata 提取 DashScope reasoningContent（与 QueryUnderstandingService 同源）。
     *
     * @param message 流式 chunk 的输出消息
     * @return reasoning 文本，无值/非字符串返回 null
     */
    private String extractReasoningContent(AssistantMessage message) {
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object value = metadata.get("reasoningContent");
        return value instanceof String s ? s : null;
    }
}
