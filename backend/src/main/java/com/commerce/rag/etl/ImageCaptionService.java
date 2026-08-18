package com.commerce.rag.etl;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.EtlProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * 图片描述（caption）服务 —— 调用 VLM 生成适合向量检索的中文图片描述
 *
 * <p>模型走 DashScopeChatOptions 按次覆盖（etl.caption-model，qwen3.7-flash）；
 * 图片以 Media 字节传入——SAA 1.1.2 实锤：Media data 为 byte[] 时转 base64 data URL
 * （本地 MinIO 对 DashScope 云不可达，不能传 URL）。
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class ImageCaptionService {

    private final ChatModel chatModel;
    private final PromptLoader promptLoader;
    private final EtlProperties etlProperties;

    /**
     * 生成图片中文描述
     *
     * <p>模型调用失败上抛——调用方（ETL 图片分片）按「该图片跳过，文档 ETL 继续」处理（spec §4.2）。
     *
     * @param imageBytes 图片字节（不允许为空）
     * @param mimeType   图片 MIME（如 image/png）
     * @return 100~200 字中文描述
     */
    public String caption(byte[] imageBytes, String mimeType) {
        Map<String, String> sections = promptLoader.loadSections("caption.yml");
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                .data((Object) imageBytes)
                .build();
        UserMessage userMessage = UserMessage.builder()
                .text(sections.getOrDefault("caption.instruction", ""))
                .media(List.of(media))
                .build();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(sections.getOrDefault("caption.system", "")), userMessage),
                DashScopeChatOptions.builder()
                        .model(etlProperties.captionModel())
                        // 多模态路由开关（SAA 1.1.2 字节码实锤）：multiModel=true 才走
                        // /multimodal-generation 接口；默认 false 走 text-generation，
                        // 图片 data URL 被当 URL 解析 → DashScope 报 "url error"
                        .multiModel(true)
                        .build());
        return chatModel.call(prompt).getResult().getOutput().getText();
    }
}
