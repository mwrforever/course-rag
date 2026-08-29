package com.commerce.rag.etl;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.EtlProperties;
import com.commerce.rag.record.AssistantMessageSink;
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
     *       AssistantMessage.metadata['reasoningContent'] 返回，spring-ai-openai 1.1.2 已映射），
     *       并把批次共享标志 {@code reasoningSeenAny} 置 true</li>
     *   <li><b>本方法不调 pusher.end</b>（评审 I-2 stage 级收口）：多图并行下每次调用各自 end
     *       会造成「END(attachments) 后又来 THINKING(attachments)」交错与 N 图 N 个 END，
     *       attachments 阶段的 THINKING_END 由批次完成点（AttachmentImageProcessor.processImages）
     *       统一补恰好一次；本方法只负责推 reasoning 并经共享标志上报「是否推过」</li>
     *   <li>聚合完整 content 文本返回——caption 最终文本语义与同步 {@link #caption} 完全一致</li>
     *   <li>流式硬超时自界（etl.image-executor.process-timeout-seconds）：blockLast 为全流
     *       总时长上限，超限/流异常直接上抛（关态交批次完成点统一收口），由调用方
     *       （captionOne 兜底）走既有「该图跳过」降级，不阻断对话</li>
     *   <li><b>消息实体化捕获（2026-08-29，spec §3.2 caption）</b>：调用完成点（成功/异常均）
     *       经 sink 捕获 {thinking全文, content}——思考全文取 pusher 累加缓冲
     *       （sink 内部按 stage 截增量，多图各实体仅含本图增量思考，拆行不重复），
     *       text 为聚合的 caption 描述文本（异常路径为 null）</li>
     * </ol>
     *
     * <p>并发说明：多图并行 caption 时多线程可并发调用同一 pusher——ThinkingPusher 内部
     * pushLock 保证「取号+入队+累加缓冲」原子，本方法无需再加锁；reasoningSeenAny 为
     * 批次级 AtomicBoolean，多线程置 true 天然幂等。
     *
     * @param imageBytes      图片字节（不允许为空）
     * @param mimeType        图片 MIME（如 image/png）
     * @param pusher          per-run 思考推送通道（非空——空指针场景调用方应走同步 {@link #caption}）
     * @param reasoningSeenAny 批次共享标志（非空）：本方法推过任一 reasoning 片段即置 true，
     *                         供批次完成点判断「确有新推送思考 → 统一补 end」；多图共用同一实例
     * @param sink            per-run LLM 调用捕获容器（可为 null——null 时行为与四参版本一致，不捕获）
     * @return 聚合后的完整 caption 文本（100~200 字中文描述，不含 reasoning）
     */
    public String captionStreaming(
            byte[] imageBytes,
            String mimeType,
            ThinkingPusher pusher,
            AtomicBoolean reasoningSeenAny,
            AssistantMessageSink sink) {
        Prompt prompt = buildCaptionPrompt(imageBytes, mimeType);
        StringBuilder contentBuf = new StringBuilder();
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
                        // 1. reasoning 片段实时推送（DashScope 思考内容在 metadata['reasoningContent']）；
                        //    推过任一片段即置批次标志，供 processImages 完成点决定是否补 end（评审 I-2）
                        String reasoning = extractReasoningContent(message);
                        if (reasoning != null && !reasoning.isEmpty()) {
                            reasoningSeenAny.set(true);
                            pusher.push(SseEventTransformer.STAGE_ATTACHMENTS, reasoning);
                        }
                        // 2. content 片段聚合（思考→回答边界不再在单图内补 end，成对契约由批次统一收口）
                        String text = message.getText();
                        if (text != null && !text.isEmpty()) {
                            contentBuf.append(text);
                        }
                    })
                    .blockLast(timeout);
        } catch (RuntimeException e) {
            // 流异常/全流总时长超限：直接上抛走既有「该图跳过」降级；已推过 reasoning 的关态
            // 由批次完成点（processImages 全部在途完成后）统一补 end，本方法不再自行关思考态
            log.warn("VLM caption 流式调用失败（上抛由调用方按图跳过降级）: mimeType={}, error={}", mimeType, e.getMessage());
            // 消息实体化：异常路径同样捕获（思考全文已累积、text 降级 null——与取消路径
            // attachments thinking 行落库语义一致，run 终态仍由 persistMessages 双路径分流）
            captureCaptionCall(pusher, sink, null);
            throw e;
        }
        // 消息实体化：调用完成点捕获（spec §3.2 caption）
        captureCaptionCall(pusher, sink, contentBuf.toString());
        return contentBuf.toString();
    }

    /**
     * 捕获一次 caption 调用到 sink（thinking 全文 + 描述文本，spec §3.2）。
     *
     * @param pusher 思考推送通道（可为 null——null 时思考全文为 null）
     * @param sink   捕获容器（可为 null——null 时不捕获）
     * @param text   聚合的 caption 文本（异常路径为 null）
     */
    private void captureCaptionCall(ThinkingPusher pusher, AssistantMessageSink sink, String text) {
        if (sink == null) {
            return;
        }
        // 思考全文 = ThinkingPusher 按阶段累加缓冲（与已推送 THINKING 事件逐字一致；
        // sink 内部按 stage 截增量，多图各实体仅含本图增量，拆行出的 thinking VO 不重复）
        String reasoning = pusher == null ? null : pusher.accumulated().get(SseEventTransformer.STAGE_ATTACHMENTS);
        sink.capture(SseEventTransformer.STAGE_ATTACHMENTS, reasoning, text, List.of());
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
